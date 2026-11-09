import unittest
from rivo_relay.codex.activity import ActivityRegistry

class ActivityTests(unittest.TestCase):
    def test_shared_snapshot_is_copied_and_event_invalidates(self):
        calls = []
        registry = ActivityRegistry(lambda: calls.append(1) or {'tasks': [{'threadId': 't'}]})
        first = registry.snapshot()
        first['tasks'].clear()
        self.assertEqual(len(registry.snapshot()['tasks']), 1)
        self.assertEqual(len(calls), 1)
        registry.invalidate()
        self.assertEqual(registry.snapshot()['revision'], 1)
        self.assertEqual(len(calls), 2)

    def test_event_during_read_does_not_publish_complete_snapshot(self):
        def load():
            registry.invalidate()
            return {'tasks': []}
        registry = ActivityRegistry(load)
        self.assertFalse(registry.snapshot()['complete'])
        self.assertIsNone(registry.cached)

    def test_failed_read_is_not_an_empty_snapshot(self):
        def load():
            raise RuntimeError('offline')
        registry = ActivityRegistry(load)
        with self.assertRaises(RuntimeError):
            registry.snapshot()
        self.assertIsNone(registry.cached)

    def test_service_rechecks_only_known_active_threads_between_discoveries(self):
        from unittest.mock import Mock
        from rivo_relay.codex.sessions import CodexService
        service = CodexService.__new__(CodexService)
        service.connection = Mock(generation='g')
        service.connection.request.return_value = {'data': [{'id': 'p', 'roots': []}]}
        service.projects = Mock()
        service.projects.thread_catalog.list.return_value = [
            {'id': 'a', 'projectId': 'p', 'status': {'type': 'active'}},
            {'id': 'b', 'projectId': 'p', 'status': {'type': 'idle'}}]
        service.projects.external_worktrees.associations.return_value = {}
        service._activity_discovery = None
        service._external_execution = Mock(return_value={})
        service.read_goal = Mock(return_value={'goal': None})
        service.interactions = Mock()
        service.interactions.list.return_value = []
        first = service._load_activity()
        self.assertEqual([t['threadId'] for t in first['tasks']], ['a'])
        service.connection.request.reset_mock()
        service.connection.request.return_value = {'thread': {'id': 'a', 'projectId': 'p', 'status': {'type': 'idle'}}}
        self.assertEqual(service._load_activity()['tasks'], [])
        service.connection.request.assert_called_once_with('thread/read', {'threadId': 'a', 'includeTurns': False})
        self.assertEqual(service.projects.thread_catalog.list.call_count, 1)

    def test_current_snapshot_is_unavailable_after_invalidation(self):
        registry = ActivityRegistry(lambda: {'tasks': []})
        registry.snapshot()
        self.assertIsNotNone(registry.current())
        registry.invalidate()
        self.assertIsNone(registry.current())

    def test_runtime_restart_invalidates_cached_snapshot(self):
        generation = ['one']
        calls = []
        registry = ActivityRegistry(lambda: calls.append(1) or {'tasks': []}, lambda: generation[0])
        registry.snapshot()
        generation[0] = 'two'
        self.assertIsNone(registry.current())
        registry.snapshot()
        self.assertEqual(len(calls), 2)

    def test_runtime_restart_during_read_cannot_confirm_snapshot(self):
        generation = ['one']
        def load():
            generation[0] = 'two'
            return {'tasks': []}
        registry = ActivityRegistry(load, lambda: generation[0])
        self.assertFalse(registry.snapshot()['complete'])
        self.assertIsNone(registry.current())

    def test_snapshot_entirely_inside_event_is_not_complete(self):
        registry = ActivityRegistry(lambda: {'tasks': []})
        with registry.mutation():
            self.assertFalse(registry.snapshot()['complete'])
            self.assertIsNone(registry.current())
        self.assertTrue(registry.snapshot()['complete'])

    def test_nested_and_failed_events_release_mutation_barrier(self):
        registry = ActivityRegistry(lambda: {'tasks': []})
        with registry.mutation():
            with self.assertRaises(ValueError):
                with registry.mutation():
                    raise ValueError('bad event')
            self.assertFalse(registry.snapshot()['complete'])
        self.assertTrue(registry.snapshot()['complete'])

    def test_concurrent_readers_share_one_load(self):
        import threading
        from concurrent.futures import ThreadPoolExecutor
        started, release = threading.Event(), threading.Event()
        calls = []
        def load():
            calls.append(1)
            started.set()
            self.assertTrue(release.wait(2))
            return {'tasks': []}
        registry = ActivityRegistry(load)
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(registry.snapshot)
            self.assertTrue(started.wait(2))
            second = pool.submit(registry.snapshot)
            release.set()
            self.assertTrue(first.result(2)['complete'])
            self.assertTrue(second.result(2)['complete'])
        self.assertEqual(len(calls), 1)
