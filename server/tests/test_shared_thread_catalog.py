import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import Mock, patch

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.thread_catalog import ThreadCatalog
from rivo_relay.read_budget import ReadStopped, read_budget


class SharedThreadCatalogTests(unittest.TestCase):
    def test_query_generation_expiry_and_copy_isolation(self):
        connection = SimpleNamespace(generation='one')
        loader = Mock(return_value=[{'id': 'thread', 'nested': {'name': 'original'}}])
        catalog = ThreadCatalog(connection, loader, ttl=10)
        with patch('rivo_relay.codex.thread_catalog.time.monotonic', return_value=100):
            first = catalog.list({'archived': False})
            first[0]['nested']['name'] = 'changed'
            self.assertEqual('original', catalog.list({'archived': False})[0]['nested']['name'])
            self.assertEqual(1, loader.call_count)
            catalog.list({'archived': True})
            catalog.list({'archived': False, 'projectId': 'p'})
            catalog.list({'archived': False, 'modelProviders': ['provider']})
            self.assertEqual(4, loader.call_count)
            connection.generation = 'two'
            catalog.list({'archived': False})
            self.assertEqual(5, loader.call_count)
        with patch('rivo_relay.codex.thread_catalog.time.monotonic', return_value=111):
            catalog.list({'archived': False})
        self.assertEqual(6, loader.call_count)

    def test_concurrent_same_query_shares_scan_and_cancelled_waiter_leaves_owner(self):
        entered, release = threading.Event(), threading.Event()
        def load(params):
            entered.set()
            self.assertTrue(release.wait(3))
            return [{'id': 'shared'}]
        loader = Mock(side_effect=load)
        catalog = ThreadCatalog(SimpleNamespace(generation='one'), loader)
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(catalog.list, {})
            self.assertTrue(entered.wait(1))
            second = pool.submit(catalog.list, {})
            try:
                with self.assertRaises(ReadStopped), read_budget(seconds=.02):
                    catalog.list({})
                self.assertFalse(first.done())
            finally:
                release.set()
            a, b = first.result(2), second.result(2)
        self.assertEqual(a, b)
        self.assertIsNot(a, b)
        self.assertEqual(1, loader.call_count)
        self.assertEqual({}, catalog._flights)

    def test_inflight_invalidation_is_nonblocking_and_forces_next_scan(self):
        entered, release = threading.Event(), threading.Event()
        def load(params):
            entered.set()
            self.assertTrue(release.wait(2))
            return []
        loader = Mock(side_effect=load)
        catalog = ThreadCatalog(SimpleNamespace(generation='one'), loader)
        with ThreadPoolExecutor(max_workers=1) as pool:
            first = pool.submit(catalog.list, {})
            self.assertTrue(entered.wait(1))
            catalog.invalidate()
            release.set()
            self.assertEqual([], first.result(2))
        catalog.list({})
        self.assertEqual(2, loader.call_count)

    def test_generation_change_and_failed_scan_are_never_cached(self):
        connection = SimpleNamespace(generation='one')
        def change(params):
            connection.generation = 'two'
            return [{'id': 'mixed'}]
        loader = Mock(side_effect=change)
        catalog = ThreadCatalog(connection, loader)
        with self.assertRaises(CodexError):
            catalog.list({})
        self.assertFalse(catalog._cache)
        loader.side_effect = RuntimeError('failed')
        with self.assertRaises(RuntimeError):
            catalog.list({})
        self.assertFalse(catalog._cache)
        self.assertFalse(catalog._flights)
        loader.side_effect = None
        loader.return_value = []
        self.assertEqual([], catalog.list({}))

    def test_cache_is_bounded_without_evicting_inflight_lock(self):
        entered, release = threading.Event(), threading.Event()
        def load(params):
            if params['key'] == 'pending':
                entered.set()
                self.assertTrue(release.wait(3))
            return []
        loader = Mock(side_effect=load)
        catalog = ThreadCatalog(SimpleNamespace(generation='one'), loader, capacity=2)
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(catalog.list, {'key': 'pending'})
            self.assertTrue(entered.wait(1))
            for key in range(4):
                catalog.list({'key': key})
            self.assertEqual(2, len(catalog._cache))
            self.assertEqual(1, len(catalog._flights))
            second = pool.submit(catalog.list, {'key': 'pending'})
            release.set()
            first.result(2); second.result(2)
        self.assertEqual(5, loader.call_count)
        self.assertEqual(2, len(catalog._cache))


if __name__ == '__main__':
    unittest.main()

class DesktopMembershipRefreshTests(unittest.TestCase):
    def test_new_db_row_expires_full_cache_without_dropping_rollout_only_rows(self):
        loader = Mock(return_value=[{'id': 'db-old'}, {'id': 'rollout-only'}])
        catalog = ThreadCatalog(SimpleNamespace(generation='one'), loader)
        params = {'archived': False}
        catalog.list(params)
        self.assertFalse(catalog.metadata_changed(params, [{'id': 'db-old'}]))
        self.assertTrue(catalog.metadata_changed(params, [{'id': 'db-old'}, {'id': 'desktop-new'}]))
        catalog.invalidate()
        loader.return_value = [{'id': 'db-old'}, {'id': 'rollout-only'}, {'id': 'desktop-new'}]
        self.assertEqual(3, len(catalog.list(params)))
        self.assertEqual(2, loader.call_count)
        self.assertFalse(catalog.metadata_changed(params, [{'id': 'desktop-new'}, {'id': 'db-old'}]))
        self.assertTrue(catalog.metadata_changed(params, [{'id': 'desktop-new'}]))

    def test_initial_metadata_observation_fences_scan_already_in_flight(self):
        entered, release = threading.Event(), threading.Event()
        def load(_):
            entered.set(); self.assertTrue(release.wait(3)); return [{'id': 'old'}]
        loader = Mock(side_effect=load)
        catalog = ThreadCatalog(SimpleNamespace(generation='one'), loader)
        params = {'archived': False}
        with ThreadPoolExecutor(max_workers=1) as pool:
            running = pool.submit(catalog.list, params)
            try:
                self.assertTrue(entered.wait(1))
                self.assertTrue(catalog.metadata_changed(params, [{'id': 'old'}, {'id': 'new'}]))
                catalog.invalidate()
            finally:release.set()
            running.result(2)
        loader.side_effect = None
        loader.return_value = [{'id': 'old'}, {'id': 'new'}]
        self.assertEqual(2, len(catalog.list(params)))
        self.assertEqual(2, loader.call_count)

    def test_same_thread_native_status_change_invalidates_old_execution_metadata(self):
        loader = Mock(return_value=[{'id': 'task', 'status': {'type': 'active'}}])
        catalog = ThreadCatalog(SimpleNamespace(generation='one'), loader)
        params = {'archived': False}
        catalog.list(params)
        self.assertFalse(catalog.metadata_changed(params, loader.return_value))
        completed = [{'id': 'task', 'status': {'type': 'idle'}}]
        self.assertTrue(catalog.metadata_changed(params, completed))
        catalog.invalidate(); loader.return_value = completed
        self.assertEqual('idle', catalog.list(params)[0]['status']['type'])
        self.assertFalse(catalog.metadata_changed(params, completed))
