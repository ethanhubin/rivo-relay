import unittest
from unittest.mock import Mock
from rivo_relay.codex.projects import ProjectCatalog, RECENT_PROJECT_ID
from rivo_relay.codex.sessions import CodexService
from relay_test_support.native import FakeNative


class ProjectCatalogTests(unittest.TestCase):
    def test_projects_only_read_native_project_pages(self):
        native = FakeNative()
        native.request = Mock(side_effect=[
            {'data': [{'id': 'a', 'name': 'A', 'roots': [{'path': '/a'}]}], 'nextCursor': 'next'},
            {'data': [{'id': 'b', 'name': 'B', 'roots': []}], 'nextCursor': None}])
        catalog = ProjectCatalog(native)
        catalog.thread_catalog.list = Mock(side_effect=AssertionError('thread scan'))
        rows = catalog.list()
        self.assertEqual(['a', 'b', RECENT_PROJECT_ID], [row['id'] for row in rows])
        self.assertEqual('/a', rows[0]['path'])
        self.assertTrue(all(set(row) == {'id', 'name', 'path', 'roots'} for row in rows))
        self.assertTrue(all(call.args[0] == 'project/list' for call in native.request.call_args_list))
        self.assertEqual('next', native.request.call_args_list[1].args[1]['cursor'])

    def test_service_reads_fresh_metadata_without_execution_or_goal_queries(self):
        native = FakeNative()
        service = CodexService(connection=native)
        native.request = Mock(side_effect=[
            {'data': [{'id': 'p', 'name': 'Before', 'roots': []}]},
            {'data': [{'id': 'p', 'name': 'After', 'roots': []}]}])
        first = service.list_projects()
        first[0]['name'] = 'local mutation'
        second = service.list_projects()
        self.assertEqual('After', second[0]['name'])
        self.assertEqual(['project/list', 'project/list'], [call.args[0] for call in native.request.call_args_list])

    def test_invalidation_still_reaches_shared_thread_catalog(self):
        catalog = ProjectCatalog(FakeNative())
        catalog.thread_catalog.invalidate = Mock()
        catalog.invalidate()
        catalog.thread_catalog.invalidate.assert_called_once_with()


class ProjectInvalidationTests(unittest.TestCase):
    def test_usage_notifications_preserve_catalog_but_lifecycle_invalidates(self):
        service = CodexService(connection=FakeNative())
        service.projects.invalidate = Mock()
        service._event({'method': 'thread/tokenUsage/updated', 'params': {
            'threadId': 't', 'turnId': 'turn', 'tokenUsage': {'total': 10}}})
        service.projects.invalidate.assert_not_called()
        service._event({'method': 'thread/name/updated', 'params': {'threadId': 't', 'name': 'new'}})
        service.projects.invalidate.assert_called_once()
