"""Exercise snapshot compression over real HTTP without native runtime access."""
import gzip
import http.client
from http.server import HTTPServer
import json
import threading
import unittest

from rivo_relay.api import Handler


class SnapshotHandler(Handler):
    def do_GET(self):
        self.send_json(409 if self.path.endswith('/error') else 200,
                       {'message': '你好，完整工具输出\n' * (1 if self.path.endswith('/small') else 2000)})

    do_POST = do_GET


class SnapshotCompressionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(('127.0.0.1', 0), SnapshotHandler)
        cls.worker = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.worker.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.worker.join()

    def request(self, encoding=None, path='/v1/threads/test', method='GET'):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        try:
            connection.request(method, path, headers={} if encoding is None else {'Accept-Encoding': encoding})
            response = connection.getresponse()
            body = response.read()
            self.assertEqual(int(response.getheader('Content-Length')), len(body))
            return dict(response.getheaders()), body
        finally:
            connection.close()

    def test_lossless_snapshot_and_negotiation(self):
        _, plain = self.request()
        for encoding in ('gzip', 'br, GZip;q=0.5', '*;q=1'):
            with self.subTest(encoding=encoding):
                headers, encoded = self.request(encoding)
                self.assertEqual(headers.get('Content-Encoding'), 'gzip')
                self.assertEqual(headers.get('Vary'), 'Accept-Encoding')
                self.assertEqual(gzip.decompress(encoded), plain)
                self.assertLess(len(encoded), len(plain) // 5)
                self.assertIn('你好', json.loads(gzip.decompress(encoded))['message'])

    def test_explicit_refusal_and_invalid_quality(self):
        for encoding in ('gzip;q=0, *;q=1', 'gzip;q=invalid', 'gzip;q=nan', 'gzip;q=2', 'br', '*;q=0'):
            with self.subTest(encoding=encoding):
                headers, body = self.request(encoding)
                self.assertNotIn('Content-Encoding', headers)
                json.loads(body)

    def test_small_error_and_non_history_responses_unchanged(self):
        for path, method in (('/v1/threads/small', 'GET'), ('/v1/threads/error', 'GET'),
                             ('/v1/node', 'GET'), ('/v1/threads/test', 'POST')):
            with self.subTest(path=path, method=method):
                headers, body = self.request('gzip', path, method)
                self.assertNotIn('Content-Encoding', headers)
                json.loads(body)
