import hashlib
from contextlib import nullcontext
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from rivo_relay.app import RelayApp
from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.sessions import CodexService
from rivo_relay.database import Database
from rivo_relay.pairing import PairingStore
from rivo_relay.submissions import SubmissionStore, SubmissionConflict
from rivo_relay.submission_recovery import inspect, recover, stopped
from relay_test_support.native import FakeNative


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.workspace = Path(self.temp.name)
        self.db = Database(self.workspace / 'data/relay.sqlite3'); self.addCleanup(self.db.close)
        pairing = PairingStore(self.db)
        self.owner = pairing.exchange(pairing.create()['code'], 'Phone')['connection_id']
        self.store = SubmissionStore(self.db)
        self.app = RelayApp.__new__(RelayApp)
        self.app.submissions = self.store; self.app.events = Mock()
        self.native = FakeNative(); self.service = CodexService(connection=self.native)

    def submit(self, sid='request', action=None, operation='thread.turns'):
        return self.app.submit(self.owner, sid, operation, 'thread', {'text': 'message'},
            action or (lambda: self.service.send('thread', 'message', client_message_id=sid)))

    def claim(self, sid='request'):
        return self.store.claim(sid, self.owner, 'thread.turns', 'thread', {'text': 'message'})

    def evidence(self, decision='not_dispatched'):
        p = self.workspace / 'proof.txt'; p.write_text('Reviewed correlated native request/process evidence')
        return {'expectedFingerprint': inspect(self.workspace, 'request')['fingerprint'],
                'decision': decision, 'reviewedBy': 'operator', 'reason': 'Confirmed request never reached turn/start',
                'dispatchNotSentVerified': True,
                'files': [{'path': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}]}

    def test_resume_transport_timeout_is_failed_not_unknown_and_not_replayed(self):
        with patch.object(self.service, '_resume', side_effect=CodexError('timeout', request_sent=True)):
            self.submit()
        row = self.store.get('request')
        self.assertEqual(('failed', 'turn_not_dispatched', 'preparing'),
                         (row['status'], row['error_code'], row['dispatch_phase']))
        self.submit()
        self.assertFalse(any(m == 'turn/start' for m, _ in self.native.calls))

    def test_actual_send_timeout_stays_unknown(self):
        original = self.native.request
        def request(method, params, **kwargs):
            if method == 'turn/start':
                self.assertEqual('dispatching', self.store.get('request')['dispatch_phase'])
                raise CodexError('timeout', request_sent=True)
            return original(method, params, **kwargs)
        self.native.request = request
        self.submit()
        self.assertEqual('unknown', self.store.get('request')['status'])
        self.assertEqual(0, self.store.recover_pending())

    def test_definite_native_rejection_remains_failed(self):
        original = self.native.request
        self.native.request = lambda m, p, **k: (_ for _ in ()).throw(CodexError('rejected')) if m == 'turn/start' else original(m, p, **k)
        self.submit()
        self.assertEqual('failed', self.store.get('request')['status'])

    def test_invalid_turn_receipt_remains_unknown(self):
        original = self.native.request
        self.native.request = lambda m, p, **k: {'turn': {}} if m == 'turn/start' else original(m, p, **k)
        self.submit()
        self.assertEqual('unknown', self.store.get('request')['status'])

    def test_receipt_survives_derived_state_failure(self):
        with patch.object(self.service, '_observe', side_effect=RuntimeError('derived state failed')):
            self.submit()
        self.assertEqual('accepted', self.store.get('request')['status'])
        self.assertEqual({'threadId': 'thread', 'turnId': 'turn'}, self.store.get('request')['receipt'])

    def test_dispatch_marker_write_failure_prevents_send(self):
        original = self.store.record_dispatch
        def record(sid, phase, receipt=None):
            if phase == 'dispatching': raise RuntimeError('disk full')
            original(sid, phase, receipt)
        with patch.object(self.store, 'record_dispatch', side_effect=record): self.submit()
        self.assertFalse(any(m == 'turn/start' for m, _ in self.native.calls))
        self.assertEqual('failed', self.store.get('request')['status'])

    def test_startup_recovers_only_proven_phases(self):
        for sid in ('legacy', 'prepared', 'sent', 'received'):
            self.claim(sid)
            if sid != 'legacy': self.store.record_dispatch(sid, 'preparing')
            if sid in ('sent', 'received'): self.store.record_dispatch(sid, 'dispatching')
            if sid == 'received': self.store.record_dispatch(sid, 'received', {'threadId': 'thread', 'turnId': 'turn'})
        self.assertEqual(4, self.store.recover_pending())
        self.assertEqual(['unknown', 'failed', 'unknown', 'accepted'],
                         [self.store.get(x)['status'] for x in ('legacy', 'prepared', 'sent', 'received')])
        self.assertEqual(0, self.store.recover_pending())
        for sid in ('legacy', 'prepared', 'sent', 'received'): self.assertFalse(self.claim(sid)['should_dispatch'])

    def test_live_reconcile_does_not_resolve_preparing(self):
        self.claim(); self.store.record_dispatch('request', 'preparing')
        self.assertEqual('pending', self.store.reconcile('request', self.owner)['status'])
        self.store.record_dispatch('request', 'dispatching')

    def test_uninstrumented_action_and_other_operations_remain_uncertain(self):
        for op in ('thread.turns', 'todo.dispatch'):
            def action(): raise CodexError('uncertain', request_sent=True)
            self.submit(sid=op, action=action, operation=op)
            self.assertEqual('unknown', self.store.get(op)['status'])

    def test_wrong_native_receipt_rejected(self):
        self.claim(); self.store.record_dispatch('request', 'preparing'); self.store.record_dispatch('request', 'dispatching')
        with self.assertRaises(SubmissionConflict):
            self.store.record_dispatch('request', 'received', {'threadId': 'other', 'turnId': 'turn'})

    def test_reviewed_legacy_recovery_dry_run_backup_audit_and_duplicate_fence(self):
        self.claim(); self.store.mark_unknown('request'); evidence = self.evidence()
        self.assertFalse(recover(self.workspace, 'request', evidence)['applied'])
        self.assertEqual('unknown', self.store.get('request')['status'])
        result = recover(self.workspace, 'request', evidence, apply=True)
        self.assertTrue(Path(result['backup']).is_file())
        self.assertEqual('failed', self.store.get('request')['status'])
        self.assertFalse(self.claim()['should_dispatch'])
        with self.db.read() as db:
            audit = json.loads(db.execute('SELECT recovery_json FROM submissions').fetchone()[0])
            self.assertEqual('unknown', audit['before']['record']['status'])
            self.assertEqual('operator', audit['evidence']['reviewedBy'])
        with self.assertRaises(SubmissionConflict): recover(self.workspace, 'request', evidence, apply=True)

    def test_reviewed_acceptance_preserves_receipt(self):
        self.claim(); self.store.mark_unknown('request'); evidence = self.evidence('accepted')
        evidence['receipt'] = {'threadId': 'thread', 'turnId': 'native-turn'}
        recover(self.workspace, 'request', evidence, apply=True)
        self.assertEqual(evidence['receipt'], self.store.get('request')['receipt'])
        self.assertEqual('accepted', self.store.get('request')['status'])

    def test_no_age_based_or_unsupported_negative_recovery(self):
        self.claim(); self.store.record_dispatch('request', 'preparing'); self.store.record_dispatch('request', 'dispatching')
        self.store.mark_unknown('request')
        with self.assertRaises(SubmissionConflict): recover(self.workspace, 'request', self.evidence(), apply=True)
        self.assertEqual('unknown', self.store.get('request')['status'])

    def test_tampered_evidence_and_missing_attestation_refused(self):
        self.claim(); self.store.mark_unknown('request'); evidence = self.evidence()
        evidence['dispatchNotSentVerified'] = False
        with self.assertRaises(ValueError): recover(self.workspace, 'request', evidence, apply=True)
        evidence['dispatchNotSentVerified'] = True
        Path(evidence['files'][0]['path']).write_text('changed')
        with self.assertRaises(ValueError): recover(self.workspace, 'request', evidence, apply=True)

    def test_running_service_lock_refuses_recovery(self):
        self.claim(); self.store.mark_unknown('request'); evidence = self.evidence()
        with stopped(self.workspace):
            with self.assertRaises(BlockingIOError): recover(self.workspace, 'request', evidence, apply=True)

    def test_conflicting_existing_receipt_rejected_in_preview(self):
        self.claim(); self.store.checkpoint('request', {'threadId': 'thread', 'turnId': 'original'})
        self.store.mark_unknown('request'); evidence = self.evidence('accepted')
        evidence['receipt'] = {'threadId': 'thread', 'turnId': 'different'}
        with self.assertRaises(SubmissionConflict): recover(self.workspace, 'request', evidence)
        self.assertEqual('unknown', self.store.get('request')['status'])

    def test_receipt_write_failure_keeps_sent_request_unknown(self):
        original = self.store.record_dispatch
        def record(sid, phase, receipt=None):
            if phase == 'received': raise RuntimeError('disk full after send')
            original(sid, phase, receipt)
        with patch.object(self.store, 'record_dispatch', side_effect=record): self.submit()
        self.assertTrue(any(m == 'turn/start' for m, _ in self.native.calls))
        self.assertEqual(('unknown', 'dispatching'),
                         (self.store.get('request')['status'], self.store.get('request')['dispatch_phase']))

    def test_observers_do_not_cross_concurrent_requests(self):
        from concurrent.futures import ThreadPoolExecutor
        import threading
        from rivo_relay.submission_dispatch import observe_dispatch, dispatch_phase
        barrier = threading.Barrier(2)
        def run(sid):
            phases = []
            with observe_dispatch(sid, 'thread', lambda phase, receipt: phases.append(phase)):
                barrier.wait()
                dispatch_phase(sid, 'thread', 'preparing')
                dispatch_phase('wrong-id', 'thread', 'dispatching')
            dispatch_phase(sid, 'thread', 'received')
            return phases
        with ThreadPoolExecutor(max_workers=2) as pool:
            self.assertEqual([['preparing'], ['preparing']], list(pool.map(run, ('first', 'second'))))

    def test_additive_migration_does_not_assign_stage_to_old_claims(self):
        import sqlite3
        self.claim(); self.store.mark_unknown('request')
        path = self.workspace / 'data/relay.sqlite3'
        with self.db.transaction() as db:
            db.execute('ALTER TABLE submissions DROP COLUMN dispatch_phase')
            db.execute('ALTER TABLE submissions DROP COLUMN recovery_json')
        reopened = Database(path)
        try:
            store = SubmissionStore(reopened)
            self.assertIsNone(store.get('request')['dispatch_phase'])
            self.assertEqual(0, store.recover_pending())
            self.assertEqual('unknown', store.get('request')['status'])
        finally:
            reopened.close()

    def test_update_gate_unblocks_only_after_evidence_recovery(self):
        import threading
        from types import SimpleNamespace
        from rivo_relay.update_gate import UpdateGate
        app = SimpleNamespace(db=self.db, queue=SimpleNamespace(_tick_lock=threading.Lock()))
        gate = UpdateGate()
        self.claim(); self.store.mark_unknown('request')
        self.assertFalse(gate._local_idle(app))
        self.assertEqual('submission_pending_or_unknown', gate.reason)
        recover(self.workspace, 'request', self.evidence(), apply=True)
        self.assertTrue(gate._local_idle(app))
        self.claim('still-uncertain'); self.store.record_dispatch('still-uncertain', 'preparing')
        self.store.record_dispatch('still-uncertain', 'dispatching'); self.store.mark_unknown('still-uncertain')
        self.store.recover_pending()
        self.assertFalse(gate._local_idle(app))

    def test_lost_turn_receipt_recovers_by_exact_client_id_without_replay(self):
        self.claim(); self.store.record_dispatch('request', 'preparing')
        self.store.record_dispatch('request', 'dispatching'); self.store.mark_unknown('request')
        native = Mock()
        native.history.return_value = {'threadId': 'thread', 'turns': [
            {'id': 'original-turn', 'items': [{'type': 'userMessage', 'clientId': 'request'}]}]}
        recovered = self.store.recover_native('request', self.owner, native)
        self.assertEqual('accepted', recovered['status'])
        self.assertEqual({'threadId': 'thread', 'turnId': 'original-turn'}, recovered['receipt'])
        self.assertFalse(self.claim()['should_dispatch'])
        native.send.assert_not_called()

    def test_history_absence_or_failure_never_authorizes_resend(self):
        self.claim(); self.store.mark_unknown('request')
        native = Mock()
        native.history.return_value = {'threadId': 'thread', 'turns': [
            {'id': 'unrelated', 'items': [{'type': 'userMessage', 'clientId': 'other', 'text': 'message'}]}]}
        self.assertEqual('unknown', self.store.recover_native('request', self.owner, native)['status'])
        native.history.side_effect = TimeoutError()
        self.assertEqual('unknown', self.store.recover_native('request', self.owner, native)['status'])
        self.assertFalse(self.claim()['should_dispatch'])

    def test_old_turn_recovery_continues_past_first_history_batch(self):
        self.claim(); self.store.mark_unknown('request')
        native = Mock()
        def page(thread, cursor=None, **kwargs):
            if cursor is None:
                return {'turns': [], 'nativeRevision': 'r', 'nextCursor': '1'}
            number = int(cursor)
            return {'turns': [{'id': 'old-turn', 'items': [{'type': 'userMessage', 'clientId': 'request'}]}] if number == 8 else [],
                    'nextCursor': str(number + 1) if number < 8 else None}
        native.history.side_effect = page
        self.assertEqual('unknown', self.store.recover_native('request', self.owner, native)['status'])
        self.assertEqual('accepted', self.store.recover_native('request', self.owner, native)['status'])
        self.assertEqual([str(x) for x in range(1, 9)], [call.kwargs['cursor'] for call in native.history.call_args_list if 'cursor' in call.kwargs])

    def test_receipt_query_does_not_finalize_a_live_dispatch(self):
        self.claim(); self.store.record_dispatch('request', 'preparing')
        native = Mock()
        self.assertEqual('pending', self.store.recover_native('request', self.owner, native)['status'])
        native.history.assert_not_called()
        self.store.record_dispatch('request', 'dispatching')

    def test_create_and_fork_receipts_survive_restart(self):
        for operation in ('thread.create', 'thread.fork'):
            self.store.claim(operation, self.owner, operation, 'source', {})
            self.store.record_dispatch(operation, 'preparing')
            self.store.record_dispatch(operation, 'dispatching')
            self.store.record_dispatch(operation, 'received', {'threadId': 'created-' + operation})
        reopened = Database(self.workspace / 'data/relay.sqlite3')
        try:
            recovered = SubmissionStore(reopened)
            self.assertEqual(2, recovered.recover_pending())
            for operation in ('thread.create', 'thread.fork'):
                self.assertEqual('accepted', recovered.get(operation)['status'])
                self.assertFalse(recovered.claim(operation, self.owner, operation, 'source', {})['should_dispatch'])
        finally:
            reopened.close()

    def test_create_preparation_can_fail_but_sent_create_stays_fenced(self):
        for sid in ('preparing-create', 'sent-create'):
            self.store.claim(sid, self.owner, 'thread.create', 'project', {})
            self.store.record_dispatch(sid, 'preparing')
        self.store.record_dispatch('sent-create', 'dispatching')
        self.store.recover_pending()
        self.assertEqual('failed', self.store.get('preparing-create')['status'])
        self.assertEqual('unknown', self.store.get('sent-create')['status'])

    def test_recovery_checks_receipt_owner_before_native_reads(self):
        self.claim(); self.store.mark_unknown('request')
        native = Mock()
        from rivo_relay.submissions import SubmissionNotFound
        with self.assertRaises(SubmissionNotFound):
            self.store.recover_native('request', 'someone-else', native)
        native.history.assert_not_called()

    def test_committed_queue_enqueue_recovers_without_enqueuing_twice(self):
        self.store.claim('request', self.owner, 'thread.queue.enqueue', 'thread', {})
        with self.db.transaction() as db:
            db.execute("INSERT INTO queued_tasks(id,connection_id,thread_id,payload_json,status,position,created_at) VALUES(?,?,?,'{}','done',0,0)",
                       ('request', self.owner, 'thread'))
        self.store.recover_pending()
        recovered = self.store.recover_native('request', self.owner, Mock())
        self.assertEqual('accepted', recovered['status'])
        self.assertEqual({'threadId': 'thread', 'itemId': 'request'}, recovered['receipt'])

    def test_worktree_preparation_failure_keeps_its_error_code(self):
        self.service.start()
        with patch.object(self.service, 'workspaces') as workspaces:
            workspaces.create.side_effect = CodexError('Git could not create worktree', code='git_operation_failed')
            result = self.app.submit(self.owner, 'create', 'thread.create', 'project', {},
                lambda: self.service.create_session('project', worktree_ref='ref'))
        self.assertEqual(('failed', 'git_operation_failed'), (result['status'], result['errorCode']))
        self.assertFalse(any(method == 'thread/start' for method, _ in self.native.calls))

    def test_create_receipt_is_saved_before_derived_metadata_failure(self):
        self.service.start()
        with patch.object(self.service.projects, 'invalidate', side_effect=RuntimeError('derived metadata failed')):
            self.app.submit(self.owner, 'create', 'thread.create', 'project', {},
                            lambda: self.service.create_session('project'))
        self.assertEqual('accepted', self.store.get('create')['status'])
        self.assertEqual({'threadId': 'thread'}, self.store.get('create')['receipt'])
        self.app.submit(self.owner, 'create', 'thread.create', 'project', {},
                        lambda: self.fail('duplicate create'))
        self.assertEqual(1, sum(m == 'thread/start' for m, _ in self.native.calls))

    def test_local_queue_receipt_and_change_commit_atomically(self):
        from rivo_relay.queue import ExecutionQueue
        queue = ExecutionQueue(self.db, Mock(), Mock())
        self.addCleanup(queue._executor.shutdown)
        queue._gate = lambda _: nullcontext()
        with patch.object(queue, '_notify', side_effect=RuntimeError('notification failed')):
            result = self.app.submit(self.owner, 'enqueue', 'thread.queue.enqueue', 'thread', {},
                lambda: queue.enqueue('thread', 'enqueue', self.owner, {'input': []}))
        self.assertEqual('accepted', result['status'])
        with patch.object(queue, '_notify', side_effect=RuntimeError('notification failed')):
            result = self.app.submit(self.owner, 'remove', 'thread.queue.remove', 'thread', {},
                lambda: queue.change('thread', 'enqueue', 0, 'remove'))
        self.assertEqual('accepted', result['status'])
        self.assertEqual([], queue.list('thread'))
        self.assertEqual(0, self.store.recover_pending())

    def test_local_queue_receipt_failure_rolls_back_mutation(self):
        from rivo_relay.queue import ExecutionQueue
        queue = ExecutionQueue(self.db, Mock(), Mock())
        self.addCleanup(queue._executor.shutdown)
        queue._gate = lambda _: nullcontext()
        with patch.object(self.store, 'mark_accepted', side_effect=RuntimeError('disk full')):
            result = self.app.submit(self.owner, 'enqueue', 'thread.queue.enqueue', 'thread', {},
                lambda: queue.enqueue('thread', 'enqueue', self.owner, {'input': []}))
        self.assertEqual('failed', result['status'])
        self.assertEqual([], queue.list('thread'))

    def test_failed_history_cursor_is_reset_for_next_automatic_poll(self):
        self.claim(); self.store.mark_unknown('request')
        native = Mock()
        head = {'turns': [], 'nativeRevision': 'r', 'nextCursor': 'first'}
        self.store._history_cursors['request'] = ('r', 'expired')
        native.history.side_effect = [head, ValueError('cursor expired')]
        self.assertEqual('unknown', self.store.recover_native('request', self.owner, native)['status'])
        self.assertNotIn('request', self.store._history_cursors)
        native.history.side_effect = [head, {'turns': [{'id': 'turn', 'items': [{'type': 'userMessage', 'clientId': 'request'}]}]}]
        self.assertEqual('accepted', self.store.recover_native('request', self.owner, native)['status'])
        self.assertEqual('first', native.history.call_args.kwargs['cursor'])

    def test_local_transaction_crash_before_commit_is_definitive_failure(self):
        self.store.claim('local', self.owner, 'thread.queue.remove', 'thread', {})
        self.store.begin_local('local')
        self.store.recover_pending()
        self.assertEqual(('failed', 'operation_not_dispatched'),
            (self.store.get('local')['status'], self.store.get('local')['error_code']))
        self.assertFalse(self.store.claim('local', self.owner, 'thread.queue.remove', 'thread', {})['should_dispatch'])

    def test_steer_recovers_by_checkpointed_queue_identity_not_new_submission_id(self):
        self.store.claim('steer-request', self.owner, 'thread.queue.steer', 'thread', {})
        self.store.checkpoint('steer-request', {'threadId': 'thread', 'itemId': 'original-item'})
        self.store.mark_unknown('steer-request')
        native = Mock()
        native.history.return_value = {'turns': [{'id': 'active-turn', 'items': [
            {'type': 'userMessage', 'clientId': 'original-item'}]}]}
        result = self.store.recover_native('steer-request', self.owner, native)
        self.assertEqual('accepted', result['status'])
        self.assertEqual({'threadId': 'thread', 'itemId': 'original-item', 'turnId': 'active-turn'}, result['receipt'])

    def test_history_timeout_keeps_progress_without_replaying_mutation(self):
        self.claim(); self.store.mark_unknown('request')
        native = Mock()
        head = {'turns': [], 'nativeRevision': 'r', 'nextCursor': 'first'}
        native.history.side_effect = [head, {'turns': [], 'nextCursor': 'second'}, TimeoutError()]
        self.assertEqual('unknown', self.store.recover_native('request', self.owner, native)['status'])
        self.assertEqual(('r', 'second'), self.store._history_cursors['request'])
        native.history.side_effect = [head, {'turns': [{'id': 'turn', 'items': [{'type': 'userMessage', 'clientId': 'request'}]}]}]
        self.assertEqual('accepted', self.store.recover_native('request', self.owner, native)['status'])
        self.assertEqual('second', native.history.call_args.kwargs['cursor'])
        native.send.assert_not_called()

    def test_slow_recovery_does_not_starve_other_submission(self):
        from concurrent.futures import ThreadPoolExecutor
        import threading
        for sid in ('slow', 'fast'):
            self.claim(sid); self.store.mark_unknown(sid)
        entered, release = threading.Event(), threading.Event()
        slow = Mock()
        def blocked_history(*args, **kwargs):
            entered.set()
            if not release.wait(5): raise TimeoutError()
            return {'turns': []}
        slow.history.side_effect = blocked_history
        fast = Mock()
        fast.history.return_value = {'turns': [{'id': 'fast-turn', 'items': [{'type': 'userMessage', 'clientId': 'fast'}]}]}
        with ThreadPoolExecutor(max_workers=2) as pool:
            task = pool.submit(self.store.recover_native, 'slow', self.owner, slow)
            try:
                self.assertTrue(entered.wait(2))
                # The same ID is coalesced, a different ID still makes progress.
                self.assertEqual('unknown', self.store.recover_native('slow', self.owner, slow)['status'])
                self.assertEqual('accepted', self.store.recover_native('fast', self.owner, fast)['status'])
                self.assertEqual(1, slow.history.call_count)
            finally:
                release.set()
            task.result()

    def test_queue_outer_commit_failure_cannot_expose_false_acceptance(self):
        import sqlite3
        from rivo_relay.queue import ExecutionQueue
        queue = ExecutionQueue(self.db, Mock(), Mock())
        self.addCleanup(queue._executor.shutdown)
        queue._gate = lambda _: nullcontext()
        raw = self.db._connection
        class FailQueueCommit:
            failed = False
            def __getattr__(self, name): return getattr(raw, name)
            def commit(self):
                if not self.failed and raw.execute('SELECT count(*) FROM queued_tasks').fetchone()[0]:
                    self.failed = True
                    raise sqlite3.OperationalError('queue commit failed')
                return raw.commit()
        with patch.object(self.db, '_connection', FailQueueCommit()):
            result = self.app.submit(self.owner, 'commit-failure', 'thread.queue.enqueue', 'thread', {},
                lambda: queue.enqueue('thread', 'commit-failure', self.owner, {'input': []}))
        self.assertEqual('failed', result['status'])
        self.assertFalse(raw.in_transaction)
        self.assertEqual([], queue.list('thread'))
        self.assertEqual('failed', self.store.get('commit-failure')['status'])
