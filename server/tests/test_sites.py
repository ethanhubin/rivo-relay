import unittest
from contextlib import contextmanager
from types import SimpleNamespace
from unittest.mock import Mock
import io
import json
from contextlib import redirect_stderr
from rivo_relay.observability import request_trace
from rivo_relay.codex.sites import list_sites, public_url
from rivo_relay.codex.connection import CodexError

class SitesTest(unittest.TestCase):
    def connection(self, result):
        helper = SimpleNamespace(codex_home='/tmp', request=Mock(side_effect=[{'thread': {'id': 'temporary'}}, result, {}]))
        closed = []
        @contextmanager
        def lease():
            try: yield helper
            finally: closed.append(True)
        return SimpleNamespace(auxiliary_connection=lease), helper, closed

    def test_read_only_projection_and_cleanup(self):
        c, helper, closed = self.connection({'structuredContent': {'items': [{'id': 'a', 'title': 'A', 'current_live_url': 'https://a.example', 'source_repository_credential': {'token':'secret'}, 'access_policy': {'email':'private'}}, {'id':'b', 'current_preview_url':'https://preview.example'}], 'cursor':'next'}})
        result = list_sites(c, 'opaque')
        self.assertEqual(result['items'][0], {'id':'a','title':'A','url':'https://a.example','accessMode':None})
        self.assertIsNone(result['items'][1]['url'])
        self.assertNotIn('secret', str(result))
        self.assertEqual(result['cursor'], 'next')
        self.assertTrue(closed)
        self.assertEqual(helper.request.call_args_list[1].args[1]['tool'], 'sites.list_sites')
        self.assertEqual(helper.request.call_args_list[1].args[1]['arguments'], {'limit':20, 'cursor':'opaque'})
        self.assertTrue(helper.request.call_args_list[0].args[1]['ephemeral'])

    def test_failures_close_lease(self):
        for result in ({'isError':True}, {'structuredContent':{}}, {}):
            c, _, closed = self.connection(result)
            with self.assertRaises(CodexError): list_sites(c)
            self.assertTrue(closed)

    def test_url_and_cursor_validation(self):
        for url in ('javascript:alert(1)','https://u:p@example.com','file:///tmp/a','https://[bad','https://example.com\n'):
            self.assertIsNone(public_url(url))
        c, helper, _ = self.connection({})
        with self.assertRaises(ValueError): list_sites(c, 'x'*16385)
        helper.request.assert_not_called()

    def test_trace_stages_and_no_private_payload(self):
        output = io.StringIO()
        c, _, _ = self.connection({'structuredContent': {'items': [{'id':'private-id','title':'secret-title','current_live_url':'https://private.example'}]}})
        with redirect_stderr(output), request_trace('a' * 32):
            list_sites(c, 'private-cursor')
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertTrue(all(row['request_id'] == 'a' * 32 for row in rows))
        self.assertEqual({'total','connect','thread_start','tool_call','unsubscribe','decode','release'}, {r['stage'] for r in rows if 'stage' in r})
        for secret in ('private-id','secret-title','private.example','private-cursor'):
            self.assertNotIn(secret, output.getvalue())

    def test_timeout_and_cleanup_failures_are_visible_without_messages(self):
        c, helper, closed = self.connection({})
        helper.request.side_effect = [{'thread':{'id':'temp'}}, CodexError('secret-token', code='native_timeout'), RuntimeError('secret-cleanup')]
        output = io.StringIO()
        with redirect_stderr(output), self.assertRaises(CodexError): list_sites(c)
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertTrue(any(r.get('stage') == 'tool_call' and r.get('error_code') == 'native_timeout' for r in rows))
        self.assertTrue(any(r.get('stage') == 'unsubscribe' and r.get('outcome') == 'failed' for r in rows))
        self.assertTrue(closed)
        self.assertNotIn('secret', output.getvalue())
