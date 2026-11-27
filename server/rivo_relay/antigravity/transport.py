"""Bounded JSON-RPC over the official Antigravity ACP child process."""

from concurrent.futures import Future, TimeoutError
from contextlib import suppress
import json
import os
import select
import subprocess
import threading
import time
from uuid import uuid4


MAX_FRAME = 8 * 1024 * 1024
WRITE_TIMEOUT = 3


class AcpError(RuntimeError):
    def __init__(self, message, *, code='antigravity_unavailable', http_status=503, sent=False):
        super().__init__(message)
        self.code = code
        self.http_status = http_status
        self.definite = not sent


class AcpTransport:
    def __init__(self, executable, cwd, on_update, on_request, on_disconnect):
        self.generation = uuid4().hex
        self._on_update = on_update
        self._on_request = on_request
        self._on_disconnect = on_disconnect
        self._lock = threading.Lock()
        self._writer = threading.Lock()
        self._pending = {}
        self._counter = 0
        self._closed = False
        self._process = subprocess.Popen(
            [str(executable), 'acp'], cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        os.set_blocking(self._process.stdin.fileno(), False)
        self._reader = threading.Thread(target=self._read, name='antigravity-acp', daemon=True)

    def start(self):
        self._reader.start()

    def send_request(self, method, params):
        with self._lock:
            self._counter += 1
            request_id = self._counter
            future = self._pending[request_id] = Future()
        try:
            self._write({'id': request_id, 'method': method, 'params': params})
        except Exception:
            self._forget(request_id)
            raise
        return request_id, future

    def request(self, method, params, *, timeout=10):
        request_id, future = self.send_request(method, params)
        try:
            return future.result(timeout=timeout)
        except TimeoutError:
            self._forget(request_id)
            raise AcpError('Antigravity ACP 请求超时', code='antigravity_timeout', http_status=504, sent=True) from None

    def respond(self, request_id, outcome):
        self._write({'id': request_id, 'result': {'outcome': outcome}})

    def _forget(self, request_id):
        with self._lock:
            return self._pending.pop(request_id, None)

    def _write(self, message):
        body = json.dumps({'jsonrpc': '2.0', **message}, ensure_ascii=False, allow_nan=False).encode() + b'\n'
        if len(body) > MAX_FRAME:
            raise AcpError('Antigravity ACP 请求过大', code='invalid_request', http_status=400)
        deadline = time.monotonic() + WRITE_TIMEOUT
        if not self._writer.acquire(timeout=WRITE_TIMEOUT):
            raise AcpError('Antigravity ACP 当前繁忙', code='antigravity_busy', http_status=409)
        written = 0
        try:
            with self._lock:
                if self._closed:
                    raise AcpError('Antigravity ACP 连接已断开')
                descriptor = self._process.stdin.fileno()
            while written < len(body):
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not select.select([], [descriptor], [], remaining)[1]:
                    raise TimeoutError
                try:
                    written += os.write(descriptor, body[written:])
                except BlockingIOError:
                    continue
        except (OSError, ValueError, TimeoutError) as error:
            # A partial JSON frame makes the stream unusable. Never send another
            # frame on it or retry a request whose delivery is uncertain.
            if written or not isinstance(error, TimeoutError):
                with suppress(ProcessLookupError):
                    self._process.terminate()
            raise AcpError('Antigravity ACP 写入失败', code='antigravity_write_failed', sent=bool(written)) from None
        finally:
            self._writer.release()

    def _read(self):
        try:
            descriptor = self._process.stdout.fileno()
            buffer = bytearray()
            while not self._closed:
                if not select.select([descriptor], [], [], .25)[0]:
                    # A native helper can keep the pipe open after ACP exits.
                    if self._process.poll() is not None:
                        break
                    continue
                chunk = os.read(descriptor, 65536)
                if not chunk:
                    break
                buffer.extend(chunk)
                while (end := buffer.find(b'\n')) >= 0:
                    if end >= MAX_FRAME:
                        raise ValueError('Invalid ACP frame')
                    message = json.loads(buffer[:end])
                    del buffer[:end + 1]
                    if not isinstance(message, dict) or message.get('jsonrpc') != '2.0':
                        raise ValueError('Invalid ACP envelope')
                    self._receive(message)
                if len(buffer) >= MAX_FRAME:
                    raise ValueError('Invalid ACP frame')
        except (OSError, ValueError, AcpError):
            pass
        finally:
            try:
                self._finish()
            finally:
                self._process.stdout.close()

    def _receive(self, message):
        method, request_id = message.get('method'), message.get('id')
        if method == 'session/update' and 'id' not in message:
            self._on_update(self, message.get('params'))
        elif isinstance(method, str):
            if type(request_id) in (str, int):
                if method == 'session/request_permission':
                    self._on_request(self, request_id, message.get('params'))
                else:
                    self._write({'id': request_id, 'error': {'code': -32601, 'message': 'Method not supported'}})
        elif type(request_id) is int and (future := self._forget(request_id)) is not None:
            error = message.get('error')
            if error is None and isinstance(message.get('result'), dict):
                future.set_result(message['result'])
            else:
                native_code = error.get('code') if isinstance(error, dict) else None
                if native_code == -32000:
                    future.set_exception(AcpError('请先在 Antigravity CLI 登录',
                        code='antigravity_auth_required', http_status=401))
                else:
                    future.set_exception(AcpError('Antigravity ACP 未确认请求', code='antigravity_rpc_failed', sent=True))

    def _finish(self):
        with self._lock:
            if self._closed:
                return
            self._closed = True
            pending, self._pending = self._pending, {}
        try:
            self._on_disconnect(self)
        finally:
            for future in pending.values():
                future.set_exception(AcpError('Antigravity ACP 连接已断开', sent=True))
            self._stop_process()

    def _stop_process(self):
        process = self._process
        if process.poll() is None:
            with suppress(ProcessLookupError):
                process.terminate()
            try:
                process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                with suppress(ProcessLookupError):
                    process.kill()
                process.wait(timeout=1)
        with self._writer:
            process.stdin.close()

    def close(self):
        self._finish()
        if self._reader.is_alive() and self._reader is not threading.current_thread():
            self._reader.join(timeout=2)
        elif self._reader.ident is None:
            self._process.stdout.close()
