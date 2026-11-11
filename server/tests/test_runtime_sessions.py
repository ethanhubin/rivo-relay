"""HTTP core contract using a backend without a connection, writer or turns."""
import copy
import unittest

from relay_test_support.http import RelayHTTPFixture, NativeFailure
from rivo_relay.runtime_capabilities import declare


class NoTurnSessionBackend:
    def __init__(self):
        self.pending = True
        self.connected = True
        self.generation = 'instance-1'
        self.answers = []
        self.bad_receipt = False

    def runtime_capabilities(self, **kwargs):
        return declare({'session.send', 'session.create', 'interaction.respond'}, connected=self.connected)

    def get_session(self, session_id, **kwargs):
        state = {'schemaVersion': 1, 'sessionId': session_id,
                 'runtimeGeneration': self.generation, 'revision': 'snapshot-1',
                 'execution': {'status': 'waiting' if self.pending else 'idle'},
                 'synchronization': {'state': 'ready', 'historyReady': True},
                 'control': {'state': 'writable', 'canOperate': True},
                 'interactions': [{'id': 'question-1', 'revision': 'request-1',
                                  'kind': 'questions', 'status': 'pending', 'title': 'Question',
                                  'payload': {'questions': []}, 'choices': []}] if self.pending else []}
        return {'sessionState': state, 'thread': {'id': session_id, 'turns': []}}

    def respond_interaction(self, session_id, generation, interaction_id, revision, response):
        if not self.pending or (session_id, generation, interaction_id, revision) != (
                'session-1', self.generation, 'question-1', 'request-1'):
            raise NativeFailure('request_expired')
        self.answers.append(copy.deepcopy(response))
        self.pending = False
        if self.bad_receipt:
            return {'threadId': session_id}
        return {'threadId': session_id, 'interactionId': interaction_id,
                'runtimeGeneration': generation, 'revision': revision}

    def recover_submission(self, record):
        return None

    def close(self):
        pass


class RuntimeSessionHttpTests(RelayHTTPFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.backend = NoTurnSessionBackend()
        self.app.backend = self.backend
        self.app.sessions._backend = self.backend
        self.payload = {'submissionId': 'answer-1', 'sessionId': 'session-1',
                        'runtimeGeneration': 'instance-1', 'interactionId': 'question-1',
                        'revision': 'request-1', 'response': {'answers': {'name': 'test'}}}

    def answer(self, **changes):
        return self.request('POST', '/v1/interactions/respond', {**self.payload, **changes}, token=self.token)

    def test_snapshot_preserves_adapter_cut_without_native_internals(self):
        status, result, _ = self.request('GET', '/v1/threads/session-1', token=self.token)
        self.assertEqual(200, status)
        self.assertEqual(self.backend.get_session('session-1')['sessionState'], result['sessionState'])
        self.assertNotIn('id', result['sessionState']['execution'])
        self.assertEqual([], result['thread']['turns'])
        self.assertTrue(result['capabilities']['operations']['interaction.respond']['available'])

    def test_accepted_replay_survives_resolution_and_disconnection(self):
        _, accepted, _ = self.answer()
        self.assertEqual('accepted', accepted['status'])
        self.assertEqual('mutation-applied', accepted['receipt']['kind'])
        self.assertEqual('question-1', accepted['receipt']['interactionId'])
        self.backend.connected = False
        self.backend.generation = 'instance-2'
        self.assertEqual(accepted, self.answer()[1])
        self.assertEqual(1, len(self.backend.answers))

    def test_new_submission_cannot_answer_old_scope_revision_or_generation(self):
        for field in ('sessionId', 'interactionId', 'runtimeGeneration', 'revision'):
            with self.subTest(field=field):
                _, result, _ = self.answer(submissionId='stale-' + field, **{field: 'old'})
                self.assertEqual('failed', result['status'])
                self.assertEqual('request_expired', result['errorCode'])
        self.assertEqual([], self.backend.answers)

    def test_other_controller_resolution_expires_the_displayed_request(self):
        self.backend.pending = False
        _, result, _ = self.answer()
        self.assertEqual('failed', result['status'])
        self.assertEqual('request_expired', result['errorCode'])
        _, snapshot, _ = self.request('GET', '/v1/threads/session-1', token=self.token)
        self.assertEqual([], snapshot['sessionState']['interactions'])

    def test_missing_identity_and_response_rejected_before_dispatch(self):
        for field in ('sessionId', 'interactionId', 'runtimeGeneration', 'revision', 'response'):
            with self.subTest(field=field):
                self.assertEqual(400, self.answer(**{field: None})[0])
        self.assertEqual([], self.backend.answers)

    def test_same_submission_cannot_change_interaction_identity(self):
        self.assertEqual('accepted', self.answer()[1]['status'])
        self.assertEqual(409, self.answer(revision='request-2')[0])
        self.assertEqual(1, len(self.backend.answers))

    def test_unconfirmed_native_answer_stays_unknown_and_is_not_resent(self):
        self.backend.bad_receipt = True
        _, result, _ = self.answer()
        self.assertEqual('unknown', result['status'])
        self.assertEqual(result, self.answer()[1])
        self.assertEqual(1, len(self.backend.answers))
        status, reconciled, _ = self.request('GET', '/v1/submissions/answer-1', token=self.token)
        self.assertEqual(200, status)
        self.assertEqual('unknown', reconciled['status'])

    def test_missing_optional_interaction_service_fails_closed(self):
        self.backend.respond_interaction = None
        _, result, _ = self.answer()
        self.assertEqual('failed', result['status'])
        self.assertEqual('runtime_unsupported', result['errorCode'])


if __name__ == '__main__':
    unittest.main()
