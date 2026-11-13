"""Node-owned activity table. HTTP readers never perform native RPCs."""
from collections import OrderedDict
import copy
from dataclasses import dataclass, field
import threading
import time
from ..read_budget import read_budget
from types import SimpleNamespace
from .projection import execution_projection, response_waiting
from .interactions import Interactions
from .response_questions import AsyncQuestionState, FAILED


@dataclass
class ActivityRead:
    epoch: str
    revision: int
    # A scan may continue after its own early metadata publication, but never
    # after another writer updated that thread. Kept private to this read.
    metadata_versions: dict = field(default_factory=dict)
    running_metadata: set = field(default_factory=set)


class ActivityRegistry:
    def __init__(self, load, generation=lambda: None, *, interactions=None):
        self.load, self.generation = load, generation
        self.lock = threading.Lock()
        self.wake = threading.Event()
        self.stopped = threading.Event()
        self.worker = None
        self._request_connection = SimpleNamespace(generation=None) if interactions is None else None
        self.interactions = interactions or Interactions(self._request_connection)
        self.questions = {}
        self.question_reads = {}
        self.question_unknown = set()
        self.question_priority = set()
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

    def _generation(self):
        epoch = self.generation()
        if self.epoch != epoch:
            if self.epoch is not None:
                self.revision += 1
            self.epoch = epoch
            if self._request_connection is not None:
                self._request_connection.generation = epoch
            self.questions.clear()
            self.question_reads.clear()
            self.question_unknown.clear()
            self.question_priority.clear()
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
                    requests = self.interactions.list(tid)
                    waiting = response_waiting(requests, native)
                    status = 'waiting' if waiting else 'running'
                    self.confirmed_idle.discard(tid)
                    self.tasks[tid] = {**self.tasks.get(tid, {}), 'threadId': tid, 'projectId': project or '', 'status': status,
                                       **execution_projection(status, requests=self.interactions.list(tid), native_status=native)}
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
        row['responseKinds'] = []
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
        question_item = method in ('item/started', 'item/completed') and (params.get('item') or {}).get('type') in ('agentMessage', 'userMessage', 'steeringUserMessage')
        relevant = question_item or method.startswith(('thread/', 'project/', 'turn/', 'goal/', 'queue/', 'relay/')) or method.endswith(('/requestApproval', '/requestUserInput')) or method in ('serverRequest/resolved', 'serverRequest/completed', 'execCommandApproval', 'applyPatchApproval', 'mcpServer/elicitation/request')
        if not relevant:
            return
        with self.lock:
            self._generation()
            if event.get('connectionId', self.epoch) != self.epoch:
                return
            if self.interactions.receive(event) is False:
                return
            if method == 'relay/nativeDisconnected':
                self.revision += 1
                self.barrier_revision = self.revision
                self.unknown.update(self.known - self.confirmed_idle)
                self.question_unknown.update(self.questions)
                self.question_reads.clear()
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
                native = None
                if question_item:
                    current = self.questions.get(tid)
                    is_answer = params['item'].get('type') in ('userMessage', 'steeringUserMessage')
                    if (not turn_id or (tid, turn_id) in self.terminal and not is_answer or
                            self.turns.get(tid) not in (None, turn_id) or
                            current and current.turn_id != turn_id and current.live):
                        return
                    if is_answer and (current is None or current.turn_id != turn_id):
                        return
                    if current is None or current.turn_id != turn_id:
                        current = self.questions[tid] = AsyncQuestionState(turn_id, live=True)
                    current.item(params['item'])
                    self.question_unknown.discard(tid)
                    # Item events do not establish execution or clear uncertainty.
                if method in ('relay/questionsAnswered', 'relay/questionsAnswerState'):
                    current = self.questions.get(tid)
                    if current:
                        state = params.get('state', 'accepted')
                        for key in params.get('questionIds', []):
                            if state is None:
                                current.submissions.pop(key, None)
                            else:
                                current.submissions[key] = state
                            if state == 'accepted':
                                current.answered.add(key)
                if method in ('relay/historyInvalidated', 'thread/closed', 'relay/writerDisconnected'):
                    self.question_unknown.add(tid)
                    self.question_reads.pop(tid, None)
                    if method == 'relay/historyInvalidated':
                        self.questions.pop(tid, None)
                if method == 'turn/started' and isinstance(turn_id, str):
                    if (tid, turn_id) in self.terminal:
                        return
                    self.turns[tid] = turn_id
                    if tid not in self.questions or self.questions[tid].turn_id != turn_id:
                        self.questions[tid] = AsyncQuestionState(turn_id, live=True)
                    self.question_unknown.discard(tid)
                    status = 'running'
                elif method in ('turn/completed', 'turn/failed', 'turn/interrupted'):
                    # A delayed terminal event must never clear a newer turn.
                    if isinstance(turn_id, str):
                        self.terminal[(tid, turn_id)] = True
                        while len(self.terminal) > 1024:
                            self.terminal.popitem(last=False)
                    # Metadata/reconciliation can restore activity without a start event.
                    # Respect any known newer turn, including one restored by the loader.
                    current = self.questions.get(tid)
                    if current and current.turn_id == turn_id:
                        current.live = False
                        final_status = (turn.get('status') or ('failed' if method == 'turn/failed' else 'interrupted' if method == 'turn/interrupted' else 'completed'))
                        if current.status not in FAILED or final_status in FAILED:
                            current.status = final_status
                        for item in turn.get('items', []):
                            current.item(item)
                    active_turn = self.turns.get(tid) or self.tasks.get(tid, {}).get('turnId')
                    if turn_id and active_turn == turn_id:
                        self._finish_execution(tid)
                    elif active_turn is None:
                        # A terminal event may belong to an older turn. Reconcile
                        # metadata-only activity instead of declaring it idle.
                        self.unknown.add(tid)
                elif method.endswith(('/requestApproval', '/requestUserInput')) or method in ('execCommandApproval', 'applyPatchApproval', 'mcpServer/elicitation/request'):
                    if turn_id and ((tid, turn_id) in self.terminal or
                                    self.turns.get(tid) not in (None, turn_id)):
                        return
                    if turn_id and (tid not in self.questions or self.questions[tid].turn_id != turn_id):
                        self.questions[tid] = AsyncQuestionState(turn_id, live=True)
                    status = 'waiting' if response_waiting(self.interactions.list(tid)) else 'running'
                elif method in ('serverRequest/resolved', 'serverRequest/completed', 'relay/writerDisconnected', 'relay/interactionAnswered', 'thread/closed'):
                    # Request IDs may resolve one of several pending prompts. Reconcile
                    # the authoritative set rather than clearing all or retaining stale hints.
                    self.unknown.add(tid)
                elif method == 'thread/status/changed':
                    native = params.get('status', {})
                    if isinstance(native, dict) and native.get('type') == 'active':
                        status = 'waiting' if response_waiting(self.interactions.list(tid), native) else 'running'
                    elif isinstance(native, dict) and native.get('type') == 'idle':
                        self._finish_execution(tid)
                    else:
                        self.unknown.add(tid)
                elif method in ('thread/deleted', 'thread/archived'):
                    self.questions.pop(tid, None)
                    self.question_reads.pop(tid, None)
                    self.question_unknown.discard(tid)
                    self.excluded.add(tid)
                    self.confirmed_idle.add(tid)
                    self.tasks.pop(tid, None)
                    self.turns.pop(tid, None)
                    self.known.add(tid)
                    self.unknown.discard(tid)
                if status:
                    self.confirmed_idle.discard(tid)
                    self.tasks[tid] = {**self.tasks.get(tid, {}), 'threadId': tid, 'projectId': project or '',
                                       'turnId': turn_id or self.turns.get(tid) or self.tasks.get(tid, {}).get('turnId'), 'status': status, **execution_projection(status, requests=self.interactions.list(tid), native_status=native)}
                    self.known.add(tid)
                    self.unknown.discard(tid)
            else:
                self.revision += 1
                self.barrier_revision = self.revision
                if method == 'relay/writerDisconnected':
                    self.question_unknown.update(params.get('threadIds', []))
                    for lost in params.get('threadIds', []):
                        self.question_reads.pop(lost, None)
                if method in ('serverRequest/resolved', 'serverRequest/completed',
                              'relay/writerDisconnected', 'relay/interactionAnswered', 'thread/closed'):
                    # Some runtimes send only a request ID. Until reconciliation,
                    # no waiting row can still claim its pending set is verified.
                    self.unknown.update(tid for tid, row in self.tasks.items()
                                        if row.get('status') == 'waiting')
        self.wake.set()

    def read_token(self):
        with self.lock:
            self._generation()
            return ActivityRead(self.epoch, self.revision)

    def observe_execution(self, thread_id, status, *, token, project_id=None,
                          turn_id=None, settled_turn_id=None, goal_active=False, queue=(), requests=(), native_status=None):
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
            projection = execution_projection(status, queue, requests=requests, native_status=native_status)
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

    def prioritize_questions(self, thread_ids):
        with self.lock:
            self._generation()
            self.question_priority = set(thread_ids[:50])
        self.wake.set()

    def question_candidates(self, threads, limit=4):
        """Bound background history IO; never scan histories in an HTTP list read."""
        with self.lock:
            self._generation()
            now = time.monotonic()
            candidates = []
            for thread in threads:
                tid = thread['id']
                if tid in self.excluded:
                    continue
                fingerprint = (thread.get('updatedAt'), repr(thread.get('status')))
                prior = self.question_reads.get(tid)
                if prior is not None and prior[0] != fingerprint:
                    self.question_unknown.add(tid)
                if prior is None or prior[0] != fingerprint or now - prior[1] >= 60:
                    candidates.append((0 if tid in self.question_priority else 1, prior[1] if prior else 0, tid, fingerprint))
            candidates.sort(key=lambda value: value[:2])
            for _, _, tid, fingerprint in candidates[:limit]:
                self.question_reads[tid] = (fingerprint, now)
            return [tid for _, _, tid, _ in candidates[:limit]]

    def observe_questions(self, thread_id, turns, *, token, answer_states=None):
        with self.lock:
            self._generation()
            if not self._accepts(thread_id, token) or thread_id in self.excluded:
                return False
            turn = turns[-1] if turns else None
            current = self.questions.get(thread_id)
            active_turn = self.turns.get(thread_id) or self.tasks.get(thread_id, {}).get('turnId')
            if active_turn and (not turn or turn['id'] != active_turn):
                return False
            if current and turn and turn['id'] == current.turn_id:
                if current.status in FAILED and turn.get('status') not in FAILED:
                    return False
                if current.status == 'completed' and turn.get('status') in ('inProgress', 'notStarted'):
                    return False
            # Persisted heads can lag an already observed new turn. A historical
            # page must not replace the live head or revive a retired old turn.
            if current and not turn:
                return False
            if current and turn['id'] != current.turn_id and (
                    current.live or turn and (thread_id, turn['id']) in self.terminal):
                return False
            if turn:
                if current is None or current.turn_id != turn['id']:
                    current = self.questions[thread_id] = AsyncQuestionState(turn['id'])
                current.observe(turn, answer_states)
            else:
                self.questions.pop(thread_id, None)
            self.question_unknown.discard(thread_id)
            self.known.add(thread_id)
            self._touch(thread_id)
            token.metadata_versions[thread_id] = self.thread_revisions[thread_id]
            return True

    def snapshot(self):
        with self.lock:
            self._generation()
            self.snapshot_version += 1
            # Activity is node-wide and is embedded in unrelated conversation reads.
            # Recovery input belongs only to its owning conversation, not this summary.
            rows = {tid: {key: value for key, value in row.items() if key != 'recoveryTask'}
                    for tid, row in self.tasks.items()}
            for tid, questions in self.questions.items():
                if (tid not in self.excluded and tid in self.known and tid not in self.unknown and
                        tid not in self.question_unknown and questions.pending):
                    row = rows.setdefault(tid, {'threadId': tid, 'projectId': self.membership.get(tid, ''),
                        'status': 'idle', **execution_projection('idle')})
                    row['responseKinds'] = list(dict.fromkeys(['question'] + row.get('responseKinds', [])))
            tasks = list(rows.values())
            return {'version': self.snapshot_version, 'tasks': copy.deepcopy(tasks),
                    'knownThreadIds': sorted(self.known), 'unknownThreadIds': sorted(self.unknown),
                    'generation': self.epoch, 'revision': self.revision,
                    # Each row has explicit uncertainty, even before first discovery.
                    'complete': True, 'reconciled': self.ready, 'lastReconciledAt': self.last_sync,
                    'error': self.error}

    def current(self):
        result = self.snapshot()
        return result if result['reconciled'] else None
