import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from rivo_relay.database import Database, SCHEMA_VERSION
from rivo_relay.events import EventBus
from rivo_relay.queue import ExecutionQueue, QueueConflict
from rivo_relay.codex.sessions import CodexService
from rivo_relay.codex.connection import CodexError
from test_codex import FakeNative

class QueueNative:
    def __init__(self):
        self.snapshot = {'thread': {'turns': []}, 'activeTurnId': None, 'status': 'idle'}
        self.sent = []
        self.error = None
    def validate_queue_target(self, thread): pass
    def get_session(self, thread): return copy.deepcopy(self.snapshot)
    def send_queued(self, thread, **kwargs):
        self.sent.append(kwargs)
        if self.error: raise self.error
        return {'threadId': thread, 'turnId': 'turn-' + str(len(self.sent))}
    def steer(self, thread, text, turn, **kwargs):
        self.sent.append(kwargs)
        if self.error: raise self.error
        return {'threadId': thread, 'turnId': turn}

class QueueTests(unittest.TestCase):
    def setUp(self):
        self.db = Database(':memory:'); self.addCleanup(self.db.close)
        with self.db.transaction() as c:
            c.execute("INSERT INTO connections VALUES ('phone','digest','Phone',1,NULL)")
        self.native = QueueNative()
        self.queue = ExecutionQueue(self.db, self.native, EventBus())
        self.addCleanup(self.queue.close)
    def add(self, id):
        return self.queue.enqueue('thread', id, 'phone', {'input': [{'type':'text','text':id}, {'type':'image','url':'data:image/png;base64,test'}], 'model':'model', 'effort':'high'})
    def test_fifo_waits_for_completed_turn_and_dispatches_once(self):
        self.add('a'); self.add('b')
        self.native.snapshot.update(activeTurnId='external', status='running')
        self.queue.tick(); self.assertEqual([], self.native.sent)
        self.native.snapshot.update(activeTurnId=None, status='idle')
        self.queue.tick(); self.assertEqual('running', self.queue.list('thread')[0]['status'])
        self.native.snapshot['thread']['turns'] = [{'id':'turn-1','status':'inProgress'}]
        self.queue.tick(); self.assertEqual(1, len(self.native.sent))
        self.native.snapshot['thread']['turns'][0]['status']='completed'
        self.queue.tick(); self.queue.tick()
        self.assertEqual(['a','b'], [r['client_message_id'] for r in self.native.sent])
    def test_readonly_queue_waits_but_can_edit_and_withdraw(self):
        self.native.snapshot['readOnly']=True
        self.add('a'); self.queue.tick(); self.assertFalse(self.native.sent)
        self.queue.change('thread','a',0,'edit','updated')
        item=self.queue.list('thread')[0]
        self.assertEqual('updated',item['text']); self.assertEqual('image',item['input'][1]['type'])
        self.assertEqual('high',item['effort']); self.assertEqual('model',item['model'])
        with self.assertRaises(QueueConflict): self.queue.change('thread','a',0,'remove')
        self.queue.change('thread','a',1,'remove'); self.assertEqual([], self.queue.list('thread'))
    def test_reorder_requires_current_order_and_exact_membership(self):
        for id in ('a','b','c'): self.add(id)
        self.queue.reorder('thread',['c','a','b'],['a','b','c'])
        self.assertEqual(['c','a','b'], [r['id'] for r in self.queue.list('thread')])
        for ids,expected in [(['a','b','c'],['a','b','c']), (['c','a','a'],['c','a','b'])]:
            with self.assertRaises(QueueConflict): self.queue.reorder('thread',ids,expected)
    def test_uncertain_send_blocks_following_work_and_cannot_be_replayed(self):
        self.add('a'); self.add('b'); self.native.error=TimeoutError()
        self.queue.tick(); self.queue.tick()
        item=self.queue.list('thread')[0]; self.assertEqual('unknown',item['status'])
        self.assertEqual(1,len(self.native.sent))
        with self.assertRaises(QueueConflict): self.queue.change('thread','a',item['revision'],'edit','retry')
        self.queue.change('thread','a',item['revision'],'remove')
        self.native.error=None; self.queue.tick(); self.assertEqual(2,len(self.native.sent))
    def test_restart_marks_dispatching_unknown_without_resending(self):
        self.add('a'); self.queue._set('a','dispatching')
        with patch.object(self.queue, '_run'):
            self.queue.start(); self.queue.worker.join()
        self.queue.tick(); self.assertEqual('unknown',self.queue.list('thread')[0]['status']); self.assertFalse(self.native.sent)
    def test_revoked_connection_cannot_auto_dispatch(self):
        self.add('a')
        with self.db.transaction() as c: c.execute("UPDATE connections SET revoked_at=1")
        self.queue.tick(); self.assertFalse(self.native.sent)
        self.assertEqual('failed',self.queue.list('thread')[0]['status'])
    def test_steer_requires_expected_turn_and_consumes_item_once(self):
        self.add('a'); self.native.snapshot['activeTurnId']='active'
        with self.assertRaises(QueueConflict): self.queue.steer('thread','a',0,'old')
        self.queue.steer('thread','a',0,'active')
        self.assertEqual([],self.queue.list('thread'))
        with self.assertRaises(QueueConflict): self.queue.steer('thread','a',0,'active')
        self.assertEqual(1,len(self.native.sent))
    def test_missing_running_result_becomes_unknown_not_completed(self):
        self.add('a'); self.queue.tick(); self.queue.tick()
        self.assertEqual('unknown',self.queue.list('thread')[0]['status'])
        self.assertEqual(1,len(self.native.sent))

class PinAndMigrationTests(unittest.TestCase):
    def test_pin_uses_native_section_pagination_without_resuming(self):
        native=FakeNative(); original=native.request
        def request(method, params, **kwargs):
            if method=='threadSection/list':
                native.calls.append((method,params))
                return {'data': [{'id':'pin','name':'Pinned'}] if params['cursor'] else [], 'nextCursor':None if params['cursor'] else 'page2'}
            return original(method,params,**kwargs)
        native.request=request; service=CodexService(connection=native)
        service.pin_session('thread',True); service.pin_session('thread',False)
        moves=[params for method,params in native.calls if method=='thread/section/move']
        self.assertEqual(['pin',None],[m['sectionId'] for m in moves])
        self.assertFalse(any(method=='thread/resume' for method,_ in native.calls))
    def test_v1_migration_keeps_credentials_and_existing_rows(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'old.sqlite3'; db=Database(path)
            with db.transaction() as c:
                c.execute("INSERT INTO connections VALUES ('phone','keep-hash','Keep',1,NULL)")
                c.execute('DROP TABLE queued_tasks'); c.execute('DROP TABLE native_observations'); c.execute('DROP TABLE workspaces'); c.execute('ALTER TABLE todos DROP COLUMN extra_json'); c.execute('PRAGMA user_version=1')
            db.close(); db=Database(path)
            try:
                with db.read() as c:
                    self.assertEqual('keep-hash',c.execute('SELECT token_hash FROM connections').fetchone()[0])
                    self.assertEqual(SCHEMA_VERSION,c.execute('PRAGMA user_version').fetchone()[0])
                    self.assertEqual(0,c.execute('SELECT COUNT(*) FROM queued_tasks').fetchone()[0])
            finally: db.close()
