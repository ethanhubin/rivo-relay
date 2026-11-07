"""Native scan and state catalog can each omit visible Desktop chats."""
import unittest
from rivo_relay.codex.projects import thread_pages


class ThreadCatalogTests(unittest.TestCase):
    def test_merges_paginated_sources_without_losing_filters_or_duplicates(self):
        calls = []

        class Native:
            def request(self, method, params):
                calls.append(params)
                self_test.assertEqual('thread/list', method)
                self_test.assertTrue(params['archived'])
                self_test.assertEqual('project', params['projectId'])
                self_test.assertEqual([], params['modelProviders'])
                if params.get('useStateDbOnly'):
                    if params['cursor'] is None:
                        return {'data': [{'id': 'shared', 'name': 'db'}], 'nextCursor': 'db-next'}
                    return {'data': [{'id': 'agent-created'}], 'nextCursor': None}
                if params['cursor'] is None:
                    return {'data': [{'id': 'shared', 'name': 'native'}], 'nextCursor': 'scan-next'}
                return {'data': [{'id': 'rollout-only'}], 'nextCursor': None}

        self_test = self
        rows = thread_pages(Native(), {'archived': True, 'projectId': 'project', 'modelProviders': []})
        self.assertEqual(['shared', 'rollout-only', 'agent-created'], [row['id'] for row in rows])
        self.assertEqual('native', rows[0]['name'])
        self.assertEqual([None, 'scan-next', None, 'db-next'], [p['cursor'] for p in calls])
