import copy
import json
import unittest
from unittest.mock import patch

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.questions import answer_input, pending_questions, QuestionClaims
from rivo_relay.database import Database
from rivo_relay.codex.observations import Observations
from rivo_relay.codex.sessions import CodexService
from rivo_relay.codex.owned_writer import SessionWriters
from relay_test_support.native import Backend
from relay_test_support.native import FakeNative


class QuestionTests(unittest.TestCase):
    def setUp(self):
        self.turn = {'id': 'turn', 'status': 'inProgress', 'items': [
            {'type': 'agentMessage', 'id': 'question', 'questions': [{'title': 'Which?', 'options': ['A', 'B']}]}]}
        self.key = next(iter(pending_questions(self.turn)))
        self.answers = [{'questionItemId': self.key, 'answer': 'Custom\n"text"'}]

    def test_identity_and_envelope_match_desktop(self):
        self.assertEqual('["request_user_input_async","question",0]', self.key)
        text = answer_input(self.turn, self.answers)[0]['text']
        self.assertEqual([{'questionItemId': self.key, 'question': 'Which?', 'answer': 'Custom\n"text"'}], json.loads(text.split('\n')[1]))

    def test_answered_questions_expire_but_rejected_steering_does_not(self):
        reply = {'type': 'steeringUserMessage', 'status': 'rejected', 'input': answer_input(self.turn, self.answers)}
        self.turn['items'].append(reply)
        self.assertIn(self.key, pending_questions(self.turn))
        reply['status'] = 'accepted'
        self.assertEqual({}, pending_questions(self.turn))
        with self.assertRaises(CodexError): answer_input(self.turn, self.answers)

    def test_invalid_duplicate_and_stale_answers_rejected(self):
        for answers in ([], None, [None], self.answers * 2, [{'questionItemId': self.key, 'answer': ' '}],
                        [{'questionItemId': 'stale', 'answer': 'A'}]):
            with self.subTest(answers=answers), self.assertRaises(CodexError): answer_input(self.turn, answers)

    def test_external_question_uses_existing_writer_without_resume(self):
        native = FakeNative()
        native.answer_questions = lambda params, **kwargs: native.request('turn/start' if kwargs.get('start_turn') else 'turn/steer', params)
        service = CodexService(connection=native)
        snapshot = {'thread': {'turns': [self.turn]}, 'activeTurnId': 'turn', 'canAnswerQuestions': True,
                    'readOnly': True, 'requests': []}
        with patch.object(service, 'get_session', return_value=snapshot), patch.object(service, '_resume', side_effect=AssertionError('must not acquire writer')):
            result = service.answer_questions('thread', 'turn', self.answers, 'submission')
        self.assertEqual('turn', result['turnId'])
        method, params = native.calls[-1]
        self.assertEqual('turn/steer', method)
        self.assertEqual('submission', params['clientUserMessageId'])
        self.assertEqual('turn', params['expectedTurnId'])

    def test_idle_desktop_question_starts_followup_on_same_owner_without_resume(self):
        native = FakeNative()
        native.answer_questions = lambda params, **kwargs: native.request('turn/start' if kwargs.get('start_turn') else 'turn/steer', params)
        service = CodexService(connection=native)
        self.turn['status'] = 'completed'
        snapshot = {'thread': {'turns': [self.turn]}, 'activeTurnId': None, 'status': 'idle',
                    'canAnswerQuestions': True, 'readOnly': True, 'requests': []}
        with patch.object(service, 'get_session', return_value=snapshot), patch.object(service, '_resume', side_effect=AssertionError('no takeover')):
            service.answer_questions('thread', 'turn', self.answers, 'submission')
        method, params = native.calls[-1]
        self.assertEqual('turn/start', method)
        self.assertNotIn('expectedTurnId', params)

    def test_changed_turn_or_unreachable_owner_never_dispatches(self):
        native = FakeNative()
        service = CodexService(connection=native)
        base = {'thread': {'turns': [self.turn]}, 'activeTurnId': 'turn', 'canAnswerQuestions': True}
        for override in ({'activeTurnId': 'new'}, {'canAnswerQuestions': False}, {'requests': [{'id': 1}]}):
            with patch.object(service, 'get_session', return_value={**base, **override}), self.assertRaises(CodexError):
                service.answer_questions('thread', 'turn', self.answers, 'submission')
        self.assertFalse(any(method.startswith('turn/') for method, _ in native.calls))

    def test_pool_only_allows_loaded_daemon_thread_and_never_creates_writer(self):
        metadata = Backend()
        pool = SessionWriters(metadata)
        self.addCleanup(pool.close)
        original = metadata.request
        def request(method, params, **kwargs):
            if method == 'thread/loaded/list': return {'data': ['desktop'], 'nextCursor': None}
            return original(method, params, **kwargs)
        with patch.object(metadata, 'request', side_effect=request), patch.object(pool, '_ensure_metadata'), \
             patch.object(pool, '_new_writer', side_effect=AssertionError('must not create writer')):
            self.assertTrue(pool.can_answer_questions('desktop'))
            pool.answer_questions({'threadId': 'desktop', 'expectedTurnId': 'turn', 'input': answer_input(self.turn, self.answers)})
            self.assertFalse(pool.owns('desktop'))
            with self.assertRaises(CodexError): pool.answer_questions({'threadId': 'unreachable'})
            # The general mutation guard remains intact.
            with self.assertRaises(CodexError): pool.request('turn/steer', {'threadId': 'desktop'})
        self.assertEqual(['turn/steer'], [method for method, _ in metadata.calls])


class QuestionRecoveryTests(unittest.TestCase):
    def test_claim_survives_restart_and_blocks_new_submission_id(self):
        import tempfile
        from pathlib import Path
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / 'recovery.db'
        db = Database(path)
        first = QuestionClaims(db)
        first.claim('thread', ['q'], 'old-submission')
        db.close()
        db = Database(path)
        self.addCleanup(db.close)
        reloaded = QuestionClaims(db)
        self.assertEqual({'q': 'pending'}, reloaded.states('thread'))
        with self.assertRaises(CodexError): reloaded.claim('thread', ['q'], 'new-submission')
        reloaded.finish('thread', ['q'], 'old-submission', 'unknown')
        with self.assertRaises(CodexError): QuestionClaims(db).claim('thread', ['q'], 'retry')
        for n in range(1002): Observations(db).put('other', 'usage', str(n), {})
        self.assertEqual({'q': 'unknown'}, QuestionClaims(db).states('thread'))

    def test_batch_claim_is_atomic_and_definite_rejection_can_retry(self):
        db = Database(':memory:')
        self.addCleanup(db.close)
        claims = QuestionClaims(db)
        claims.claim('thread', ['existing'], 'one')
        with self.assertRaises(CodexError): claims.claim('thread', ['new', 'existing'], 'two')
        self.assertNotIn('new', claims.states('thread'))
        claims.finish('thread', ['existing'], 'wrong-owner', None)
        self.assertIn('existing', claims.states('thread'))
        claims.finish('thread', ['existing'], 'one', None)
        claims.claim('thread', ['existing'], 'retry')

    def test_native_timeout_and_acceptance_block_stale_history_resubmission(self):
        for error in (None, CodexError('lost response', request_sent=True)):
            native = FakeNative()
            calls = []
            def send(params, **kwargs):
                calls.append(params)
                if error: raise error
                return {'turnId': 'turn'}
            native.answer_questions = send
            service = CodexService(connection=native)
            turn = {'id': 'turn', 'status': 'inProgress', 'items': [{'type': 'agentMessage', 'id': 'q', 'questions': [{'title': 'Q'}]}]}
            key = next(iter(pending_questions(turn)))
            snapshot = {'thread': {'turns': [turn]}, 'activeTurnId': 'turn', 'canAnswerQuestions': True}
            with patch.object(service, 'get_session', return_value=snapshot):
                if error:
                    with self.assertRaises(CodexError): service.answer_questions('thread', 'turn', [{'questionItemId': key, 'answer': 'A'}], 'one')
                else: service.answer_questions('thread', 'turn', [{'questionItemId': key, 'answer': 'A'}], 'one')
                with self.assertRaises(CodexError): service.answer_questions('thread', 'turn', [{'questionItemId': key, 'answer': 'A'}], 'two')
            self.assertEqual(1, len(calls))

    def test_definite_native_failure_releases_dispatch_fence(self):
        native = FakeNative()
        service = CodexService(connection=native)
        turn = {'id': 'turn', 'status': 'inProgress', 'items': [{'type': 'agentMessage', 'id': 'q', 'questions': [{'title': 'Q'}]}]}
        key = next(iter(pending_questions(turn)))
        snapshot = {'thread': {'turns': [turn]}, 'activeTurnId': 'turn', 'canAnswerQuestions': True}
        with patch.object(service, 'get_session', return_value=snapshot), patch.object(
                native, 'answer_questions', create=True, side_effect=[CodexError('not sent'), {'turnId': 'turn'}]) as send:
            with self.assertRaises(CodexError):
                service.answer_questions('thread', 'turn', [{'questionItemId': key, 'answer': 'A'}], 'one')
            service.answer_questions('thread', 'turn', [{'questionItemId': key, 'answer': 'A'}], 'two')
        self.assertEqual(2, send.call_count)
