"""Small dependency-free support for the same-version, isolated history repair.

This module is also bundled into an on-host command. It never starts a turn,
copies credentials or silently answers an approval request.
"""
from __future__ import annotations

from contextlib import contextmanager
import json
import os
import queue
import sqlite3
import subprocess
import threading

try:
    import fcntl as _fcntl
except ImportError:
    _fcntl = None

LOCK_EX, LOCK_UN, LOCK_NB = 1, 2, 4


def flock(fd, operation):
    if _fcntl is not None:
        translated = sum(value for bit, value in ((LOCK_EX, _fcntl.LOCK_EX),
                         (LOCK_UN, _fcntl.LOCK_UN), (LOCK_NB, _fcntl.LOCK_NB)) if operation & bit)
        _fcntl.flock(fd, translated)
        return
    import msvcrt
    position = os.lseek(fd, 0, os.SEEK_CUR)
    os.lseek(fd, 0, os.SEEK_SET)
    try:
        mode = msvcrt.LK_UNLCK if operation & LOCK_UN else msvcrt.LK_NBLCK if operation & LOCK_NB else msvcrt.LK_LOCK
        try:
            msvcrt.locking(fd, mode, 1)
        except OSError as error:
            if operation & LOCK_NB:
                raise BlockingIOError(error.errno, str(error)) from error
            raise
    finally:
        os.lseek(fd, position, os.SEEK_SET)


def background_process_options():
    if os.name == 'nt':
        return {'creationflags': subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP}
    return {'start_new_session': True}


@contextmanager
def sqlite_connection(database, **kwargs):
    connection = sqlite3.connect(database, **kwargs)
    try:
        with connection:
            yield connection
    finally:
        connection.close()


class AppServerConnection:
    """Bounded stdio transport used only for initialize/resume/history reads."""
    def __init__(self, process, _on_notification):
        self.process = process
        self._lock = threading.RLock()
        self._pending = {}
        self._counter = 0
        self._closed = False
        self._reader = threading.Thread(target=self._read, daemon=True)
        self._reader.start()

    def _write(self, value):
        self.process.stdin.write(json.dumps(value, ensure_ascii=False) + '\n')
        self.process.stdin.flush()

    def request(self, method, params, *, timeout=30):
        if method not in ('initialize', 'thread/resume', 'thread/turns/list'):
            raise RuntimeError('History repair cannot issue execution requests')
        waiter = queue.Queue(maxsize=1)
        with self._lock:
            if self._closed:
                raise RuntimeError('Codex 历史重建进程已退出')
            self._counter += 1
            identity = self._counter
            self._pending[identity] = waiter
            try:
                self._write({'id': identity, 'method': method, 'params': params})
            except Exception:
                self._pending.pop(identity, None)
                raise
        try:
            result = waiter.get(timeout=timeout)
        except queue.Empty as error:
            raise RuntimeError('Codex 官方历史重建请求超时') from error
        finally:
            with self._lock:
                self._pending.pop(identity, None)
        if result.get('error'):
            raise RuntimeError(str(result['error'].get('message', 'Codex 官方历史重建失败'))[:1000])
        return result.get('result') or {}

    def notify(self, method, params=None):
        if method != 'initialized':
            raise RuntimeError('History repair only initializes the isolated reader')
        with self._lock:
            self._write({'method': method, 'params': params or {}})

    def _read(self):
        try:
            while True:
                line = self.process.stdout.readline(32 * 1024 * 1024 + 1)
                if not line:
                    break
                if len(line) > 32 * 1024 * 1024:
                    raise RuntimeError('History repair RPC response exceeds limit')
                value = json.loads(line)
                with self._lock:
                    waiter = self._pending.get(value.get('id'))
                    if waiter and ('result' in value or 'error' in value):
                        waiter.put_nowait(value)
                    elif 'method' in value and 'id' in value:
                        self._write({'id': value['id'], 'error': {'code': -32601,
                            'message': 'History repair does not authorize interactive requests'}})
        except Exception:
            pass
        finally:
            with self._lock:
                self._closed = True
                for waiter in self._pending.values():
                    try:
                        waiter.put_nowait({'error': {'message': 'Codex 官方历史重建连接已关闭'}})
                    except queue.Full:
                        pass

    def close_input(self):
        with self._lock:
            try:
                self.process.stdin.close()
            except OSError:
                pass

    def join(self, timeout=2):
        self._reader.join(timeout)
