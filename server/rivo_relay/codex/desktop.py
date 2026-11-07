"""QiGuang protocol-1 transport for Desktop's initialized AppServerConnection."""
from __future__ import annotations

import json
import os
import socket
import stat
import threading
import uuid
from pathlib import Path

from .connection import CodexError, NativeConnection

PROTOCOL_VERSION = 1
MAX_MESSAGE_BYTES = 16 * 1024 * 1024


def default_bridge_directory():
    configured = (os.environ.get('RIVO_CODEX_DESKTOP_BRIDGE_DIR') or
                  os.environ.get('QIGUANG_CODEX_DESKTOP_BRIDGE_DIR'))
    return Path(configured).expanduser() if configured else (
        Path.home() / 'Library/Application Support/QiGuang/codex-desktop-bridge')


class _JsonLines:
    def __init__(self, sock, owner):
        self.sock, self.owner = sock, owner

    def send(self, raw):
        data = (raw + '\n').encode('utf-8')
        if len(data) > MAX_MESSAGE_BYTES:
            raise CodexError('Desktop 桥接消息过大', code='native_frame_too_large', http_status=413)
        self.sock.sendall(data)

    def __iter__(self):
        buffer = bytearray()
        while chunk := self.sock.recv(65536):
            buffer.extend(chunk)
            while b'\n' in buffer:
                raw, _, buffer = buffer.partition(b'\n')
                if len(raw) > MAX_MESSAGE_BYTES:
                    raise ValueError('desktop frame too large')
                if not raw.strip():
                    continue
                envelope = json.loads(raw)
                if not isinstance(envelope, dict):
                    raise ValueError('invalid desktop envelope')
                if envelope.get('method') == 'app-server/message':
                    envelope = envelope.get('params', {}).get('message')
                    if not isinstance(envelope, dict):
                        continue
                    if envelope.get('method') == 'qiguang/desktopConnectionState':
                        self.owner._set_state(envelope.get('params', {}).get('state'))
                    yield json.dumps(envelope)
                elif 'id' in envelope and ('result' in envelope or 'error' in envelope):
                    yield json.dumps(envelope)
            if len(buffer) > MAX_MESSAGE_BYTES:
                raise ValueError('desktop frame too large')

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()


class DesktopConnection(NativeConnection):
    """Own only the bridge socket; Desktop owns initialization and native lifetime."""
    _error_request_sent = True

    def __init__(self, bridge_directory=None, on_event=None, codex_home=None, timeout=30):
        self.bridge_directory = Path(bridge_directory or default_bridge_directory()).expanduser()
        super().__init__(self.bridge_directory / 'bridge.sock', on_event, codex_home, timeout)
        self._hello = {}
        self._state = 'unknown'

    def start(self):
        with self._start_lock:
            with self._lock:
                if self._closed:
                    raise CodexError('Desktop 桥接已关闭')
                if self._socket is not None:
                    return self.status()
            candidate = None
            try:
                token_path = self.bridge_directory / 'token'
                token_stat = token_path.lstat()
                # Accept QiGuang's existing owner-only endpoint, never a public token.
                if (not stat.S_ISREG(token_stat.st_mode) or token_stat.st_uid != os.getuid()
                        or token_stat.st_mode & 0o077):
                    raise ValueError('bridge token permissions')
                token = token_path.read_text(encoding='utf-8').strip()
                if len(token) < 32:
                    raise ValueError('bridge token length')
                candidate = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                candidate.settimeout(2)
                candidate.connect(self.socket_path)
                candidate.settimeout(None)
                sock = _JsonLines(candidate, self)
                with self._lock:
                    self._socket, self.generation = sock, uuid.uuid4().hex
                    self._ready = False
                    generation = self.generation
                reader = threading.Thread(target=self._read, args=(sock, generation),
                                          daemon=True, name='rivo-desktop-reader')
                with self._lock:
                    self._readers.add(reader)
                reader.start()
                hello = super().request('hello', {'token': token, 'protocolVersion': PROTOCOL_VERSION}, timeout=3)
                if hello.get('protocolVersion') != PROTOCOL_VERSION:
                    raise CodexError('Desktop 桥接协议版本不兼容', code='desktop_protocol_mismatch')
                self._hello = hello
                self._set_state(hello.get('connectionState'))
                self._last_error = None
                return self.status()
            except (OSError, ValueError, CodexError) as error:
                if candidate is not None:
                    candidate.close()
                with self._lock:
                    self._socket = None
                    self._ready = False
                self._last_error = 'Desktop 桥接不可用；请确认宿主桥接已安装并运行'
                raise CodexError(self._last_error, code='desktop_unavailable') from error

    def _set_state(self, state):
        if isinstance(state, dict):
            state = state.get('state') or state.get('type') or state.get('status')
        with self._lock:
            self._state = str(state or 'unknown').lower()
            self._ready = self._state in {'ready', 'connected'}

    def request(self, method, params=None, *, timeout=None):
        if not self.status()['connected']:
            raise CodexError('Desktop 原生连接尚未就绪', code='desktop_not_ready')
        return super().request('rpc', {'method': method, 'params': params or {}}, timeout=timeout)

    def respond(self, request_id, result):
        if not self.status()['connected']:
            raise CodexError('Desktop 原生连接尚未就绪', code='desktop_not_ready')
        return super().request('server/respond', {'requestId': request_id, 'result': result}, timeout=10)

    def notify(self, method, params=None):
        return self.request(method, params)

    def status(self):
        with self._lock:
            ready = self._socket is not None and self._ready and not self._closed
            return {'connected': ready, 'transport': 'desktop-json-lines',
                    'runtime': 'desktop', 'desktopShared': ready,
                    'desktopVerification': 'authenticated_desktop_bridge' if ready else 'bridge_unavailable',
                    'runtimeId': self._hello.get('runtimeId'), 'generation': self.generation,
                    'platform': self._hello.get('platform'), 'version': self._hello.get('appVersion'),
                    'error': self._last_error, 'bridge': {
                        'available': self._socket is not None, 'writerReady': ready,
                        'connectionState': self._state, 'protocolVersion': self._hello.get('protocolVersion'),
                        'directory': str(self.bridge_directory),
                        'provenance': self._hello.get('provenance', 'qiguang-protocol-1'),
                        'capabilities': self._hello.get('capabilities', {}),
                    }}
