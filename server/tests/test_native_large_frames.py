import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch
from websockets.sync.server import unix_serve
from rivo_relay.codex.connection import NativeConnection, CodexError


class NativeLargeFrameTests(unittest.TestCase):
    def exchange(self, length, ceiling=None):
        def handle(sock):
            try:
                for raw in sock:
                    req = json.loads(raw)
                    if 'id' not in req:
                        continue
                    value = {'data': 'x' * length} if req['method'] == 'thread/turns/list' else {}
                    sock.send(json.dumps({'id': req['id'], 'result': value}))
            except Exception:
                pass  # A deliberate size-limit rejection closes this fixture.
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / 'native.sock')
            with unix_serve(handle, path) as server:
                worker = threading.Thread(target=server.serve_forever, daemon=True)
                worker.start()
                native = NativeConnection(path)
                try:
                    native.start()
                    return native.request('thread/turns/list', {}, timeout=10)
                finally:
                    native.close()
                    server.shutdown()
                    worker.join(2)

    def test_single_image_turn_above_old_32_mib_ceiling(self):
        size = 33 * 1024 * 1024
        self.assertEqual(size, len(self.exchange(size)['data']))

    def test_finite_ceiling_reports_size_without_replaying(self):
        with patch('rivo_relay.codex.connection.MAX_NATIVE_MESSAGE_BYTES', 4096):
            with self.assertRaises(CodexError) as caught:
                self.exchange(8192)
        self.assertEqual('native_response_too_large', caught.exception.code)
        self.assertTrue(caught.exception.request_sent)
