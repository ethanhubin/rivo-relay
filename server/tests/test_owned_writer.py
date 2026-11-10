"""Per-thread writer routing, release, and generation fencing regressions."""
from relay_test_support.native import Backend
import unittest
import threading
import os
import signal
import subprocess
import sys
from unittest.mock import patch
from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.owned_writer import SessionWriters
from rivo_relay.codex.runtime import RuntimeConnection
from rivo_relay.codex.sessions import CodexService


class OwnedRoutingTests(unittest.TestCase):
    def setUp(self):
        self.metadata = Backend()
        self.pool = SessionWriters(self.metadata)
        self.created = []
        def create():
            writer = Backend()
            writer.on_event = lambda event: self.pool._event(writer, event)
            self.created.append(writer)
            return writer
        self.pool._new_writer = create
        self.busy = patch('rivo_relay.codex.owned_writer.WriterProcesses.busy', return_value=False).start()
        self.addCleanup(patch.stopall)
        self.addCleanup(self.pool.close)
    def test_fork_rejects_old_schema_before_native_mutation(self):
        with patch.object(Backend, 'native_host_info', return_value={'executable': '/codex'}, create=True), \
             patch('rivo_relay.codex.owned_writer.subprocess.run'), \
             patch('pathlib.Path.read_text', return_value='{"properties":{"cwd":{}}}'):
            with self.assertRaises(CodexError) as caught:
                self.pool.request('thread/fork', {'threadId': 'parent', 'lastTurnId': 'turn'})
        self.assertEqual('fork_unsupported', caught.exception.code)
        self.assertTrue(self.created[-1].closed)
        self.assertFalse(any(method == 'thread/fork' for method, _ in self.created[-1].calls))

    def test_prepared_takeover_acquires_without_metadata_and_adopts_writer(self):
        with self.pool.prepare_takeover('target') as acquire:
            self.assertFalse(self.pool.owns('target'))
            self.metadata.closed = True
            result = acquire({'threadId': 'target', 'excludeTurns': True})
        self.assertEqual('target', result['thread']['id'])
        self.assertTrue(self.pool.owns('target'))
        self.assertFalse(self.created[-1].closed)

    def test_prepared_takeover_closes_unused_writer(self):
        with self.pool.prepare_takeover('target'):
            pass
        self.assertTrue(self.created[-1].closed)
        self.assertFalse(self.pool.owns('target'))

    def test_unmaterialized_release_preserves_writer_and_queue_state(self):
        runtime = RuntimeConnection(native=self.pool, desktop=Backend(), mode='app_server')
        service = CodexService(connection=runtime)
        service.start()
        service._resume('a')
        service._created['a'] = {'id': 'a'}
        runtime_state = service._thread_runtime('a')
        self.assertFalse(runtime_state['releaseAvailable'])
        self.assertIn('持久化', runtime_state['releaseUnavailableReason'])
        with patch.object(runtime, 'verify_release_history', side_effect=CodexError('thread not loaded')):
            with self.assertRaises(CodexError) as caught:
                service.preview_handoff('a', 'release', 'phone')
            self.assertEqual('thread_unmaterialized', caught.exception.code)
        preview = service.preview_handoff('a', 'release', 'phone')
        with patch.object(runtime, 'verify_release_history', side_effect=CodexError('history not durable')):
            with self.assertRaises(CodexError):
                service.handoff('a', 'release', owner='phone', ticket=preview['ticket'])
        self.assertFalse(self.created[0].closed)
        self.assertIn('a', service._created)
        self.assertFalse(service.observations_for('a').get(('ownership', ''), {}).get('released'))

    def test_materialized_writer_can_release_without_empty_session_notice(self):
        runtime = RuntimeConnection(native=self.pool, desktop=Backend(), mode='app_server')
        service = CodexService(connection=runtime)
        service.start()
        service._resume('a')
        self.assertTrue(service._thread_runtime('a')['releaseAvailable'])
        self.assertIsNone(service._thread_runtime('a')['releaseUnavailableReason'])

    def test_definite_approval_error_cannot_restore_request_after_retirement(self):
        events = []
        self.pool.on_event = events.append
        self.pool.request('thread/resume', {'threadId': 'a'})
        writer = self.created[0]
        writer.on_event({'id': 1, 'method': 'item/tool/requestUserInput', 'params': {'threadId': 'a'}})
        request_id = events[-1]['id']
        def respond(*args):
            writer.close()
            raise CodexError('closed before delivery')
        writer.respond = respond
        with self.assertRaises(CodexError):
            self.pool.respond(request_id, {})
        self.assertEqual({}, self.pool._requests)
        with self.assertRaises(CodexError):
            self.pool.respond(request_id, {})

    def test_shutdown_during_acquisition_cannot_register_or_leak_writer(self):
        entered, finish = threading.Event(), threading.Event()
        create = self.pool._new_writer
        errors = []
        def slow_create():
            writer = create()
            def request(*args, **kwargs):
                entered.set()
                finish.wait(2)
                # Even a late successful native response cannot undo shutdown.
                return {'thread': {'id': 'a'}}
            writer.request = request
            return writer
        self.pool._new_writer = slow_create
        def run():
            try:
                self.pool.request('thread/resume', {'threadId': 'a'})
            except CodexError as error:
                errors.append(error)
        worker = threading.Thread(target=run)
        worker.start()
        self.assertTrue(entered.wait(2))
        self.pool.close()
        finish.set()
        worker.join(3)
        self.assertFalse(worker.is_alive())
        self.assertEqual(1, len(errors))
        self.assertTrue(self.created[0].closed)
        self.assertFalse(self.pool.owns('a'))

    def test_wrong_resume_identity_is_closed_and_never_registered(self):
        create = self.pool._new_writer
        def wrong():
            writer = create()
            writer.request = lambda *a, **k: {'thread': {'id': 'other'}}
            return writer
        self.pool._new_writer = wrong
        with self.assertRaises(CodexError):
            self.pool.request('thread/resume', {'threadId': 'a'})
        self.assertTrue(self.created[0].closed)
        self.assertEqual({}, self.pool._writers)

    def test_failed_acquisition_retires_approval_even_without_eof_notification(self):
        create = self.pool._new_writer
        events = []
        self.pool.on_event = events.append
        def failed():
            writer = create()
            writer.close = lambda: setattr(writer, 'closed', True)
            def request(*args, **kwargs):
                writer.on_event({'id': 1, 'method': 'item/tool/requestUserInput', 'params': {'threadId': 'a'}})
                raise CodexError('uncertain', request_sent=True)
            writer.request = request
            return writer
        self.pool._new_writer = failed
        with self.assertRaises(CodexError):
            self.pool.request('thread/resume', {'threadId': 'a'})
        self.assertEqual({}, self.pool._requests)
        self.assertEqual([events[0]['id']], events[-1]['params']['requestIds'])

    def test_close_failure_does_not_skip_other_writers(self):
        for tid in ('a', 'b'):
            self.pool.request('thread/resume', {'threadId': tid})
        original = self.created[0].close
        self.created[0].close = lambda: (_ for _ in ()).throw(CodexError('close failed'))
        try:
            with self.assertRaises(CodexError):
                self.pool.close()
            self.assertTrue(self.created[1].closed)
            self.assertTrue(self.metadata.closed)
        finally:
            self.created[0].close = original

    def test_two_threads_release_one_keeps_other_and_public_connection_alive(self):
        for tid in ('a', 'b'):
            self.pool.request('thread/resume', {'threadId': tid})
            self.pool.request('turn/start', {'threadId': tid, 'input': []})
        a, b = self.created
        self.assertIsNot(a, b)
        self.assertFalse(self.metadata.calls)
        self.pool.release('a', a)
        self.assertTrue(a.closed)
        self.assertFalse(b.closed)
        self.assertFalse(self.metadata.closed)
        self.pool.request('turn/steer', {'threadId': 'b'})
        self.assertEqual('turn/steer', b.calls[-1][0])
        self.pool.request('thread/read', {'threadId': 'a', 'includeTurns': False})
        self.assertEqual('thread/read', self.metadata.calls[-1][0])
    def test_public_reads_never_resume_and_unbound_mutations_are_rejected(self):
        for method in ('thread/list', 'thread/turns/list'):
            self.pool.request(method, {'threadId': 'a'})
        self.assertEqual([], self.created)
        with self.assertRaises(CodexError):
            self.pool.request('turn/start', {'threadId': 'a'})
        self.pool.request('thread/name/set', {'threadId': 'a', 'name': 'renamed'})
        self.assertTrue(self.created[-1].closed)
        self.assertNotIn('thread/name/set', [m for m, _ in self.metadata.calls])

    def test_viewing_free_or_external_thread_does_not_acquire_writer(self):
        runtime = RuntimeConnection(native=self.pool, desktop=Backend(), mode='app_server')
        service = CodexService(connection=runtime)
        with patch('rivo_relay.codex.host_probe.local_native_host_info', return_value={'codexHome': '/fixture'}), \
             patch.object(service, 'history', return_value={'turns': [], 'nextCursor': None,
                 'backwardsCursor': None, 'nativeRevision': 'fixture', 'nativeSync': {'state': 'ready'}}):
            for held in (False, True):
                self.busy.return_value = held
                snapshot = service.get_session('thread')
                self.assertEqual('external' if held else 'none', snapshot['writerOwner'])
                self.assertEqual(held, snapshot['readOnly'])
                if not held:
                    self.assertTrue(snapshot['control']['canOperate'])
                    self.assertIsNone(snapshot['control']['action'])
                self.assertEqual([], self.created)
            self.busy.return_value = False
            service._resume('thread')
            self.assertEqual(1, len(self.created))
            self.assertTrue(runtime.owns_writer('thread'))

    def test_unverified_host_does_not_authorize_local_writer(self):
        with patch('rivo_relay.codex.host_probe.local_native_host_info', side_effect=CodexError('forwarded host')):
            self.assertEqual('unknown', self.pool.writer_owner('thread'))
        self.assertEqual([], self.created)

    def test_unverified_ownership_is_not_reported_as_external_holder(self):
        runtime = RuntimeConnection(native=self.pool, desktop=Backend(), mode='app_server')
        service = CodexService(connection=runtime)
        with patch('rivo_relay.codex.host_probe.local_native_host_info', side_effect=CodexError('unverified host')), \
             patch.object(service, 'history', return_value={'turns': [], 'nextCursor': None,
                 'backwardsCursor': None, 'nativeRevision': 'fixture', 'nativeSync': {'state': 'ready'}}):
            snapshot = service.get_session('thread')
        self.assertEqual('unknown', snapshot['writerOwner'])
        self.assertTrue(snapshot['readOnly'])
        self.assertEqual('ownership_unverified', snapshot['blockReason'])
        self.assertEqual('ownership_unverified', snapshot['control']['state'])
        self.assertFalse(snapshot['control']['canOperate'])
        self.assertIsNone(snapshot['control']['action'])
        self.assertIn('核验', snapshot['control']['unavailableReason'])
        self.assertEqual([], self.created)
    def test_same_request_id_from_two_writers_routes_answer_only_to_origin(self):
        events = []
        self.pool.on_event = events.append
        for tid in ('a', 'b'):
            self.pool.request('thread/resume', {'threadId': tid})
            self.created[-1].on_event({'id': 1, 'method': 'item/tool/requestUserInput', 'params': {'threadId': tid}})
        self.assertNotEqual(events[0]['id'], events[1]['id'])
        self.pool.respond(events[1]['id'], {'answers': {}})
        self.assertEqual([], self.created[0].replies)
        self.assertEqual([(1, {'answers': {}})], self.created[1].replies)
        self.created[0].close()
        with self.assertRaises(CodexError):
            self.pool.respond(events[0]['id'], {})
    def test_disconnect_never_replays_turn_or_invalidates_other_writer(self):
        events = []
        self.pool.on_event = events.append
        for tid in ('a', 'b'):
            self.pool.request('thread/resume', {'threadId': tid})
        self.created[0].close()
        self.assertEqual(['a'], events[-1]['params']['threadIds'])
        self.assertTrue(events[-1]['params']['ownedWriter'])
        with self.assertRaises(CodexError):
            self.pool.request('turn/start', {'threadId': 'a'})
        self.assertEqual(2, len(self.created))
        self.pool.request('thread/resume', {'threadId': 'a'})
        self.assertEqual(3, len(self.created))
        self.assertFalse(self.created[1].closed)
    def test_retired_writer_events_cannot_revive_thread_or_approval(self):
        events = []
        self.pool.on_event = events.append
        self.pool.request('thread/resume', {'threadId': 'a'})
        old = self.created[0]
        old.close()
        before = len(events)
        old.on_event({'method': 'turn/started', 'params': {'threadId': 'a'}})
        old.on_event({'id': 1, 'method': 'item/tool/requestUserInput', 'params': {'threadId': 'a'}})
        self.assertEqual(before, len(events))
        self.assertEqual({}, self.pool._requests)
        self.assertEqual({}, self.pool._thread_locks)

    def test_metadata_disconnect_preserves_owned_writer_and_status(self):
        events = []
        self.pool.on_event = events.append
        self.pool.request('thread/resume', {'threadId': 'a'})
        self.metadata.close()
        self.assertEqual([], events[-1]['params']['threadIds'])
        self.assertTrue(self.pool.status()['connected'])
        self.assertFalse(self.pool.status()['metadataConnected'])
        self.pool.request('turn/steer', {'threadId': 'a'})
        self.assertEqual('turn/steer', self.created[0].calls[-1][0])

    def test_auto_mode_does_not_move_app_session_into_shared_desktop(self):
        desktop = Backend()
        runtime = RuntimeConnection(native=self.pool, desktop=desktop, mode='auto', clock=lambda: 100)
        runtime.start()
        runtime.request('thread/resume', {'threadId': 'a'})
        self.assertEqual([], desktop.calls)
        self.assertTrue(runtime.owns_writer('a'))
        self.assertIs(runtime.history_host_connection('a'), self.created[0])

    def test_acquisition_failure_closes_only_failed_child_and_never_uses_public_resume(self):
        self.pool.request('thread/resume', {'threadId': 'b'})
        create = self.pool._new_writer
        def denied():
            writer = create()
            writer.request = lambda *args, **kwargs: (_ for _ in ()).throw(CodexError('already has an active writer', code='native_rejected', http_status=409))
            return writer
        self.pool._new_writer = denied
        with self.assertRaises(CodexError):
            self.pool.request('thread/resume', {'threadId': 'a'})
        self.assertTrue(self.created[-1].closed)
        self.assertFalse(self.created[0].closed)
        self.assertFalse(self.pool.owns('a'))
        self.assertEqual([], self.metadata.calls)

    def test_service_writer_death_requires_explicit_resume_and_expires_only_its_approval(self):
        runtime = RuntimeConnection(native=self.pool, desktop=Backend(), mode='app_server')
        service = CodexService(connection=runtime)
        service.start()
        for tid in ('a', 'b'):
            service._resume(tid)
            self.created[-1].on_event({'id': 1, 'method': 'item/tool/requestUserInput', 'params': {'threadId': tid}})
        self.assertEqual(1, len(service.interactions.list('a')))
        self.assertEqual(1, len(service.interactions.list('b')))
        self.created[0].close()
        self.assertEqual([], service.interactions.list('a'))
        self.assertEqual(1, len(service.interactions.list('b')))
        with self.assertRaises(CodexError) as caught:
            service._resume('a')
        self.assertEqual('writer_released', caught.exception.code)
        self.assertEqual(2, len(self.created))
        self.assertIn('b', service._joined)

    def test_release_fails_if_actual_lock_remains(self):
        self.pool.request('thread/resume', {'threadId': 'a'})
        self.busy.return_value = True
        with self.assertRaises(CodexError) as caught:
            self.pool.release('a', self.created[0])
        self.assertEqual('writer_still_held', caught.exception.code)
    def test_service_release_uses_only_owned_writer_and_keeps_queue_fence(self):
        outer = RuntimeConnection(native=self.pool, desktop=Backend(), mode='app_server')
        service = CodexService(connection=outer)
        service.start()
        service._resume('a')
        service._resume('b')
        service._active['a'] = 'turn-a'
        service._live[('a', 'turn-a')] = {'id': 'turn-a', 'status': 'inProgress'}
        service._live[('b', 'turn-b')] = {'id': 'turn-b', 'status': 'inProgress'}
        self.created[0].close = lambda: setattr(self.created[0], 'closed', True)
        preview = service.preview_handoff('a', 'release', 'phone')
        self.assertEqual('session-writer', preview['releaseScope'])
        receipt = service.handoff('a', 'release', owner='phone', ticket=preview['ticket'])
        self.assertEqual(['a'], receipt['affectedThreadIds'])
        self.assertTrue(service.observations_for('a')[('ownership', '')]['released'])
        self.assertFalse(service.observations_for('b'))
        self.assertNotIn(('a', 'turn-a'), service._live)
        self.assertIn(('b', 'turn-b'), service._live)
        self.assertEqual('turn/interrupt', self.created[0].calls[-1][0])
        self.assertFalse(self.created[1].closed)

class ProcessCleanupTests(unittest.TestCase):
    def test_stalled_partial_write_closes_transport_instead_of_reusing_line(self):
        from rivo_relay.codex.owned_writer import _ProcessSocket
        process = subprocess.Popen([sys.executable, '-c', 'import sys; sys.stdin.read()'],
                                   start_new_session=True, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        transport = _ProcessSocket(process)
        fd = process.stdin.fileno()
        try:
            with patch('rivo_relay.codex.owned_writer.select.select', side_effect=[([], [fd], []), ([], [], [])]), \
                 patch('rivo_relay.codex.owned_writer.os.write', return_value=1):
                with self.assertRaises(TimeoutError):
                    transport.send('partial request')
            self.assertIsNotNone(process.poll())
            self.assertTrue(transport._finished)
        finally:
            transport.close()
            process.stdout.close()

    def test_children_are_terminated_even_when_parent_already_exited(self):
        from rivo_relay.codex.owned_writer import _ProcessSocket
        child = 'import os,signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); print(os.getpid(),flush=True); time.sleep(60)'
        parent = 'import subprocess,sys; p=subprocess.Popen([sys.executable,"-c",'+repr(child)+'],stdout=subprocess.PIPE,text=True); print(p.stdout.readline().strip(),flush=True)'
        process = subprocess.Popen([sys.executable, '-c', parent], start_new_session=True,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        transport = _ProcessSocket(process)
        try:
            child_pid = int(process.stdout.readline())
            process.wait(timeout=5)
            transport.close()
            state = subprocess.run(['ps', '-p', str(child_pid), '-o', 'stat='], capture_output=True, text=True).stdout.strip()
            self.assertTrue(not state or state.startswith('Z'), state)
            # Repeated close must never signal a later process reusing the PID.
            with patch('os.killpg') as kill:
                transport.close()
                kill.assert_not_called()
        finally:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=5)
            process.stdin.close()
            process.stdout.close()

if __name__ == '__main__':
    unittest.main()
