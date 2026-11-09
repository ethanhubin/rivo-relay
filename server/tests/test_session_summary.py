import unittest
from unittest.mock import Mock
from test_codex import FakeNative
from rivo_relay.codex.sessions import CodexService
from rivo_relay.codex.projects import RECENT_PROJECT_ID


class SessionSummaryTests(unittest.TestCase):
    def service(self):
        native = FakeNative()
        native.calls = []
        def request(method, params=None, **kwargs):
            params = params or {}; native.calls.append((method, params))
            if method == 'project/read':
                return {'project': {'id': 'project', 'roots': [{'path': '/root'}]}}
            if method == 'project/list':
                return {'data': [{'id': 'project', 'roots': [{'path': '/root'}]}], 'nextCursor': None}
            if method == 'thread/list':
                self.assertTrue(params.get('useStateDbOnly'))
                if params.get('projectId'):
                    return {'data': [{'id': 'native-member', 'cwd': '/elsewhere', 'updatedAt': 1}], 'nextCursor': None}
                return {'data': [
                    {'id': 'legacy', 'cwd': '/root', 'updatedAt': 3},
                    {'id': 'recent', 'cwd': '/unassigned', 'updatedAt': 2}], 'nextCursor': None}
            self.fail('unexpected blocking operation: ' + method)
        native.request = request
        service = CodexService(connection=native)
        service._external_execution = Mock(side_effect=AssertionError('history scan'))
        service.read_goal = Mock(side_effect=AssertionError('goal scan'))
        service.projects.external_worktrees.associations = Mock(side_effect=AssertionError('git scan'))
        return native, service

    def test_metadata_keeps_root_and_native_membership_without_history_or_git(self):
        native, service = self.service()
        result = service.list_sessions('project', summary=True)
        self.assertEqual(['legacy', 'native-member'], [r['id'] for r in result])
        self.assertTrue(all(r['status'] == 'unknown' for r in result))
        self.assertTrue(all(p['archived'] is False for m,p in native.calls if m == 'thread/list'))

    def test_recent_and_archive_filters_survive_provisional_view(self):
        native, service = self.service()
        self.assertEqual(['recent'], [r['id'] for r in service.list_sessions(RECENT_PROJECT_ID, archived=True, summary=True)])
        self.assertTrue(all(p['archived'] is True for m,p in native.calls if m == 'thread/list'))

    def test_runtime_switch_rejects_mixed_summary(self):
        native, service = self.service()
        original = native.request
        def changed(method, params=None, **kwargs):
            result = original(method, params, **kwargs)
            if method == 'project/read':native.generation = 'changed'
            return result
        native.request = changed
        from rivo_relay.codex.connection import CodexError
        with self.assertRaises(CodexError):service.list_sessions('project', summary=True)

    def test_desktop_index_changes_are_seen_without_an_event_or_full_cache_expiry(self):
        native, service = self.service()
        initial = service.list_sessions('project', summary=True)
        self.assertNotIn('desktop-new', {row['id'] for row in initial})
        original = native.request
        state = {'type': 'active'}
        def updated(method, params=None, **kwargs):
            result = original(method, params, **kwargs)
            if method == 'thread/list' and not (params or {}).get('projectId'):
                result['data'].append({'id': 'desktop-new', 'name': 'Desktop task',
                                       'cwd': '/root', 'updatedAt': 4, 'status': dict(state)})
            return result
        native.request = updated
        rows = service.list_sessions('project', summary=True)
        self.assertEqual('desktop-new', rows[0]['id'])
        self.assertEqual('running', rows[0]['status'])
        state['type'] = 'idle'
        self.assertEqual('idle', service.list_sessions('project', summary=True)[0]['status'])
