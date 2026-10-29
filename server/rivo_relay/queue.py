"""Persistent per-thread waiting messages. Native turns remain execution authority."""
from __future__ import annotations
import json
import threading
import time
from .codex.connection import CodexError
from .submissions import SubmissionConflict

class QueueConflict(SubmissionConflict):
    # These checks happen before a native write; a stale edit is safe to retry.
    definite = True



class ExecutionQueue:
    def __init__(self, db, codex, events):
        self.db, self.codex, self.events = db, codex, events
        self.lock = threading.RLock()
        self.codex.queue_lock = self.lock
        self.stopping = threading.Event()
        self.worker = None

    def start(self):
        with self.db.transaction() as c:
            c.execute("UPDATE queued_tasks SET status='unknown', error='结果待核对，请查看原会话；不会自动重发' WHERE status='dispatching'")
            # An inserted queue row proves the local enqueue committed even if
            # the phone lost the acceptance receipt. Reconcile, never re-enqueue.
            for row in c.execute("SELECT q.id,q.thread_id FROM queued_tasks q JOIN submissions s ON s.id=q.id WHERE s.operation='thread.queue.enqueue' AND s.status IN ('pending','unknown')").fetchall():
                c.execute("UPDATE submissions SET status='accepted',receipt_json=?,updated_at=? WHERE id=?", (json.dumps({'threadId': row['thread_id'], 'itemId': row['id']}), time.time(), row['id']))
        self.worker = threading.Thread(target=self._run, daemon=True, name='relay-execution-queue')
        self.worker.start()

    def close(self):
        self.stopping.set()
        if self.worker:
            self.worker.join(timeout=3)

    def _notify(self, thread_id):
        self.events.publish({'method': 'relay/queueChanged', 'params': {'threadId': thread_id}})

    @staticmethod
    def _public(row):
        payload = json.loads(row['payload_json'])
        return {'id': row['id'], 'threadId': row['thread_id'], 'status': row['status'],
                'position': row['position'], 'revision': row['revision'], 'turnId': row['turn_id'],
                'error': row['error'], 'input': payload['input'], 'model': payload.get('model'),
                'effort': payload.get('effort'), 'mode': payload.get('mode'), 'review': payload.get('review'), 'text': '\n'.join(i.get('text', '') for i in payload['input'] if i.get('type') == 'text')}

    def list(self, thread_id):
        with self.db.read() as c:
            return [self._public(r) for r in c.execute("SELECT * FROM queued_tasks WHERE thread_id=? AND status NOT IN ('done','cancelled') ORDER BY position,created_at,id", (thread_id,))]

    def enqueue(self, thread_id, item_id, connection_id, payload):
        # Read metadata only: queuing never acquires a writer or starts a turn.
        with self.lock, getattr(self.codex, "_operations", self.lock), self.db.transaction() as c:
            self.codex.validate_queue_target(thread_id)
            if c.execute("SELECT count(*) FROM queued_tasks WHERE status NOT IN ('done','cancelled')").fetchone()[0] >= 100:
                raise QueueConflict('Queue is full')
            position = c.execute('SELECT COALESCE(MAX(position),0)+1 FROM queued_tasks WHERE thread_id=?', (thread_id,)).fetchone()[0]
            c.execute('INSERT INTO queued_tasks(id,connection_id,thread_id,payload_json,status,position,revision,created_at) VALUES(?,?,?,?,?,?,0,?)',
                      (item_id, connection_id, thread_id, json.dumps(payload, ensure_ascii=False), 'waiting', position, time.time()))
        self._notify(thread_id)
        return {'threadId': thread_id, 'itemId': item_id}

    def change(self, thread_id, item_id, revision, operation, text=None):
        with self.lock, self.db.transaction() as c:
            row = c.execute('SELECT * FROM queued_tasks WHERE id=? AND thread_id=?', (item_id, thread_id)).fetchone()
            if row is None or row['revision'] != revision or row['status'] not in ('waiting', 'failed', 'unknown'):
                raise QueueConflict('Queue item changed; refresh before editing')
            if operation == 'remove':
                c.execute("UPDATE queued_tasks SET status='cancelled',revision=revision+1 WHERE id=?", (item_id,))
            elif operation == 'edit' and row['status'] != 'unknown':
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

    def reorder(self, thread_id, ids, expected_ids):
        with self.lock, self.db.transaction() as c:
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
        with self.lock:
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
                self._set(item_id, 'failed' if isinstance(e, CodexError) and e.definite else 'unknown', error='引导未完成，请核对原会话')
                self._notify(thread_id)
                raise
        self._notify(thread_id)
        return {'threadId': thread_id, 'itemId': item_id, 'turnId': receipt['turnId']}

    def _set(self, item_id, status, *, turn_id=None, error=None):
        with self.db.transaction() as c:
            c.execute('UPDATE queued_tasks SET status=?,turn_id=COALESCE(?,turn_id),error=?,revision=revision+1 WHERE id=?', (status, turn_id, error, item_id))

    def tick(self):
        with self.db.read() as c:
            threads = [r[0] for r in c.execute("SELECT DISTINCT thread_id FROM queued_tasks WHERE status IN ('waiting','running')")]
        for thread_id in threads:
            if self.stopping.is_set(): return
            try:
                snapshot = self.codex.get_session(thread_id)
                if snapshot.get('readOnly'): continue
                with self.lock:
                    items = self.list(thread_id)
                    if not items: continue
                    first = items[0]
                    if first['status'] == 'running':
                        turn = next((t for t in snapshot['thread']['turns'] if t['id'] == first['turnId']), None)
                        if turn and turn.get('status') == 'completed':
                            self._set(first['id'], 'done'); self._notify(thread_id)
                        elif turn and turn.get('status') in ('failed','interrupted','canceled'):
                            self._set(first['id'], 'failed', error='上一项未正常完成，请编辑或移除后继续'); self._notify(thread_id)
                        elif turn is None and snapshot.get('activeTurnId') != first['turnId']:
                            self._set(first['id'], 'unknown', error='未找到此任务的执行结果，请核对会话后移除记录'); self._notify(thread_id)
                        continue
                    if first['status'] != 'waiting' or snapshot.get('activeTurnId') or snapshot['status'] != 'idle': continue
                    with self.db.read() as c:
                        owner = c.execute('SELECT revoked_at FROM connections WHERE id=(SELECT connection_id FROM queued_tasks WHERE id=?)', (first['id'],)).fetchone()
                    if owner is None or owner['revoked_at'] is not None:
                        self._set(first['id'], 'failed', error='提交此任务的连接已撤销'); self._notify(thread_id); continue
                    # Commit before dispatch. Restart and uncertain results never replay this item.
                    self._set(first['id'], 'dispatching')
                    try:
                        if first.get('review'):
                            receipt = self.codex.review(thread_id, first['review'])
                        else:
                            kwargs = {'model': first['model'], 'effort': first['effort'], 'client_message_id': first['id'], 'input_items': first['input']}
                            if first.get('mode') is not None: kwargs['mode'] = first['mode']
                            receipt = self.codex.send_queued(thread_id, **kwargs)
                        self._set(first['id'], 'running', turn_id=receipt['turnId']) if receipt else self._set(first['id'], 'waiting')
                    except Exception as e:
                        definite = isinstance(e, CodexError) and e.definite
                        self._set(first['id'], 'failed' if definite else 'unknown', error='执行未确认，请查看原会话；不会自动重发')
                    self._notify(thread_id)
            except Exception:
                # Read failures leave waiting work intact. No dispatch took place.
                continue

    def _run(self):
        while not self.stopping.is_set():
            self.tick()
            self.stopping.wait(2)
