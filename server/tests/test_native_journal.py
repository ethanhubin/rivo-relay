"""Only disposable Codex homes are used by journal/host routing regressions."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.history import NativeHistoryAdapter
from rivo_relay.codex.native_convergence import NativeConvergenceResult
from rivo_relay.codex.native_history_host import inspect_journal, dispatch, verify_runtime
from rivo_relay.codex.native_history_support import flock, LOCK_EX, LOCK_NB, LOCK_UN, sqlite_connection
from rivo_relay.codex.native_journal import NativeJournal
from rivo_relay.codex.native_reprojection import NativeHistoryReprojectionError
from rivo_relay.codex.sessions import CodexService
from test_codex import FakeNative


THREAD = '01a04609-7917-7c73-ad6d-cae86ce70da8'
TURN = '019fc374-aaaa-7ac2-a9c9-edeee37051ea'


class JournalHostTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.home = Path(temporary.name)
        self.path = self.home / 'sessions' / f'rollout-{THREAD}.jsonl'
        self.path.parent.mkdir()
        self.records = [
            {'type': 'session_meta', 'ordinal': 0, 'payload': {'id': THREAD, 'history_mode': 'paginated'}},
            {'type': 'event_msg', 'ordinal': 1, 'payload': {'type': 'task_started', 'turn_id': TURN}},
            {'type': 'response_item', 'ordinal': 2, 'payload': {'type': 'message', 'content': 'private body'}},
            {'type': 'event_msg', 'ordinal': 3, 'payload': {'type': 'task_complete', 'turn_id': TURN}},
        ]
        self.write()
        with sqlite_connection(self.home / 'state_5.sqlite') as database:
            database.execute('CREATE TABLE threads(id TEXT PRIMARY KEY, rollout_path TEXT, history_mode TEXT, cwd TEXT)')
            database.execute('INSERT INTO threads VALUES(?,?,?,?)', (THREAD, str(self.path), 'paginated', str(self.home)))

    def write(self):
        self.path.write_text(''.join(json.dumps(record) + '\n' for record in self.records))

    def test_inspection_only_returns_lifecycle_and_never_message_content(self):
        inspected = inspect_journal(self.home, THREAD)
        self.assertEqual({'turnId': TURN, 'status': 'completed'}, inspected['journal'])
        self.assertFalse(inspected['writerBusy'])
        self.assertFalse(inspected['changing'])
        self.assertNotIn('private body', json.dumps(inspected))
        self.assertFalse((self.home / 'thread-writer-locks').exists())

    def test_partial_append_is_converging_and_a_live_writer_is_never_repaired(self):
        with self.path.open('ab') as stream:
            stream.write(b'{"type":"event_msg"')
        lock = self.home / 'thread-writer-locks' / f'{THREAD}.lock'
        lock.parent.mkdir()
        with lock.open('a+b') as writer:
            flock(writer.fileno(), LOCK_EX | LOCK_NB)
            try:
                with patch('rivo_relay.codex.native_history_host.verify_runtime', return_value=self.home), patch(
                        'rivo_relay.codex.native_history_host.NativeHistoryReprojector') as reprojector:
                    result = dispatch({'runtime': {}, 'threadId': THREAD, 'operation': 'repair'})
                self.assertTrue(result['writerBusy'])
                self.assertTrue(result['changing'])
                self.assertFalse(result['reprojected'])
                reprojector.assert_not_called()
            finally:
                flock(writer.fileno(), LOCK_UN)

    def test_unchanged_watermark_cache_avoids_body_reads_and_append_invalidates_it(self):
        known = inspect_journal(self.home, THREAD)
        with patch('rivo_relay.codex.native_history_host._rollout_reader', side_effect=AssertionError('unchanged body read')):
            self.assertEqual(known['journal'], inspect_journal(self.home, THREAD, known)['journal'])
        self.records.append({'type': 'event_msg', 'payload': {'type': 'turn_aborted', 'turn_id': TURN}})
        self.write()
        self.assertEqual('interrupted', inspect_journal(self.home, THREAD, known)['journal']['status'])

    def test_malformed_complete_record_unknown_identity_and_bounds_fail_closed(self):
        original = self.path.read_bytes()
        self.path.write_bytes(original + b'{broken}\n')
        with self.assertRaisesRegex(NativeHistoryReprojectionError, '损坏'):
            inspect_journal(self.home, THREAD)
        self.records[1]['payload']['turn_id'] = 'unknown'
        self.write()
        with self.assertRaisesRegex(NativeHistoryReprojectionError, 'turn ID'):
            inspect_journal(self.home, THREAD)
        self.path.write_bytes(original)
        with patch('rivo_relay.codex.native_history_host.MAX_RECORD_BYTES', 16):
            with self.assertRaisesRegex(NativeHistoryReprojectionError, '大小限制'):
                inspect_journal(self.home, THREAD)

    def test_metadata_path_escape_is_rejected_without_reading_target(self):
        with sqlite_connection(self.home / 'state_5.sqlite') as database:
            database.execute('UPDATE threads SET rollout_path=?', (str(self.home / f'outside-{THREAD}.jsonl'),))
        with self.assertRaisesRegex(NativeHistoryReprojectionError, '原生会话目录'):
            inspect_journal(self.home, THREAD)

    def test_stale_fingerprint_never_starts_repair(self):
        with patch('rivo_relay.codex.native_history_host.verify_runtime', return_value=self.home), patch(
                'rivo_relay.codex.native_history_host.NativeHistoryReprojector') as repair:
            with self.assertRaisesRegex(NativeHistoryReprojectionError, '已更新'):
                dispatch({'runtime': {}, 'threadId': THREAD, 'operation': 'repair', 'fingerprint': [0, 0, 0, 0]})
            repair.assert_not_called()

    def test_helper_bundle_runs_on_verified_disposable_host(self):
        try:
            subprocess.check_output(['ps', '-p', str(os.getpid()), '-o', 'lstart='], text=True)
        except PermissionError:
            self.skipTest('sandbox blocks process inspection; this fixture also runs with ps access')
        compiler = shutil.which('cc')
        if compiler is None:
            self.skipTest('a C compiler is required for the disposable native process fixture')
        executable = self.home / 'codex'
        # Copying an Apple system binary can be killed asynchronously by code
        # signing enforcement. Build our own inert executable instead.
        subprocess.run([compiler, '-x', 'c', '-o', str(executable), '-'],
                       input='#include <unistd.h>\nint main(void) { sleep(60); return 0; }\n',
                       text=True, capture_output=True, check=True, timeout=20)
        process = subprocess.Popen([str(executable)])
        self.addCleanup(lambda: (process.kill(), process.wait()) if process.poll() is None else None)
        try:
            started = subprocess.check_output(['ps', '-p', str(process.pid), '-o', 'lstart='], text=True).strip()
        except PermissionError:
            self.skipTest('sandbox blocks process inspection; this fixture also runs with ps access')
        descriptor = {'pid': process.pid, 'processStart': started, 'processStartMethod': 'ps-lstart',
                      'executable': str(executable), 'codexHome': str(self.home), 'generation': 'fixture'}
        self.assertEqual(self.home.resolve(), verify_runtime(descriptor))

        class SelectedHost:
            generation = 'fixture'
            def native_host_info(self):
                return descriptor
            def request(self, method, params, **kwargs):
                self_method = method
                if self_method == 'thread/turns/list':
                    return {'data': [{'id': TURN, 'status': 'completed', 'items': []}], 'nextCursor': None}
                if self_method != 'command/exec':
                    raise AssertionError(method)
                result = subprocess.run(params['command'], capture_output=True, text=True, timeout=10)
                return {'exitCode': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr}
        result = NativeJournal(SelectedHost(), NativeHistoryAdapter()).ensure(THREAD)
        self.assertEqual('ready', result.state, result.error)
        self.assertFalse((self.home / 'rivo-history-repairs').exists())
        changed = {**descriptor, 'processStart': 'different process'}
        with self.assertRaisesRegex(NativeHistoryReprojectionError, '运行时已更换'):
            verify_runtime(changed)


class JournalAdapterTests(unittest.TestCase):
    def denied_host(self):
        native = FakeNative()
        native.native_host_info = Mock(side_effect=CodexError(
            'sandbox denied ps', code='native_host_unverified', http_status=409))
        return native

    def test_denied_probe_preserves_official_history_pagination_and_external_writer(self):
        native = self.denied_host()
        original = native.request
        def request(method, params, **kwargs):
            native.calls.append((method, params))
            if method == 'thread/turns/list':
                return {'data': [{'id': TURN, 'status': 'completed', 'items': [
                    {'id': 'answer', 'type': 'agentMessage', 'text': 'Original answer'}]}],
                    'nextCursor': None if params.get('cursor') else 'older'}
            if method == 'thread/resume':
                raise CodexError('already has an active writer', code='native_rejected', http_status=409)
            return original(method, params, **kwargs)
        native.request = request
        service = CodexService(connection=native)
        service.native_journal._repair = Mock(side_effect=AssertionError('must not repair'))
        snapshot = service.get_session('thread')
        self.assertTrue(snapshot['readOnly'])
        self.assertEqual('Original answer', snapshot['thread']['turns'][0]['items'][0]['text'])
        self.assertEqual('ready', snapshot['nativeSync']['state'])
        self.assertEqual('unavailable', snapshot['nativeSync']['journalCheck'])
        self.assertEqual('older', snapshot['nextCursor'])
        page = service.history('thread', cursor='older', native_revision=snapshot['nativeRevision'])
        self.assertEqual(TURN, page['turns'][0]['id'])
        service.native_journal._repair.assert_not_called()
        self.assertNotIn('command/exec', [call[0] for call in native.calls])

    def test_denied_probe_does_not_hide_failed_rpc_or_known_watermark(self):
        native = self.denied_host()
        journal = NativeJournal(native, NativeHistoryAdapter())
        native.request = Mock(side_effect=CodexError('history failed', code='native_rejected'))
        self.assertEqual('unavailable', journal.ensure(THREAD).state)
        native.request.reset_mock()
        journal._inspections[(native.generation, THREAD)] = {'journal': {'turnId': TURN}}
        self.assertEqual('unavailable', journal.ensure(THREAD).state)
        native.request.assert_not_called()

    def test_denied_probe_cannot_claim_expected_turn_is_persisted(self):
        journal = NativeJournal(self.denied_host(), NativeHistoryAdapter())
        self.assertEqual('unavailable', journal.ensure(THREAD, expected_turn_id=TURN).state)

    def native(self):
        native = FakeNative()
        native.native_host_info = lambda: {'generation': native.generation}
        return native

    def test_runtime_binding_keeps_probe_and_rpc_on_pinned_backend(self):
        backend = self.native()
        wrapper = Mock()
        wrapper.history_host_connection.return_value = backend
        journal = NativeJournal(wrapper, NativeHistoryAdapter())
        calls = []
        def call(thread_id, operation, **extra):
            calls.append(journal.connection)
            return {'journal': None, 'writerBusy': False}
        journal._call = call
        self.assertTrue(journal.ensure(THREAD).ready)
        wrapper.history_host_connection.assert_called_once_with(THREAD)
        self.assertEqual([backend], calls)
        self.assertEqual('thread/turns/list', backend.calls[-1][0])
        wrapper.request.assert_not_called()

    def test_generation_change_during_helper_invalidates_result(self):
        native = self.native()
        def request(*args, **kwargs):
            native.generation = 'new-native-process'
            return {'exitCode': 0, 'stdout': '{"ok":true,"result":{"journal":null}}'}
        native.request = request
        journal = NativeJournal(native, NativeHistoryAdapter())
        result = journal.ensure(THREAD)
        self.assertEqual('unavailable', result.state)
        self.assertIn('运行时已更换', result.error)

    def test_partial_append_from_active_writer_returns_converging(self):
        native = self.native()
        journal = NativeJournal(native, NativeHistoryAdapter())
        journal._call = Mock(return_value={'journal': None, 'changing': True, 'writerBusy': True})
        result = journal.ensure(THREAD)
        self.assertEqual('converging', result.state)
        self.assertEqual(1, journal._call.call_count)
        self.assertEqual([], native.calls)

    def test_unavailable_history_prevents_resume_new_turn_and_empty_fallback(self):
        native = FakeNative()
        service = CodexService(connection=native)
        service.native_journal.ensure = Mock(return_value=NativeConvergenceResult('unavailable', 0, 1, error='bad identity'))
        for operation in (lambda: service.get_session('thread'), lambda: service.send('thread', 'must not replay')):
            with self.assertRaises(CodexError) as raised:
                operation()
            self.assertEqual(503, raised.exception.http_status)
        self.assertEqual([], native.calls)
        service._materializing['thread'] = ({'id': 'thread'}, time.monotonic() + 30)
        with self.assertRaises(CodexError):
            service.history('thread')

    def test_convergence_runs_before_resume_and_repair_changes_revision(self):
        native = FakeNative()
        service = CodexService(connection=native)
        def check(*args, **kwargs):
            self.assertNotIn('thread/resume', [call[0] for call in native.calls])
            return NativeConvergenceResult('ready', 1, 1, reprojected=True)
        service.native_journal.ensure = check
        before = service._history_revision('thread', [])
        service.native_journal.generation = lambda _thread: 1
        snapshot = service.get_session('thread')
        self.assertNotEqual(before, snapshot['nativeRevision'])
        self.assertEqual('ready', snapshot['nativeSync']['state'])

    def test_repair_or_converging_state_invalidates_older_cursor(self):
        for state in (NativeConvergenceResult('ready', 1, 1, reprojected=True), NativeConvergenceResult('converging', 0, 1)):
            service = CodexService(connection=FakeNative())
            service.native_journal.ensure = Mock(return_value=state)
            with self.assertRaises(CodexError) as raised:
                service.history('thread', cursor='previous-projection')
            self.assertEqual('history_changed', raised.exception.code)

    def test_external_writer_projection_lag_preserves_canonical_message(self):
        native = FakeNative()
        original = native.request
        def request(method, params, **kwargs):
            if method == 'thread/resume':
                raise CodexError('already has an active writer', code='native_rejected', http_status=409)
            if method == 'thread/turns/list':
                return {'data': [{'id': TURN, 'status': 'completed', 'items': [
                    {'id': 'native-answer', 'type': 'agentMessage', 'text': 'Original answer'}]}], 'nextCursor': 'older'}
            return original(method, params, **kwargs)
        native.request = request
        service = CodexService(connection=native)
        service.native_journal.ensure = Mock(return_value=NativeConvergenceResult('converging', 0, 1))
        snapshot = service.get_session('thread')
        self.assertEqual('converging', snapshot['nativeSync']['state'])
        self.assertTrue(snapshot['readOnly'])
        self.assertEqual('Original answer', snapshot['thread']['turns'][0]['items'][0]['text'])
        self.assertIsNone(snapshot['nextCursor'])

    def test_snapshot_runtime_describes_pinned_thread_not_global_preference(self):
        native = FakeNative()
        native.status = lambda: {'connected': True, 'runtime': 'desktop', 'desktopShared': True}
        pinned = FakeNative()
        pinned.status = lambda: {'connected': True, 'runtime': 'existing-codex-daemon', 'desktopShared': False}
        native.history_host_connection = lambda _thread: pinned
        snapshot = CodexService(connection=native).get_session('thread')
        self.assertEqual('existing-codex-daemon', snapshot['nativeRuntime']['runtime'])
        self.assertFalse(snapshot['nativeRuntime']['desktopShared'])


if __name__ == '__main__':
    unittest.main()
