"""Creation recovery, rejected input and native activity use positive evidence."""
import io
import unittest

from relay_test_support.http import RelayHTTPFixture
from rivo_relay.codex.connection import CodexError
from rivo_relay.dsh import DshService
from rivo_relay.submissions import SubmissionConflict
import test_dsh_features
from test_dsh import FakeBridge


class DshRecoveryHttpTests(RelayHTTPFixture, unittest.TestCase):
    def _start(self, bus=None):
        test_dsh_features.DshFeatureHttpTests._start(self, bus)

    def test_pre_prompt_rejection_is_failed_but_unknown_certainty_cannot_be_retried(self):
        for certainty, expected in [('rejected', 'failed'), ('unknown', 'unknown'), ('unrecognised', 'unknown')]:
            self.bridge.errors['prompt'] = {'code': 'session/model-unavailable',
                'message': 'before prompt', 'certainty': certainty, 'uncertain': False}
            payload = {'submissionId': certainty, 'text': 'hello'}
            _, result, _ = self.request('POST', '/v1/threads/s/turns', payload, token=self.token)
            self.assertEqual(expected, result['status'])
            _, repeated, _ = self.request('POST', '/v1/threads/s/turns', payload, token=self.token)
            self.assertEqual(result, repeated)
            if expected == 'failed':
                self.assertEqual('session/model-unavailable', result['errorCode'])
        self.assertEqual({}, self.bridge.prompts)

    def test_legacy_bridge_rejection_stays_compatible(self):
        self.bridge.errors['prompt'] = {'code': 'invalid_argument', 'message': 'invalid', 'uncertain': False}
        _, result, _ = self.request('POST', '/v1/threads/s/turns', {'submissionId': 'legacy', 'text': 'hi'}, token=self.token)
        self.assertEqual('failed', result['status'])
        self.assertEqual('invalid_argument', result['errorCode'])

    def test_lost_creation_response_recovers_exact_intent_after_relay_restart(self):
        self.bridge.drop_create_reply = True
        payload = {'submissionId': 'create-lost', 'projectId': 'p'}
        _, first, _ = self.request('POST', '/v1/threads', payload, token=self.token)
        self.assertEqual('unknown', first['status'])
        self.assertIsNone(first['receipt'])
        record = self.app.submissions.get('create-lost')
        identity = record['intent']['sessionId']
        self.assertEqual({'kind': 'session-create', 'sessionId': identity, 'projectId': 'p'}, record['intent'])
        self.assertEqual({identity: 'p'}, self.bridge.created)
        self._stop()
        self._start()
        self.bridge.created[identity] = 'p'  # Native session survives Relay restart.
        _, recovered, _ = self.request('GET', '/v1/submissions/create-lost', token=self.token)
        self.assertEqual('accepted', recovered['status'])
        self.assertEqual(identity, recovered['receipt']['threadId'])
        _, repeated, _ = self.request('POST', '/v1/threads', payload, token=self.token)
        self.assertEqual(recovered, repeated)
        self.assertFalse(any(method == 'create' for method, _ in self.bridge.calls))

    def test_creation_without_matching_project_proof_remains_unknown(self):
        self.bridge.drop_create_reply = True
        payload = {'submissionId': 'create-mismatch', 'projectId': 'p'}
        self.request('POST', '/v1/threads', payload, token=self.token)
        identity = self.app.submissions.get('create-mismatch')['intent']['sessionId']
        self.bridge.created[identity] = 'different-project'
        _, result, _ = self.request('GET', '/v1/submissions/create-mismatch', token=self.token)
        self.assertEqual('unknown', result['status'])
        self.assertEqual(1, len(self.bridge.created))
        self.assertIsNone(result['receipt'])

    def test_creation_intent_cannot_be_replaced_or_accepted_as_another_session(self):
        self.bridge.drop_create_reply = True
        self.request('POST', '/v1/threads', {'submissionId': 'bound', 'projectId': 'p'}, token=self.token)
        with self.assertRaises(SubmissionConflict):
            self.app.submissions.mark_accepted('bound', {'threadId': 'someone-else'})
        self.assertEqual('unknown', self.app.submissions.get('bound')['status'])

    def test_attachment_expiry_and_scope_errors_remain_actionable(self):
        staged = self.native.upload_attachment_stream('s', 'note.txt', io.BytesIO(b'note'), 4)
        _, result, _ = self.request('POST', '/v1/threads/other/turns',
            {'submissionId': 'scope', 'input': [staged]}, token=self.token)
        self.assertEqual(('failed', 'attachment_scope'), (result['status'], result['errorCode']))
        clock = self.native._uploads.clock
        self.native._uploads.clock = lambda: clock() + 86401
        _, result, _ = self.request('POST', '/v1/threads/s/turns',
            {'submissionId': 'expired', 'input': [staged]}, token=self.token)
        self.assertEqual(('failed', 'attachment_expired'), (result['status'], result['errorCode']))
        self.assertEqual([], self.bridge.uploads)
        self.assertEqual({}, self.bridge.prompts)


class DshNativeStateTests(unittest.TestCase):
    def setUp(self):
        self.bridge = FakeBridge()
        class Transport:
            call = staticmethod(self.bridge.rpc)
        self.events = []
        self.service = DshService('/tmp/unused', transport=Transport(), on_event=self.events.append)
        self.service._refresh_status()

    def test_native_catalog_waiting_hints_clear_without_history_reads(self):
        original = self.bridge.rpc
        waiting = ['question', 'approval']
        def call(method, params=None):
            result = original(method, params)
            if method == 'sessions': result['items'][0]['pendingInput'] = list(waiting)
            return result
        self.service.transport.call = call
        activity = self.service.active_tasks()
        self.assertEqual(['approval', 'question'], activity['tasks'][0]['responseKinds'])
        self.assertEqual('waiting', activity['tasks'][0]['status'])
        self.assertEqual(['approval', 'question'], self.service.list_sessions('p')[0]['responseKinds'])
        waiting.clear()
        refreshed = self.service.active_tasks()
        self.assertEqual([], refreshed['tasks'])
        self.assertGreater(refreshed['version'], activity['version'])
        self.assertEqual([], self.service.list_sessions('p')[0]['responseKinds'])

    def test_unopened_running_session_and_project_are_visible_without_history_read(self):
        self.bridge.running = True
        result = self.service.active_tasks()
        self.assertEqual([{'threadId': 's', 'projectId': 'p', 'status': 'running'}], result['tasks'])
        self.assertEqual(['s'], result['knownThreadIds'])
        self.assertEqual(set(), self.service._known)
        self.bridge.running = False
        newer = self.service.active_tasks()
        self.assertEqual([], newer['tasks'])
        self.assertEqual(['s'], newer['unknownThreadIds'])
        self.assertGreater(newer['version'], result['version'])

    def test_catalog_can_invalidate_old_running_snapshot_without_inventing_idle(self):
        self.bridge.running = True
        self.service.get_session('s')
        self.service.active_tasks()
        self.bridge.running = False
        result = self.service.active_tasks()
        self.assertEqual([], result['tasks'])
        self.assertNotIn('s', result['knownThreadIds'])
        self.assertIn('s', result['unknownThreadIds'])

    def test_late_catalog_cannot_restore_old_generation_or_control_cut(self):
        for change in (lambda: self.service._admit('new-generation'),
                       lambda: self.service._event({'type': 'session', 'sessionId': 's'})):
            self.bridge.generation = self.service._generation
            original = self.bridge.rpc
            def call(method, params=None):
                result = original(method, params)
                if method == 'sessions':
                    result['items'][0]['running'] = True
                    change()
                return result
            self.service.transport.call = call
            self.assertEqual([], self.service.active_tasks()['tasks'])

    def test_native_question_is_visible_and_open_turn_stays_cancellable(self):
        self.bridge.events.pop()  # Real turn/start with no turn/end.
        original = self.bridge.rpc
        def call(method, params=None):
            result = original(method, params)
            if method == 'snapshot':
                result['state']['pendingInput'] = ['question']
            return result
        self.service.transport.call = call
        snapshot = self.service.get_session('s')
        self.assertEqual('waiting', snapshot['status'])
        self.assertEqual('dsh-turn-7', snapshot['activeTurnId'])
        state = snapshot['sessionState']
        self.assertEqual({'status': 'waiting', 'id': 'dsh-turn-7'}, state['execution'])
        card, = state['interactions']
        self.assertEqual('unsupported', card['kind'])
        self.assertIn('DSH', card['title'])
        self.assertIn('问题回答', card['detail'])
        self.assertEqual([], card['choices'])
        self.assertEqual({}, card['payload'])
        self.assertTrue(state['control']['canOperate'])
        self.service.interrupt('s', turn_id='dsh-turn-7')
        self.assertEqual({'sessionId': 's', 'expectedTurn': 7}, self.bridge.last_cancel)
        with self.assertRaises(CodexError):
            self.service.respond_interaction('s', state['runtimeGeneration'], card['id'], card['revision'], {})

    def test_owned_question_has_no_duplicate_native_input_card(self):
        original = self.bridge.rpc
        self.bridge.requests = [{'key': 'owned', 'sessionId': 's', 'kind': 'questions',
                                 'questions': [{'id': 'q', 'question': 'Why?'}]}]
        def call(method, params=None):
            result = original(method, params)
            if method == 'snapshot': result['state']['pendingInput'] = ['question']
            return result
        self.service.transport.call = call
        state = self.service.get_session('s')['sessionState']
        self.assertEqual(['questions'], [row['kind'] for row in state['interactions']])

    def test_owned_approval_has_no_duplicate_native_input_card(self):
        original = self.bridge.rpc
        self.bridge.requests = [{'key': 'owned', 'sessionId': 's'}]
        def call(method, params=None):
            result = original(method, params)
            if method == 'snapshot': result['state']['pendingInput'] = ['approval']
            return result
        self.service.transport.call = call
        state = self.service.get_session('s')['sessionState']
        self.assertEqual(['approval'], [row['kind'] for row in state['interactions']])

    def test_reasoning_snapshot_and_live_delta_share_identity_and_native_index(self):
        self.bridge.events.pop()
        self.bridge.running = True
        original = self.bridge.rpc
        def call(method, params=None):
            result = original(method, params)
            if method == 'snapshot':
                result['page']['assistantStream'] = {'revision': 3, 'activeAttempt': {
                    'attemptId': 'a', 'turn': 7, 'nextIndex': 9,
                    'stream': [{'type': 'reasoning-chunks', 'texts': ['think']},
                               {'type': 'text-chunks', 'texts': ['answer']}]}}
            return result
        self.service.transport.call = call
        snapshot = self.service.get_session('s')
        item = next(item for item in snapshot['thread']['turns'][0]['items'] if item['id'] == 'live-reasoning-a')
        self.assertEqual(['think'], item['content'])
        self.assertEqual('answer', self.service._live['s']['text'])
        self.service._event({'type': 'assistant-stream', 'sessionId': 's', 'frame': {
            'type': 'chunk', 'attemptId': 'a', 'index': 9, 'revision': 4,
            'chunk': {'type': 'reasoning-delta', 'text': ' more'}}})
        delta = self.events[-1]
        self.assertEqual('item/reasoning/textDelta', delta['method'])
        self.assertEqual('live-reasoning-a', delta['params']['itemId'])
        self.assertEqual(5, delta['params']['relayOffset'])
        self.assertEqual(0, delta['params']['contentIndex'])
        self.assertEqual('think more', self.service._live['s']['reasoning'])
