"""Navigation uses the native state index, matching Desktop's thread catalog."""
import unittest
from rivo_relay.codex.projects import thread_pages
from rivo_relay.codex.connection import CodexError


class ThreadCatalogTests(unittest.TestCase):
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
