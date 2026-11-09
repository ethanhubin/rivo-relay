import socket
import threading
import unittest
from types import SimpleNamespace
from http.server import BaseHTTPRequestHandler

from rivo_relay.http_server import BoundedHTTPServer


class OverloadTest(unittest.TestCase):
    def test_busy_plain_http_returns_503_and_recovers(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header('Content-Length', '0')
                self.end_headers()
            def log_message(self, *args):
                pass
        server = BoundedHTTPServer(('127.0.0.1', 0), Handler,
            app=SimpleNamespace(events=SimpleNamespace(close=lambda: None)), max_workers=2, max_streams=1)
        thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .01})
        thread.start()
        try:
            server._slots.acquire()
            server._slots.acquire()
            with socket.create_connection(server.server_address, timeout=2) as client:
                client.sendall(b'GET /health HTTP/1.0\r\n\r\n')
                response = client.recv(4096)
                self.assertIn(b'503 Service Unavailable', response)
                self.assertIn(b'Retry-After: 1', response)
            server._slots.release()
            server._slots.release()
            with socket.create_connection(server.server_address, timeout=2) as client:
                client.sendall(b'GET /health HTTP/1.0\r\n\r\n')
                self.assertIn(b'200 OK', client.recv(4096))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)
