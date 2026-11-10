"""Node-owned activity table. HTTP readers never perform native RPCs."""
from collections import OrderedDict
import copy
import threading
import time
from ..read_budget import read_budget
from .projection import execution_projection


class ActivityRegistry:
    def __init__(self, load, generation=lambda: None):
        self.load, self.generation = load, generation
        self.lock = threading.Lock()
        self.wake = threading.Event()
        self.stopped = threading.Event()
        self.worker = None
        self.epoch = None
        self.revision = 0
        self.snapshot_version = 0
        self.tasks, self.membership, self.turns = {}, {}, {}
        self.known, self.unknown = set(), set()
        self.terminal = OrderedDict()
        self.excluded = set()
        self.confirmed_idle = set()
        self.ready = False
        self.last_sync = None
        self.error = None

    def _generation(self):
        epoch = self.generation()
        if self.epoch != epoch:
            if self.epoch is not None:
                self.revision += 1
            self.epoch = epoch
            self.tasks.clear()
            self.membership.clear()
            self.turns.clear()
            self.terminal.clear()
            self.excluded.clear()
            self.confirmed_idle.clear()
            self.known.clear()
            self.unknown.clear()
            self.ready = False
            self.last_sync = None
            self.error = None

    def start(self):
        with self.lock:
            if self.worker is not None or self.stopped.is_set():
                return
            self.worker = threading.Thread(target=self._run, name='node-activity', daemon=True)
            self.worker.start()

    def close(self):
        self.stopped.set()
        self.wake.set()

    def _run(self):
        while not self.stopped.is_set():
            self.wake.clear()
            try:
                with read_budget(seconds=20):
                    self.reconcile()
            except Exception:
                with self.lock:
                    self.error = 'reconciliation_failed'
                    self.unknown.update(self.known - self.confirmed_idle)
                    self.ready = False
            self.wake.wait(5)

    def reconcile(self):
        with self.lock:
            self._generation()
            revision, epoch = self.revision, self.epoch
        result = self.load()
        with self.lock:
            self._generation()
            if revision != self.revision or epoch != self.epoch or self.stopped.is_set():
                return False
            tasks = {row['threadId']: row for row in result['tasks'] if row['threadId'] not in self.excluded}
            unknown = set(result['unknownThreadIds']) - self.excluded - (self.confirmed_idle - tasks.keys())
            for tid in unknown:
                if tid in self.tasks:
                    tasks.setdefault(tid, self.tasks[tid])
            self.turns = {tid: turn for tid, turn in self.turns.items() if tid in tasks or tid in unknown}
            self.confirmed_idle.difference_update(tasks)
            self.tasks = copy.deepcopy(tasks)
            self.membership.update(result.get('projectByThread', {}))
            self.known = set(result['knownThreadIds']) | self.excluded
            self.unknown = unknown
            self.ready = True
            self.revision += 1
            self.last_sync = time.time()
            self.error = None
            return True

    def observe_metadata(self, threads, roots, generation, revision):
        """Publish direct running evidence before slower external/goal probes."""
        with self.lock:
            self._generation()
            if generation != self.epoch or revision != self.revision:
                return
            for thread in threads:
                tid = thread['id']
                if tid in self.excluded:
                    continue
                project = thread.get('projectId') or roots.get(thread.get('cwd')) or self.membership.get(tid)
                if project:
                    self.membership[tid] = project
                    if tid in self.tasks:
                        self.tasks[tid]['projectId'] = project
                native = thread.get('status', {})
                if isinstance(native, dict) and native.get('type') == 'active' and tid not in self.turns:
                    waiting = any(flag in ('waitingOnApproval', 'waitingOnUserInput') for flag in native.get('activeFlags', []))
                    status = 'waiting' if waiting else 'running'
                    self.confirmed_idle.discard(tid)
                    self.tasks[tid] = {**self.tasks.get(tid, {}), 'threadId': tid, 'projectId': project or '', 'status': status,
                                       **execution_projection(status)}
                    self.known.add(tid)
                    self.unknown.discard(tid)

    def _finish_execution(self, tid):
        self.turns.pop(tid, None)
        row = self.tasks.get(tid, {})
        if row.get('goalActive'):
            row.update(turnId=None, status='running')
            row['nativeExecution'] = 'running'
            row['displayState'] = 'executing_with_queue' if row.get('queueDepth') else 'executing'
        elif row.get('deliveryState', 'idle') != 'idle':
            row.update(turnId=None, status='idle', nativeExecution='idle',
                       displayState=row['deliveryState'])
        else:
            self.tasks.pop(tid, None)
        if row.get('goalActive'):
            self.confirmed_idle.discard(tid)
        else:
            self.confirmed_idle.add(tid)
        self.known.add(tid)
        self.unknown.discard(tid)

    def receive(self, event):
        method, params = event.get('method'), event.get('params', {})
        if not isinstance(method, str) or not isinstance(params, dict):
            return
        if method.endswith(('/delta', 'Delta')) or method in ('thread/tokenUsage/updated', 'turn/plan/updated', 'turn/diff/updated'):
            return
        relevant = method.startswith(('thread/', 'project/', 'turn/', 'goal/', 'queue/', 'relay/')) or method.endswith(('/requestApproval', '/requestUserInput')) or method == 'serverRequest/resolved'
        if not relevant:
            return
        with self.lock:
            self._generation()
            if event.get('connectionId', self.epoch) != self.epoch:
                return
            self.revision += 1
            thread = params.get('thread') or {}
            turn = params.get('turn') or {}
            if not isinstance(thread, dict) or not isinstance(turn, dict):
                return
            tid = params.get('threadId') or params.get('conversationId') or thread.get('id')
            turn_id = params.get('turnId') or turn.get('id')
            if isinstance(tid, str) and tid:
                project = params.get('projectId') or thread.get('projectId') or self.membership.get(tid)
                if project:
                    self.membership[tid] = project
                if method in ('thread/unarchived', 'thread/started'):
                    self.excluded.discard(tid)
                if tid in self.excluded and method not in ('thread/deleted', 'thread/archived'):
                    return
                status = None
                if method == 'turn/started' and isinstance(turn_id, str):
                    if (tid, turn_id) in self.terminal:
                        return
                    self.turns[tid] = turn_id
                    status = 'running'
                elif method in ('turn/completed', 'turn/failed', 'turn/interrupted'):
                    # A delayed terminal event must never clear a newer turn.
                    if isinstance(turn_id, str):
                        self.terminal[(tid, turn_id)] = True
                        while len(self.terminal) > 1024:
                            self.terminal.popitem(last=False)
                    # Metadata/reconciliation can restore activity without a start event.
                    # Respect any known newer turn, including one restored by the loader.
                    active_turn = self.turns.get(tid) or self.tasks.get(tid, {}).get('turnId')
                    if turn_id and active_turn == turn_id:
                        self._finish_execution(tid)
                    elif active_turn is None:
                        # A terminal event may belong to an older turn. Reconcile
                        # metadata-only activity instead of declaring it idle.
                        self.unknown.add(tid)
                elif method.endswith(('/requestApproval', '/requestUserInput')):
                    if turn_id and ((tid, turn_id) in self.terminal or
                                    self.turns.get(tid) not in (None, turn_id)):
                        return
                    status = 'waiting'
                elif method == 'thread/status/changed':
                    native = params.get('status', {})
                    if isinstance(native, dict) and native.get('type') == 'active':
                        status = 'waiting' if any(flag in ('waitingOnApproval', 'waitingOnUserInput')
                            for flag in native.get('activeFlags', [])) else 'running'
                    elif isinstance(native, dict) and native.get('type') == 'idle':
                        self._finish_execution(tid)
                    else:
                        self.unknown.add(tid)
                elif method in ('thread/deleted', 'thread/archived'):
                    self.excluded.add(tid)
                    self.confirmed_idle.add(tid)
                    self.tasks.pop(tid, None)
                    self.turns.pop(tid, None)
                    self.known.add(tid)
                    self.unknown.discard(tid)
                if status:
                    self.confirmed_idle.discard(tid)
                    self.tasks[tid] = {**self.tasks.get(tid, {}), 'threadId': tid, 'projectId': project or '',
                                       'turnId': turn_id or self.turns.get(tid) or self.tasks.get(tid, {}).get('turnId'), 'status': status, **execution_projection(status)}
                    self.known.add(tid)
                    self.unknown.discard(tid)
            if method == 'relay/nativeDisconnected':
                self.unknown.update(self.known - self.confirmed_idle)
                self.ready = False
        self.wake.set()

    def read_token(self):
        with self.lock:
            self._generation()
            return self.epoch, self.revision

    def observe_execution(self, thread_id, status, *, token, project_id=None,
                          turn_id=None, settled_turn_id=None, goal_active=False, queue=()):
        """Verified native reads update the same table as events and discovery.

        A read that predates a newer event/reconciliation cannot overwrite it.
        Pagination and unavailable history must never be submitted here.
        """
        with self.lock:
            self._generation()
            if token != (self.epoch, self.revision) or thread_id in self.excluded:
                return False
            if status == 'unknown':
                if thread_id not in self.unknown:
                    self.revision += 1
                self.known.add(thread_id)
                self.unknown.add(thread_id)
                self.confirmed_idle.discard(thread_id)
                return True
            if settled_turn_id:
                self.terminal[(thread_id, settled_turn_id)] = True
                while len(self.terminal) > 1024:
                    self.terminal.popitem(last=False)
            project_id = project_id or self.membership.get(thread_id, '')
            projection = execution_projection(status, queue)
            row = {'threadId': thread_id, 'projectId': project_id, 'turnId': turn_id,
                   'status': status, 'goalActive': goal_active, **projection}
            desired = row if projection['hasOutstandingWork'] else None
            # Even unchanged evidence is newer than an in-flight scan. Fence
            # that scan instead of letting it resurrect a missed, finished turn.
            self.revision += 1
            self.known.add(thread_id)
            self.unknown.discard(thread_id)
            if project_id:
                self.membership[thread_id] = project_id
            if desired:
                self.tasks[thread_id] = row
            else:
                self.tasks.pop(thread_id, None)
            if turn_id:
                self.turns[thread_id] = turn_id
            else:
                self.turns.pop(thread_id, None)
            if status == 'idle':
                self.confirmed_idle.add(thread_id)
            else:
                self.confirmed_idle.discard(thread_id)
            return True

    def snapshot(self):
        with self.lock:
            self._generation()
            self.snapshot_version += 1
            return {'version': self.snapshot_version, 'tasks': copy.deepcopy(list(self.tasks.values())),
                    'knownThreadIds': sorted(self.known), 'unknownThreadIds': sorted(self.unknown),
                    'generation': self.epoch, 'revision': self.revision,
                    # Each row has explicit uncertainty, even before first discovery.
                    'complete': True, 'reconciled': self.ready, 'lastReconciledAt': self.last_sync,
                    'error': self.error}

    def current(self):
        result = self.snapshot()
        return result if result['reconciled'] else None
