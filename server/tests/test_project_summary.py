import unittest
from unittest.mock import Mock
from rivo_relay.codex.projects import ProjectCatalog, RECENT_PROJECT_ID
from rivo_relay.codex.sessions import CodexService
from test_codex import FakeNative


class ProjectSummaryTests(unittest.TestCase):
    def test_summary_only_reads_native_project_pages_even_during_full_scan(self):
        native = FakeNative()
        native.request = Mock(side_effect=[
            {'data': [{'id': 'a', 'name': 'A', 'roots': [{'path': '/a'}]}], 'nextCursor': 'next'},
            {'data': [{'id': 'b', 'name': 'B', 'roots': []}], 'nextCursor': None}])
        catalog = ProjectCatalog(native)
        catalog.thread_catalog.list = Mock(side_effect=AssertionError('thread scan'))
        catalog.goal_provider = Mock(side_effect=AssertionError('goal scan'))
        catalog.execution_provider = Mock(side_effect=AssertionError('execution scan'))
        catalog._read_lock.acquire()
        try:
            rows = catalog.summary()
        finally:
            catalog._read_lock.release()
        self.assertEqual(['a', 'b', RECENT_PROJECT_ID], [row['id'] for row in rows])
        self.assertEqual('/a', rows[0]['path'])
        self.assertTrue(all(row['statsReady'] is False for row in rows))
        self.assertTrue(all('threadCount' not in row for row in rows))
        self.assertTrue(all(call.args[0] == 'project/list' for call in native.request.call_args_list))
        self.assertEqual('next', native.request.call_args_list[1].args[1]['cursor'])

    def test_service_keeps_full_contract_and_explicit_summary_separate(self):
        service = CodexService(connection=FakeNative())
        service.projects.summary = Mock(return_value=[{'statsReady': False}])
        service.projects.list = Mock(return_value=[{'threadCount': 7}])
        self.assertEqual([{'statsReady': False}], service.list_projects(summary=True))
        service.projects.list.assert_not_called()
        self.assertEqual([{'threadCount': 7}], service.list_projects())
