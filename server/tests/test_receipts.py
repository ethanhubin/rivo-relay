"""Acceptance contracts use a third backend with no turn or history protocol."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from rivo_relay.app import RelayApp
from rivo_relay.database import Database
from rivo_relay.pairing import PairingStore
from rivo_relay.receipts import decode_input_receipt, input_accepted, execution_started
from rivo_relay.runtime_capabilities import declare
from rivo_relay.submission_dispatch import dispatch_phase
from rivo_relay.submissions import SubmissionStore, SubmissionConflict


class NoTurnBackend:
    """Synthetic acceptance ledger, not a claim about any vendor's interface."""
    def __init__(self):
        self.accepted = set()
        self.sends = 0
        self.lose_reply = False

    def send(self, thread_id, submission_id):
        dispatch_phase(submission_id, thread_id, 'preparing')
        dispatch_phase(submission_id, thread_id, 'dispatching')
        self.sends += 1
        self.accepted.add((thread_id, submission_id))
        if self.lose_reply:
            raise TimeoutError('Accepted but response was lost')
        receipt = input_accepted(submission_id, thread_id)
        dispatch_phase(submission_id, thread_id, 'received', receipt)
        return receipt

    def runtime_capabilities(self, **kwargs):
        return declare({'session.send'})

    def recover_submission(self, record):
        if (record['target'], record['id']) in self.accepted:
            return input_accepted(record['id'], record['target'])
        return None


class ReceiptContractTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / 'relay.sqlite3'
        self.db = Database(self.path)
        self.addCleanup(self.db.close)
        pairing = PairingStore(self.db)
        self.owner = pairing.exchange(pairing.create()['code'], 'Phone')['connection_id']
        self.store = SubmissionStore(self.db)
        self.app = RelayApp.__new__(RelayApp)
        self.app.submissions, self.app.events = self.store, Mock()

    def submit(self, backend, sid='request'):
        self.app.codex = backend
        self.app.backend = backend
        return self.app.submit(self.owner, sid, 'thread.turns', 'session', {'text': 'same input'},
                               lambda: backend.send('session', sid))

    def claim(self, sid='request', operation='thread.turns'):
        return self.store.claim(sid, self.owner, operation, 'session', {})

    def test_third_backend_accepts_without_turn_and_duplicate_never_sends(self):
        backend = NoTurnBackend()
        result = self.submit(backend)
        self.assertEqual('accepted', result['status'])
        self.assertEqual({'kind': 'input-accepted', 'submissionId': 'request', 'threadId': 'session'}, result['receipt'])
        self.assertEqual(result, self.submit(backend))
        self.assertEqual(1, backend.sends)

    def test_third_backend_lost_reply_recovers_after_restart_without_resend(self):
        backend = NoTurnBackend()
        backend.lose_reply = True
        self.assertEqual('unknown', self.submit(backend)['status'])
        reopened = Database(self.path)
        self.addCleanup(reopened.close)
        store = SubmissionStore(reopened)
        store.recover_pending()
        recovered = store.recover_native('request', self.owner, backend)
        self.assertEqual('accepted', recovered['status'])
        self.assertEqual('input-accepted', recovered['receipt']['kind'])
        self.assertEqual(1, backend.sends)
        self.assertEqual('accepted', self.submit(backend)['status'])
        self.assertEqual(1, backend.sends)

    def test_no_evidence_stays_unknown_even_if_legacy_history_looks_positive(self):
        self.claim()
        self.store.mark_unknown('request')
        class HistoryOnly:
            history = Mock(return_value={'turns': [{'id': 't', 'items': [{'type': 'userMessage', 'clientId': 'request'}]}]})
        backend = HistoryOnly()
        self.assertEqual('unknown', self.store.recover_native('request', self.owner, backend)['status'])
        backend.history.assert_not_called()
        self.assertFalse(self.claim()['should_dispatch'])

    def test_wrong_submission_session_or_acceptance_kind_cannot_confirm(self):
        for receipt in (input_accepted('wrong', 'session'), input_accepted('request', 'wrong'),
                        {'kind': 'mutation-applied', 'submissionId': 'request', 'threadId': 'session'}):
            self.claim()
            with self.subTest(receipt=receipt), self.assertRaises(SubmissionConflict):
                self.store.mark_accepted('request', receipt)
        for operation in ('thread.continue', 'thread.answers', 'thread.queue.steer'):
            self.claim(operation, operation)
            with self.assertRaises(SubmissionConflict):
                self.store.mark_accepted(operation, input_accepted(operation, 'session'))

    def test_explicit_neutral_kind_never_falls_back_to_valid_legacy_turn(self):
        self.claim()
        for fields in ({'kind': 'execution-started'}, {'kind': 'future-kind'},
                       {'kind': 'execution-started', 'submissionId': 'request'},
                       {'kind': 'input-accepted', 'submissionId': 'request'}):
            receipt = {'threadId': 'session', 'turnId': 'native-turn', **fields}
            with self.subTest(receipt=receipt), self.assertRaises(ValueError):
                self.store.mark_accepted('request', receipt)
        self.assertEqual('pending', self.store.get('request')['status'])

    def test_checkpoint_merge_preserves_aliases_and_native_execution_identity(self):
        self.claim()
        self.store.checkpoint('request', {'thread_id': 'session', 'todoId': 'todo'})
        receipt = execution_started('request', 'session', 'native-turn', turnId='native-turn')
        accepted = self.store.mark_accepted('request', receipt)
        self.assertEqual('todo', accepted['receipt']['todoId'])
        self.assertEqual('session', accepted['receipt']['thread_id'])
        self.assertEqual(accepted, self.store.mark_accepted('request', receipt))
        with self.assertRaises(SubmissionConflict):
            self.store.mark_accepted('request', {'executionId': 'other-turn'})
        with self.assertRaises(SubmissionConflict):
            self.store.mark_accepted('request', {'thread_id': 'other-session'})

    def test_old_durable_receipts_decode_and_recover_without_rewriting_identities(self):
        for sid, receipt in (
            ('old-codex', {'threadId': 'session', 'turnId': 'native-turn'}),
            ('old-dsh', {'threadId': 'session', 'runtime': 'dsh', 'acceptance': 'input', 'requestId': 'old-dsh'}),
        ):
            self.claim(sid)
            with self.db.transaction() as db:
                db.execute("UPDATE submissions SET dispatch_phase='received', receipt_json=? WHERE id=?", (json.dumps(receipt), sid))
            self.assertIsNotNone(decode_input_receipt(receipt, sid))
        self.assertEqual(2, self.store.recover_pending())
        self.assertEqual('accepted', self.store.get('old-codex')['status'])
        self.assertEqual('accepted', self.store.get('old-dsh')['status'])
        self.assertNotIn('kind', self.store.get('old-dsh')['receipt'])

    def test_thread_checkpoint_alone_is_not_input_acceptance(self):
        self.claim()
        self.store.checkpoint('request', {'threadId': 'session'})
        with self.assertRaises(SubmissionConflict):
            self.store.mark_accepted('request', {'threadId': 'session'})
        self.store.recover_pending()
        self.assertEqual('unknown', self.store.get('request')['status'])

    def test_received_boundary_cannot_replace_checkpointed_identity(self):
        self.claim()
        self.store.checkpoint('request', {'threadId': 'session', 'turnId': 'original'})
        self.store.record_dispatch('request', 'preparing')
        self.store.record_dispatch('request', 'dispatching')
        with self.assertRaises(SubmissionConflict):
            self.store.record_dispatch('request', 'received', execution_started('request', 'session', 'other', turnId='other'))
        self.assertEqual('dispatching', self.store.get('request')['dispatch_phase'])
        self.assertEqual('original', self.store.get('request')['receipt']['turnId'])
