"""Connect to an existing Codex daemon; never spawn or restart a runtime.

The request/notification separation follows QiGuang's app-server transport.
The Unix WebSocket protocol is verified against Codex 0.159.x. A connection
alone does not prove that Desktop uses the same runtime.
"""
from __future__ import annotations

import json
import os
import queue
import threading
import uuid
from pathlib import Path
from typing import Callable, Optional


class CodexError(RuntimeError):
    def __init__(self, message, *, code="native_unavailable", http_status=503,
                 request_sent=False, native_code=None):
        super().__init__(message)
        self.code = code
        self.http_status = http_status
        self.request_sent = request_sent
        self.definite = not request_sent
        self.native_code = native_code


class NativeConnection:
    def __init__(self, socket_path=None, on_event: Optional[Callable] = None,
                 codex_home=None, timeout=30):
        self.codex_home = Path(codex_home or os.environ.get("CODEX_HOME", "~/.codex")).expanduser()
        self.socket_path = str(socket_path or self.codex_home / "app-server-control/app-server-control.sock")
        self.on_event = on_event or (lambda event: None)
        self.timeout = timeout
        self.generation = uuid.uuid4().hex
        self._lock = threading.RLock()
        self._start_lock = threading.Lock()
        self._pending = {}
        self._counter = 0
        self._socket = None
        self._ready = False
        self._events = queue.Queue(maxsize=1024)
        self._dispatch_stop = threading.Event()
        self._readers = set()
        self._closed = False
        self._info = {}
        self._process = {}
        self._last_error = None
        self._dispatcher = threading.Thread(target=self._dispatch, daemon=True,
                                            name="rivo-native-events")
        self._dispatcher.start()

    def start(self):
        with self._start_lock:
            with self._lock:
                if self._closed:
                    raise CodexError("原生连接已关闭")
                if self._socket is not None:
                    return self.status()
            try:
                from websockets.sync.client import unix_connect
                sock = unix_connect(self.socket_path, uri="ws://codex-app-server/rpc",
                                    open_timeout=5, close_timeout=2,
                                    max_size=32 * 1024 * 1024)
            except Exception as error:
                self._last_error = "无法连接已有 Codex 运行时"
                raise CodexError(self._last_error) from error
            with self._lock:
                self.generation = uuid.uuid4().hex
                self._socket = sock
                self._ready = False
                generation = self.generation
            reader = threading.Thread(target=self._read, args=(sock, generation), daemon=True,
                                      name="rivo-native-reader")
            with self._lock:
                self._readers.add(reader)
            reader.start()
            try:
                self._info = self.request("initialize", {
                    "clientInfo": {"name": "rivo_relay", "title": "Rivo Relay", "version": "0.1.0"},
                    "capabilities": {"experimentalApi": True},
                }, timeout=10)
                self.notify("initialized", {})
                self._process = self.request("server/diagnostics", {}, timeout=5).get("process", {})
                self._last_error = None
                self._ready = True
                return self.status()
            except Exception:
                sock.close()
                raise

    def status(self):
        with self._lock:
            return {"connected": self._socket is not None and self._ready, "transport": "unix-websocket",
                    "runtime": "existing-codex-daemon", "desktopShared": None,
                    "desktopVerification": "requires_desktop_confirmation",
                    "runtimeId": self._process.get("id"), "generation": self.generation,
                    "platform": self._info.get("platformOs"),
                    "version": self._info.get("userAgent"), "error": self._last_error}

    def request(self, method, params=None, *, timeout=None):
        reply = queue.Queue(maxsize=1)
        with self._lock:
            sock = self._socket
            if sock is None:
                raise CodexError("Codex 运行时未连接")
            self._counter += 1
            request_id = self._counter
            self._pending[request_id] = reply
            try:
                sock.send(json.dumps({"id": request_id, "method": method, "params": params or {}}))
            except Exception as error:
                self._pending.pop(request_id, None)
                raise CodexError("Codex 请求发送中断，需核对结果", request_sent=True) from error
        try:
            response = reply.get(timeout=self.timeout if timeout is None else timeout)
        except queue.Empty as error:
            raise CodexError("Codex 请求超时，需核对结果", code="native_timeout", request_sent=True) from error
        finally:
            with self._lock:
                self._pending.pop(request_id, None)
        if isinstance(response, Exception):
            raise response
        error = response.get("error")
        if error:
            message = str(error.get("message") or "Codex 拒绝请求")
            unsupported = error.get("code") == -32601 or (
                error.get("code") == -32600 and
                message.startswith(f"Invalid request: unknown variant `{method}`"))
            missing = "thread not found" in message.lower() or "thread does not exist" in message.lower()
            raise CodexError(message, code="native_method_unsupported" if unsupported else "native_rejected",
                             http_status=501 if unsupported else (404 if missing else 409),
                             request_sent=False, native_code=error.get("code"))
        return response.get("result") or {}

    def notify(self, method, params=None):
        self._send({"method": method, "params": params or {}})

    def respond(self, request_id, result):
        self._send({"id": request_id, "result": result})

    def _send(self, message):
        with self._lock:
            if self._socket is None:
                raise CodexError("Codex 运行时未连接")
            try:
                self._socket.send(json.dumps(message))
            except Exception as error:
                raise CodexError("Codex 响应发送中断，需刷新状态", request_sent=True) from error

    def _read(self, sock, generation):
        try:
            for raw in sock:
                message = json.loads(raw)
                if not isinstance(message, dict):
                    raise ValueError("invalid native envelope")
                if "method" not in message and "id" in message:
                    with self._lock:
                        target = self._pending.get(message["id"])
                    if target:
                        target.put_nowait(message)
                elif "method" in message:
                    self._events.put_nowait({**message, "connectionId": generation})
        except Exception:
            pass
        finally:
            with self._lock:
                self._readers.discard(threading.current_thread())
                if self._socket is not sock:
                    return
                self._socket = None
                self._ready = False
                self._last_error = None if self._closed else "Codex 连接已断开，请重新读取会话"
                for target in self._pending.values():
                    try:
                        target.put_nowait(CodexError("Codex 连接断开，需核对结果", request_sent=True))
                    except queue.Full:
                        pass
            sock.close()
            try:
                self._events.put_nowait({"method": "relay/nativeDisconnected", "params": {},
                                        "connectionId": generation})
            except queue.Full:
                # The dispatcher will observe disconnected state before any new write.
                pass

    def _dispatch(self):
        while not self._dispatch_stop.is_set():
            try:
                event = self._events.get(timeout=.2)
            except queue.Empty:
                continue
            if event is None:
                return
            try:
                self.on_event(event)
            except Exception:
                # A consumer must not terminate the native response reader.
                continue

    def close(self):
        with self._lock:
            self._closed = True
            sock = self._socket
            readers = list(self._readers)
        if sock:
            sock.close()
        self._dispatch_stop.set()
        try:
            self._events.put_nowait(None)
        except queue.Full:
            pass
        for reader in readers:
            if reader is not threading.current_thread():
                reader.join(timeout=3)
        if self._dispatcher is not threading.current_thread():
            self._dispatcher.join(timeout=3)
