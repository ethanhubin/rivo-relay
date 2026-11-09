"""Persistent per-thread waiting messages. Native turns remain execution authority."""
from __future__ import annotations
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor, wait
from .operation_gates import ThreadGates
from .codex.connection import CodexError
from .submissions import SubmissionConflict

class QueueConflict(SubmissionConflict):
    # These checks happen before a native write; a stale edit is safe to retry.
    definite = True



class ExecutionQueue:
    def __init__(self, db, codex, events):
        self.db, self.codex, self.events = db, codex, events
        self._gates = ThreadGates()
        self._gate = getattr(codex, "operation_gate", self._gates.hold)
        self._tick_lock = threading.Lock()
        self._lifecycle_lock = threading.Lock()
        self._wake = threading.Event()
        self._executor = ThreadPoolExecutor(max_workers=4, thread_name_prefix="relay-queue")
        self.codex.execution_queue = self
        self.stopping = threading.Event()
        self.worker = None
        self._reconcile_at = {}

    def start(self):
        with self.db.transaction() as c:
            c.execute("UPDATE queued_tasks SET status='unknown', error='结果待核对，请查看原会话；不会自动重发' WHERE status='dispatching'")
            # An inserted queue row proves the local enqueue committed even if
            # the phone lost the acceptance receipt. Reconcile, never re-enqueue.
            for row in c.execute("SELECT q.id,q.thread_id,q.payload_json FROM queued_tasks q JOIN submissions s ON s.id=q.id WHERE s.operation IN ('thread.queue.enqueue','thread.queue.recover','thread.review') AND s.status IN ('pending','unknown')").fetchall():
                receipt = {'threadId': row['thread_id'], 'itemId': row['id']}
                recovered = json.loads(row['payload_json']).get('recoveredItemId')
                if recovered:
                    receipt['recoveredItemId'] = recovered
                c.execute("UPDATE submissions SET status='accepted',receipt_json=?,updated_at=? WHERE id=?", (json.dumps(receipt), time.time(), row['id']))
        self.worker = threading.Thread(target=self._run, daemon=True, name='relay-execution-queue')
        self.worker.start()

    def close(self):
        with self._lifecycle_lock:
            self.stopping.set()
        self._wake.set()
        if self.worker:
            self.worker.join()
        self._executor.shutdown(wait=True, cancel_futures=True)

    def _notify(self, thread_id):
        self._wake.set()
        projects = getattr(self.codex, 'projects', None)
        if projects is not None:
            projects.invalidate()
        self.events.publish({'method': 'relay/queueChanged', 'params': {'threadId': thread_id}})

    @staticmethod
    def _public(row):
        payload = json.loads(row['payload_json'])
        return {'id': row['id'], 'threadId': row['thread_id'], 'status': row['status'],
                'position': row['position'], 'revision': row['revision'], 'turnId': row['turn_id'],
                'error': row['error'], 'input': payload['input'], 'model': payload.get('model'),
                'effort': payload.get('effort'), 'serviceTier': payload.get('serviceTier'),
                'recoveryRequired': row['status'] == 'unknown' or (row['status'] == 'failed' and bool(row['turn_id'])),
                'mode': payload.get('mode'), 'review': payload.get('review'), 'text': '\n'.join(i.get('text', '') for i in payload['input'] if i.get('type') == 'text')}

    def by_thread(self):
        result = {}
        with self.db.read() as c:
            for row in c.execute("SELECT * FROM queued_tasks WHERE status NOT IN ('done','cancelled') ORDER BY position,created_at,id"):
                result.setdefault(row['thread_id'], []).append(self._public(row))
        return result

    def list(self, thread_id):
        with self.db.read() as c:
            return [self._public(r) for r in c.execute("SELECT * FROM queued_tasks WHERE thread_id=? AND status NOT IN ('done','cancelled') ORDER BY position,created_at,id", (thread_id,))]

    def enqueue(self, thread_id, item_id, connection_id, payload):
        # Read metadata only: queuing never acquires a writer or starts a turn.
        with self._gate(thread_id):
            self.codex.validate_queue_target(thread_id)
            with self.db.transaction() as c:
                if c.execute("SELECT count(*) FROM queued_tasks WHERE status NOT IN ('done','cancelled')").fetchone()[0] >= 100:
                    raise QueueConflict('Queue is full')
                position = c.execute('SELECT COALESCE(MAX(position),0)+1 FROM queued_tasks WHERE thread_id=?', (thread_id,)).fetchone()[0]
                c.execute('INSERT INTO queued_tasks(id,connection_id,thread_id,payload_json,status,position,revision,created_at) VALUES(?,?,?,?,?,?,0,?)',
                          (item_id, connection_id, thread_id, json.dumps(payload, ensure_ascii=False), 'waiting', position, time.time()))
        self._notify(thread_id)
        self._answer_async_question(thread_id, item_id)
        return {'threadId': thread_id, 'itemId': item_id}

    def _answer_async_question(self, thread_id, item_id):
        """A newly submitted ordinary answer may steer one live async question."""
        with self._gate(thread_id):
            try:
                snapshot = self.codex.get_session(thread_id)
                if self.stopping.is_set(): return
                turn_id = snapshot.get('activeTurnId')
                if snapshot.get('readOnly') or not turn_id or snapshot.get('requests'):
                    return
                turn = next((turn for turn in snapshot.get('thread', {}).get('turns', []) if turn['id'] == turn_id), {})
                question = None
                for item in reversed(turn.get('items', [])):
                    if item.get('type') == 'userMessage':
                        break
                    if item.get('type') == 'agentMessage':
                        if item.get('delivery') == 'async' and isinstance(item.get('questions'), list) and item['questions']:
                            question = item.get('id')
                            break
                        if item.get('phase') != 'commentary':
                            break
                if not question:
                    return
                with self.db.transaction() as c:
                    row = c.execute('SELECT * FROM queued_tasks WHERE id=?', (item_id,)).fetchone()
                    payload = json.loads(row['payload_json'])
                    if row['status'] != 'waiting' or payload.get('mode') == 'plan' or payload.get('review'):
                        return
                    # The queue row itself is the durable claim. A response lost
                    # after the RPC cannot make a second message answer it again.
                    rows = c.execute('SELECT payload_json FROM queued_tasks WHERE thread_id=?', (thread_id,)).fetchall()
                    if any(json.loads(value[0]).get('asyncQuestionId') == question for value in rows):
                        return
                    payload['asyncQuestionId'] = question
                    c.execute('UPDATE queued_tasks SET payload_json=? WHERE id=?', (json.dumps(payload, ensure_ascii=False), item_id))
                try:
                    self.steer(thread_id, item_id, row['revision'], turn_id)
                except CodexError as error:
                    if error.definite:
                        with self.db.transaction() as c:
                            payload.pop('asyncQuestionId', None)
                            c.execute('UPDATE queued_tasks SET payload_json=? WHERE id=?', (json.dumps(payload, ensure_ascii=False), item_id))
            except Exception:
                # Enqueue already committed. Its current row records acceptance,
                # uncertain guidance or waiting; never return a false rejection.
                pass

    def change(self, thread_id, item_id, revision, operation, text=None):
        with self._gate(thread_id), self.db.transaction() as c:
            row = c.execute('SELECT * FROM queued_tasks WHERE id=? AND thread_id=?', (item_id, thread_id)).fetchone()
            if row is None or row['revision'] != revision or row['status'] not in ('waiting', 'failed', 'unknown'):
                raise QueueConflict('Queue item changed; refresh before editing')
            if operation in ('remove', 'dismiss'):
                c.execute("UPDATE queued_tasks SET status='cancelled',revision=revision+1 WHERE id=?", (item_id,))
            elif operation == 'edit' and not self._public(row)['recoveryRequired']:
                if not isinstance(text, str) or not text.strip() or len(text) > 200000:
                    raise QueueConflict('Message text required')
                payload = json.loads(row['payload_json'])
                if payload.get('review'):
                    raise QueueConflict('审查任务请撤回后重新选择审查范围')
                payload['input'] = [{'type': 'text', 'text': text, 'text_elements': []}] + [i for i in payload['input'] if i.get('type') != 'text']
                c.execute("UPDATE queued_tasks SET payload_json=?,status='waiting',error=NULL,revision=revision+1 WHERE id=?", (json.dumps(payload, ensure_ascii=False), item_id))
            else:
                raise QueueConflict('Uncertain execution must not be replayed')
        self._notify(thread_id)
        return {'threadId': thread_id, 'itemId': item_id}

    def recover(self, thread_id, item_id, revision, new_id, connection_id):
        """Continue from native history, never replay an uncertain instruction."""
        with self._gate(thread_id):
            snapshot = self.codex.get_session(thread_id)
            if snapshot.get('readOnly') or snapshot.get('activeTurnId') or snapshot.get('requests') or snapshot.get('status') != 'idle':
                raise QueueConflict('请等待当前任务结束后继续')
            with self.db.transaction() as c:
                row = c.execute('SELECT * FROM queued_tasks WHERE id=? AND thread_id=?', (item_id, thread_id)).fetchone()
                if row is None or row['revision'] != revision or not self._public(row)['recoveryRequired']:
                    raise QueueConflict('待恢复任务已变化，请刷新')
                previous = json.loads(row['payload_json'])
                payload = {key: previous.get(key) for key in ('model', 'effort', 'serviceTier', 'mode')}
                payload['input'] = [{'type': 'text', 'text':
                    '上一项任务的执行结果未确认或已中断。请先检查本会话历史和当前工作区的实际状态，'
                    '确认哪些工作已经完成，再继续完成尚未完成的部分。不要重放已经完成的操作。', 'text_elements': []}]
                payload['recoveredItemId'] = item_id
                c.execute("UPDATE queued_tasks SET status='cancelled',revision=revision+1 WHERE id=?", (item_id,))
                c.execute('INSERT INTO queued_tasks(id,connection_id,thread_id,payload_json,status,position,revision,created_at) VALUES(?,?,?,?,?,?,0,?)',
                          (new_id, connection_id, thread_id, json.dumps(payload, ensure_ascii=False), 'waiting', row['position'], time.time()))
        self._notify(thread_id)
        return {'threadId': thread_id, 'itemId': new_id, 'recoveredItemId': item_id}

    def reorder(self, thread_id, ids, expected_ids):
        with self._gate(thread_id), self.db.transaction() as c:
            rows = list(c.execute("SELECT id FROM queued_tasks WHERE thread_id=? AND status='waiting' ORDER BY position,created_at,id", (thread_id,)))
            expected = [r['id'] for r in rows]
            if expected_ids != expected or not isinstance(ids, list) or len(ids) != len(set(ids)) or set(ids) != set(expected):
                raise QueueConflict('Queue changed while reordering')
            # Keep the next waiting batch behind any running/uncertain item.
            base = c.execute("SELECT COALESCE(MAX(position),0) FROM queued_tasks WHERE thread_id=? AND status IN ('running','dispatching','unknown','failed')", (thread_id,)).fetchone()[0]
            for index, item_id in enumerate(ids):
                c.execute('UPDATE queued_tasks SET position=?,revision=revision+1 WHERE id=?', (base+index+1, item_id))
        self._notify(thread_id)
        return {'threadId': thread_id}

    def steer(self, thread_id, item_id, revision, expected_turn):
        with self._gate(thread_id):
            snapshot = self.codex.get_session(thread_id)
            if snapshot.get('readOnly') or not expected_turn or snapshot['activeTurnId'] != expected_turn:
                raise QueueConflict('Active turn changed')
            with self.db.transaction() as c:
                row = c.execute("SELECT * FROM queued_tasks WHERE id=? AND thread_id=? AND status='waiting' AND revision=?", (item_id, thread_id, revision)).fetchone()
                if row is None: raise QueueConflict('Queue item changed')
                payload = json.loads(row['payload_json'])
                if payload.get('review') or payload.get('mode') == 'plan':
                    raise QueueConflict('审查和讨论任务不能转为当前任务的引导')
                c.execute("UPDATE queued_tasks SET status='dispatching',revision=revision+1 WHERE id=?", (item_id,))
            payload = json.loads(row['payload_json'])
            try:
                receipt = self.codex.steer(thread_id, '', expected_turn, client_message_id=item_id, input_items=payload['input'])
                self._set(item_id, 'done', turn_id=receipt['turnId'])
            except Exception as e:
                # A definite rejection has not consumed the queued message.
                self._set(item_id, 'waiting' if isinstance(e, CodexError) and e.definite else 'unknown', error='引导未完成，请核对原会话')
                self._notify(thread_id)
                raise
        self._notify(thread_id)
        return {'threadId': thread_id, 'itemId': item_id, 'turnId': receipt['turnId']}

    def _set(self, item_id, status, *, turn_id=None, error=None):
        with self.db.transaction() as c:
            c.execute('UPDATE queued_tasks SET status=?,turn_id=COALESCE(?,turn_id),error=?,revision=revision+1 WHERE id=?', (status, turn_id, error, item_id))

    def _reconcile_unknown(self, thread_id, snapshot):
        """Read exact native identities only; reconciliation never dispatches."""
        unknown = [item for item in self.list(thread_id) if item['status'] == 'unknown']
        if not unknown:
            self._reconcile_at.pop(thread_id, None)
            return
        if (snapshot.get('nativeSync') or {}).get('state', 'ready') != 'ready':
            return
        turns = list(snapshot.get('thread', {}).get('turns', []))
        cursor = snapshot.get('nextCursor')
        revision = snapshot.get('nativeRevision')
        # Recent turns are checked every tick; older bounded pages at most once
        # per 30 seconds. A missing identity stays unknown, never becomes retry.
        if cursor and hasattr(self.codex, 'history') and self._reconcile_at.get(thread_id, 0) <= time.monotonic():
            self._reconcile_at[thread_id] = time.monotonic() + 30
            seen = set()
            for _ in range(3):
                if not cursor or cursor in seen:
                    break
                seen.add(cursor)
                try:
                    page = self.codex.history(thread_id, cursor=cursor, limit=100, native_revision=revision)
                except Exception:
                    break
                if (page.get('nativeSync') or {}).get('state', 'ready') != 'ready':
                    break
                turns.extend(page.get('turns', []))
                cursor = page.get('nextCursor')
        by_id = {turn.get('id'): turn for turn in turns if turn.get('id')}
        changed = False
        for item in unknown:
            if item.get('turnId'):
                matches = [by_id[item['turnId']]] if item['turnId'] in by_id else []
            else:
                matches = [turn for turn in by_id.values() if any(
                    native.get('type') == 'userMessage' and native.get('clientId') == item['id']
                    for native in turn.get('items', []))]
            if len(matches) != 1:
                continue
            turn = matches[0]
            state = {'completed': 'done', 'inProgress': 'running', 'failed': 'failed',
                     'interrupted': 'failed', 'canceled': 'failed'}.get(turn.get('status'))
            if state is None:
                continue
            error = '原生任务未正常完成，请继续检查现状或忽略' if state == 'failed' else None
            with self.db.transaction() as c:
                result = c.execute("UPDATE queued_tasks SET status=?,turn_id=?,error=?,revision=revision+1 WHERE id=? AND status='unknown' AND revision=?",
                    (state, turn['id'], error, item['id'], item['revision']))
                changed = changed or result.rowcount > 0
        if changed:
            self._notify(thread_id)

    def cancel_waiting(self, thread_id):
        """Called after native archive succeeds, under the shared thread gate."""
        with self._gate(thread_id):
            with self.db.transaction() as c:
                c.execute("UPDATE queued_tasks SET status='cancelled',revision=revision+1 WHERE thread_id=? AND status='waiting'", (thread_id,))
        self._notify(thread_id)

    def signal(self):
        self._wake.set()

    def tick(self):
        with self._tick_lock:
            if self.stopping.is_set(): return
            with self.db.read() as c:
                threads = [r[0] for r in c.execute("SELECT DISTINCT thread_id FROM queued_tasks WHERE status IN ('waiting','running','unknown')")]
            self._reconcile_at = {key: value for key, value in self._reconcile_at.items() if key in threads}
            wait([self._executor.submit(self._tick_thread, thread_id) for thread_id in threads])

    def _tick_thread(self, thread_id):
        try:
            with self._gate(thread_id):
                if self.stopping.is_set(): return
                snapshot = self.codex.get_session(thread_id)
                if self.stopping.is_set(): return
                self._reconcile_unknown(thread_id, snapshot)
                items = self.list(thread_id)
                if not items: return
                first = items[0]
                if first['status'] == 'running':
                    turn = next((t for t in snapshot['thread']['turns'] if t['id'] == first['turnId']), None)
                    if turn and turn.get('status') == 'completed':
                        self._set(first['id'], 'done'); self._notify(thread_id)
                    elif turn and turn.get('status') in ('failed','interrupted','canceled'):
                        self._set(first['id'], 'failed', error='上一项未正常完成，请继续检查现状或忽略'); self._notify(thread_id)
                    elif turn is None and snapshot.get('activeTurnId') != first['turnId']:
                        self._set(first['id'], 'unknown', error='未找到此任务的执行结果，请核对会话后移除记录'); self._notify(thread_id)
                    return
                if snapshot.get('readOnly'): return
                if first['status'] != 'waiting' or snapshot.get('activeTurnId') or snapshot['status'] != 'idle': return
                with self.db.read() as c:
                    owner = c.execute('SELECT revoked_at FROM connections WHERE id=(SELECT connection_id FROM queued_tasks WHERE id=?)', (first['id'],)).fetchone()
                if owner is None or owner['revoked_at'] is not None:
                    self._set(first['id'], 'failed', error='提交此任务的连接已撤销'); self._notify(thread_id); return
                # Commit before dispatch. Restart and uncertain results never replay this item.
                with self._lifecycle_lock:
                    if self.stopping.is_set(): return
                    # Admission is atomic with shutdown. close() waits for an
                    # admitted call and its receipt before closing storage.
                    self._set(first['id'], 'dispatching')
                try:
                    if first.get('review'):
                        receipt = self.codex.review(thread_id, first['review'])
                    else:
                        kwargs = {'model': first['model'], 'effort': first['effort'], 'client_message_id': first['id'], 'input_items': first['input']}
                        if first.get('mode') is not None: kwargs['mode'] = first['mode']
                        if first.get('serviceTier') is not None: kwargs['service_tier'] = first['serviceTier']
                        receipt = self.codex.send_queued(thread_id, **kwargs)
                    self._set(first['id'], 'running', turn_id=receipt['turnId']) if receipt else self._set(first['id'], 'waiting')
                except Exception as e:
                    definite = isinstance(e, CodexError) and e.definite
                    self._set(first['id'], 'failed' if definite else 'unknown', error='执行未确认，请查看原会话；不会自动重发')
                self._notify(thread_id)
        except Exception:
            # Read failures leave waiting work intact. No dispatch took place.
            return

    def _run(self):
        while not self.stopping.is_set():
            self.tick()
            self._wake.wait(2)
            self._wake.clear()
