"""Node-owned activity table. HTTP readers never perform native RPCs."""
from collections import OrderedDict
import copy
from functools import wraps
from dataclasses import dataclass, field
import threading
import time
from ..read_budget import read_budget
from .projection import execution_projection


@dataclass
class ActivityRead:
    epoch: str
    revision: int
    # A scan may continue after its own early metadata publication, but never
    # after another writer updated that thread. Kept private to this read.
    metadata_versions: dict = field(default_factory=dict)
    running_metadata: set = field(default_factory=set)


def publishes_activity(method):
    @wraps(method)
    def wrapped(self, *args, **kwargs):
        try:
            return method(self, *args, **kwargs)
        finally:
            self._notify_changes()
    return wrapped


class ActivityRegistry:
    def __init__(self, load, generation=lambda: None, on_change=None):
        self.load, self.generation = load, generation
        self.on_change = on_change
        self._notification_lock = threading.Lock()
        self._notified_epoch = None
        self._notified_rows = {}
        self.lock = threading.Lock()
        self.wake = threading.Event()
        self.stopped = threading.Event()
        self.worker = None
        self.epoch = None
        self.revision = 0
        self.snapshot_version = 0
        self.thread_revisions = {}
        self.barrier_revision = 0
        self.tasks, self.membership, self.turns = {}, {}, {}
        self.known, self.unknown = set(), set()
        self.terminal = OrderedDict()
        self.excluded = set()
        self.confirmed_idle = set()
        self.ready = False
        self.last_sync = None
        self.error = None

    def _notify_changes(self):
        if self.on_change is None:
            return
        # Real-time list updates come from semantic changes, not faster polling.
        # Ignore scan/read revisions and turn IDs: metadata scans omit turn IDs,
        # and repeated detail reads must not create a notification/read loop.
        fields = ('projectId', 'status', 'nativeExecution', 'deliveryState',
                  'displayState', 'queueDepth', 'recoveryRequired',
                  'hasOutstandingWork', 'blockReason', 'goalActive')
        with self._notification_lock:
            with self.lock:
                rows = {tid: (tid in self.known, tid in self.unknown,
                    tuple(self.tasks.get(tid, {}).get(k) for k in fields)
                    if tid not in self.unknown else ())
                    for tid in self.known | self.unknown | self.tasks.keys()}
                changed = sorted(tid for tid in rows.keys() | self._notified_rows.keys()
                                 if rows.get(tid) != self._notified_rows.get(tid))
                generation_changed = self._notified_epoch != self.epoch
                if not changed and not generation_changed:
                    return
                if generation_changed:
                    changed = sorted(rows.keys() | self._notified_rows.keys())
                params = {'generation': self.epoch, 'revision': self.revision, 'threadIds': changed}
                self._notified_epoch, self._notified_rows = self.epoch, rows
                if not changed:
                    return
            # Outside the table lock: readers remain available to the event sink.
            # The watermark lets a detail containing this change satisfy the
            # notification instead of discarding its own authoritative result.
            self.on_change({'method': 'relay/activityChanged', 'params': params})

    def _generation(self):
        epoch = self.generation()
        if self.epoch != epoch:
            if self.epoch is not None:
                self.revision += 1
            self.epoch = epoch
            self.thread_revisions.clear()
            self.barrier_revision = self.revision
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
                # reconcile() has already fenced and applied this failure.
                pass
            self.wake.wait(5)

    def _touch(self, thread_id):
        self.revision += 1
        self.thread_revisions[thread_id] = self.revision

    def _current(self, token):
        return token.epoch == self.epoch and token.revision >= self.barrier_revision

    def _accepts(self, thread_id, token):
        version = token.metadata_versions.get(thread_id, token.revision)
        return self._current(token) and self.thread_revisions.get(thread_id, 0) <= version

    def _scan_failed(self, token):
        with self.lock:
            self._generation()
            if not self._current(token):
                return
            # The scan's early metadata is independent positive evidence too.
            # Do not let a later failed probe invalidate it or newer detail/event rows.
            affected = {tid for tid in self.known
                        if self._accepts(tid, token) and tid not in token.running_metadata}
            if self.known and not affected:
                return
            for tid in affected - self.confirmed_idle:
                self.unknown.add(tid)
                self._touch(tid)
            self.error = 'reconciliation_failed'
            self.ready = False

    @publishes_activity
    def reconcile(self):
        token = self.read_token()
        try:
            result = self.load(token)
        except Exception:
            self._scan_failed(token)
            raise
        with self.lock:
            self._generation()
            if not self._current(token) or self.stopped.is_set():
                return False
            tasks = {row['threadId']: row for row in result['tasks']}
            known = set(result['knownThreadIds']) | set(tasks)
            unknown = set(result['unknownThreadIds'])
            membership = result.get('projectByThread', {})
            candidates = self.known | known | unknown | self.excluded
            applied = False
            for tid in candidates:
                if not self._accepts(tid, token):
                    continue
                applied = True
                self._touch(tid)
                if tid in self.excluded:
                    self.known.add(tid)
                    continue
                if tid not in known:
                    self.known.discard(tid)
                    self.unknown.discard(tid)
                    self.tasks.pop(tid, None)
                    self.turns.pop(tid, None)
                    self.confirmed_idle.discard(tid)
                    continue
                self.known.add(tid)
                if tid in membership:
                    self.membership[tid] = membership[tid]
                row = tasks.get(tid)
                uncertain = tid in unknown and not (tid in self.confirmed_idle and row is None)
                if uncertain:
                    self.unknown.add(tid)
                    if row is None:
                        row = self.tasks.get(tid)
                else:
                    self.unknown.discard(tid)
                if row is not None:
                    self.tasks[tid] = copy.deepcopy(row)
                    self.confirmed_idle.discard(tid)
                else:
                    self.tasks.pop(tid, None)
                if row and row.get('turnId'):
                    self.turns[tid] = row['turnId']
                elif not uncertain:
                    # A metadata-only running row does not prove that a previous
                    # turn is still current; delayed completion must reconcile it.
                    self.turns.pop(tid, None)
            if applied or not candidates:
                self.ready = True
                self.last_sync = time.time()
                self.error = None
                return True
            return False

    @publishes_activity
    def observe_metadata(self, threads, roots, token):
        """Publish direct running evidence before slower external/goal probes."""
        with self.lock:
            self._generation()
            for thread in threads:
                tid = thread['id']
                if tid in self.excluded or not self._accepts(tid, token):
                    continue
                changed = False
                project = thread.get('projectId') or roots.get(thread.get('cwd')) or self.membership.get(tid)
                if project:
                    changed = self.membership.get(tid) != project
                    self.membership[tid] = project
                    if tid in self.tasks:
                        changed |= self.tasks[tid].get('projectId') != project
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
                    changed = True
                    token.running_metadata.add(tid)
                if changed:
                    self._touch(tid)
                    token.metadata_versions[tid] = self.thread_revisions[tid]

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
            if method == 'relay/nativeDisconnected':
                self.revision += 1
                self.barrier_revision = self.revision
                self.unknown.update(self.known - self.confirmed_idle)
                self.ready = False
                self.wake.set()
                return
            thread = params.get('thread') or {}
            turn = params.get('turn') or {}
            if not isinstance(thread, dict) or not isinstance(turn, dict):
                return
            tid = params.get('threadId') or params.get('conversationId') or thread.get('id')
            turn_id = params.get('turnId') or turn.get('id')
            if isinstance(tid, str) and tid:
                self._touch(tid)
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
            else:
                self.revision += 1
                self.barrier_revision = self.revision
        self.wake.set()

    def read_token(self):
        with self.lock:
            self._generation()
            return ActivityRead(self.epoch, self.revision)

    @publishes_activity
    def observe_execution(self, thread_id, status, *, token, project_id=None,
                          turn_id=None, settled_turn_id=None, goal_active=False, queue=()):
        """Verified native reads update the same table as events and discovery.

        A read that predates a newer event/reconciliation cannot overwrite it.
        Pagination and unavailable history must never be submitted here.
        """
        with self.lock:
            self._generation()
            if not self._accepts(thread_id, token) or thread_id in self.excluded:
                return False
            if status == 'unknown':
                self._touch(thread_id)
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
            self._touch(thread_id)
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
            # Activity is node-wide and is embedded in unrelated conversation reads.
            # Recovery input belongs only to its owning conversation, not this summary.
            tasks = [{key: value for key, value in row.items() if key != 'recoveryTask'}
                     for row in self.tasks.values()]
            return {'version': self.snapshot_version, 'tasks': copy.deepcopy(tasks),
                    'knownThreadIds': sorted(self.known), 'unknownThreadIds': sorted(self.unknown),
                    'generation': self.epoch, 'revision': self.revision,
                    # Each row has explicit uncertainty, even before first discovery.
                    'complete': True, 'reconciled': self.ready, 'lastReconciledAt': self.last_sync,
                    'error': self.error}

    def current(self):
        result = self.snapshot()
        return result if result['reconciled'] else None
