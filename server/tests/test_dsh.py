"""DSH contracts through real Unix + HTTP carriers; no user's runtime touched."""
import copy
import json
import os
from pathlib import Path
import socketserver
import threading
import tempfile
import unittest
from http.server import BaseHTTPRequestHandler

from relay_test_support.http import RelayHTTPFixture
from rivo_relay.api import Handler
from rivo_relay.app import RelayApp
from rivo_relay.codex.connection import CodexError
from rivo_relay.dsh import DshService
from rivo_relay.dsh.projection import project
from rivo_relay.dsh.transport import BridgeTransport
from rivo_relay.http_server import BoundedHTTPServer
from rivo_relay.submissions import SubmissionConflict


def event(seq, kind, data):
    return {'seq': seq, 'type': kind, 'time': seq * 1000, 'data': data}


class FakeBridge:
    def __init__(self):
        self.generation = 'generation-1'
        self.prompts = {}
        self.drop_prompt_reply = False
        self.running = False
        self.requests = []
        self.last_response = None
        self.events = [event(0, 'turn/start', {'turn': 7}),
            event(1, 'user/message', {'id': 'u', 'source': {'kind': 'user', 'rpcId': 'previous'}, 'content': [{'type': 'text', 'text': '你好'}]}),
            event(2, 'assistant/message', {'turn': 7, 'message': {'id': 'a', 'content': [{'type': 'text', 'text': '🙂 reply'}]}}),
            event(3, 'turn/end', {'turn': 7, 'reason': {'kind': 'completed'}})]

    def rpc(self, method, params):
        if method == 'status':
            return {'generation': self.generation, 'connected': True}
        if method == 'projects':
            return {'items': [{'workspaceId': 'p', 'path': '/tmp/project', 'title': 'Project', 'sessionIds': ['s']}], 'archivedSessionIds': [], 'pinnedSessionIds': []}
        if method == 'sessions':
            return {'items': [{'sessionId': 's', 'updatedAt': 1000, 'running': False}]}
        if method == 'snapshot':
            return {'generation': self.generation, 'page': {'cursor': 3, 'header': {'id': 's'}, 'hasMore': False,
                     'records': [{'type': 'event', 'event': e} for e in self.events]},
                    'state': {'cursor': 3, 'running': self.running}, 'requests': self.requests}
        if method == 'create':
            if set(params) != {'workspaceId'}: raise ValueError('workspace and cwd cannot coexist')
            return {'sessionId': 'new-session'}
        if method == 'prompt':
            self.prompts.setdefault(params['requestId'], params)
            if self.drop_prompt_reply:
                raise ConnectionResetError()
            return {'accepted': True, 'requestId': params['requestId']}
        if method == 'receipt':
            return {'accepted': params['requestId'] in self.prompts, 'requestId': params['requestId']}
        if method == 'respond':
            self.last_response = params
            return {'accepted': True}
        raise ValueError(method)


class BridgeHandler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        try:
            result = self.server.bridge.rpc(body['method'], body['params'])
        except ConnectionResetError:
            self.close_connection = True
            return
        encoded = json.dumps({'result': result}).encode()
        self.send_response(200)
        self.send_header('Content-Length', str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


class UnixServer(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True


class DshHttpTests(RelayHTTPFixture, unittest.TestCase):
    def _start(self, bus=None):
        # Keep Unix paths below macOS's 104-byte limit.
        self.socket_dir = tempfile.TemporaryDirectory(prefix='dsh-', dir='/tmp')
        self.addCleanup(self.socket_dir.cleanup)
        path = self.socket_dir.name + '/b.sock'
        self.bridge = FakeBridge()
        self.bridge_server = UnixServer(path, BridgeHandler)
        self.bridge_server.bridge = self.bridge
        os.chmod(path, 0o600)
        worker = threading.Thread(target=self.bridge_server.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
        worker.start()
        self.addCleanup(self.bridge_server.server_close)
        self.addCleanup(self.bridge_server.shutdown)
        self.native = DshService(path)
        self.native._admit(self.bridge.generation)
        self.app = RelayApp(self.config, self.native, bus)
        self.server = BoundedHTTPServer(('127.0.0.1', 0), Handler, app=self.app, max_workers=6, max_streams=1)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
        self.thread.start()

    def test_paired_chat_receipt_and_duplicate_submission(self):
        status, body, _ = self.request('GET', '/v1/projects', token=self.token)
        self.assertEqual(200, status)
        self.assertEqual('p', body['projects'][0]['id'])
        path = '/v1/threads/s/turns'
        payload = {'submissionId': 'input-1', 'text': 'hello'}
        first = self.request('POST', path, payload, token=self.token)
        again = self.request('POST', path, payload, token=self.token)
        self.assertEqual(200, first[0])
        self.assertEqual('accepted', first[1]['status'])
        self.assertEqual({'kind': 'input-accepted', 'submissionId': 'input-1', 'threadId': 's',
                          'runtime': 'dsh', 'acceptance': 'input', 'requestId': 'input-1'}, first[1]['receipt'])
        self.assertEqual(first[1], again[1])
        self.assertEqual(1, len(self.bridge.prompts))
        status, snap, _ = self.request('GET', '/v1/threads/s?limit=2', token=self.token)
        self.assertEqual(200, status)
        self.assertEqual('dsh-turn-7', snap['thread']['turns'][0]['id'])
        self.assertEqual('previous', snap['thread']['turns'][0]['items'][0]['clientId'])
        self.assertTrue(snap['activity']['complete'])
        self.assertEqual(['s'], snap['activity']['knownThreadIds'])

    def test_create_uses_workspace_identity_without_conflicting_cwd(self):
        status, result, _ = self.request('POST', '/v1/threads', {'submissionId': 'new', 'projectId': 'p'}, token=self.token)
        self.assertEqual(200, status)
        self.assertEqual('accepted', result['status'])
        self.assertEqual('new-session', result['receipt']['threadId'])

    def test_disconnect_after_acceptance_recovers_without_resending(self):
        self.bridge.drop_prompt_reply = True
        _, body, _ = self.request('POST', '/v1/threads/s/turns', {'submissionId': 'lost', 'text': 'hello'}, token=self.token)
        self.assertEqual('unknown', body['status'])
        _, recovered, _ = self.request('GET', '/v1/submissions/lost', token=self.token)
        self.assertEqual('accepted', recovered['status'])
        self.assertEqual('lost', recovered['receipt']['requestId'])
        self.assertEqual(1, len(self.bridge.prompts))

    def test_input_receipt_cannot_accept_another_submission_or_continue(self):
        self.app.submissions.claim('expected', self.paired['deviceId'], 'thread.turns', 's', {})
        receipt = {'runtime': 'dsh', 'acceptance': 'input', 'requestId': 'wrong', 'threadId': 's'}
        with self.assertRaises(SubmissionConflict): self.app.submissions.mark_accepted('expected', receipt)
        self.app.submissions.claim('continue', self.paired['deviceId'], 'thread.continue', 's', {})
        receipt['requestId'] = 'continue'
        with self.assertRaises(SubmissionConflict): self.app.submissions.mark_accepted('continue', receipt)

    def test_approval_and_disconnected_old_request(self):
        key = 'generation-1:approval'
        self.bridge.requests = [{'key': key, 'sessionId': 's', 'toolName': 'bash', 'reason': 'test'}]
        _, snap, _ = self.request('GET', '/v1/threads/s', token=self.token)
        self.assertEqual('runtime/approval', snap['requests'][0]['method'])
        _, receipt, _ = self.request('POST', '/v1/requests/respond', {'key': key, 'submissionId': 'approval', 'response': {'decision': 'accept'}}, token=self.token)
        self.assertEqual('accepted', receipt['status'])
        self.assertEqual('allowed-once', self.bridge.last_response['outcome'])
        self.native.get_session('s')
        self.native._disconnected()
        with self.assertRaises(CodexError): self.native.respond(key, {'decision': 'accept'})

    def test_socket_permissions_and_unsupported_queue_fail_closed(self):
        os.chmod(self.native.transport.path, 0o666)
        with self.assertRaises(CodexError) as caught: self.native.list_projects()
        self.assertEqual('dsh_socket_untrusted', caught.exception.code)
        with self.assertRaises(CodexError) as caught: self.native.validate_queue_target('s')
        self.assertEqual('runtime_unsupported', caught.exception.code)


class DshActivityTests(unittest.TestCase):
    def setUp(self):
        self.bridge = FakeBridge()
        class Transport:
            call = staticmethod(self.bridge.rpc)
        self.service = DshService('/tmp/unused', transport=Transport())

    def test_only_authoritative_snapshot_confirms_idle_and_advances_version(self):
        self.service._admit(self.bridge.generation)
        self.service.list_sessions('p')
        self.assertFalse(self.service.active_tasks()['complete'])
        self.assertEqual([], self.service.active_tasks()['knownThreadIds'])
        snapshot = self.service.get_session('s')
        activity = snapshot['activity']
        self.assertTrue(activity['complete'])
        self.assertEqual(['s'], activity['knownThreadIds'])
        self.assertEqual([], activity['tasks'])
        self.assertEqual(activity, self.service.active_tasks())
        self.assertGreater(self.service.get_session('s')['activity']['version'], activity['version'])

    def test_approval_and_status_events_update_confirmed_activity(self):
        self.bridge.running = True
        self.service.get_session('s')
        self.assertEqual('running', self.service.active_tasks()['tasks'][0]['status'])
        request = {'key': 'approval', 'sessionId': 's'}
        version = self.service.active_tasks()['version']
        self.service._event({'type': 'approval', 'sessionId': 's', 'request': request})
        self.assertEqual('waiting', self.service.active_tasks()['tasks'][0]['status'])
        self.assertGreater(self.service.active_tasks()['version'], version)
        self.bridge.requests = [request]
        snapshot = self.service.get_session('s')
        self.assertEqual('waiting', snapshot['status'])
        self.assertEqual('waiting', snapshot['activity']['tasks'][0]['status'])
        self.service._event({'type': 'approval-resolved', 'sessionId': 's', 'key': 'approval'})
        self.service._event({'type': 'status', 'sessionId': 's', 'running': False})
        self.assertEqual([], self.service.active_tasks()['tasks'])

    def test_disconnect_generation_and_session_change_require_new_snapshot(self):
        for invalidate in (self.service._disconnected,
                           lambda: self.service._admit('new-generation'),
                           lambda: self.service._event({'type': 'session', 'sessionId': 's'})):
            with self.subTest(invalidate=invalidate):
                self.bridge.generation = self.service._generation or self.bridge.generation
                self.service.get_session('s')
                invalidate()
                activity = self.service.active_tasks()
                self.assertFalse(activity['complete'])
                self.assertEqual([], activity['knownThreadIds'])
                self.assertEqual([], activity['tasks'])

    def test_late_snapshot_does_not_reconfirm_after_control_change(self):
        self.bridge.requests = [{'key': 'approval', 'sessionId': 's'}]
        original = self.service._request
        def changing_request(request, events):
            self.service._event({'type': 'session', 'sessionId': 's'})
            return original(request, events)
        self.service._request = changing_request
        with self.assertRaises(CodexError) as caught:
            self.service.get_session('s')
        self.assertEqual('history_changed', caught.exception.code)
        self.assertFalse(self.service.active_tasks()['complete'])
        self.assertEqual([], self.service.active_tasks()['knownThreadIds'])


class DshProjectionTests(unittest.TestCase):
    def test_tool_failure_is_not_success_and_context_is_not_user(self):
        events = [event(0, 'turn/start', {'turn': 1}),
                  event(1, 'user/message', {'source': {'kind': 'context'}, 'content': [{'type': 'text', 'text': 'private context'}]}),
                  event(2, 'tool/call', {'turn': 1, 'callId': 'tool1', 'name': 'bash', 'arguments': '{}'}),
                  event(3, 'tool/result', {'turn': 1, 'message': {'toolCallId': 'tool1', 'isError': True, 'content': [{'type': 'text', 'text': 'failed'}]}})]
        turns = project(events)
        self.assertEqual(1, len(turns[0]['items']))
        self.assertEqual('failed', turns[0]['items'][0]['status'])
        self.assertFalse(turns[0]['items'][0]['success'])

    def test_failed_attempt_and_unsupported_image_are_not_lost(self):
        events = [event(0, 'turn/start', {'turn': 1}),
                  event(1, 'user/message', {'source': {'kind': 'user'}, 'content': [{'type': 'image', 'data': 'private-bytes'}]}),
                  event(2, 'assistant/attempt', {'turn': 1, 'stream': [{'type': 'text-chunks', 'texts': ['partial']}]})]
        items = project(events)[0]['items']
        self.assertIn('暂不支持预览', items[0]['content'][0]['text'])
        self.assertNotIn('private-bytes', str(items))
        self.assertIn('partial', items[1]['text'])
        self.assertIn('未完成', items[1]['text'])

    def test_stream_offset_counts_unicode_and_gap_forces_snapshot(self):
        seen = []
        s = DshService('/tmp/unused', on_event=seen.append)
        s._event({'type': 'assistant-stream', 'sessionId': 's', 'frame': {'type': 'start', 'turn': 1, 'attemptId': 'a'}})
        for i, text in enumerate(['🙂', '你']):
            s._event({'type': 'assistant-stream', 'sessionId': 's', 'frame': {'type': 'chunk', 'attemptId': 'a', 'index': i, 'chunk': {'type': 'text-delta', 'text': text}}})
        self.assertEqual(1, seen[-1]['params']['relayOffset'])
        s._event({'type': 'assistant-stream', 'sessionId': 's', 'frame': {'type': 'chunk', 'attemptId': 'a', 'index': 5, 'chunk': {'type': 'text-delta', 'text': 'gap'}}})
        self.assertEqual('relay/historyInvalidated', seen[-1]['method'])


class DshPollGenerationTests(unittest.TestCase):
    def stale_status_transport(self, service):
        class Transport:
            def call(self, method, params=None):
                self.asserted_method = method
                service._admit('new')  # Another reader completed during this RPC.
                service._cursor = 42
                service._requests['new-key'] = {'sessionId': 's'}
                service._known.add('s')
                service._session_states['s'] = True
                return {'generation': 'old'}
            def poll(self, generation, cursor):
                self.polled = (generation, cursor)
                service._stop.set()
                return {}
        return Transport()

    def assert_new_state_preserved(self, service):
        self.assertEqual('new', service._generation)
        self.assertTrue(service._connected)
        self.assertEqual(42, service._cursor)
        self.assertEqual({'new-key'}, set(service._requests))
        self.assertEqual(['s'], service.active_tasks()['knownThreadIds'])

    def test_late_status_endpoint_cannot_replace_new_generation(self):
        service = DshService('/tmp/unused')
        service.start = lambda: None
        service._admit('old')
        service.transport = self.stale_status_transport(service)
        response = service.status()
        self.assertEqual('new', response['generation'])
        self.assert_new_state_preserved(service)

    def test_late_reconnect_status_cannot_replace_new_generation(self):
        service = DshService('/tmp/unused')
        service._admit('old')
        service._disconnected()
        service.transport = self.stale_status_transport(service)
        service._run()
        self.assertEqual(('new', 42), service.transport.polled)
        self.assert_new_state_preserved(service)

    def test_current_status_allows_same_and_new_generation_reconnection(self):
        for generation in ('old', 'new'):
            with self.subTest(generation=generation):
                service = DshService('/tmp/unused')
                service.start = lambda: None
                service._admit('old')
                service._disconnected()
                class Transport:
                    def call(self, method, params=None):
                        return {'generation': generation}
                service.transport = Transport()
                response = service.status()
                self.assertTrue(response['connected'])
                self.assertEqual(generation, response['generation'])

    def run_poll(self, first_reply):
        service = DshService('/tmp/unused')
        service._admit('old')
        service._cursor = 12
        calls = []
        class Transport:
            def poll(self, generation, cursor):
                calls.append((generation, cursor))
                if len(calls) == 1:
                    return first_reply(service)
                service._stop.set()
                return {}
        service.transport = Transport()
        service._run()
        return service, calls

    def test_late_old_generation_cannot_replace_concurrent_status(self):
        def reply(service):
            service._admit('new')  # Another request completed while poll waited.
            service._requests['new-key'] = {'sessionId': 's'}
            return {'generation': 'old', 'cursor': 999, 'reset': False,
                    'events': [{'type': 'approval', 'request': {'key': 'old-key', 'sessionId': 's'}}]}
        service, calls = self.run_poll(reply)
        self.assertEqual('new', service._generation)
        self.assertEqual(0, service._cursor)
        self.assertEqual({'new-key'}, set(service._requests))
        self.assertEqual([('old', 12), ('new', 0)], calls)

    def test_same_generation_old_control_epoch_cannot_restore_approval(self):
        def reply(service):
            service._disconnected()
            service._admit('old')  # Reconnected to the same Host during the wait.
            return {'generation': 'old', 'cursor': 999, 'reset': False,
                    'events': [{'type': 'approval', 'request': {'key': 'stale', 'sessionId': 's'}}]}
        service, calls = self.run_poll(reply)
        self.assertEqual(12, service._cursor)
        self.assertEqual({}, service._requests)
        self.assertEqual([('old', 12), ('old', 12)], calls)

    def test_current_poll_can_admit_real_new_generation_reset(self):
        def reply(service):
            service._requests['old-key'] = {'sessionId': 's'}
            service._known.add('s')
            service._session_states['s'] = True
            return {'generation': 'new', 'cursor': 5, 'reset': True, 'events': []}
        service, calls = self.run_poll(reply)
        self.assertEqual('new', service._generation)
        self.assertEqual(5, service._cursor)
        self.assertEqual({}, service._requests)
        self.assertEqual([], service.active_tasks()['knownThreadIds'])
        self.assertFalse(service.active_tasks()['complete'])
        self.assertEqual([('old', 12), ('new', 5)], calls)

    def test_same_generation_reset_invalidates_confirmed_activity(self):
        def reply(service):
            service._known.add('s')
            service._session_states['s'] = True
            return {'generation': 'old', 'cursor': 15, 'reset': True, 'events': []}
        service, _ = self.run_poll(reply)
        self.assertEqual([], service.active_tasks()['knownThreadIds'])
        self.assertFalse(service.active_tasks()['complete'])
