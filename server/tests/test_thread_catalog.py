"""Navigation uses the native state index, matching Desktop's thread catalog."""
import unittest
from rivo_relay.codex.projects import thread_pages
from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.sessions import CodexService
from rivo_relay.codex.projects import RECENT_PROJECT_ID
from relay_test_support.native import FakeNative


class ThreadCatalogTests(unittest.TestCase):
    def test_moved_unassigned_worktree_uses_current_project_not_initial_rollout_cwd(self):
        projects = [{'id': 'merge', 'roots': [{'path': '/merge'}]},
                    {'id': 'release', 'roots': [{'path': '/release'}]}]
        class Native(FakeNative):
            def request(self, method, params, **kwargs):
                if method == 'project/list':
                    return {'data': projects, 'nextCursor': None}
                if method == 'project/read':
                    return {'project': next(p for p in projects if p['id'] == params['projectId'])}
                if method == 'thread/list':
                    # No explicit membership: Desktop moved cwd after creation.
                    rows = [] if params.get('projectId') else [{'id': 'thread', 'projectId': None,
                        'cwd': '/worktree/merge' if params.get('useStateDbOnly') else '/release',
                        'status': {'type': 'idle'}}]
                    return {'data': rows, 'nextCursor': None}
                return super().request(method, params, **kwargs)
        service = CodexService(connection=Native())
        service.projects.external_worktrees.associations = lambda threads, selected: {
            t['id']: {'projectId': 'merge', 'path': t['cwd']}
            for t in threads if t.get('cwd') == '/worktree/merge' and any(p['id'] == 'merge' for p in selected)}
        self.assertEqual(['thread'], [t['id'] for t in service.list_sessions('merge')])
        self.assertEqual([], service.list_sessions('release'))
        self.assertEqual([], service.list_sessions(RECENT_PROJECT_ID))

    def test_only_reads_index_and_preserves_filters_and_pagination(self):
        calls = []
        owner = self

        class Native:
            def request(self, method, params):
                calls.append(params)
                owner.assertEqual('thread/list', method)
                owner.assertTrue(params['archived'])
                owner.assertEqual('project', params['projectId'])
                owner.assertEqual([], params['modelProviders'])
                owner.assertTrue(params['useStateDbOnly'], 'Navigation must never scan rollout files')
                if params['cursor'] is None:
                    return {'data': [{'id': 'indexed'}], 'nextCursor': 'db-next'}
                return {'data': [{'id': 'agent-created'}], 'nextCursor': None}

        rows = thread_pages(Native(), {'archived': True, 'projectId': 'project', 'modelProviders': []})
        self.assertEqual(['indexed', 'agent-created'], [row['id'] for row in rows])
        self.assertEqual([None, 'db-next'], [p['cursor'] for p in calls])

    def test_index_failure_does_not_silently_fall_back_to_disk_scan(self):
        class Native:
            def request(self, method, params):
                if not params['useStateDbOnly']:
                    raise AssertionError('disk scan fallback')
                raise CodexError('index unavailable')
        with self.assertRaises(CodexError):
            thread_pages(Native(), {})

    def test_repeated_index_cursor_is_rejected(self):
        class Native:
            def request(self, method, params):
                return {'data': [], 'nextCursor': 'same'}
        with self.assertRaises(CodexError):
            thread_pages(Native(), {})
