"""Shutdown/publication races using blocked fake transports and temporary media."""
import os
from pathlib import Path
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.runtime import RuntimeConnection
from rivo_relay.media import MediaService
from test_media_parity import NativeMedia
from test_owned_writer import Backend


class RuntimeLifecycleTests(unittest.TestCase):
    def test_history_routing_does_not_block_update_but_subscriptions_do(self):
        native, desktop = Backend(), Backend()
        runtime = RuntimeConnection(native=native, desktop=desktop, mode='app_server')
        runtime.start()
        try:
            for i in range(200):
                runtime.history_host_connection('history-' + str(i))
            self.assertEqual(set(), runtime.update_threads())
            runtime.request('thread/resume', {'threadId': 'live'})
            self.assertIn('live', runtime.update_threads())
            runtime.request('thread/unsubscribe', {'threadId': 'live'})
            self.assertNotIn('live', runtime.update_threads())
            native.update_threads = lambda: {'owned'}
            self.assertIn('owned', runtime.update_threads())
        finally:
            runtime.close()

    @staticmethod
    def closing_gate(runtime):
        waiting = threading.Event()
        gate = runtime._start_close_locks['desktop']
        class ObservedGate:
            def __enter__(self):
                if runtime._closed:
                    waiting.set()
                return gate.__enter__()
            def __exit__(self, *error):
                return gate.__exit__(*error)
        runtime._start_close_locks['desktop'] = ObservedGate()
        return waiting

    def blocked_runtime(self, *, mode='desktop', fail=False):
        native, desktop = Backend(), Backend()
        desktop.closed = True
        entered, release = threading.Event(), threading.Event()
        calls = []
        def start():
            calls.append('start')
            entered.set()
            if not release.wait(3):
                raise AssertionError('fixture did not release connection establishment')
            # Reproduce a transport that publishes after its initial closed check.
            desktop.closed = False
            if fail:
                raise CodexError('handshake failed after socket publication')
        desktop.start = start
        runtime = RuntimeConnection(native=native, desktop=desktop, mode=mode)
        return runtime, desktop, entered, release, calls

    def test_close_waits_for_late_start_and_does_not_leave_open_transport(self):
        for fail in (False, True):
            with self.subTest(handshake_fails=fail):
                runtime, desktop, entered, release, _ = self.blocked_runtime(fail=fail)
                close_waiting = self.closing_gate(runtime)
                with ThreadPoolExecutor(max_workers=2) as executor:
                    starting = executor.submit(runtime.start)
                    self.assertTrue(entered.wait(1))
                    closing = executor.submit(runtime.close)
                    try:
                        # close must establish the fence before waiting on startup.
                        self.assertTrue(close_waiting.wait(1))
                        with runtime._lock:
                            self.assertTrue(runtime._closed)
                        self.assertFalse(closing.done())
                    finally:
                        release.set()
                    with self.assertRaises(CodexError):
                        starting.result(3)
                    closing.result(3)
                self.assertTrue(desktop.closed)
                self.assertTrue(runtime.native.closed)

    def test_auto_probe_is_fenced_without_blocking_live_native_operations(self):
        runtime, desktop, entered, release, _ = self.blocked_runtime(mode='auto')
        close_waiting = self.closing_gate(runtime)
        runtime._pins['live'] = 'app_server'
        with ThreadPoolExecutor(max_workers=3) as executor:
            probe = executor.submit(runtime._refresh)
            self.assertTrue(entered.wait(1))
            try:
                self.assertTrue(executor.submit(runtime.start).result(1)['connected'])
                executor.submit(runtime.request, 'thread/read', {
                    'threadId': 'live', 'includeTurns': False}).result(1)
                closing = executor.submit(runtime.close)
                self.assertTrue(close_waiting.wait(1))
            finally:
                release.set()
            probe.result(3)
            closing.result(3)
        self.assertTrue(desktop.closed)
        self.assertTrue(runtime.native.closed)

    def test_start_after_close_never_attempts_transport_creation(self):
        runtime, desktop, _, release, calls = self.blocked_runtime()
        runtime.close()
        release.set()
        with self.assertRaises(CodexError):
            runtime.start()
        runtime._refresh()
        self.assertEqual([], calls)
        self.assertTrue(desktop.closed)


class MediaPruneLifecycleTests(unittest.TestCase):
    def media(self, directory):
        media = MediaService(directory, NativeMedia())
        media.close()
        return media

    def test_temporary_deleted_after_stat_does_not_fail_pruning(self):
        with tempfile.TemporaryDirectory() as directory:
            media = self.media(directory)
            temporary = media.cache / 'finished-download.tmp'
            temporary.write_bytes(b'temporary')
            os.utime(temporary, (0, 0))
            inspected, deleted = threading.Event(), threading.Event()
            original_stat = Path.stat
            counts = []
            def racing_stat(path, *args, **kwargs):
                result = original_stat(path, *args, **kwargs)
                if path == temporary:
                    counts.append(1)
                    inspected.set()
                    if not deleted.wait(3):
                        raise AssertionError('fixture did not remove temporary file')
                return result
            with ThreadPoolExecutor(max_workers=1) as executor, patch.object(Path, 'stat', racing_stat):
                pruning = executor.submit(media._prune, force=True)
                try:
                    self.assertTrue(inspected.wait(1))
                    temporary.unlink()
                finally:
                    deleted.set()
                pruning.result(3)
            self.assertEqual(1, len(counts))
            self.assertFalse(temporary.exists())

    def test_enumerated_missing_file_is_skipped_and_pinned_files_survive(self):
        with tempfile.TemporaryDirectory() as directory:
            media = self.media(directory)
            missing = media.cache / 'missing.tmp'
            old = media.cache / 'old.tmp'
            pinned = media.cache / 'pinned.tmp'
            protected = media.cache / 'protected.tmp'
            subdirectory = media.cache / 'directory'
            subdirectory.mkdir()
            for path in (old, pinned, protected):
                path.write_bytes(b'x')
                os.utime(path, (0, 0))
            original_iterdir = Path.iterdir
            def entries(path):
                if path == media.cache:
                    return iter([missing, *original_iterdir(path)])
                return original_iterdir(path)
            with media._pin(pinned), patch.object(Path, 'iterdir', entries):
                media._prune(protected=(protected,), force=True)
            self.assertFalse(old.exists())
            self.assertTrue(pinned.exists())
            self.assertTrue(protected.exists())
            self.assertTrue(subdirectory.is_dir())
            self.assertFalse(media._pins)


if __name__ == '__main__':
    unittest.main()
