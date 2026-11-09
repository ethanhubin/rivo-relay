"""Socket-scoped update tuning and credential-free transfer diagnostics."""
import os
import socket
import struct
import sys
import time

from aiohttp import web

from ..observability import event, request_id


def congestion(sock):
    if sys.platform != 'linux' or sock is None:
        return None
    try:
        return sock.getsockopt(socket.IPPROTO_TCP, socket.TCP_CONGESTION, 16).split(b'\0', 1)[0].decode('ascii')
    except (OSError, AttributeError, UnicodeError):
        return None


def tune(sock):
    """Never change the host default or make an unsupported kernel fail a download.

    Keep the selected algorithm on this connection (including keep-alive reuse).
    Restoring CUBIC after prepare() would affect bytes still queued in the kernel.
    """
    before = congestion(sock)
    mode = os.environ.get('RIVO_UPDATE_CONGESTION', 'bbr')
    result = {'congestion_before': before, 'congestion': before, 'tuning': 'system'}
    if mode == 'system':
        return result
    if mode != 'bbr':
        return result | {'tuning': 'invalid_setting'}
    if before is None:
        return result | {'tuning': 'unsupported'}
    if before == 'bbr':
        return result | {'tuning': 'already_bbr'}
    try:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_CONGESTION, b'bbr')
    except OSError as error:
        return result | {'tuning': 'unavailable', 'tuning_errno': error.errno}
    return result | {'congestion': congestion(sock), 'tuning': 'selected_bbr'}


def tcp_info(sock):
    """Read only the stable Linux tcp_info prefix; other platforms simply omit it."""
    if sys.platform != 'linux' or sock is None:
        return {}
    try:
        raw = sock.getsockopt(socket.IPPROTO_TCP, socket.TCP_INFO, 104)
        if len(raw) < 104:
            return {}
        # Eight u8 fields, followed by native-endian u32 counters in linux/tcp.h.
        fields = struct.unpack_from('=24I', raw, 8)
        return {'rtt_us': fields[15], 'snd_cwnd': fields[18],
                'total_retrans': fields[23]}
    except (OSError, AttributeError):
        return {}


class UpdateFileResponse(web.FileResponse):
    async def prepare(self, request):
        if getattr(self, '_download_logged', False):
            return await web.StreamResponse.prepare(self, request)
        self._download_logged = True
        trace = request_id()
        self.headers['X-Rivo-Download-Id'] = trace
        transport = request.transport
        sock = transport.get_extra_info('socket') if transport else None
        tuning = tune(sock)
        initial = tcp_info(sock)
        started = time.monotonic()
        event('update_download_started', download_id=trace, method=request.method,
              range_requested='Range' in request.headers, **tuning)
        outcome = 'interrupted'
        try:
            writer = await super().prepare(request)
            await self.write_eof()
            outcome = 'sent' if self.status in (200, 206) else 'no_file_body'
            return writer
        finally:
            final = tcp_info(sock)
            retrans = (max(0, final['total_retrans'] - initial['total_retrans'])
                       if 'total_retrans' in initial and 'total_retrans' in final else None)
            # These are server-side send timings, NOT client receipt or SHA verification.
            body_bytes = (self.content_length if request.method == 'GET' and outcome == 'sent'
                          else 0 if outcome != 'interrupted' else None)
            event('update_download_finished', download_id=trace, status=self.status,
                  outcome=outcome, response_bytes=body_bytes,
                  duration_ms=round((time.monotonic() - started) * 1000, 2),
                  retrans_segments=retrans, tcp=final, **tuning)
