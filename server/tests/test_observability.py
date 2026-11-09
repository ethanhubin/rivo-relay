import contextlib
import io
import json
import unittest
from rivo_relay.observability import error_code, event, request_id, request_trace


class ObservabilityTests(unittest.TestCase):
    def test_request_context_is_correlated_and_reset_without_logging_input(self):
        output = io.StringIO()
        with contextlib.redirect_stderr(output):
            with request_trace('a' * 32):
                event('project_catalog_stage', stage='threads')
            event('outside')
            with request_trace('Bearer private'):
                event('invalid_trace')
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual('a' * 32, rows[0]['request_id'])
        self.assertNotIn('request_id', rows[1])
        self.assertNotIn('private', output.getvalue())
        self.assertEqual('workspace_busy', error_code('workspace_busy'))
        self.assertEqual('other', error_code('private_secret'))
        self.assertEqual('other', error_code({'secret': 'private'}))
    def test_untrusted_identifier_is_not_logged(self):
        for value in ['Bearer secret', 'a' * 33, '\nsecret', None]:
            result = request_id(value)
            self.assertEqual(len(result), 32)
            self.assertTrue(all(c in '0123456789abcdef' for c in result))
        self.assertEqual(request_id('a' * 32), 'a' * 32)

    def test_event_is_timestamped_json(self):
        output = io.StringIO()
        with contextlib.redirect_stderr(output):
            event('finished', duration_ms=12, status=502)
        record = json.loads(output.getvalue())
        self.assertTrue(record['time'].endswith('+00:00'))
        self.assertEqual(record['status'], 502)
