from concurrent.futures import Future, ThreadPoolExecutor
from pathlib import Path
import sys
import tempfile
import textwrap
import threading
import unittest
from unittest.mock import patch
from uuid import uuid4

from relay_test_support.http import RelayHTTPFixture
from rivo_relay.antigravity import AntigravityService
from rivo_relay.antigravity.transport import AcpError, AcpTransport
from rivo_relay.config import Config
from rivo_relay.events import EventBus
from rivo_relay.app import RelayApp
from rivo_relay.api import Handler
from rivo_relay.http_server import BoundedHTTPServer


def text_update(kind, value):
    return {'sessionUpdate': kind + '_message_chunk', 'content': {'type': 'text', 'text': value}}


class FakeAcp:
    def __init__(self, executable, cwd, on_update, on_request, on_disconnect):
        self.generation = uuid4().hex
        self.on_update = on_update
        self.on_request = on_request
        self.on_disconnect = on_disconnect
        self.requests = []
        self.prompts = []
        self.answers = []
        self.answer_written = threading.Event()
        self.auth = True
        self.external_wait = False
        self.closed = False
        self.project = None

    def start(self):
        pass

    def request(self, method, params, *, timeout=10):
        self.requests.append((method, dict(params)))
        if method == 'initialize':
            return {'protocolVersion': 1, 'agentInfo': {'version': 'test-version'},
                    'agentCapabilities': {'loadSession': True, 'sessionCapabilities': {'list': {}}}}
        if not self.auth:
            raise AcpError('sign in first', code='antigravity_auth_required', http_status=401)
        if method == 'session/list':
            approved = {'sessionId': 'old', 'cwd': self.project, 'title': 'Existing'}
            outside = {'sessionId': 'outside', 'cwd': self.project + '-different', 'title': 'Outside'}
            return {'sessions': [approved]} if params.get('cursor') else {
                'sessions': [outside], 'nextCursor': 'second',
            }
        if method == 'session/new':
            return {'sessionId': 'new'}
        if method == 'session/load':
            sid = params['sessionId']
            for number in (1, 2):
                self.emit(sid, text_update('user', f'问{number}'))
                self.emit(sid, text_update('agent', f'答{number}'))
            if self.external_wait:
                self.permission(sid)
            return {}
        raise AssertionError(method)

    def send_request(self, method, params):
        if method != 'session/prompt':
            raise AssertionError(method)
        future = Future()
        self.prompts.append((method, params, future))
        return len(self.prompts), future

    def emit(self, sid, update):
        self.on_update(self, {'sessionId': sid, 'update': update})

    def permission(self, sid):
        self.on_request(self, 'permission-1', {
            'sessionId': sid,
            'toolCall': {'toolCallId': 'tool-1', 'title': 'Run the fixture',
                         'rawInput': {'command': ['python', 'fixture.py']}},
            'options': [{'kind': 'allow_once', 'optionId': 'allow', 'name': 'Allow once'},
                        {'kind': 'reject_once', 'optionId': 'reject', 'name': 'Reject'},
                        {'kind': 'allow_always', 'optionId': 'always', 'name': 'Always allow'}],
        })

    def respond(self, request_id, outcome):
        self.answers.append((request_id, outcome))
        self.answer_written.set()

    def close(self):
        if self.closed:
            return
        self.closed = True
        self.on_disconnect(self)
        for _, _, future in self.prompts:
            if not future.done():
                future.set_exception(AcpError('fixture disconnect', sent=True))


class AntigravityBackendTests(RelayHTTPFixture, unittest.TestCase):
    def _start(self, bus=None):
        project = self.config.workspace / 'project'
        project.mkdir()
        self.config = Config(workspace=self.config.workspace, runtime='antigravity',
                             antigravity_executable=Path(sys.executable), antigravity_projects=(project,))
        bus = bus or EventBus()

        def transport_factory(*args):
            self.acp = FakeAcp(*args)
            self.acp.project = str(project)
            return self.acp

        self.native = AntigravityService(self.config.antigravity_executable, self.config.workspace,
                                        bus.publish, project_dirs=(project,), transport_factory=transport_factory)
        self.native.start()
        self.app = RelayApp(self.config, self.native, bus)
        self.server = BoundedHTTPServer(('127.0.0.1', 0), Handler, app=self.app,
                                        max_workers=6, max_streams=1)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       kwargs={'poll_interval': 0.01}, daemon=True)
        self.thread.start()

    def call(self, method, path, body=None):
        status, result, _ = self.request(method, path, body, token=self.token)
        return status, result

    def project(self):
        code, payload = self.call('GET', '/v1/projects')
        self.assertEqual(200, code)
        self.assertEqual(1, len(payload['projects']))
        return payload['projects'][0]['id']

    def make_session(self):
        code, result = self.call('POST', '/v1/threads', {'submissionId': 'creation', 'projectId': self.project()})
        self.assertEqual(200, code)
        self.assertEqual('accepted', result['status'])
        return result['receipt']['threadId']

    def send(self, sid='new', submission_id='message'):
        status, result = self.call('POST', f'/v1/threads/{sid}/turns',
                                   {'submissionId': submission_id, 'text': '你好'})
        self.assertEqual(200, status)
        return result

    @staticmethod
    def answer_payload(state, submission_id='decision'):
        request = state['interactions'][0]
        return {'submissionId': submission_id, 'sessionId': state['sessionId'],
                'runtimeGeneration': state['runtimeGeneration'], 'interactionId': request['id'],
                'revision': request['revision'], 'response': {'choiceId': request['choices'][0]['id']}}

    def test_catalog_and_history_use_scope_and_bounded_cursor(self):
        project = self.project()
        self.assertEqual(['old'], [row['id'] for row in self.call('GET', f'/v1/threads?projectId={project}')[1]['threads']])
        self.assertEqual(404, self.call('GET', '/v1/threads/outside')[0])
        self.assertFalse(any(method == 'session/load' for method, _ in self.acp.requests))
        latest = self.call('GET', '/v1/threads/old?limit=1')[1]
        self.assertEqual('答2', latest['thread']['turns'][0]['items'][1]['text'])
        cursor = latest['nextCursor']
        prior = self.call('GET', f'/v1/threads/old/history?limit=1&cursor={cursor}')[1]
        self.assertEqual('答1', prior['turns'][0]['items'][1]['text'])
        self.assertEqual(409, self.call('GET', '/v1/threads/old/history?cursor=invalid')[0])
        self.assertEqual(1, sum(method == 'session/load' for method, _ in self.acp.requests))

    def test_prompt_remains_pending_until_correlated_result_with_scoped_approval(self):
        sid = self.make_session()
        before = self.app.events.cursor(self.app.events._sequence)
        self.assertEqual('pending', self.send()['status'])
        self.assertEqual('pending', self.send()['status'])
        self.assertEqual('failed', self.send(submission_id='other')['status'])
        self.assertEqual(1, len(self.acp.prompts))
        self.acp.emit(sid, text_update('user', '你好'))
        self.acp.emit(sid, text_update('agent', '🍃'))
        self.acp.emit(sid, text_update('agent', '正在运行'))
        self.acp.permission(sid)
        snapshot = self.call('GET', '/v1/threads/new')[1]
        state = snapshot['sessionState']
        self.assertEqual({'status': 'waiting'}, state['execution'])
        self.assertEqual(['allow-once', 'deny'], [row['effect'] for row in state['interactions'][0]['choices']])
        self.assertEqual('python fixture.py', state['interactions'][0]['detail'])
        self.assertEqual(['agentMessage'], [item['type'] for item in snapshot['thread']['turns'][0]['items']])
        self.assertFalse(snapshot['capabilities']['operations']['session.interrupt']['supported'])
        self.assertEqual('native_waiting_user', self.native.update_blocker())
        stale = self.answer_payload(state, 'stale') | {'runtimeGeneration': 'old'}
        self.assertEqual('failed', self.call('POST', '/v1/interactions/respond', stale)[1]['status'])
        self.assertEqual([], self.acp.answers)
        payload = self.answer_payload(state)
        with ThreadPoolExecutor(max_workers=1) as executor:
            result = executor.submit(self.call, 'POST', '/v1/interactions/respond', payload)
            self.assertTrue(self.acp.answer_written.wait(2))
            self.assertFalse(result.done())
            self.acp.emit(sid, {'sessionUpdate': 'tool_call_update', 'toolCallId': 'tool-1', 'status': 'in_progress'})
            self.assertEqual('accepted', result.result(timeout=2)[1]['status'])
        self.assertEqual([('permission-1', {'outcome': 'selected', 'optionId': 'allow'})], self.acp.answers)
        self.assertEqual('pending', self.call('GET', '/v1/submissions/message')[1]['status'])
        self.acp.emit(sid, {'sessionUpdate': 'tool_call_update', 'toolCallId': 'tool-1',
                            'title': 'Run the fixture', 'status': 'completed'})
        self.acp.prompts[0][2].set_result({'stopReason': 'end_turn'})
        accepted = self.call('GET', '/v1/submissions/message')[1]
        self.assertEqual('accepted', accepted['status'])
        self.assertEqual({'kind': 'input-accepted', 'threadId': 'new', 'submissionId': 'message'}, accepted['receipt'])
        settled = self.call('GET', '/v1/threads/new')[1]
        self.assertEqual('message', settled['thread']['turns'][0]['items'][0]['clientId'])
        tool = settled['thread']['turns'][0]['items'][-1]
        self.assertEqual(('dynamicToolCall', 'Run the fixture', 'completed'),
                         (tool['type'], tool['tool'], tool['status']))
        self.assertEqual([], settled['sessionState']['interactions'])
        _, events, reset = self.app.events.read(before, timeout=0)
        self.assertFalse(reset)
        self.assertEqual(1, next(row['params']['relayOffset'] for row in events if row['method'] == 'item/agentMessage/delta'))

    def test_load_preserves_permission_owned_by_existing_native_session(self):
        self.acp.external_wait = True
        snapshot = self.call('GET', '/v1/threads/old')[1]
        state = snapshot['sessionState']
        self.assertEqual('waiting', state['execution']['status'])
        self.assertFalse(state['control']['canOperate'])
        self.assertEqual('unsupported', state['interactions'][0]['kind'])
        self.assertEqual([], self.acp.answers)
        self.assertEqual('failed', self.send('old')['status'])
        self.assertEqual([], self.acp.prompts)

    def test_disconnect_keeps_dispatched_prompt_unknown_without_resending(self):
        self.make_session()
        self.assertEqual('pending', self.send()['status'])
        self.acp.close()
        self.assertEqual('unknown', self.call('GET', '/v1/submissions/message')[1]['status'])
        self.assertEqual('unknown', self.send()['status'])
        self.assertEqual(1, len(self.acp.prompts))
        self.assertEqual('native_state_unknown', self.native.update_blocker())

    def test_unconfirmed_approval_remains_unknown_without_another_native_choice(self):
        self.make_session()
        self.send()
        self.acp.permission('new')
        state = self.call('GET', '/v1/threads/new')[1]['sessionState']
        payload = self.answer_payload(state)
        with patch('rivo_relay.antigravity.service.PERMISSION_CONFIRMATION_SECONDS', 0.01):
            first = self.call('POST', '/v1/interactions/respond', payload)[1]
            self.assertEqual('unknown', first['status'])
            self.assertEqual(first, self.call('POST', '/v1/interactions/respond', payload)[1])
        self.assertEqual(1, len(self.acp.answers))

    def test_start_requires_native_authentication_and_reuses_the_process(self):
        acp_factory = self.native._transport_factory
        self.native.close()

        def signed_out(*args):
            transport = acp_factory(*args)
            transport.auth = False
            return transport

        service = AntigravityService(self.config.antigravity_executable, self.config.workspace,
                                     project_dirs=self.config.antigravity_projects, transport_factory=signed_out)
        self.addCleanup(service.close)
        status = service.status()
        initial = self.acp
        self.assertTrue(status['authRequired'])
        self.assertFalse(status['connected'])
        self.assertFalse(service.runtime_capabilities()['operations']['session.send']['available'])
        initial.auth = True
        service._retry_at = 0
        self.assertTrue(service.status()['connected'])
        self.assertIs(initial, self.acp)


class AntigravityStdioTests(unittest.TestCase):
    def test_child_exit_disconnects_while_helper_still_holds_stdout(self):
        with tempfile.TemporaryDirectory() as temporary:
            executable = Path(temporary) / 'fake-agy'
            executable.write_text('#!' + sys.executable + '\n' + textwrap.dedent('''\
                import subprocess
                import sys

                sys.stdin.readline()
                subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(3)'],
                                 stdin=subprocess.DEVNULL)
            '''))
            executable.chmod(0o700)
            disconnected = threading.Event()
            transport = AcpTransport(executable, temporary, lambda *_: None, lambda *_: None,
                                     lambda _: disconnected.set())
            self.addCleanup(transport.close)
            transport.start()
            _, pending = transport.send_request('session/prompt', {})
            self.assertTrue(disconnected.wait(1.5))
            with self.assertRaises(AcpError) as error:
                pending.result(timeout=1)
            self.assertFalse(error.exception.definite)

    def test_bidirectional_newline_delimited_json_rpc(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            executable = directory / 'fake-agy'
            executable.write_text('#!' + sys.executable + '\n' + textwrap.dedent('''\
                import json
                import sys

                assert sys.argv[1:] == ['acp']

                def emit(frame):
                    print(json.dumps({'jsonrpc': '2.0', **frame}, ensure_ascii=False), flush=True)

                for line in sys.stdin:
                    frame = json.loads(line)
                    if frame.get('method') == 'initialize':
                        emit({'id': frame['id'], 'result': {'protocolVersion': 1}})
                    elif frame.get('method') == 'session/prompt':
                        prompt_id = frame['id']
                        emit({'method': 'session/update', 'params': {'sessionId': 'native', 'text': '你好'}})
                        emit({'id': 'tool-request', 'method': 'session/request_permission', 'params': {}})
                    elif frame.get('id') == 'tool-request':
                        assert frame['result'] == {'outcome': {'outcome': 'selected', 'optionId': 'actual'}}
                        emit({'id': prompt_id, 'result': {'stopReason': 'end_turn'}})
            '''))
            executable.chmod(0o700)
            updates = []
            transport = AcpTransport(executable, directory, lambda _, update: updates.append(update),
                                     lambda peer, key, _: peer.respond(key, {'outcome': 'selected', 'optionId': 'actual'}),
                                     lambda _: None)
            self.addCleanup(transport.close)
            transport.start()
            self.assertEqual({'protocolVersion': 1}, transport.request('initialize', {}, timeout=2))
            self.assertEqual({'stopReason': 'end_turn'}, transport.request('session/prompt', {}, timeout=2))
            self.assertEqual([{'sessionId': 'native', 'text': '你好'}], updates)
