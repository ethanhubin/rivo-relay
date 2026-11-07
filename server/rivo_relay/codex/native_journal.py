"""Relay adapter for QiGuang's independent native journal convergence check."""
from __future__ import annotations

from functools import lru_cache
from dataclasses import replace
from collections import OrderedDict
import json
from pathlib import Path
import threading
import time

from .connection import CodexError
from .native_convergence import (NativeHistoryConverger, NativeJournalWatermark,
                                 NativeProjectionWatermark, NativeConvergenceResult)


_BOOTSTRAP = '''
import json, sys, types
payload=json.loads(sys.argv[1])
package=types.ModuleType('rivo_native'); package.__path__=[]; sys.modules['rivo_native']=package
for name, source in payload['sources']:
 module=types.ModuleType('rivo_native.'+name); module.__package__='rivo_native'
 sys.modules[module.__name__]=module; setattr(package,name,module)
 exec(compile(source,name+'.py','exec'),module.__dict__)
try:
 result=sys.modules['rivo_native.native_history_host'].dispatch(payload['request'])
 print(json.dumps({'ok':True,'result':result},ensure_ascii=False))
except Exception as error:
 print(json.dumps({'ok':False,'error':str(error)[:1000]},ensure_ascii=False))
'''


@lru_cache(maxsize=1)
def _sources():
    directory = Path(__file__).parent
    return [(name, (directory / (name + '.py')).read_text()) for name in
            ('native_history_support', 'native_reprojection', 'native_history_host')]


class NativeJournal:
    """All file access happens through the selected daemon's native command RPC.

    Test doubles without host introspection retain their strict RPC contract.
    Production transports expose native_host_info. If host identity cannot be
    inspected, official RPC remains usable with journalCheck=unavailable;
    filesystem repair remains disabled and known watermarks stay enforced.
    """
    def __init__(self, connection, history_adapter, on_repaired=None):
        self._connection = connection
        self.history_adapter = history_adapter
        self.on_repaired = on_repaired or (lambda _thread_id, _result: None)
        self._local = threading.local()
        self._descriptor_lock = threading.Lock()
        self._hosts = OrderedDict()
        self._inspection_lock = threading.Lock()
        self._inspections = OrderedDict()
        self._converger = NativeHistoryConverger(journal_watermark=self._journal,
            projection_watermark=self._projection, projection_contains=self._contains,
            has_live_writer=self._busy, reproject_projection=self._repair)

    @property
    def connection(self):
        return getattr(self._local, 'connection', self._connection)

    @property
    def supported(self):
        return callable(getattr(self.connection, 'native_host_info', None))

    def generation(self, thread_id):
        return self._converger.generation(thread_id)

    def cached_host(self, thread_id):
        binder = getattr(self._connection, 'history_host_connection', None)
        connection = binder(thread_id) if binder else self._connection
        with self._descriptor_lock:
            descriptor = self._hosts.get(str(connection.generation))
            if descriptor:
                return dict(descriptor)
        return None

    def execution_states(self, thread_ids):
        if not self.supported or not thread_ids:
            return {}
        binder = getattr(self._connection, 'history_host_connection', None)
        groups = {}
        for thread_id in thread_ids:
            connection = binder(thread_id) if binder else self._connection
            groups.setdefault(connection, []).append(thread_id)
        states = {}
        for connection, ids in groups.items():
            try:
                self._local.connection = connection
                states.update(self._call(ids[0], 'activity', threadIds=ids).get('states', {}))
            except CodexError:
                # Unverified hosts cannot turn unknown execution into idle.
                pass
            finally:
                self._local.__dict__.pop('connection', None)
        return states

    def _host(self):
        with self._descriptor_lock:
            generation = str(self.connection.generation)
            if generation not in self._hosts:
                self._hosts[generation] = self.connection.native_host_info()
                while len(self._hosts) > 16:
                    self._hosts.popitem(last=False)
            return self._hosts[generation]

    def _call(self, thread_id, operation, **extra):
        generation = str(self.connection.generation)
        runtime = self._host()
        if generation != str(self.connection.generation) or str(runtime.get('generation')) != generation:
            raise CodexError('原生运行时已更换，请重新读取历史', code='history_changed', http_status=409)
        request = {'runtime': runtime, 'threadId': thread_id, 'operation': operation, **extra}
        if runtime.get('identityMethod') in {'local-socket-and-open-state-database', 'relay-owned-child'}:
            # A sandboxed command cannot inspect ps even after the transport
            # proved this is a local socket. Re-attest locality for every call;
            # cached descriptors alone never authorize local filesystem access.
            from .host_probe import local_native_host_info
            from .native_history_host import dispatch
            from .native_reprojection import NativeHistoryReprojectionError
            current = self.connection.native_host_info() if runtime.get('identityMethod') == 'relay-owned-child' else local_native_host_info(self.connection)
            if current != runtime or generation != str(self.connection.generation):
                with self._descriptor_lock:
                    self._hosts.pop(generation, None)
                raise CodexError('原生运行时已更换，请重新读取历史', code='history_changed', http_status=409)
            try:
                result = dispatch(request)
            except (OSError, NativeHistoryReprojectionError) as error:
                raise CodexError(str(error)[:1000], code='native_history_unavailable') from error
            if generation != str(self.connection.generation):
                raise CodexError('原生运行时已更换，请重新读取历史', code='history_changed', http_status=409)
            return result
        payload = json.dumps({'sources': _sources(), 'request': request}, ensure_ascii=True, separators=(',', ':'))
        response = self.connection.request('command/exec', {'command': ['python3', '-c', _BOOTSTRAP, payload],
            'timeoutMs': 240000 if operation == 'repair' else 30000, 'outputBytesCap': 16384},
            timeout=250 if operation == 'repair' else 35)
        if generation != str(self.connection.generation):
            raise CodexError('原生运行时已更换，请重新读取历史', code='history_changed', http_status=409)
        try:
            result = json.loads(response.get('stdout', ''))
        except (ValueError, TypeError) as error:
            raise CodexError('无法读取实际节点的原生历史检查结果', code='native_history_unavailable') from error
        if response.get('exitCode') != 0 or not isinstance(result, dict) or not result.get('ok') or not isinstance(result.get('result'), dict):
            with self._descriptor_lock:
                self._hosts.pop(generation, None)
            message = result.get('error') if isinstance(result, dict) else None
            raise CodexError(str(message or '原生历史检查失败')[:1000], code='native_history_unavailable')
        return result['result']

    def _inspect(self, thread_id):
        key = (str(self.connection.generation), thread_id)
        with self._inspection_lock:
            known = self._inspections.get(key)
        result = self._call(thread_id, 'inspect', known=known)
        with self._inspection_lock:
            if result.get('available') and not result.get('changing'):
                self._inspections[key] = {field: result.get(field) for field in ('fingerprint', 'journal', 'changing')}
                self._inspections.move_to_end(key)
                while len(self._inspections) > 128:
                    self._inspections.popitem(last=False)
            else:
                self._inspections.pop(key, None)
        self._local.inspection = result
        return result

    def _journal(self, thread_id):
        generation = str(self.connection.generation)
        try:
            inspection = self._inspect(thread_id)
        except CodexError as error:
            # command/exec may forbid process inspection even while official
            # history RPC works. Do not infer a local home or attempt repair.
            # A previously observed watermark must not be silently discarded.
            with self._inspection_lock:
                known = (generation, thread_id) in self._inspections
            if (error.code != 'native_host_unverified' or known
                    or generation != str(self.connection.generation)):
                raise
            self._local.journal_check = 'unavailable'
            return None
        if inspection.get('changing'):
            raise CodexError('Codex rollout 正在更新，请等待原生写入完成', code='history_converging', http_status=409)
        watermark = inspection.get('journal')
        return NativeJournalWatermark(watermark['turnId'], watermark['status']) if watermark else None

    def _projection(self, thread_id):
        page = self.history_adapter.read_page(self.connection, thread_id, limit=1)
        if not page.turns:
            return None
        turn = page.turns[-1]
        return NativeProjectionWatermark(turn['id'], turn['status'])

    def _contains(self, thread_id, turn_id):
        cursor = None
        seen = set()
        for _ in range(100):
            page = self.history_adapter.read_page(self.connection, thread_id, limit=100, cursor=cursor)
            if any(turn['id'] == turn_id for turn in page.turns):
                return True
            cursor = page.next_cursor
            if not cursor or cursor in seen:
                return False
            seen.add(cursor)
        return False

    def _busy(self, thread_id):
        return bool(self._inspect(thread_id).get('writerBusy'))

    def _repair(self, thread_id):
        current = self._inspect(thread_id)
        if current.get('writerBusy') or current.get('changing'):
            return False
        result = self._call(thread_id, 'repair', fingerprint=current.get('fingerprint'),
                            workspaceRoot=current.get('workspaceRoot') or getattr(self._local, 'workspace_root', ''))
        if result.get('reprojected'):
            self.on_repaired(thread_id, result)
        return bool(result.get('reprojected'))

    def ensure(self, thread_id, *, workspace_root='', expected_turn_id=None):
        if not self.supported:
            return NativeConvergenceResult('ready', self.generation(thread_id), int(time.time()),
                                           journal_check='unsupported')
        self._local.workspace_root = workspace_root
        self._local.inspection = None
        self._local.journal_check = 'verified'
        binder = getattr(self._connection, 'history_host_connection', None)
        try:
            # Probe, filesystem helper and persisted RPC must use the exact
            # pinned writer even if auto preference changes during this read.
            self._local.connection = binder(thread_id) if binder else self._connection
            result = self._converger.ensure_consistent(thread_id, expected_turn_id=expected_turn_id)
        except CodexError as error:
            result = NativeConvergenceResult('unavailable', self.generation(thread_id),
                                             int(time.time()), error=str(error))
        finally:
            self._local.__dict__.pop('connection', None)
        inspection = getattr(self._local, 'inspection', None) or {}
        # An incomplete final append is not corruption and must never be repaired.
        if inspection.get('changing') and inspection.get('writerBusy'):
            return NativeConvergenceResult('converging', result.generation, result.checked_at,
                                           error='Codex rollout 正在更新')
        return replace(result, journal_check=self._local.journal_check)
