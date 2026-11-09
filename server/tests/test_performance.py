import threading
from concurrent.futures import ThreadPoolExecutor
import unittest
from unittest.mock import Mock, patch
from rivo_relay.codex.projects import ProjectCatalog
from rivo_relay.codex.sessions import CodexService
from test_codex import FakeNative


class PerformanceTests(unittest.TestCase):
    def test_usage_notifications_preserve_catalog_but_lifecycle_invalidates(self):
        service = CodexService(connection=FakeNative())
        service.projects.invalidate = Mock()
        service._event({'method': 'thread/tokenUsage/updated', 'params': {
            'threadId': 't', 'turnId': 'turn', 'tokenUsage': {'total': 10}}})
        service.projects.invalidate.assert_not_called()
        service._event({'method': 'thread/name/updated', 'params': {'threadId': 't', 'name': 'new'}})
        service.projects.invalidate.assert_called_once()

    def test_project_counters_use_running_probe_while_session_states_remain_complete(self):
        service = CodexService(connection=FakeNative())
        service.native_journal.execution_states = Mock(return_value={})
        threads = [{'id': 'unloaded', 'status': {'type': 'notLoaded'}}]
        service.projects.execution_provider(threads)
        service.native_journal.execution_states.assert_called_once_with(['unloaded'], running_only=True)
        service.native_journal.execution_states.reset_mock()
        service._external_execution(threads)
        service.native_journal.execution_states.assert_called_once_with(['unloaded'])
    def test_project_cache_expiry_invalidation_and_copy_isolation(self):
        native = FakeNative()
        def request(method, params):
            native.calls.append(method)
            return {'data': ([{'id': 'p', 'roots': [{'path': '/p'}]}] if method == 'project/list' else
                [{'id': 't', 'cwd': '/p', 'status': {'type': 'active'}}])}
        native.request = request
        catalog = ProjectCatalog(native)
        with patch('rivo_relay.codex.projects.time.monotonic', return_value=10):
            first = catalog.list()
            self.assertEqual(1, first[0]['outstandingWorkCount'])
            first[0]['threadCount'] = 99
            self.assertEqual(1, catalog.list()[0]['threadCount'])
            self.assertEqual(3, len(native.calls))
            catalog.invalidate()
            catalog.list()
            self.assertEqual(6, len(native.calls))
        with patch('rivo_relay.codex.projects.time.monotonic', return_value=13):
            catalog.list()
            # Project statistics expired, but the complete metadata scan is shared.
            self.assertEqual(7, len(native.calls))

    def test_live_offsets_are_monotonic_and_native_event_is_unchanged(self):
        events = []
        service = CodexService(connection=FakeNative(), on_event=events.append)
        event = {'method': 'item/agentMessage/delta', 'params': {
            'threadId': 't', 'turnId': 'turn', 'itemId': 'i', 'delta': 'hi'}}
        service._event(event)
        service._event(event)
        self.assertEqual([0, 2], [e['params']['relayOffset'] for e in events])
        self.assertNotIn('relayOffset', event['params'])

    def test_concurrent_catalog_reads_share_rpcs_and_inflight_invalidation_is_not_lost(self):
        native = FakeNative()
        catalog = ProjectCatalog(native)
        entered, release = threading.Event(), threading.Event()
        def request(method, params):
            native.calls.append(method)
            if method == 'project/list':
                entered.set()
                self.assertTrue(release.wait(3))
            return {'data': []}
        native.request = request
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(catalog.list)
            self.assertTrue(entered.wait(3))
            second = pool.submit(catalog.list)
            release.set()
            rows = first.result(3)
            self.assertEqual(['__rivo_recent__'], [row['id'] for row in rows])
            self.assertEqual(rows, second.result(3))
        self.assertEqual(3, len(native.calls))
        catalog.invalidate()
        entered.clear()
        release.clear()
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(catalog.list)
            self.assertTrue(entered.wait(3))
            catalog.invalidate()
            release.set()
            pending.result(3)
        catalog.list()
        self.assertEqual(7, len(native.calls))
        self.assertEqual(3, native.calls.count('project/list'))

    def test_catalog_waiter_can_cancel_without_blocking_refresh_owner(self):
        from rivo_relay.read_budget import ReadStopped, read_budget
        native = FakeNative()
        catalog = ProjectCatalog(native)
        entered, release = threading.Event(), threading.Event()
        def request(method, params):
            native.calls.append(method)
            if method == 'project/list':
                entered.set()
                self.assertTrue(release.wait(3))
            return {'data': []}
        native.request = request
        with ThreadPoolExecutor(max_workers=1) as pool:
            owner = pool.submit(catalog.list)
            try:
                self.assertTrue(entered.wait(1))
                with self.assertRaises(ReadStopped), read_budget(seconds=.02):
                    catalog.list()
                self.assertFalse(owner.done())
                self.assertEqual(['project/list'], native.calls)
            finally:
                release.set()
            rows = owner.result(2)
        self.assertEqual(rows, catalog.list())
        self.assertEqual(3, len(native.calls))

    def test_notification_bursts_are_lossless_and_lifecycle_is_a_barrier(self):
        from rivo_relay.codex.notifications import NotificationQueue
        q = NotificationQueue(maxsize=4)
        event = {'method': 'item/agentMessage/delta', 'connectionId': 'one',
                 'params': {'threadId': 't', 'turnId': 'turn', 'itemId': 'i', 'delta': '😀'}}
        for _ in range(1000):
            q.put_nowait(event)
        self.assertEqual(1, q.qsize())
        barrier = {'method': 'item/completed', 'params': {}}
        q.put_nowait(barrier)
        q.put_nowait(event)
        self.assertEqual('😀' * 1000, q.get_nowait()['params']['delta'])
        self.assertEqual(barrier, q.get_nowait())
        self.assertEqual('😀', q.get_nowait()['params']['delta'])
        self.assertEqual('😀', event['params']['delta'])

    def test_notification_bounds_and_identity_never_truncate_or_mix(self):
        import queue
        from rivo_relay.codex.notifications import NotificationQueue
        q = NotificationQueue(maxsize=3, max_delta_chars=3)
        def event(thread='t', generation='one', delta='ab'):
            return {'method': 'item/agentMessage/delta', 'connectionId': generation,
                    'params': {'threadId': thread, 'turnId': 'turn', 'itemId': 'i', 'delta': delta}}
        q.put_nowait(event())
        q.put_nowait(event())
        q.put_nowait(event(thread='other'))
        with self.assertRaises(queue.Full):
            q.put_nowait(event(generation='two'))
        self.assertEqual(['ab', 'ab', 'ab'], [q.get_nowait()['params']['delta'] for _ in range(3)])
        q.put_nowait(event(delta='a'))
        q.put_nowait(event(generation='two', delta='b'))
        self.assertEqual(2, q.qsize())

class DisconnectQueueTests(unittest.TestCase):
    def test_disconnect_survives_saturated_notifications(self):
        from rivo_relay.codex.notifications import NotificationQueue
        q = NotificationQueue(maxsize=1)
        q.put_nowait({'method': 'turn/started'})
        q.put_disconnect({'method': 'relay/nativeDisconnected'})
        self.assertEqual('relay/nativeDisconnected', q.get_nowait()['method'])
        q.task_done()
        self.assertEqual(0, q.unfinished_tasks)
