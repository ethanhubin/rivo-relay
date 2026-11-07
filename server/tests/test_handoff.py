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
        writers.preview_scope.return_value = [TID, OTHER]
        service.writers = writers
        service.native_journal.ensure = Mock(return_value=NativeConvergenceResult('ready', 0, 1))
        service._handoff_hosts['ticket'] = copy.deepcopy(descriptor)
        snapshot = {'threadId': TID, 'holders': [{'pid': 10, 'threads': [TID, OTHER]}]}
        writers.consume.return_value = snapshot
        writers.restart_command.return_value = ['fixture'] if restart else None
        writers.snapshot.return_value = {'threadId': TID, 'holders': [{'pid': 20}]}
        writers.busy.return_value = False
        return service, native, writers

    def test_missing_history_host_cache_can_request_live_handoff_preview(self):
        service, native, writers = self.service()
        service.native_journal.cached_host = Mock(return_value=None)
        runtime = service._thread_runtime(TID)
        self.assertFalse(runtime['handoffAvailable'])
        self.assertTrue(runtime['handoffCheckAvailable'])
        writers.verify_native_host.assert_not_called()
        connection, descriptor = service._handoff_connection(TID)
        self.assertIs(connection, native)
        writers.verify_native_host.assert_called_once()

    def test_failed_handoff_verification_expires_and_can_recover(self):
        service, native, writers = self.service()
        service.native_journal.cached_host = Mock(return_value=native.native_host_info())
        writers.verify_native_host.side_effect = [CodexError('temporarily unavailable'), None]
        with patch('rivo_relay.codex.sessions.time.monotonic', return_value=100):
            self.assertFalse(service._thread_runtime(TID)['handoffAvailable'])
        with patch('rivo_relay.codex.sessions.time.monotonic', return_value=106):
            self.assertTrue(service._thread_runtime(TID)['handoffAvailable'])
        self.assertEqual(2, writers.verify_native_host.call_count)

    def test_resume_released_shared_desktop_without_killing_or_starting_turn(self):
        service, native, writers = self.service()
        native.status = lambda: {'connected': True, 'runtime': 'desktop', 'desktopShared': True}
        service._observe(TID, 'ownership', '', {'released': True})
        preview = service.preview_handoff(TID, 'resume', 'phone')
        service.handoff(TID, 'resume', owner='phone', ticket=preview['ticket'])
        self.assertNotIn(('ownership', ''), service.observations_for(TID))
        self.assertIn(TID, service._joined)
        writers.terminate.assert_not_called()
        self.assertNotIn('turn/start', [m for m, _ in native.calls])
        with self.assertRaises(CodexError):
            service.handoff(TID, 'resume', owner='phone', ticket=preview['ticket'])

    def test_resume_failure_keeps_release_intent(self):
        service, native, writers = self.service()
        service._observe(TID, 'ownership', '', {'released': True})
        preview = service.preview_handoff(TID, 'resume', 'phone')
        original = native.request
        def request(method, params, **kwargs):
            if method == 'thread/resume':
                raise CodexError('already has an active writer', code='native_rejected')
            return original(method, params, **kwargs)
        native.request = request
        with self.assertRaises(CodexError):
            service.handoff(TID, 'resume', owner='phone', ticket=preview['ticket'])
        self.assertTrue(service.observations_for(TID)[('ownership', '')]['released'])
        self.assertTrue(service.observations_for(TID)[('ownership', '')]['externalWriter'])
        snapshot = service.get_session(TID)
        self.assertEqual('takeover', snapshot['control']['action'])
        self.assertFalse(snapshot['control']['canOperate'])
        writers.terminate.assert_not_called()

    def test_open_released_session_checks_owner_without_acquiring(self):
        for owner, expected in [('none', 'released'), ('external', 'external'), ('unknown', 'ownership_unverified')]:
            with self.subTest(owner=owner):
                service, native, writers = self.service()
                native.writer_owner = Mock(return_value=owner)
                service._observe(TID, 'ownership', '', {'released': True, 'releaseState': 'verified'})
                native.calls.clear()
                snapshot = service.get_session(TID)
                self.assertEqual(expected, snapshot['control']['state'])
                native.writer_owner.assert_called_once_with(TID)
                self.assertNotIn('thread/resume', [m for m, _ in native.calls])
                self.assertNotIn('turn/start', [m for m, _ in native.calls])
                writers.terminate.assert_not_called()

    def test_explicit_send_acquires_released_writer_then_starts_once(self):
        service, native, writers = self.service()
        service._observe(TID, 'ownership', '', {'released': True, 'releaseState': 'verified'})
        receipt = service.send(TID, 'hello', acquire_released=True)
        methods = [m for m, _ in native.calls]
        self.assertEqual('turn', receipt['turnId'])
        self.assertLess(methods.index('thread/resume'), methods.index('turn/start'))
        self.assertEqual(1, methods.count('turn/start'))
        self.assertNotIn(('ownership', ''), service.observations_for(TID))
        writers.terminate.assert_not_called()

    def test_send_conflict_preserves_release_and_never_starts_or_kills(self):
        service, native, writers = self.service()
        service._observe(TID, 'ownership', '', {'released': True, 'releaseState': 'verified'})
        original = native.request
        def request(method, params, **kwargs):
            if method == 'thread/resume':
                raise CodexError('already has an active writer', code='native_rejected')
            return original(method, params, **kwargs)
        native.request = request
        with self.assertRaises(CodexError):
            service.send(TID, 'hello', acquire_released=True)
        self.assertTrue(service.observations_for(TID)[('ownership', '')]['released'])
        self.assertEqual('external', service.get_session(TID)['control']['state'])
        self.assertNotIn('turn/start', [m for m, _ in native.calls])
        writers.terminate.assert_not_called()

    def test_background_send_cannot_reacquire_released_session(self):
        service, native, writers = self.service()
        service._observe(TID, 'ownership', '', {'released': True, 'releaseState': 'verified'})
        with self.assertRaises(CodexError) as caught:
            service.send(TID, 'hello')
        self.assertEqual('writer_released', caught.exception.code)
        self.assertIsNone(service.send_queued(TID, input_items=[{'type': 'text', 'text': 'queued'}]))
        self.assertNotIn('thread/resume', [m for m, _ in native.calls])
        self.assertNotIn('turn/start', [m for m, _ in native.calls])

    def test_resume_ticket_cannot_release_or_cross_owners(self):
        for action, owner in [('release', 'phone'), ('resume', 'other')]:
            service, native, writers = self.service()
            preview = service.preview_handoff(TID, 'resume', 'phone')
            with self.assertRaises(CodexError):
                service.handoff(TID, action, owner=owner, ticket=preview['ticket'])
            self.assertNotIn('thread/resume', [m for m, _ in native.calls])
            self.assertNotIn('thread/unsubscribe', [m for m, _ in native.calls])

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
            service.handoff(TID, 'release', owner='phone', ticket='ticket', confirmed=True)
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

    def test_joined_daemon_release_uses_process_preview_and_checks_writer_lock(self):
        service, native, writers = self.service()
        service._joined.add(TID)
        writers.preview.return_value = {'ticket': 'ticket', 'affectedThreadIds': [TID, OTHER],
            'processIds': [10], 'requiresConfirmation': False}
        preview = service.preview_handoff(TID, 'release', 'phone')
        self.assertEqual('writer-process', preview['releaseScope'])
        self.assertTrue(preview['requiresConfirmation'])
        self.assertEqual([TID, OTHER], preview['affectedThreadIds'])
        service.handoff(TID, 'release', owner='phone', ticket=preview['ticket'], confirmed=True)
        writers.terminate.assert_called_once()
        writers.busy.assert_any_call(TID)
        self.assertNotIn('thread/unsubscribe', [method for method, _ in native.calls])
        self.assertNotIn('thread/resume', [method for method, _ in native.calls])

    def test_release_interrupts_owned_turn_and_does_not_require_restart(self):
        service, native, writers = self.service(restart=True)
        service._joined.add(TID)
        service._active[TID] = 'active-turn'
        service.interactions.receive({'id': 7, 'method': 'item/tool/requestUserInput', 'params': {'threadId': TID}})
        service.interactions.receive({'id': 8, 'method': 'item/tool/requestUserInput', 'params': {'threadId': 'unrelated'}})
        writers.restart_command.side_effect = AssertionError('release must not require daemon restartability')
        def stop(snapshot):
            self.assertIn(('turn/interrupt', {'threadId': TID, 'turnId': 'active-turn'}), native.calls)
            with self.assertRaises(CodexError): service._resume(TID)
        writers.terminate.side_effect = stop
        result = service.handoff(TID, 'release', owner='phone', ticket='ticket', confirmed=True)
        self.assertTrue(result['released'])
        self.assertEqual('verified', service.observations_for(TID)[('ownership', '')]['releaseState'])
        self.assertEqual('verified', service.observations_for(OTHER)[('ownership', '')]['releaseState'])
        self.assertNotIn(TID, service._joined)
        self.assertFalse(service.interactions.list(TID))
        self.assertEqual(1, len(service.interactions.list('unrelated')))
        writers.restart.assert_not_called()

    def test_release_failure_keeps_queue_fence_but_never_marks_verified(self):
        service, native, writers = self.service()
        writers.terminate.side_effect = CodexError('still held', code='writer_still_held')
        with self.assertRaises(CodexError):
            service.handoff(TID, 'release', owner='phone', ticket='ticket', confirmed=True)
        for target in (TID, OTHER):
            ownership = service.observations_for(target)[('ownership', '')]
            self.assertTrue(ownership['released'])
            self.assertEqual('failed', ownership['releaseState'])
            with self.assertRaises(CodexError): service._resume(target)

    def test_interrupt_failure_does_not_block_confirmed_release(self):
        service, native, writers = self.service()
        service._joined.add(TID)
        service._active[TID] = 'active-turn'
        original = native.request
        def request(method, params, **kwargs):
            if method == 'turn/interrupt': raise CodexError('timeout', request_sent=True)
            return original(method, params, **kwargs)
        native.request = request
        self.assertTrue(service.handoff(TID, 'release', owner='phone', ticket='ticket', confirmed=True)['released'])
        writers.terminate.assert_called_once()

    def test_process_release_requires_explicit_confirmation(self):
        service, native, writers = self.service()
        with self.assertRaises(CodexError) as caught:
            service.handoff(TID, 'release', owner='phone', ticket='ticket')
        self.assertEqual('handoff_confirmation_required', caught.exception.code)
        writers.terminate.assert_not_called()

    def test_release_cannot_succeed_when_writer_lock_remains(self):
        service, native, writers = self.service()
        writers.busy.return_value = True
        with self.assertRaises(CodexError) as caught:
            service.handoff(TID, 'release', owner='phone', ticket='ticket', confirmed=True)
        self.assertEqual('writer_still_held', caught.exception.code)

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
        native.status = lambda: {'connected': True, 'runtime': 'desktop', 'desktopShared': True}
        service._joined.add(TID)
        preview = service.preview_handoff(TID, 'release', 'phone')
        native.generation = 'different-writer'
        with self.assertRaises(CodexError):
            service.handoff(TID, 'release', owner='phone', ticket=preview['ticket'])
        self.assertNotIn('thread/unsubscribe', [method for method, _ in native.calls])
        writers.terminate.assert_not_called()

    def test_uncertain_unsubscribe_keeps_release_intent_and_never_replays(self):
        service, native, writers = self.service()
        native.status = lambda: {'connected': True, 'runtime': 'desktop', 'desktopShared': True}
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

    def test_exited_children_do_not_invalidate_preview_but_new_children_do(self):
        for added in (False, True):
            manager, snapshot = self.manager()
            child = {'pid': 456, 'start': 'start', 'command': 'tool', 'executable': '/tool'}
            snapshot['holders'][0]['children'] = [child]
            manager.snapshot.return_value = copy.deepcopy(snapshot)
            ticket = manager.preview(TID, 'phone', 'release')['ticket']
            snapshot['holders'][0]['children'] = [{**child, 'pid': 789}] if added else []
            manager.snapshot.return_value = snapshot
            if added:
                with self.assertRaises(CodexError): manager.consume(TID, 'phone', 'release', ticket, True)
            else:
                self.assertEqual(123, manager.consume(TID, 'phone', 'release', ticket, True)['holders'][0]['pid'])

    def test_surviving_previewed_child_is_stopped_after_parent_exits(self):
        manager, snapshot = self.manager()
        child = {'pid': 456, 'start': 'start', 'command': 'tool', 'executable': '/tool'}
        snapshot['holders'][0]['children'] = [child]
        manager.snapshot.return_value = {'threadId': TID, 'holders': []}
        manager.identity = Mock(return_value=child)
        manager.busy = Mock(return_value=False)
        with patch('rivo_relay.codex.writers.os.kill') as kill:
            manager.terminate(snapshot)
        import signal
        kill.assert_called_once_with(456, signal.SIGKILL)

    def test_already_released_lock_completes_without_signals(self):
        manager, snapshot = self.manager()
        ticket = manager.preview(TID, 'phone', 'release')['ticket']
        manager.snapshot.return_value = {'threadId': TID, 'holders': []}
        current = manager.consume(TID, 'phone', 'release', ticket, True)
        manager.busy = Mock(return_value=False)
        with patch('rivo_relay.codex.writers.os.kill') as kill:
            manager.terminate(current)
        kill.assert_not_called()

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
