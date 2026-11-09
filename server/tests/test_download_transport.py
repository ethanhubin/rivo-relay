import asyncio
import errno
import os
from pathlib import Path
import socket
import struct
import tempfile
import unittest
from unittest.mock import AsyncMock, Mock, patch

from aiohttp import ClientSession, web
from aiohttp.test_utils import make_mocked_request

from rivo_relay.gateway import download_transport as transport


class SocketTests(unittest.TestCase):
    def test_bbr_is_socket_scoped_and_reuse_does_not_reset_it(self):
        sock = Mock()
        sock.getsockopt.side_effect = [b'cubic\0', b'bbr\0', b'bbr\0']
        with patch.object(transport.sys, 'platform', 'linux'), \
                patch.object(socket, 'TCP_CONGESTION', 13, create=True), \
                patch.dict(os.environ, {}, clear=True):
            self.assertEqual(transport.tune(sock)['congestion'], 'bbr')
            self.assertEqual(transport.tune(sock)['tuning'], 'already_bbr')
        sock.setsockopt.assert_called_once_with(socket.IPPROTO_TCP, 13, b'bbr')

    def test_unavailable_or_unprivileged_bbr_preserves_download(self):
        for code in (errno.ENOENT, errno.EPERM):
            sock = Mock()
            sock.setsockopt.side_effect = OSError(code, 'must not log exception text')
            with patch.object(transport, 'congestion', return_value='cubic'), \
                    patch.object(socket, 'TCP_CONGESTION', 13, create=True), \
                    patch.dict(os.environ, {}, clear=True):
                result = transport.tune(sock)
            self.assertEqual(result['congestion'], 'cubic')
            self.assertEqual(result['tuning'], 'unavailable')
            self.assertEqual(result['tuning_errno'], code)

    def test_opt_out_and_other_platforms_do_not_change_socket(self):
        sock = Mock()
        with patch.object(transport, 'congestion', return_value='cubic'), \
                patch.dict(os.environ, {'RIVO_UPDATE_CONGESTION': 'system'}):
            self.assertEqual(transport.tune(sock)['tuning'], 'system')
        with patch.object(transport.sys, 'platform', 'darwin'), \
                patch.dict(os.environ, {}, clear=True):
            self.assertEqual(transport.tune(sock)['tuning'], 'unsupported')
        sock.setsockopt.assert_not_called()

    def test_linux_tcp_info_offsets_and_short_or_closed_socket(self):
        fields = [0] * 24
        fields[15], fields[18], fields[23] = 180000, 11, 659
        sock = Mock()
        sock.getsockopt.side_effect = [bytes(8) + struct.pack('=24I', *fields), bytes(12), OSError()]
        with patch.object(transport.sys, 'platform', 'linux'), \
                patch.object(socket, 'TCP_INFO', 11, create=True):
            self.assertEqual(transport.tcp_info(sock), {'rtt_us': 180000, 'snd_cwnd': 11, 'total_retrans': 659})
            self.assertEqual(transport.tcp_info(sock), {})
            self.assertEqual(transport.tcp_info(sock), {})


class DownloadTests(unittest.IsolatedAsyncioTestCase):
    async def test_full_range_head_cache_and_invalid_range_keep_http_semantics(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'fixture.apk'
            raw = bytes(range(256)) * 4096
            path.write_bytes(raw)
            app = web.Application()
            async def download(request):
                return transport.UpdateFileResponse(path)
            app.router.add_get('/file', download)
            runner = web.AppRunner(app)
            await runner.setup()
            site = web.TCPSite(runner, '127.0.0.1', 0)
            await site.start()
            url = 'http://127.0.0.1:' + str(site._server.sockets[0].getsockname()[1]) + '/file'
            try:
                with patch.object(transport, 'event') as log:
                    async with ClientSession() as client:
                        async with client.get(url + '?secret=never-log', headers={'Authorization': 'Bearer never-log'}) as r:
                            self.assertEqual(await r.read(), raw)
                            self.assertEqual(r.status, 200)
                            trace, etag = r.headers['X-Rivo-Download-Id'], r.headers['ETag']
                        async with client.get(url, headers={'Range': 'bytes=123-456'}) as r:
                            self.assertEqual(await r.read(), raw[123:457])
                            self.assertEqual(r.status, 206)
                            self.assertEqual(r.headers['Content-Range'], f'bytes 123-456/{len(raw)}')
                        async with client.head(url) as r:
                            self.assertEqual(await r.read(), b'')
                            self.assertEqual(r.content_length, len(raw))
                        async with client.get(url, headers={'If-None-Match': etag}) as r:
                            self.assertEqual(r.status, 304)
                            self.assertEqual(await r.read(), b'')
                        async with client.get(url, headers={'Range': f'bytes={len(raw)}-'}) as r:
                            self.assertEqual(r.status, 416)
                            await r.read()
                    await asyncio.sleep(0)
                    finished = [c.kwargs for c in log.call_args_list if c.args[0] == 'update_download_finished']
                    self.assertEqual(len(finished), 5)
                    self.assertEqual(finished[0]['download_id'], trace)
                    self.assertEqual([e['response_bytes'] for e in finished], [len(raw), 334, 0, 0, 0])
                    self.assertNotIn('never-log', str(log.call_args_list))
                    self.assertNotIn(str(path), str(log.call_args_list))
            finally:
                await runner.cleanup()

    async def test_cancellation_and_disconnect_are_not_reported_as_success(self):
        for error in (asyncio.CancelledError(), ConnectionResetError()):
            response = transport.UpdateFileResponse('/unused')
            request = make_mocked_request('GET', '/file')
            with patch.object(web.FileResponse, 'prepare', AsyncMock(side_effect=error)), \
                    patch.object(transport, 'tune', return_value={}), \
                    patch.object(transport, 'tcp_info', return_value={}), \
                    patch.object(transport, 'event') as log:
                with self.assertRaises(type(error)):
                    await response.prepare(request)
            self.assertEqual(log.call_args.kwargs['outcome'], 'interrupted')
            self.assertIsNone(log.call_args.kwargs['response_bytes'])

    async def test_final_flush_failure_is_not_reported_as_success(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'fixture.apk'
            path.write_bytes(b'x' * 1024)
            request = make_mocked_request('GET', '/file')
            response = transport.UpdateFileResponse(path)
            with patch.object(web.FileResponse, 'prepare', AsyncMock(return_value=request.writer)), \
                    patch.object(transport, 'tune', return_value={}), \
                    patch.object(transport, 'tcp_info', return_value={}), \
                    patch.object(response, 'write_eof', AsyncMock(side_effect=ConnectionResetError)), \
                    patch.object(transport, 'event') as log:
                with self.assertRaises(ConnectionResetError):
                    await response.prepare(request)
            self.assertEqual(log.call_args.kwargs['outcome'], 'interrupted')
