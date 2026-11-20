"""Control cuts and replies remain bound to each adapter's live authority."""
import copy
import threading
import unittest
from unittest.mock import Mock, patch

from relay_test_support.native import FakeNative
from rivo_relay.client_projection import client_session
from rivo_relay.codex.connection import CodexError, NativeConnection
from rivo_relay.codex.desktop import DesktopConnection
from rivo_relay.codex.interactions import Interactions
from rivo_relay.codex.sessions import CodexService
from rivo_relay.dsh import DshService
from rivo_relay.runtime_state import session_state


class CodexStateContractTests(unittest.TestCase):
    def setUp(self):
        self.native = FakeNative()
        self.events = []
        self.service = CodexService(connection=self.native, on_event=self.events.append)
        self.service.start()

    def approval(self, params=None, method='item/commandExecution/requestApproval', request_id=7):
        self.service._event({'id': request_id, 'method': method, 'connectionId': self.native.generation,
            'params': {'threadId': 'thread', 'turnId': 'turn', 'command': 'echo test',
                       'availableDecisions': ['accept', 'cancel'], **(params or {})}})
        return self.service.interactions.snapshot('thread')['interactions'][-1]

    def respond(self, view, response=None, **identities):
        args = {'session_id': 'thread', 'generation': str(self.native.generation), 'interaction_id': view['id'],
                'revision': view['revision'], 'response': response or {'choiceId': view['choices'][0]['id']}, **identities}
        return self.service.respond_interaction(**args)

    def test_snapshot_preserves_native_control_and_converging_history(self):
        view = self.approval()
        original = self.service.history
        def history(*args, **kwargs):
            result = original(*args, **kwargs)
            result['nativeSync'] = {'state': 'converging', 'historyReady': True, 'generation': 123}
            return result
        with patch.object(self.service, 'history', side_effect=history):
            snapshot = self.service.get_session('thread')
        state = snapshot['sessionState']
        self.assertEqual(str(self.native.generation), state['runtimeGeneration'])
        self.assertNotEqual('123', state['runtimeGeneration'])
        self.assertEqual(snapshot['control'], state['control'])
        self.assertEqual({'state': 'converging', 'historyReady': True}, state['synchronization'])
        self.assertEqual(view, state['interactions'][0])
        self.assertEqual('waiting', state['execution']['status'])

    def test_opaque_choice_maps_exact_native_policy_and_rejects_raw_decision(self):
        policy = {'acceptWithExecpolicyAmendment': {'execpolicy_amendment': ['echo', 'test']}}
        view = self.approval({'availableDecisions': [policy, 'cancel']})
        self.assertEqual('allow-policy', view['choices'][0]['effect'])
        with self.assertRaises(CodexError):
            self.respond(view, {'decision': policy})
        self.assertEqual([], self.native.replies)
        receipt = self.respond(view)
        self.assertEqual([(7, {'decision': policy})], self.native.replies)
        self.assertEqual(view['id'], receipt['interactionId'])
        self.assertEqual(view['revision'], receipt['revision'])
        with self.assertRaises(CodexError): self.respond(view)

    def test_wrong_scope_generation_revision_or_choice_cannot_dispatch(self):
        view = self.approval()
        for field in ('session_id', 'generation', 'interaction_id', 'revision'):
            with self.subTest(field=field), self.assertRaises(CodexError):
                self.respond(view, **{field: 'wrong'})
        with self.assertRaises(CodexError): self.respond(view, {'choiceId': 'invented'})
        self.assertEqual([], self.native.replies)

    def test_same_key_replacement_changes_revision_and_emits_invalidation(self):
        before = self.approval()
        self.events.clear()
        after = self.approval({'command': 'different command'})
        self.assertEqual(before['id'], after['id'])
        self.assertNotEqual(before['revision'], after['revision'])
        self.assertTrue(any(event['method'] == 'session/invalidated' and event['params']['scope'] == 'interactions' for event in self.events))
        with self.assertRaises(CodexError): self.respond(before)
        self.assertEqual([], self.native.replies)

    def test_resolved_during_snapshot_cannot_restore_old_pending_request(self):
        self.approval()
        read_goal = self.service.read_goal
        changed = False
        def resolve(*args, **kwargs):
            nonlocal changed
            if not changed:
                changed = True
                self.service._event({'method': 'serverRequest/resolved', 'params': {'requestId': 7}})
            return read_goal(*args, **kwargs)
        with patch.object(self.service, 'read_goal', side_effect=resolve):
            snapshot = self.service.get_session('thread')
        self.assertEqual([], snapshot['requests'])
        self.assertEqual([], snapshot['sessionState']['interactions'])
        self.assertTrue(any(e['method'] == 'session/invalidated' and e['params']['sessionId'] == 'thread' for e in self.events))

    def test_connection_replacement_expires_view_and_lost_reply_is_not_retried(self):
        view = self.approval()
        self.native.generation = 'replacement'
        with self.assertRaises(CodexError): self.respond(view)
        view = self.approval()
        with patch.object(self.native, 'respond', side_effect=CodexError('lost', request_sent=True)) as sent:
            with self.assertRaises(CodexError): self.respond(view)
            with self.assertRaises(CodexError): self.respond(view)
        self.assertEqual(1, sent.call_count)

    def test_forms_preserve_structured_values_and_reject_permission_expansion(self):
        question = self.approval({'questions': [{'id': 'name'}]}, method='item/tool/requestUserInput')
        self.assertEqual('questions', question['kind'])
        self.respond(question, {'answers': {'name': {'answers': ['answer']}}})
        requested = {'fileSystem': {'write': ['/allowed']}}
        permissions = self.approval({'permissions': requested}, method='item/permissions/requestApproval', request_id=8)
        with self.assertRaises(CodexError):
            self.respond(permissions, {'permissions': {'fileSystem': {'write': ['/other']}}, 'scope': 'session'})
        self.respond(permissions, {'permissions': requested, 'scope': 'turn'})
        schema = {'type': 'object', 'properties': {'values': {'type': 'array', 'items': {'type': 'string', 'enum': ['a', 'b']}}}, 'required': ['values']}
        elicitation = self.approval({'mode': 'form', 'requestedSchema': schema}, method='mcpServer/elicitation/request', request_id=9)
        self.respond(elicitation, {'action': 'accept', 'content': {'values': ['a', 'b']}, '_meta': None})
        self.assertEqual({'values': ['a', 'b']}, self.native.replies[-1][1]['content'])

    def test_unknown_requests_are_visible_but_not_answerable(self):
        view = self.approval(method='extension/unknownRequest')
        self.assertEqual('unsupported', view['kind'])
        self.assertEqual([], view['choices'])
        with self.assertRaises(CodexError): self.respond(view, {'decision': 'accept'})
        self.assertEqual([], self.native.replies)

    def test_definite_rejection_restores_only_an_uninvalidated_claim(self):
        for event_kind in ('none', 'resolved', 'replacement', 'disconnect', 'discard'):
            with self.subTest(event_kind=event_kind):
                self.setUp()  # Each native request identity is scoped to a fresh connection.
                view = self.approval()
                replacement = []
                def rejected(*args, **kwargs):
                    if event_kind == 'resolved':
                        self.service._event({'method': 'serverRequest/resolved', 'params': {'requestId': 7}})
                    elif event_kind == 'replacement':
                        self.service._event({'id': 7, 'method': 'item/commandExecution/requestApproval',
                            'params': {'threadId': 'thread', 'command': 'new command'}})
                    elif event_kind == 'disconnect':
                        self.service._event({'method': 'relay/nativeDisconnected', 'params': {}})
                    elif event_kind == 'discard':
                        self.service.interactions.discard_thread('thread')
                    raise CodexError('not sent')
                with patch.object(self.native, 'respond', side_effect=rejected):
                    with self.assertRaises(CodexError): self.respond(view)
                pending = self.service.interactions.snapshot('thread')['interactions']
                self.assertEqual([view] if event_kind in ('none', 'replacement') else replacement, pending)
                self.assertEqual({}, self.service.interactions._claims)
                self.service.interactions.discard_thread('thread')


class NativeResponseGenerationTests(unittest.TestCase):
    def test_socket_send_checks_generation_after_waiting_for_send_lock(self):
        connection = NativeConnection.__new__(NativeConnection)
        connection.generation, connection._lock, connection._socket = 'old', threading.RLock(), Mock()
        entered, errors = threading.Event(), []
        def respond():
            entered.set()
            try:
                connection.respond(7, {'decision': 'accept'}, expected_generation='old')
            except CodexError as error:
                errors.append(error)
        with connection._lock:
            worker = threading.Thread(target=respond)
            worker.start()
            self.assertTrue(entered.wait(1))
            connection.generation = 'new'
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(1, len(errors))
        self.assertTrue(errors[0].definite)
        connection._socket.send.assert_not_called()

    def test_desktop_response_checks_generation_inside_rpc_send(self):
        connection = DesktopConnection.__new__(DesktopConnection)
        connection.generation, connection._lock, connection._socket = 'old', threading.RLock(), Mock()
        def status():
            connection.generation = 'new'
            return {'connected': True}
        connection.status = status
        with self.assertRaises(CodexError) as caught:
            connection.respond(7, {'decision': 'accept'}, expected_generation='old')
        self.assertTrue(caught.exception.definite)
        connection._socket.send.assert_not_called()


class DshStateContractTests(unittest.TestCase):
    def setUp(self):
        class Transport:
            requests = [{'key': 'g:approval', 'sessionId': 's', 'toolName': 'Bash', 'reason': 'outside cwd'}]
            responses = []
            reject = False
            def call(self, method, params):
                if method == 'snapshot':
                    return {'generation': 'g', 'state': {'cursor': 0, 'running': True},
                            'page': {'cursor': 0, 'records': [], 'hasMore': False}, 'requests': copy.deepcopy(self.requests)}
                if method == 'respond':
                    self.responses.append(params)
                    if self.reject:
                        raise CodexError('Native lease expired', code='stale_approval', http_status=409)
                    self.requests = []
                    return {'accepted': True}
                raise AssertionError(method)
        self.transport = Transport()
        self.transport.responses = []
        self.events = []
        self.service = DshService('/unused', transport=self.transport, on_event=self.events.append)

    def view(self):
        return self.service.get_session('s')['sessionState']['interactions'][0]

    def test_native_approval_maps_one_shot_and_keeps_missing_execution_missing(self):
        snapshot = self.service.get_session('s')
        state = snapshot['sessionState']
        self.assertEqual('g', state['runtimeGeneration'])
        self.assertNotIn('id', state['execution'])
        view = state['interactions'][0]
        result = self.service.respond_interaction('s', 'g', view['id'], view['revision'], {'choiceId': view['choices'][0]['id']})
        self.assertEqual('allowed-once', self.transport.responses[-1]['outcome'])
        self.assertEqual(view['id'], result['interactionId'])
        self.assertEqual([], self.service.get_session('s')['sessionState']['interactions'])

    def test_resolved_or_disconnected_view_never_reaches_native_response(self):
        view = self.view()
        self.service._event({'type': 'approval-resolved', 'sessionId': 's', 'key': view['id']})
        with self.assertRaises(CodexError):
            self.service.respond_interaction('s', 'g', view['id'], view['revision'], {'choiceId': view['choices'][0]['id']})
        self.assertEqual([], self.transport.responses)
        self.assertTrue(any(e['method'] == 'session/invalidated' and e['params']['scope'] == 'interactions' for e in self.events))
        view = self.view()
        self.service._disconnected()
        with self.assertRaises(CodexError):
            self.service.respond_interaction('s', 'g', view['id'], view['revision'], {'choiceId': view['choices'][0]['id']})
        self.assertEqual([], self.transport.responses)

    def test_host_still_checks_lease_after_local_identity_validation(self):
        view = self.view()
        self.transport.reject = True
        with self.assertRaises(CodexError) as caught:
            self.service.respond_interaction('s', 'g', view['id'], view['revision'], {'choiceId': view['choices'][0]['id']})
        self.assertEqual('stale_approval', caught.exception.code)
        self.assertEqual(1, len(self.transport.responses))


class StateEnvelopeTests(unittest.TestCase):
    def test_missing_generation_is_not_stringified_into_authority(self):
        for generation in (None, ''):
            with self.subTest(generation=generation), self.assertRaises(ValueError):
                session_state(session_id='s', generation=generation, control_revision='r', execution_status='idle',
                    execution_id=None, synchronization={'state': 'ready'}, control={'state': 'writable', 'canOperate': True}, interactions=[])

    def test_unchanged_history_never_omits_current_neutral_control(self):
        state = session_state(session_id='s', generation='g', control_revision='r', execution_status='idle', execution_id=None,
                              synchronization={'state': 'ready'}, control={'state': 'writable', 'canOperate': True}, interactions=[])
        result = client_session({'thread': {'id': 's', 'turns': []}, 'nativeSync': {'state': 'ready'},
                                 'nativeHeadRevision': 'head', 'sessionState': state}, known_head_revision='head')
        self.assertTrue(result['historyUnchanged'])
        self.assertEqual(state, result['sessionState'])
