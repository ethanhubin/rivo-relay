import contextlib
import io
import json
import unittest
from rivo_relay.api import Handler


class RequestLoggingTest(unittest.TestCase):
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
