"""Cooperative limits for HTTP reads; never cancel submitted mutations."""
from contextlib import contextmanager
from contextvars import ContextVar
import queue
import select
import socket
import ssl
import time

from .observability import event

_current = ContextVar('read_budget', default=None)


class ReadStopped(RuntimeError):
    http_status = 504
    code = 'read_deadline'

    def __init__(self, message, *, reason='deadline'):
        super().__init__(message)
        self.reason = reason if reason in ('deadline', 'client_disconnected', 'connection_closed') else 'unknown'


@contextmanager
def read_budget(connection=None, seconds=30):
    token = _current.set((time.monotonic() + seconds, connection))
    try:
        checkpoint()
        yield
        checkpoint()
    finally:
        _current.reset(token)


def checkpoint():
    budget = _current.get()
    if budget is None:
        return
    deadline, connection = budget
    if time.monotonic() >= deadline:
        raise ReadStopped('Read deadline exceeded')
    # TLS sockets cannot MSG_PEEK. They still receive the deadline protection.
    if connection is not None and not isinstance(connection, ssl.SSLSocket):
        try:
            if select.select([connection], [], [], 0)[0] and not connection.recv(1, socket.MSG_PEEK):
                raise ReadStopped('Read client disconnected', reason='client_disconnected')
        except OSError as error:
            raise ReadStopped('Read connection closed', reason='connection_closed') from error


@contextmanager
def read_lock(lock):
    started = time.monotonic()
    acquired = False
    waited = None
    try:
        if _current.get() is None:
            lock.acquire()
            acquired = True
        else:
            while not acquired:
                checkpoint()
                acquired = lock.acquire(timeout=.1)
            checkpoint()
        waited = time.monotonic() - started
        yield
    finally:
        if acquired:
            lock.release()
        waited = time.monotonic() - started if waited is None else waited
        if waited >= 1:
            event('read_lock_wait', duration_ms=round(waited * 1000, 2), acquired=acquired)


def read_reply(reply, timeout):
    if _current.get() is None:
        return reply.get(timeout=timeout)
    deadline = time.monotonic() + timeout
    while True:
        checkpoint()
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise queue.Empty
        try:
            return reply.get(timeout=min(.1, remaining))
        except queue.Empty:
            pass
