"""Explicit node filesystem/Git operations, separate from model turn tools.

Windows stdio nodes use the native host process API. No command/exec fallback or
turn permission overrides: callers are fixed Relay features, never model output.
"""
from contextlib import nullcontext
import base64
import threading
import uuid

from .connection import CodexError


def node_command(connection, params, *, timeout=None):
    status = connection.status() if callable(getattr(connection, 'status', None)) else {}
    if status.get('hostCommands') != 'native-process':
        return connection.request('command/exec', params, timeout=timeout)
    lease = getattr(connection, 'auxiliary_connection', None)
    with lease() if lease else nullcontext(connection) as helper:
        return helper.host_command(params, timeout=timeout)


class NativeProcessCommands:
    def __init__(self, connection):
        self.connection = connection
        self.lock = threading.Lock()
        self.pending = {}

    def event(self, event):
        method, params = event.get('method'), event.get('params') or {}
        if method not in ('process/outputDelta', 'process/exited'):
            return False
        with self.lock:
            item = self.pending.get(params.get('processHandle'))
            if item is None:
                return False
            if method == 'process/exited':
                for stream in ('stdout', 'stderr'):
                    item[stream].extend(str(params.get(stream, '')).encode('utf-8')[:max(0, item['cap'] - len(item[stream]))])
                if params.get('outputDrainTimedOut'):
                    item['error'] = 'Native process output did not finish draining'
                item['code'] = params.get('exitCode')
                item['done'].set()
            else:
                stream = params.get('stream')
                if stream not in ('stdout', 'stderr'):
                    item['error'] = 'Invalid native process output stream'
                    item['done'].set()
                else:
                    try:
                        data = base64.b64decode(params.get('deltaBase64', ''), validate=True)
                        item[stream].extend(data[:max(0, item['cap'] - len(item[stream]))])
                    except (ValueError, TypeError):
                        item['error'] = 'Invalid native process output'
                        item['done'].set()
        return True

    def run(self, params, timeout=None):
        cap = params.get('outputBytesCap', 2 * 1024 * 1024)
        duration = min(timeout or 130, params.get('timeoutMs', 120000) / 1000)
        if type(cap) is not int or not 0 < cap <= 32 * 1024 * 1024 or not 0 < duration <= 300:
            raise ValueError('Invalid host command bounds')
        handle = 'rivo-' + uuid.uuid4().hex
        item = {'done': threading.Event(), 'stdout': bytearray(), 'stderr': bytearray(), 'cap': cap}
        with self.lock:
            self.pending[handle] = item
        completed = False
        try:
            self.connection.request('process/spawn', {'processHandle': handle,
                'command': params['command'], 'cwd': params.get('cwd') or str(self.connection.codex_home),
                'tty': False, 'outputBytesCap': cap, 'timeoutMs': int(duration * 1000)}, timeout=10)
            if not item['done'].wait(duration):
                raise CodexError('节点命令超时，请核对结果', code='native_timeout', request_sent=True)
            if item.get('error') or type(item.get('code')) is not int:
                raise CodexError(item.get('error') or '节点命令未确认退出', code='native_protocol', request_sent=True)
            completed = True
            return {'exitCode': item['code'], **{key: bytes(item[key]).decode('utf-8', errors='replace') for key in ('stdout', 'stderr')}}
        finally:
            if not completed:
                try:
                    self.connection.request('process/kill', {'processHandle': handle}, timeout=5)
                except CodexError:
                    pass
            with self.lock:
                self.pending.pop(handle, None)
