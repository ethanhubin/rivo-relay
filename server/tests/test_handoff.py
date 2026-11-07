import copy
import unittest
import socket
import sys
from pathlib import Path
from unittest.mock import patch, Mock
from test_codex import FakeNative
from rivo_relay.codex.sessions import CodexService
from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.writers import WriterProcesses
from rivo_relay.codex.native_convergence import NativeConvergenceResult

TID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
OTHER = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'

class HandoffTests(unittest.TestCase):
    def service(self, restart=False):
        native = FakeNative()
        native.socket_path = '/fixture/native.sock'
        native.status = lambda: {'connected': True, 'runtimeId': 20}
        descriptor = {'generation': native.generation, 'pid': 20, 'hostName': 'fixture',
                      'codexHome': '/fixture', 'processStart': 'fixture'}
        native.native_host_info = lambda: copy.deepcopy(descriptor)
        service = CodexService(connection=native)
        service.start()
        writers = Mock()
        service.writers = writers
        service.native_journal.ensure = Mock(return_value=NativeConvergenceResult('ready', 0, 1))
        service._handoff_hosts['ticket'] = copy.deepcopy(descriptor)
        snapshot = {'threadId': TID, 'holders': [{'pid': 10, 'threads': [TID, OTHER]}]}
        writers.consume.return_value = snapshot
        writers.restart_command.return_value = ['fixture'] if restart else None
        writers.snapshot.return_value = {'threadId': TID, 'holders': [{'pid': 20}]}
        writers.busy.return_value = False
        return service, native, writers

    def test_kill_then_acquire_and_preserve_other_queue_pause(self):
        service, native, writers = self.service()
        steps = []
        original = native.request
        def request(method, params, **kwargs):
            steps.append(method)
            return original(method, params, **kwargs)
        native.request = request
        writers.terminate.side_effect = lambda snapshot: steps.append('kill')
        self.assertEqual({'threadId': TID}, service.handoff(TID, 'takeover', owner='phone', ticket='ticket', confirmed=True))
        self.assertLess(steps.index('kill'), steps.index('thread/resume'))
        self.assertNotIn(('ownership', ''), service.observations_for(TID))
        self.assertTrue(service.observations_for(OTHER)[('ownership', '')]['released'])
        self.assertNotIn('turn/start', steps)

    def test_release_does_not_resume(self):
        service, native, writers = self.service()
        service.handoff(TID, 'release', owner='phone', ticket='ticket', confirmed=True)
        self.assertTrue(service.observations_for(TID)[('ownership', '')]['released'])
        self.assertNotIn('thread/resume', [m for m, _ in native.calls])

    def test_stale_confirmation_never_kills(self):
        service, native, writers = self.service()
        writers.consume.side_effect = CodexError('changed')
        with self.assertRaises(CodexError): service.handoff(TID, 'takeover', owner='phone', ticket='ticket')
        writers.terminate.assert_not_called()
        self.assertFalse(service.observations_for(TID))

    def test_kill_failure_never_resumes(self):
        service, native, writers = self.service()
        writers.terminate.side_effect = CodexError('still held')
        with self.assertRaises(CodexError): service.handoff(TID, 'takeover', owner='phone', ticket='ticket')
        self.assertNotIn('thread/resume', [m for m, _ in native.calls])

    def test_success_requires_verified_new_writer_pid(self):
        service, native, writers = self.service()
        writers.snapshot.return_value = {'holders': [{'pid': 999}]}
        with self.assertRaises(CodexError) as caught: service.handoff(TID, 'takeover', owner='phone', ticket='ticket')
        self.assertEqual('handoff_acquire_failed', caught.exception.code)
        self.assertTrue(service.observations_for(TID)[('ownership', '')]['released'])

    def test_shared_desktop_never_previews_or_kills_local_process(self):
        service, native, writers = self.service()
        native.status = lambda: {'connected': True, 'runtime': 'desktop', 'desktopShared': True}
        for call in (lambda: service.preview_handoff(TID, 'takeover', 'phone'),
                     lambda: service.handoff(TID, 'release', owner='phone', ticket='ticket')):
            with self.assertRaises(CodexError) as raised:
                call()
            self.assertEqual('handoff_desktop_shared', raised.exception.code)
        writers.preview.assert_not_called()
        writers.consume.assert_not_called()
        writers.terminate.assert_not_called()

    def test_changed_host_since_preview_cannot_consume_ticket_or_kill(self):
        service, native, writers = self.service()
        original = native.native_host_info
        native.native_host_info = lambda: {**original(), 'hostName': 'changed-native-host'}
        with self.assertRaises(CodexError) as raised:
            service.handoff(TID, 'takeover', owner='phone', ticket='ticket')
        self.assertEqual('handoff_host_changed', raised.exception.code)
        writers.consume.assert_not_called()
        writers.terminate.assert_not_called()

    def test_host_change_after_preflight_cannot_pause_queue_or_kill(self):
        service, native, writers = self.service()
        original = native.native_host_info()
        native.native_host_info = Mock(side_effect=[original, {**original, 'processStart': 'reused'}])
        with self.assertRaises(CodexError):
            service.handoff(TID, 'release', owner='phone', ticket='ticket')
        writers.terminate.assert_not_called()
        self.assertFalse(service.observations_for(TID))

    def test_takeover_reconciles_history_after_kill_before_acquiring_new_writer(self):
        service, native, writers = self.service()
        def reconcile(*args, **kwargs):
            writers.terminate.assert_called_once()
            self.assertNotIn('thread/resume', [method for method, _ in native.calls])
            return NativeConvergenceResult('unavailable', 0, 1, error='repair failed without mutation')
        service.native_journal.ensure = reconcile
        with self.assertRaises(CodexError) as raised:
            service.handoff(TID, 'takeover', owner='phone', ticket='ticket')
        self.assertEqual('native_history_unavailable', raised.exception.code)
        self.assertTrue(service.observations_for(TID)[('ownership', '')]['released'])
        self.assertNotIn('thread/resume', [method for method, _ in native.calls])

    def test_shared_desktop_releases_owned_subscription_without_process_signals(self):
        service, native, writers = self.service()
        native.status = lambda: {'connected': True, 'runtime': 'desktop', 'desktopShared': True}
        service._joined.add(TID)
        preview = service.preview_handoff(TID, 'release', 'phone')
        self.assertEqual('relay-subscription', preview['releaseScope'])
        result = service.handoff(TID, 'release', owner='phone', ticket=preview['ticket'])
        self.assertEqual('relay-subscription', result['releaseScope'])
        self.assertIn(('thread/unsubscribe', {'threadId': TID}), native.calls)
        self.assertNotIn(TID, service._joined)
        self.assertTrue(service.observations_for(TID)[('ownership', '')]['released'])
        writers.terminate.assert_not_called()
        writers.preview.assert_not_called()

    def test_subscription_release_ticket_is_bound_to_writer_generation(self):
        service, native, writers = self.service()
        service._joined.add(TID)
        preview = service.preview_handoff(TID, 'release', 'phone')
        native.generation = 'different-writer'
        with self.assertRaises(CodexError):
            service.handoff(TID, 'release', owner='phone', ticket=preview['ticket'])
        self.assertNotIn('thread/unsubscribe', [method for method, _ in native.calls])
        writers.terminate.assert_not_called()

    def test_uncertain_unsubscribe_keeps_release_intent_and_never_replays(self):
        service, native, writers = self.service()
        service._joined.add(TID)
        preview = service.preview_handoff(TID, 'release', 'phone')
        native.request = Mock(side_effect=CodexError('disconnected after dispatch', request_sent=True))
        with self.assertRaises(CodexError):
            service.handoff(TID, 'release', owner='phone', ticket=preview['ticket'])
        self.assertTrue(service.observations_for(TID)[('ownership', '')]['released'])
        self.assertEqual(1, native.request.call_count)
        writers.terminate.assert_not_called()

class PreviewTests(unittest.TestCase):
    def test_verified_host_requires_local_home_identity_and_socket_before_inspection(self):
        manager = WriterProcesses('/fixture')
        descriptor = {'hostName': socket.gethostname(), 'platform': sys.platform, 'codexHome': '/fixture',
                      'pid': 123, 'processStart': 'start', 'processStartMethod': 'ps-lstart', 'executable': '/bin/codex'}
        manager.identity = Mock(return_value={'pid': 123, 'start': 'start', 'command': 'codex app-server', 'executable': '/bin/codex'})
        manager.verify_local_runtime = Mock()
        for changed in ({'hostName': 'remote-host'}, {'codexHome': '/another/home'}, {'processStart': 'reused-pid'}):
            with self.assertRaises(CodexError):
                manager.verify_native_host({**descriptor, **changed}, 123, '/fixture/socket')
        manager.verify_local_runtime.assert_not_called()
        manager.verify_native_host(descriptor, 123, '/fixture/socket')
        manager.verify_local_runtime.assert_called_once_with(123, '/fixture/socket')

    def manager(self):
        manager = WriterProcesses('/fixture')
        snapshot = {'threadId': TID, 'holders': [{'pid': 123, 'start': 'start', 'command': '/bin/codex app-server', 'executable': '/bin/codex', 'threads': [TID, OTHER], 'children': []}]}
        manager.snapshot = Mock(return_value=copy.deepcopy(snapshot))
        return manager, snapshot

    def test_preview_has_impact_but_no_command_or_credentials(self):
        manager, snapshot = self.manager()
        preview = manager.preview(TID, 'phone', 'takeover')
        self.assertTrue(preview['requiresConfirmation'])
        self.assertEqual(sorted([TID, OTHER]), preview['affectedThreadIds'])
        self.assertNotIn('command', str(preview))
        self.assertEqual(snapshot, manager.consume(TID, 'phone', 'takeover', preview['ticket'], True))
        with self.assertRaises(CodexError): manager.consume(TID, 'phone', 'takeover', preview['ticket'], True)

    def test_confirmation_required_and_bound_to_owner_action_and_thread(self):
        for owner, action, thread, confirm in [('other', 'takeover', TID, True), ('phone', 'release', TID, True), ('phone', 'takeover', OTHER, True), ('phone', 'takeover', TID, False)]:
            manager, _ = self.manager()
            ticket = manager.preview(TID, 'phone', 'takeover')['ticket']
            with self.assertRaises(CodexError): manager.consume(thread, owner, action, ticket, confirm)

    def test_expired_or_changed_pid_or_added_thread_rejected(self):
        for changed in ['pid', 'threads', 'start']:
            manager, snapshot = self.manager()
            ticket = manager.preview(TID, 'phone', 'takeover')['ticket']
            snapshot['holders'][0][changed] = 999 if changed == 'pid' else ['extra'] if changed == 'threads' else 'new'
            manager.snapshot.return_value = snapshot
            with self.assertRaises(CodexError): manager.consume(TID, 'phone', 'takeover', ticket, True)
        manager, _ = self.manager()
        manager.clock = lambda: 0
        ticket = manager.preview(TID, 'phone', 'takeover')['ticket']
        manager.clock = lambda: 121
        with self.assertRaises(CodexError): manager.consume(TID, 'phone', 'takeover', ticket, True)

    def test_restart_rejects_arbitrary_command_or_different_socket(self):
        manager, snapshot = self.manager()
        self.assertIsNone(manager.restart_command(snapshot, 999, '/fixture/native.sock'))
        with self.assertRaises(CodexError): manager.restart_command(snapshot, 123, '/fixture/native.sock')
        snapshot['holders'][0]['command'] += ' --listen unix:///wrong.sock'
        with self.assertRaises(CodexError): manager.restart_command(snapshot, 123, '/fixture/native.sock')

    def test_reused_pid_never_escalated_to_sigkill(self):
        manager, snapshot = self.manager()
        manager.identity = Mock(return_value=None)
        manager.busy = Mock(return_value=False)
        with patch('rivo_relay.codex.writers.os.kill') as kill:
            manager.terminate(snapshot)
        self.assertEqual(1, kill.call_count)

class RealProcessTests(unittest.TestCase):
    def test_real_file_lock_and_child_process_are_released(self):
        import os
        import signal
        import subprocess
        import sys
        import tempfile
        import time
        from pathlib import Path
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)/'thread-writer-locks'
            directory.mkdir()
            lock = directory/(TID+'.lock')
            child_code = "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)"
            script = "import fcntl,subprocess,sys,time; f=open(sys.argv[1],'w'); fcntl.flock(f,fcntl.LOCK_EX); c=subprocess.Popen([sys.executable,'-c',sys.argv[2]]); print(c.pid,flush=True); time.sleep(60)"
            process = subprocess.Popen([sys.executable,'-c',script,str(lock),child_code],stdout=subprocess.PIPE,text=True,start_new_session=True)
            child = int(process.stdout.readline())
            manager = WriterProcesses(root)
            # Only the known fixture executable is accepted in this test.
            manager.validate_executable = lambda identity: self.assertEqual(process.pid, identity['pid'])
            try:
                self.assertTrue(manager.busy(TID))
                preview = manager.preview(TID, 'test', 'release')
                self.assertEqual([process.pid], preview['processIds'])
                self.assertEqual(1, preview['childProcessCount'])
                snapshot = manager.consume(TID, 'test', 'release', preview['ticket'], False)
                manager.terminate(snapshot)
                process.wait(timeout=3)
                self.assertFalse(manager.busy(TID))
                deadline = time.monotonic()+3
                while time.monotonic()<deadline:
                    state=subprocess.run(['ps','-p',str(child),'-o','stat='],text=True,capture_output=True).stdout.strip()
                    if not state or state.startswith('Z'): break
                    time.sleep(.05)
                self.assertTrue(not state or state.startswith('Z'), state)
            finally:
                for pid in (child, process.pid):
                    try: os.kill(pid, signal.SIGKILL)
                    except ProcessLookupError: pass
                process.wait(timeout=3)
                process.stdout.close()
