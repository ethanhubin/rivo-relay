import contextlib
import io
import json
import unittest
from rivo_relay.observability import event, request_id


class ObservabilityTests(unittest.TestCase):
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
