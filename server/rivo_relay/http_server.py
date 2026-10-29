"""Bounded HTTP worker pool adapted from QiGuang qiguang_http_server.py.

Retains the connection ownership/timeout pattern. Internal gateway transports
and upstream tracking are removed; Relay has one TCP listener.
"""
from __future__ import annotations

import socket
import ssl
import threading
from concurrent.futures import ThreadPoolExecutor
from http.server import HTTPServer


class BoundedHTTPServer(HTTPServer):
    allow_reuse_address = True
    request_queue_size = 32

    def __init__(self, address, handler, *, app, max_workers: int = 16, max_streams: int = 4,
                 tls_context: ssl.SSLContext | None = None):
        if not 0 < max_streams < max_workers:
            raise ValueError("SSE limit must reserve ordinary HTTP workers")
        self.app = app
        self.stream_slots = threading.BoundedSemaphore(max_streams)
        self._slots = threading.BoundedSemaphore(max_workers)
        self._executor = ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="relay-http")
        self._closing = threading.Event()
        self._active: set[socket.socket] = set()
        self._active_lock = threading.Lock()
        self._tls_context = tls_context
        super().__init__(address, handler)

    def process_request(self, request, client_address):
        if not self._slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        with self._active_lock:
            self._active.add(request)
        try:
            self._executor.submit(self._serve, request, client_address)
        except RuntimeError:
            with self._active_lock:
                self._active.discard(request)
            self._slots.release()
            self.shutdown_request(request)

    def _serve(self, request, client_address):
        original = request
        try:
            request.settimeout(10)
            if self._tls_context:
                request = self._tls_context.wrap_socket(request, server_side=True)
                with self._active_lock:
                    self._active.discard(original)
                    self._active.add(request)
            self.finish_request(request, client_address)
        except (OSError, TimeoutError):
            pass
        finally:
            self.shutdown_request(request)
            with self._active_lock:
                self._active.discard(request)
                self._active.discard(original)
            self._slots.release()

    def server_close(self):
        self._closing.set()
        self.app.events.close()
        with self._active_lock:
            active = list(self._active)
        for request in active:
            try:
                request.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            request.close()
        super().server_close()
        self._executor.shutdown(wait=True, cancel_futures=True)
