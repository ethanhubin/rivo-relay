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

    def test_catalogs_are_compressed_losslessly_including_query_strings(self):
        for path in ('/v1/projects', '/v1/threads?projectId=test', '/v1/models',
                     '/v1/todos', '/v1/projects/test/threads'):
            with self.subTest(path=path):
                _, plain = self.request(path=path)
                headers, encoded = self.request('gzip', path)
                self.assertEqual('gzip', headers.get('Content-Encoding'))
                self.assertEqual(plain, gzip.decompress(encoded))
                self.assertLess(len(encoded), len(plain) // 5)

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


class SlowSnapshotTest(unittest.TestCase):
    def test_backpressure_does_not_truncate_history_or_block_other_requests(self):
        import socket
        import time
        from http.server import ThreadingHTTPServer
        payload = {'message': 'large history ' * 500000}
        class SlowHandler(Handler):
            def setup(self):
                super().setup()
                self.connection.settimeout(.05)
                self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
            def do_GET(self):
                self.send_json(200, payload if self.path.endswith('/old') else {'status': 'ok'})
        server = ThreadingHTTPServer(('127.0.0.1', 0), SlowHandler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        old = http.client.HTTPConnection(*server.server_address, timeout=5)
        healthy = http.client.HTTPConnection(*server.server_address, timeout=5)
        try:
            old.request('GET', '/v1/threads/old')
            response = old.getresponse()
            time.sleep(.2)  # Exceed the header timeout while applying backpressure.
            healthy.request('GET', '/health')
            self.assertEqual(json.loads(healthy.getresponse().read()), {'status': 'ok'})
            body = response.read()
            self.assertEqual(len(body), int(response.getheader('Content-Length')))
            self.assertEqual(json.loads(body), payload)
        finally:
            old.close(); healthy.close(); server.shutdown(); server.server_close(); worker.join()
