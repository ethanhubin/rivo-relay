"""Bounded, authenticated-by-filesystem transport to the local DSH plugin."""
from __future__ import annotations
import http.client
import json
import os
from pathlib import Path
import socket
import stat
from urllib.parse import urlencode

from ..codex.connection import CodexError

MAX_RESPONSE = 32 * 1024 * 1024


class UnixHTTPConnection(http.client.HTTPConnection):
    def __init__(self, path, timeout=25):
        super().__init__('localhost', timeout=timeout)
        self.path = path

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self.path)


class BridgeTransport:
    def __init__(self, socket_path):
        self.path = str(Path(socket_path).expanduser())
        if not os.path.isabs(self.path):
            raise ValueError('DSH bridge socket must be absolute')

    def _request(self, method, path, body=None, *, timeout=25):
        conn = UnixHTTPConnection(self.path, timeout=timeout)
        sent = False
        try:
            info = os.stat(self.path, follow_symlinks=False)
            if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
                raise CodexError('DSH socket must be owned by this user and mode 0600', code='dsh_socket_untrusted')
            conn.connect()
            encoded = json.dumps(body).encode() if body is not None else None
            # Any failure after dispatch begins is unknown, including partial writes.
            sent = method == 'POST'
            conn.request(method, path, body=encoded, headers={'Content-Type': 'application/json'})
            response = conn.getresponse()
            raw = response.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                raise CodexError('DSH response exceeds limit', code='dsh_protocol', request_sent=sent)
            value = json.loads(raw)
            if not isinstance(value, dict):
                raise ValueError('Expected object')
            if response.status != 200 or 'error' in value:
                error = value.get('error') or {}
                # New bridges report certainty for this operation's boundary.
                # Unknown/unrecognised declarations never become a safe retry.
                sent = (error.get('certainty') != 'rejected' if 'certainty' in error
                        else error.get('uncertain') is not False)
                raise CodexError(str(error.get('message', 'DSH bridge rejected request')),
                                 code=str(error.get('code', 'dsh_rejected')), http_status=409,
                                 request_sent=sent)
            return value
        except CodexError:
            raise
        except (OSError, ValueError, http.client.HTTPException) as error:
            raise CodexError('DSH bridge unavailable; reconnect before continuing',
                             code='dsh_disconnected', request_sent=sent) from error
        finally:
            conn.close()

    def call(self, method, params=None, *, timeout=25):
        value = self._request('POST', '/rpc', {'method': method, 'params': params or {}}, timeout=timeout)
        if 'result' not in value:
            raise CodexError('DSH result missing', code='dsh_protocol', request_sent=True)
        return value['result']

    def poll(self, generation, cursor):
        return self._request('GET', '/events?' + urlencode({'generation': generation or '', 'cursor': cursor}))
