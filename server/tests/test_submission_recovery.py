import hashlib
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
