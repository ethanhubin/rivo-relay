import contextlib
import io
import json
import unittest
from rivo_relay.api import Handler
from rivo_relay.observability import route_kind


class RequestLoggingTest(unittest.TestCase):
    def test_categories_never_include_identifiers(self):
        self.assertEqual(route_kind('/v1/threads/secret?token=private'), 'threads')
        self.assertEqual(route_kind('/v1/private/secret'), 'other')
        self.assertEqual(route_kind('/health?token=secret'), 'health')

    def test_request_log_omits_credentials_and_path(self):
        handler = object.__new__(Handler)
        handler.command = 'GET'
        handler.path = '/private/path?token=secret'
        output = io.StringIO()
        with contextlib.redirect_stderr(output):
            handler.log_request(401)
        row = json.loads(output.getvalue())
        self.assertEqual(row['status'], 401)
        self.assertEqual(row['method'], 'GET')
        self.assertNotIn('secret', output.getvalue())
        self.assertNotIn('/private', output.getvalue())
        self.assertIn('time', row)
