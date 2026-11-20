import json
import unittest
from unittest.mock import Mock

from relay_test_support.native import FakeNative
from rivo_relay.codex.sessions import CodexService
from rivo_relay.codex.projection import response_kinds, response_waiting
from rivo_relay.codex.questions import OPEN, CLOSE


def question(item_id='q'):
    return {'id': item_id, 'type': 'agentMessage', 'questions': [{'title': 'Choose', 'options': ['A', 'B']}]}


def identity(item_id='q'):
    return json.dumps(['request_user_input_async', item_id, 0], separators=(',', ':'))


def reply(item_id='a', status='accepted'):
    return {'id': item_id, 'type': 'steeringUserMessage', 'status': status,
            'input': [{'type': 'text', 'text': OPEN + json.dumps([
                {'questionItemId': identity(), 'question': 'Choose', 'answer': 'A'}]) + CLOSE}]}


class ResponseHintsTests(unittest.TestCase):
    def service(self):
        native = FakeNative()
        original = native.request
        self.metadata = {'id': 'thread', 'projectId': 'p', 'status': {'type': 'idle'}, 'updatedAt': 1}
        self.head = []
        def request(method, params=None, **kwargs):
            if method == 'project/list':
                return {'data': [{'id': 'p', 'roots': []}]}
            if method == 'thread/list':
                return {'data': [dict(self.metadata)]}
            if method == 'thread/turns/list':
                return {'data': list(self.head)}
            return original(method, params or {}, **kwargs)
        native.request = request
        service = CodexService(connection=native)
        service.projects.external_worktrees.associations = Mock(return_value={})
        service.read_goal = Mock(return_value={'goal': None})
        service._external_running = Mock(return_value={})
        service._external_execution = Mock(return_value={})
        return service

    def emit(self, service, method, turn='turn', request_id=None, **params):
        event = {'method': method, 'params': {'threadId': 'thread', 'turnId': turn, **params}}
        if method.startswith('turn/'):
            event['params']['turn'] = {'id': turn, 'status': 'completed' if method == 'turn/completed' else 'inProgress', 'items': []}
        if request_id is not None:
            event['id'] = request_id
        service._event(event)

    def row(self, service):
        return next((r for r in service.activity.snapshot()['tasks'] if r['threadId'] == 'thread'), {})

    def test_history_only_question_changes_notify_once_without_execution_change(self):
        s = self.service()
        notices = []
        s.activity.on_change = notices.append
        s.activity.observe_execution('thread', 'idle', token=s.activity.read_token())
        notices.clear()
        head = [{'id': 'turn', 'status': 'completed', 'items': [question()]}]
        for _ in range(2):
            s.activity.observe_questions('thread', head, token=s.activity.read_token())
        self.assertEqual(1, len(notices))
        self.assertEqual(['thread'], notices[0]['params']['threadIds'])
        self.assertEqual('idle', self.row(s)['status'])
        self.assertEqual(['question'], self.row(s)['responseKinds'])
        head[0]['items'].append(reply())
        for _ in range(2):
            s.activity.observe_questions('thread', head, token=s.activity.read_token())
        self.assertEqual(2, len(notices))
        self.assertFalse(self.row(s).get('responseKinds'))

    def test_resolved_then_new_request_does_not_resurrect_type(self):
        s = self.service()
        self.emit(s, 'turn/started')
        self.emit(s, 'item/commandExecution/requestApproval', request_id='approval')
        self.emit(s, 'serverRequest/resolved', requestId='approval')
        self.emit(s, 'item/tool/requestUserInput', request_id='question')
        self.assertEqual(['question'], self.row(s)['responseKinds'])
        self.assertEqual([], s.activity.snapshot()['unknownThreadIds'])
        # Same-turn duplicate/replayed requests must not restore a solved approval.
        self.emit(s, 'item/commandExecution/requestApproval', request_id='approval')
        self.assertEqual(['question'], self.row(s)['responseKinds'])
        self.assertEqual(1, len(s.interactions.list('thread')))

    def test_partial_resolution_and_duplicate_requests_use_ids(self):
        s = self.service()
        self.emit(s, 'turn/started')
        for request_id in ('a', 'b', 'b'):
            self.emit(s, 'item/permissions/requestApproval', request_id=request_id)
        self.emit(s, 'serverRequest/resolved', requestId='a')
        self.emit(s, 'item/tool/requestUserInput', request_id='q')
        self.assertEqual({'question', 'approval'}, set(self.row(s)['responseKinds']))
        self.emit(s, 'serverRequest/completed', requestId='b')
        self.emit(s, 'item/tool/requestUserInput', request_id='q')
        self.assertEqual(['question'], self.row(s)['responseKinds'])

    def test_completed_old_rpc_cannot_reappear_on_reconcile(self):
        s = self.service()
        self.emit(s, 'turn/started')
        self.emit(s, 'turn/completed')
        self.emit(s, 'item/commandExecution/requestApproval', request_id='late')
        for _ in range(3):
            s.activity.reconcile()
            self.assertFalse(s.interactions.list('thread'))
            self.assertNotIn('approval', self.row(s).get('responseKinds', []))

    def test_new_turn_clears_old_rpc_and_old_completion_preserves_new(self):
        s = self.service()
        self.emit(s, 'turn/started', turn='old')
        self.emit(s, 'item/tool/requestUserInput', turn='old', request_id='old')
        self.emit(s, 'turn/started', turn='new')
        self.emit(s, 'item/permissions/requestApproval', turn='new', request_id='new')
        self.emit(s, 'turn/completed', turn='old')
        self.emit(s, 'item/tool/requestUserInput', turn='old', request_id='late')
        self.assertEqual(['new'], [r['id'] for r in s.interactions.list('thread')])
        self.assertEqual(['approval'], self.row(s)['responseKinds'])

    def test_mcp_permission_and_form_have_single_correct_kind(self):
        native = {'type': 'active', 'activeFlags': ['waitingOnApproval']}
        for params, expected in [({'mode': 'form', '_meta': {'codex_approval_kind': 'mcp_tool_call'}}, 'approval'),
                                 ({'mode': 'form'}, 'question'), ({'mode': 'url'}, 'approval')]:
            request = {'method': 'mcpServer/elicitation/request', 'params': params}
            self.assertEqual([expected], response_kinds([request], native))

    def test_nonblocking_rpc_keeps_execution_running_and_question_visible(self):
        s = self.service()
        self.emit(s, 'turn/started')
        self.emit(s, 'item/tool/requestUserInput', request_id='q', isBlocking=False)
        self.assertEqual('running', self.row(s)['status'])
        self.assertEqual(['question'], self.row(s)['responseKinds'])
        self.emit(s, 'thread/status/changed', status={'type': 'active', 'activeFlags': ['waitingOnUserInput']})
        self.assertEqual('running', self.row(s)['status'])

    def test_async_question_survives_completion_without_outstanding_work(self):
        s = self.service()
        self.emit(s, 'turn/started')
        self.emit(s, 'item/completed', item=question())
        self.assertEqual('running', self.row(s)['status'])
        self.assertEqual(['question'], self.row(s)['responseKinds'])
        self.emit(s, 'turn/completed')
        self.assertEqual('idle', self.row(s)['status'])
        self.assertFalse(self.row(s)['hasOutstandingWork'])
        self.assertEqual(['question'], self.row(s)['responseKinds'])

    def test_async_acceptance_is_idempotent_and_pending_steer_not_an_answer(self):
        s = self.service()
        self.emit(s, 'turn/started')
        self.emit(s, 'item/completed', item=question())
        self.emit(s, 'item/completed', item=reply(status='pending'))
        self.assertEqual(['question'], self.row(s)['responseKinds'])
        self.emit(s, 'item/completed', item=reply())
        self.emit(s, 'item/completed', item=question())
        self.assertEqual([], self.row(s)['responseKinds'])

    def test_async_failed_turn_new_turn_and_archive_clear_hints(self):
        for method in ('turn/failed', 'turn/interrupted', 'thread/archived', 'thread/deleted', 'turn/started'):
            s = self.service()
            self.emit(s, 'turn/started')
            self.emit(s, 'item/completed', item=question())
            if method in ('turn/failed', 'turn/interrupted'):
                s._event({'method': 'turn/completed', 'params': {'threadId': 'thread',
                    'turn': {'id': 'turn', 'status': method.split('/')[1], 'items': []}}})
            else:
                self.emit(s, method, turn='next' if method == 'turn/started' else 'turn')
            self.assertNotIn('question', self.row(s).get('responseKinds', []), method)

    def test_restart_recovers_idle_async_question_from_latest_native_head(self):
        s = self.service()
        self.head = [{'id': 'turn', 'status': 'completed', 'items': [question()]}]
        s.activity.reconcile()
        self.assertEqual('idle', self.row(s)['status'])
        self.assertEqual(['question'], self.row(s)['responseKinds'])
        self.metadata['updatedAt'] = 2
        self.head = [{'id': 'next', 'status': 'completed', 'items': []}]
        s.activity.reconcile()
        self.assertFalse(self.row(s).get('responseKinds'))

    def test_history_read_and_claims_cannot_resurrect_answered_question(self):
        s = self.service()
        self.emit(s, 'turn/started')
        self.emit(s, 'item/completed', item=question())
        token = s.activity.read_token()
        self.emit(s, 'relay/questionsAnswered', questionIds=[identity()])
        turn = {'id': 'turn', 'status': 'inProgress', 'items': [question()]}
        self.assertFalse(s.activity.observe_questions('thread', [turn], token=token))
        s.activity.observe_questions('thread', [turn], token=s.activity.read_token())
        self.assertEqual([], self.row(s)['responseKinds'])

    def test_disconnect_hides_question_then_head_read_restores_it(self):
        s = self.service()
        self.head = [{'id': 'turn', 'status': 'completed', 'items': [question()]}]
        s.activity.reconcile()
        s._event({'method': 'relay/nativeDisconnected', 'params': {}})
        self.assertNotIn('question', self.row(s).get('responseKinds', []))
        s.activity.reconcile()
        self.assertEqual(['question'], self.row(s)['responseKinds'])

    def test_discovery_limits_io_and_rejects_old_head_after_new_turn(self):
        s = self.service()
        threads = [{'id': str(i), 'updatedAt': 1} for i in range(20)]
        first = s.activity.question_candidates(threads)
        self.assertEqual(4, len(first))
        self.assertFalse(set(first) & set(s.activity.question_candidates(threads)))
        self.emit(s, 'turn/started', turn='new')
        self.assertFalse(s.activity.observe_questions('thread', [{'id': 'old', 'status': 'completed', 'items': [question()]}],
            token=s.activity.read_token()))
        self.assertEqual([], self.row(s)['responseKinds'])

    def test_reply_submission_pending_unknown_and_definite_failure(self):
        s = self.service()
        self.emit(s, 'turn/started')
        self.emit(s, 'item/completed', item=question())
        for state in ('pending', 'unknown'):
            self.emit(s, 'relay/questionsAnswerState', questionIds=[identity()], state=state)
            self.assertEqual([], self.row(s)['responseKinds'])
        self.emit(s, 'relay/questionsAnswerState', questionIds=[identity()], state=None)
        self.assertEqual(['question'], self.row(s)['responseKinds'])
        self.emit(s, 'relay/questionsAnswered', questionIds=[identity()])
        self.assertEqual([], self.row(s)['responseKinds'])

    def test_generation_change_and_subscription_replay(self):
        s = self.service()
        self.emit(s, 'turn/started')
        self.emit(s, 'item/permissions/requestApproval', request_id='r')
        self.emit(s, 'thread/closed')
        self.assertFalse(s.interactions.list('thread'))
        self.emit(s, 'item/permissions/requestApproval', request_id='r')
        self.assertEqual(['approval'], self.row(s)['responseKinds'])
        self.emit(s, 'serverRequest/resolved', requestId='r')
        s.connection.generation = 'new'
        self.emit(s, 'turn/started')
        self.emit(s, 'item/permissions/requestApproval', request_id='r')
        self.assertEqual(['approval'], self.row(s)['responseKinds'])

    def test_partial_async_answers_and_rpc_resolution_are_independent(self):
        s = self.service()
        self.emit(s, 'turn/started')
        self.emit(s, 'item/completed', item=question('q'))
        self.emit(s, 'item/completed', item=question('q2'))
        self.emit(s, 'item/permissions/requestApproval', request_id='r')
        self.emit(s, 'relay/questionsAnswered', questionIds=[identity('q')])
        self.assertEqual({'question', 'approval'}, set(self.row(s)['responseKinds']))
        self.emit(s, 'relay/questionsAnswered', questionIds=[identity('q2')])
        self.assertEqual(['approval'], self.row(s)['responseKinds'])
        self.emit(s, 'turn/completed')
        self.assertFalse(self.row(s).get('responseKinds'))

    def test_terminal_async_state_survives_lagging_or_empty_persisted_head(self):
        s = self.service()
        self.emit(s, 'turn/started')
        self.emit(s, 'item/completed', item=question())
        self.emit(s, 'turn/completed')
        self.assertFalse(s.activity.observe_questions('thread', [], token=s.activity.read_token()))
        self.assertEqual(['question'], self.row(s)['responseKinds'])
        s._event({'method': 'turn/completed', 'params': {'threadId': 'thread',
            'turn': {'id': 'turn', 'status': 'interrupted'}}})
        self.assertFalse(s.activity.observe_questions('thread', [{'id': 'turn', 'status': 'inProgress', 'items': [question()]}],
            token=s.activity.read_token()))
        self.assertFalse(s.activity.observe_questions('thread', [{'id': 'turn', 'status': 'completed', 'items': [question()]}],
            token=s.activity.read_token()))
        self.assertFalse(self.row(s).get('responseKinds'))

    def test_background_head_does_not_resolve_a_current_rpc(self):
        s = self.service()
        self.emit(s, 'turn/started', turn='new')
        self.emit(s, 'item/permissions/requestApproval', turn='new', request_id='r')
        self.head = [{'id': 'old', 'status': 'completed', 'items': []}]
        s.activity.reconcile()
        self.assertEqual(1, len(s.interactions.list('thread')))

    def test_history_only_question_claims_and_visible_list_priority(self):
        s = self.service()
        self.head = [{'id': 'turn', 'status': 'completed', 'items': [question()]}]
        s.question_claims.claim('thread', [identity()], 'submission')
        s.activity.reconcile()
        self.assertFalse(self.row(s).get('responseKinds'))
        threads = [{'id': str(i), 'updatedAt': 1} for i in range(20)]
        s.activity.prioritize_questions(['18', '19'])
        self.assertEqual(['18', '19'], s.activity.question_candidates(threads)[:2])

    def test_accepted_answer_after_completion_clears_but_late_question_does_not_revive(self):
        s = self.service()
        self.emit(s, 'turn/started')
        self.emit(s, 'item/completed', item=question())
        self.emit(s, 'turn/completed')
        self.emit(s, 'item/completed', item=reply())
        self.emit(s, 'item/completed', item=question())
        self.assertFalse(self.row(s).get('responseKinds'))

    def test_resolution_during_failed_send_is_not_restored(self):
        from concurrent.futures import ThreadPoolExecutor
        from rivo_relay.codex.connection import CodexError
        s = self.service()
        self.emit(s, 'turn/started')
        self.emit(s, 'item/permissions/requestApproval', request_id='r')
        key = s.interactions.list('thread')[0]['key']
        def respond(*args):
            with ThreadPoolExecutor(max_workers=1) as pool:
                pool.submit(self.emit, s, 'serverRequest/resolved', requestId='r').result(timeout=2)
            raise CodexError('definite rejection')
        s.connection.respond = respond
        with self.assertRaises(CodexError):
            s.respond(key, {})
        self.assertFalse(s.interactions.list('thread'))
        self.emit(s, 'item/permissions/requestApproval', request_id='r')
        self.assertFalse(s.interactions.list('thread'))
