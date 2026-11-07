import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from rivo_relay.codex.connection import CodexError, NativeConnection
from rivo_relay.codex.desktop import DesktopConnection
from rivo_relay.codex.host_probe import native_host_info
from rivo_relay.codex.runtime import RuntimeConnection


HOST = Path(__file__).parents[1] / 'rivo_relay/codex/desktop_host.cjs'
FIXTURE = r'''
const {startDesktopBridge} = require(process.argv[1]);
const notify = new Set(), state = new Set(), human = new Set();
let ready = 'connected';
const connection = {
 getConnectionState: () => ready, getTransportKind: () => 'fixture',
 registerInternalNotificationHandler: fn => {notify.add(fn); return () => notify.delete(fn)},
 registerInternalConnectionStateHandler: fn => {state.add(fn); return () => state.delete(fn)},
 registerInternalServerRequestHandler: entry => {human.add(entry); return () => human.delete(entry)},
 handleClientResponse: async response => { for(const fn of notify) fn({method:'serverRequest/resolved',params:{requestId:response.id}}); },
 sendAppServerRequest: async (method, params) => {
   if(method === 'thread/start') return {thread:{id:'desktop-thread'}};
   if(method === 'test/approval') {
     for(const entry of human) entry.handler({id:42,method:'item/commandExecution/requestApproval',params:{threadId:'desktop-thread'}});
     return {};
   }
   if(method === 'test/disconnect') process.exit(0);
   if(method === 'test/notReady') {ready='reconnecting'; for(const fn of state)fn(ready); return {}};
   return {method,params};
 },
};
startDesktopBridge({connection,directory:process.argv[2],appVersion:'fixture'}).then(bridge => {
 console.log('ready');
 process.on('SIGTERM',()=>bridge.close().then(()=>process.exit(0)));
});
'''


@unittest.skipUnless(shutil.which('node'), 'Node is needed for the isolated Desktop host fixture')
class DesktopBridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='rivo-bridge-', dir='/tmp')
        self.process = subprocess.Popen(['node', '-e', FIXTURE, str(HOST), self.temp.name],
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual('ready', self.process.stdout.readline().strip())
        self.events = []
        self.connection = DesktopConnection(self.temp.name, self.events.append, timeout=.5)

    def tearDown(self):
        self.connection.close()
        if self.process.poll() is None:
            self.process.terminate()
        self.process.communicate(timeout=5)
        self.temp.cleanup()

    def wait_for(self, predicate):
        until = time.monotonic() + 2
        while time.monotonic() < until:
            if predicate(): return
            time.sleep(.01)
        self.fail('fixture event was not dispatched')

    def test_authenticated_rpc_notifications_and_human_response_share_connection(self):
        status = self.connection.start()
        self.assertTrue(status['desktopShared'])
        self.assertEqual('rivo-desktop-host-protocol-1', status['bridge']['provenance'])
        self.assertEqual('desktop-thread', self.connection.request('thread/start')['thread']['id'])
        self.connection.request('test/approval')
        self.wait_for(lambda: any(e.get('id') == 42 for e in self.events))
        self.assertEqual(self.connection.generation, self.events[-1]['connectionId'])
        self.connection.respond(42, {'decision': 'accept'})
        self.wait_for(lambda: any(e['method'] == 'serverRequest/resolved' for e in self.events))
        with self.assertRaises(CodexError) as caught:
            self.connection.respond(42, {'decision': 'accept'})
        self.assertTrue(caught.exception.definite)

    def test_socket_alive_does_not_mean_writer_ready(self):
        self.connection.start()
        self.connection.request('test/notReady')
        self.wait_for(lambda: not self.connection.status()['connected'])
        self.assertTrue(self.connection.status()['bridge']['available'])
        with self.assertRaises(CodexError) as caught:
            self.connection.request('turn/start')
        self.assertFalse(caught.exception.request_sent)

    def test_sent_request_is_unknown_on_disconnect_and_never_replayed(self):
        self.connection.start()
        with self.assertRaises(CodexError) as caught:
            self.connection.request('test/disconnect')
        self.assertTrue(caught.exception.request_sent)

    def test_public_token_is_rejected_before_connect(self):
        Path(self.temp.name, 'token').chmod(0o644)
        with self.assertRaises(CodexError) as caught:
            self.connection.start()
        self.assertFalse(caught.exception.request_sent)

    def test_notification_can_make_rpc_without_blocking_reader(self):
        completed = threading.Event()
        def callback(event):
            if event.get('id') == 42:
                self.connection.request('test/echo', {'fromNotification': True})
                completed.set()
        self.connection.on_event = callback
        self.connection.start()
        self.connection.request('test/approval')
        self.assertTrue(completed.wait(2))

    def test_client_disconnect_and_reconnect_do_not_stop_desktop(self):
        self.connection.start()
        original = self.connection.generation
        self.connection._socket.close()
        self.wait_for(lambda: not self.connection.status()['bridge']['available'])
        self.connection.start()
        self.assertNotEqual(original, self.connection.generation)
        self.connection.close()
        self.assertIsNone(self.process.poll())


class Backend:
    def __init__(self, name, ready=True, home='/native/home'):
        self.name, self.ready, self.generation = name, ready, name + '-one'
        self.codex_home, self.socket_path = Path('/relay/wrong-home'), '/forwarded.sock'
        self.calls, self.home, self.on_event = [], home, lambda _: None
    def status(self):
        return {'connected': self.ready, 'runtime': self.name, 'bridge': {'writerReady': self.ready}}
    def start(self): return self.status()
    def request(self, method, params=None, **kwargs):
        self.calls.append((method, params))
        if method == 'thread/start': return {'thread': {'id': self.name + '-thread'}}
        return {}
    def respond(self, request_id, result): self.calls.append(('respond', request_id))
    def native_host_info(self):
        return {'pid':1,'processStart':'one','executable':'/native/codex','codexHome':self.home,
                'hostName':'native-host','platform':'darwin'}
    def close(self): pass


class RuntimeTests(unittest.TestCase):
    def runtime(self, desktop_home='/native/home'):
        self.now = 0
        self.native, self.desktop = Backend('app_server'), Backend('desktop', False, desktop_home)
        self.events = []
        return RuntimeConnection(native=self.native, desktop=self.desktop,
                                 clock=lambda: self.now, on_event=self.events.append)

    def test_auto_hysteresis_and_unmaterialized_thread_pinning(self):
        runtime = self.runtime()
        runtime.start()
        original = runtime.request('thread/start')['thread']['id']
        self.desktop.ready = True
        self.now = 2; runtime.start()
        self.assertEqual('app_server', runtime.status()['preferredWriter'])
        self.now = 5; runtime.start()
        self.assertEqual('desktop', runtime.status()['preferredWriter'])
        runtime.request('turn/start', {'threadId': original})
        self.assertEqual('turn/start', self.native.calls[-1][0])
        self.native.ready = False
        with self.assertRaises(CodexError) as caught:
            runtime.request('turn/start', {'threadId': original})
        self.assertFalse(caught.exception.request_sent)
        self.assertFalse(any(call[0] == 'turn/start' for call in self.desktop.calls))

    def test_mismatched_native_home_never_auto_promotes(self):
        runtime = self.runtime('/another/native/home')
        self.desktop.ready = True
        runtime.start(); self.now = 5; runtime.start()
        self.assertEqual('app_server', runtime.status()['preferredWriter'])
        self.assertIsNotNone(runtime.status()['bridge']['error'])

    def test_unavailable_forwarded_target_cannot_switch_to_local_desktop(self):
        runtime = self.runtime()
        self.native.ready, self.desktop.ready = False, True
        with self.assertRaises(CodexError): runtime.start()
        self.assertEqual('app_server', runtime.status()['preferredWriter'])

    def test_writer_disconnect_preserves_other_connection_live_turn(self):
        from rivo_relay.codex.sessions import CodexService
        runtime = self.runtime()
        service = CodexService(connection=runtime)
        runtime.start()
        runtime.request('thread/start')
        self.desktop.ready = True
        self.now = 2; runtime.start()
        self.now = 5; runtime.start()
        runtime.request('thread/start')
        for backend in (self.native, self.desktop):
            backend.on_event({'method': 'turn/started', 'params': {
                'threadId': backend.name + '-thread', 'turn': {'id': 'active-' + backend.name}}})
        self.native.on_event({'method': 'relay/nativeDisconnected', 'params': {}})
        self.assertNotIn('app_server-thread', service._active)
        self.assertEqual('active-desktop', service._active['desktop-thread'])

    def test_reconnect_generation_retires_only_its_pinned_state_without_old_disconnect(self):
        from rivo_relay.codex.sessions import CodexService
        runtime = self.runtime()
        service = CodexService(connection=runtime)
        runtime.start(); runtime.request('thread/start')
        self.desktop.ready = True
        self.now = 2; runtime.start()
        self.now = 5; runtime.start(); runtime.request('thread/start')
        for backend in (self.native, self.desktop):
            tid = backend.name + '-thread'
            service._created[tid] = {'id': tid}
            service._joined.add(tid)
            backend.on_event({'id': 4, 'method': 'item/commandExecution/requestApproval',
                              'params': {'threadId': tid}, 'connectionId': backend.generation})
        desktop_request = service.interactions.list('desktop-thread')[0]['key']
        wrapper_generation = runtime.generation
        self.native.generation = 'app_server-two'
        self.now = 8; runtime.start()
        self.assertEqual(wrapper_generation, runtime.generation)
        self.assertNotIn('app_server-thread', service._created)
        self.assertNotIn('app_server-thread', service._joined)
        self.assertEqual([], service.interactions.list('app_server-thread'))
        self.assertIn('desktop-thread', service._created)
        self.assertIn('desktop-thread', service._joined)
        self.assertEqual(desktop_request, service.interactions.list('desktop-thread')[0]['key'])
        # The late old notification cannot clear a newly observed turn.
        self.native.on_event({'method': 'turn/started', 'params': {'threadId': 'app_server-thread',
                              'turn': {'id': 'new-turn'}}, 'connectionId': 'app_server-two'})
        self.native.on_event({'method': 'relay/nativeDisconnected', 'params': {}, 'connectionId': 'app_server-one'})
        self.assertEqual('new-turn', service._active['app_server-thread'])

    def test_reconnected_desktop_cannot_reuse_old_host_proof_while_native_is_offline(self):
        runtime = self.runtime()
        self.desktop.ready = True
        runtime.start(); self.now = 3; runtime.start()
        self.assertEqual('desktop', runtime.status()['preferredWriter'])
        self.native.ready = False
        self.desktop.generation = 'desktop-two'
        self.now = 6
        with self.assertRaises(CodexError): runtime.start()
        self.assertEqual('app_server', runtime.status()['preferredWriter'])

    def test_response_keeps_origin_when_numeric_request_ids_collide(self):
        runtime = self.runtime(); runtime.start()
        for backend in (self.native, self.desktop):
            backend.on_event({'id': 7, 'method': 'item/tool/requestUserInput', 'params': {},
                              'connectionId': backend.generation})
        self.assertNotEqual(self.events[0]['id'], self.events[1]['id'])
        runtime.respond(self.events[0]['id'], {})
        runtime.respond(self.events[1]['id'], {})
        self.assertIn(('respond', 7), self.native.calls)
        self.assertIn(('respond', 7), self.desktop.calls)

    def test_host_probe_uses_native_rpc_and_ignores_relay_codex_home(self):
        backend = Backend('native')
        descriptor = {'pid': 42, 'processStart': 'started', 'executable': '/host/codex',
                      'codexHome': '/host/codex-home'}
        calls = []
        def request(method, params, **kwargs):
            calls.append((method, params))
            return {'process': {'id': 42}} if method == 'server/diagnostics' else {
                'exitCode': 0, 'stdout': json.dumps(descriptor)}
        backend.request = request
        self.assertEqual('/host/codex-home', native_host_info(backend)['codexHome'])
        self.assertEqual('command/exec', calls[1][0])
        self.assertNotIn('sandboxPolicy', calls[1][1])
        self.assertEqual('42', calls[1][1]['command'][-1])

    def test_host_probe_executes_on_isolated_native_process_home(self):
        try:
            subprocess.run(['ps', '-p', str(os.getpid()), '-o', 'lstart='],
                           capture_output=True, check=True, timeout=3)
        except PermissionError:
            self.skipTest('sandbox blocks test-process inspection; run this fixture with process inspection allowed')
        backend = Backend('native')
        def request(method, params, **kwargs):
            if method == 'server/diagnostics': return {'process': {'id': os.getpid()}}
            result = subprocess.run(params['command'], capture_output=True, text=True, timeout=15)
            return {'exitCode': result.returncode, 'stdout': result.stdout}
        backend.request = request
        with tempfile.TemporaryDirectory() as native_home, patch.dict(os.environ, {'CODEX_HOME': native_home}):
            descriptor = native_host_info(backend)
            self.assertEqual(str(Path(native_home).resolve()), descriptor['codexHome'])
            self.assertEqual(os.getpid(), descriptor['pid'])
            self.assertTrue(Path(descriptor['executable']).is_file())
            self.assertEqual('ps-lstart', descriptor['processStartMethod'])
