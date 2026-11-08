"""One Relay-owned stdio app-server per thread, following QiGuang's writer lifecycle.

The selected daemon proves the executable and CODEX_HOME; it is not used to
resume/start threads. Never infer a local home from a forwarded socket.
"""
from __future__ import annotations

from contextlib import contextmanager, nullcontext
import os
import re
from pathlib import Path
import signal
import select
import socket
import subprocess
import sys
import threading
import time
import uuid
import weakref

from .connection import CodexError, NativeConnection
from .writers import WriterProcesses


class _ProcessSocket:
    def __init__(self, process):
        self.process = process
        self._close_lock = threading.Lock()
        self._finished = False
        if os.name != 'nt':
            os.set_blocking(process.stdin.fileno(), False)

    def send(self, raw):
        if os.name == 'nt':
            return self._send_windows(raw)
        data = memoryview((raw + '\n').encode('utf-8'))
        deadline = time.monotonic() + 30
        fd = self.process.stdin.fileno()
        try:
            while data:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not select.select([], [fd], [], remaining)[1]:
                    raise TimeoutError('writer input stalled')
                try:
                    written = os.write(fd, data)
                except BlockingIOError:
                    continue
                if not written:
                    raise BrokenPipeError('writer input closed')
                data = data[written:]
        except Exception:
            # A partial JSON line cannot be reused for the next request.
            self.close()
            raise

    def _send_windows(self, raw):
        # select() accepts sockets only on Windows. A bounded writer thread is
        # cancelled by closing its owned job when a pipe stops consuming input.
        done = threading.Event()
        errors = []
        def write():
            try:
                data = memoryview((raw + '\n').encode('utf-8'))
                while data:
                    size = os.write(self.process.stdin.fileno(), data)
                    if not size:
                        raise BrokenPipeError('writer input closed')
                    data = data[size:]
            except Exception as error:
                errors.append(error)
            finally:
                done.set()
        worker = threading.Thread(target=write, daemon=True, name='rivo-windows-pipe-write')
        worker.start()
        if not done.wait(30):
            self.close()
            worker.join(timeout=3)
            raise TimeoutError('writer input stalled')
        if errors:
            self.close()
            raise errors[0]

    def __iter__(self):
        limit = 32 * 1024 * 1024
        try:
            while True:
                line = self.process.stdout.readline(limit + 1)
                if not line:
                    return
                if len(line) > limit:
                    raise ValueError('native message exceeds limit')
                yield line
        finally:
            self.process.stdout.close()

    def close(self):
        with self._close_lock:
            if self._finished:
                return
            p = self.process
            if os.name == 'nt':
                job = getattr(p, '_rivo_job', None)
                if job is None:
                    raise CodexError('Windows writer 缺少进程组身份，拒绝按 PID 清理')
                job.terminate()
                p.wait(timeout=5)
                p.stdin.close()
                job.close()
                self._finished = True
                return
            try:
                p.stdin.close()
            except (OSError, ValueError):
                pass
            if p.poll() is None:
                try:
                    p.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    pass
            # A parent may exit before its command children. Its private process
            # group remains the cleanup boundary even after the parent exits.
            for sig in (signal.SIGTERM, signal.SIGKILL):
                try:
                    os.killpg(p.pid, sig)
                except ProcessLookupError:
                    break
                deadline = time.monotonic() + 3
                while time.monotonic() < deadline:
                    p.poll()
                    try:
                        os.killpg(p.pid, 0)
                    except ProcessLookupError:
                        break
                    time.sleep(.05)
                else:
                    continue
                break
            try:
                p.wait(timeout=3)
            except subprocess.TimeoutExpired as error:
                raise CodexError('会话 writer 尚未退出', code='writer_still_held', http_status=409) from error
            self._finished = True



class OwnedWriter(NativeConnection):
    def __init__(self, descriptor, on_event):
        super().__init__(codex_home=descriptor['codexHome'], on_event=on_event)
        self.descriptor = descriptor
        self.socket_path = None
        self.process = None
        self._identity = None
        from .host_commands import NativeProcessCommands
        self._commands = NativeProcessCommands(self)

    def start(self):
        with self._start_lock:
            if self.process is not None:
                if not self.status()['connected']:
                    raise CodexError('此会话 writer 已退出，请重新核对后恢复', code='writer_unavailable')
                return self.status()
            if self._closed:
                raise CodexError('会话 writer 已关闭', code='writer_unavailable')
            allowed = ('HOME', 'USER', 'LOGNAME', 'PATH', 'TMPDIR', 'LANG', 'LC_ALL',
                       'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY',
                       'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy')
            env = {key: os.environ[key] for key in allowed if key in os.environ}
            if os.name == 'nt':
                allowed_windows = {'systemroot', 'windir', 'comspec', 'pathext', 'userprofile',
                                   'appdata', 'localappdata', 'temp', 'tmp', 'programdata',
                                   'programfiles', 'programfiles(x86)', 'systemdrive', 'path'}
                env.update({key: value for key, value in os.environ.items() if key.lower() in allowed_windows})
            env['CODEX_HOME'] = str(self.codex_home)
            if os.name == 'nt' and self.descriptor.get('windowsSandboxPackageFamily'):
                env['CODEX_WINDOWS_SANDBOX_PACKAGE_FAMILY'] = self.descriptor['windowsSandboxPackageFamily']
            try:
                spawn = subprocess.Popen
                options = {'start_new_session': True}
                if os.name == 'nt':
                    from .windows_process import spawn_owned
                    spawn, options = spawn_owned, {}
                command = [self.descriptor['executable'], 'app-server', '--stdio']
                if os.name == 'nt' and self.descriptor.get('windowsSandboxPackageFamily'):
                    # Match Desktop's Windows sandbox feature configuration.
                    # This does not guarantee Session 0 runner compatibility.
                    command += ['--enable', 'windows_sandbox_service']
                self.process = spawn(command,
                    cwd=str(self.codex_home), env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL, text=True, encoding='utf-8', errors='replace', **options)
                self._identity = WriterProcesses(self.codex_home).identity(self.process.pid)
                self._socket = _ProcessSocket(self.process)
                self.generation = uuid.uuid4().hex
                reader = threading.Thread(target=self._read, args=(self._socket, self.generation), daemon=True,
                                          name='rivo-owned-writer-reader')
                self._readers.add(reader)
                reader.start()
                self._info = self.request('initialize', {
                    'clientInfo': {'name': 'Codex Desktop', 'title': 'Codex Desktop', 'version': '1'},
                    'capabilities': {'experimentalApi': True}}, timeout=15)
                self.notify('initialized')
                if os.name == 'nt' and self.descriptor.get('windowsSandboxPackageFamily'):
                    self.request('experimentalFeature/enablement/set', {'enablement': {'windows_sandbox_service': True}}, timeout=10)
                self._process = self.request('server/diagnostics', {}, timeout=5).get('process', {})
                if (int(self._process.get('id', 0)) != self.process.pid or not self._identity
                        or Path(self._identity['executable']).resolve() != Path(self.descriptor['executable']).resolve()):
                    raise CodexError('会话 writer 身份无法核对', code='native_host_unverified')
                self._ready = True
                return self.status()
            except Exception:
                if self.process is not None and self._socket is None and self.process.poll() is None:
                    _ProcessSocket(self.process).close()
                super().close()
                raise

    def _receive_event(self, event):
        if not self._commands.event(event):
            super()._receive_event(event)

    def host_command(self, params, *, timeout=None):
        if os.name != 'nt':
            raise CodexError('此节点未启用本机进程接口')
        command = params.get('command', [])
        if command[:1] == ['python3']:
            interpreter = Path(sys.executable).with_name('python.exe')
            params = {**params, 'command': [str(interpreter), *command[1:]]}
        return self._commands.run(params, timeout)

    def request(self, method, params=None, *, timeout=None):
        if os.name == 'nt' and method == 'command/exec' and (params or {}).get('command', [])[:1] == ['python3']:
            # Internal helpers use the interpreter that hosts this local Relay,
            # never a Windows Store alias or a different machine's Python path.
            params = {**params, 'command': [sys.executable, *params['command'][1:]]}
        return super().request(method, params, timeout=timeout)

    def status(self):
        result = super().status()
        result.update(runtime='relay-session-writer', desktopShared=False, desktopVerification='not_shared',
                      transport='stdio', ownedWriter=True)
        if os.name == 'nt':
            result['hostCommands'] = 'native-process'
        return result

    def native_host_info(self):
        if (not self.process or self.process.poll() is not None or not self._identity
                or WriterProcesses(self.codex_home).identity(self.process.pid) != self._identity):
            raise CodexError('会话 writer 身份已变化', code='native_host_unverified', http_status=409)
        return {**self.descriptor, 'pid': self.process.pid, 'processStart': self._identity['start'],
                'generation': self.generation, 'identityMethod': 'relay-owned-child', 'processStartMethod': 'windows-filetime' if os.name == 'nt' else 'ps-lstart',
                'platform': sys.platform, 'hostName': socket.gethostname()}


class StdioMetadata:
    """Explicit local metadata process; no fallback from an existing daemon.

    A fresh metadata child may reconnect; session writers keep their own pins.
    """
    def __init__(self, executable, codex_home):
        if not executable or not Path(executable).is_absolute() or not Path(executable).is_file():
            raise ValueError('stdio mode requires an absolute Codex executable')
        if os.name == 'nt' and not re.fullmatch(r'codex(?:-x86_64-pc-windows-msvc)?\.exe', Path(executable).name, re.IGNORECASE):
            raise ValueError('Windows stdio mode requires codex.exe, not a shell wrapper')
        self.codex_home = Path(codex_home or os.environ.get('CODEX_HOME', '~/.codex')).expanduser().resolve()
        if not self.codex_home.is_dir():
            raise ValueError('CODEX_HOME must already exist; log in to Codex first')
        self.socket_path = None
        self.on_event = lambda _: None
        self._descriptor = {'executable': str(Path(executable).resolve()), 'codexHome': str(self.codex_home)}
        if os.name == 'nt':
            from .windows_process import desktop_package_family
            family = desktop_package_family(executable)
            if family:
                self._descriptor['windowsSandboxPackageFamily'] = family
        self._writer = self._create()
        self._closed = False

    def _create(self):
        return OwnedWriter(self._descriptor, lambda event: self.on_event(event))

    @property
    def generation(self):
        return self._writer.generation

    def start(self):
        if self._closed:
            raise CodexError('原生连接已关闭')
        if self._writer.process is not None and not self._writer.status().get('connected'):
            self._writer.close()
            self._writer = self._create()
        return self._writer.start()

    def status(self):
        result = self._writer.status()
        return {**result, 'runtime': 'relay-stdio-metadata', 'ownedWriter': False}

    def native_host_info(self):
        return self._writer.native_host_info()

    def request(self, *args, **kwargs):
        return self._writer.request(*args, **kwargs)

    def respond(self, *args, **kwargs):
        return self._writer.respond(*args, **kwargs)

    def close(self):
        self._closed = True
        self._writer.close()


class SessionWriters:
    """Public RPC facade with thread-affine writers and non-writing metadata reads."""
    def __init__(self, metadata, on_event=None, factory=OwnedWriter):
        self.metadata, self.on_event, self.factory = metadata, on_event or (lambda _: None), factory
        self.codex_home, self.socket_path = metadata.codex_home, metadata.socket_path
        self.generation = uuid.uuid4().hex
        self._lock = threading.RLock()
        self._thread_locks = {}
        self._writers, self._requests = {}, {}
        self._children = set()
        self._retired = weakref.WeakKeyDictionary()
        self._closed = False
        self._metadata_start_lock = threading.Lock()
        self._reconnect_thread = None
        self.metadata.on_event = lambda event: self._event(self.metadata, event)

    def start(self):
        if self._closed:
            raise CodexError('会话连接池已关闭')
        if not self.metadata.status().get('connected'):
            with self._lock:
                live = any(writer.status().get('connected') for writer in self._writers.values())
                if live and (self._reconnect_thread is None or not self._reconnect_thread.is_alive()):
                    self._reconnect_thread = threading.Thread(target=self._reconnect_metadata, daemon=True,
                                                              name='rivo-metadata-reconnect')
                    self._reconnect_thread.start()
            if not live:
                self._ensure_metadata()
        return self.status()

    def _ensure_metadata(self):
        with self._metadata_start_lock:
            if self._closed:
                raise CodexError('会话连接池已关闭')
            if not self.metadata.status().get('connected'):
                self.metadata.start()

    def _reconnect_metadata(self):
        try:
            self._ensure_metadata()
        except Exception:
            # An existing session keeps its own transport. Never move/replay it.
            pass

    def status(self):
        result = self.metadata.status()
        with self._lock:
            live = any(writer.status().get('connected') for writer in self._writers.values())
        return {**result, 'connected': bool(result.get('connected') or live),
                'metadataConnected': bool(result.get('connected')), 'generation': self.generation, 'sessionIsolation': True}

    def native_host_info(self):
        return {**self.metadata.native_host_info(), 'generation': self.generation}

    def history_host_connection(self, thread_id):
        with self._lock:
            return self._writers.get(thread_id, self.metadata)

    def owns(self, thread_id):
        with self._lock:
            return thread_id in self._writers

    def writer_owner(self, thread_id):
        with self._thread_lock(thread_id):
            if self.owns(thread_id):
                return 'relay'
            from .host_probe import local_native_host_info
            try:
                descriptor = (self.metadata.native_host_info() if isinstance(self.metadata, StdioMetadata)
                              else local_native_host_info(self.metadata))
                return 'external' if WriterProcesses(descriptor['codexHome']).busy(thread_id) else 'none'
            except (CodexError, OSError, subprocess.SubprocessError):
                # Forwarded/temporarily unverifiable hosts remain readable.
                # Unknown ownership cannot authorize local writer creation.
                return 'unknown'

    @contextmanager
    def _thread_lock(self, thread_id):
        with self._lock:
            entry = self._thread_locks.setdefault(thread_id, [threading.RLock(), 0])
            entry[1] += 1
        try:
            with entry[0]:
                yield
        finally:
            with self._lock:
                entry[1] -= 1
                if not entry[1]:
                    self._thread_locks.pop(thread_id, None)

    def _new_writer(self):
        if self._closed:
            raise CodexError('会话连接池已关闭')
        self._ensure_metadata()
        # The local socket proof is mandatory even if a remote probe succeeded.
        from .host_probe import local_native_host_info
        descriptor = (self.metadata.native_host_info() if isinstance(self.metadata, StdioMetadata)
                              else local_native_host_info(self.metadata))
        if Path(descriptor['codexHome']).resolve() != self.codex_home.resolve():
            raise CodexError('所选运行时的 CODEX_HOME 不一致', code='native_host_unverified')
        writer = self.factory(descriptor, lambda event: None)
        writer.on_event = lambda event: self._event(writer, event)
        with self._lock:
            closed = self._closed
            if not closed:
                self._children.add(writer)
        if closed:
            writer.close()
            raise CodexError('会话连接池已关闭')
        try:
            writer.start()
            if self._closed:
                raise CodexError('会话连接池已关闭')
            return writer
        except Exception:
            self._close_writer(writer)
            raise

    def request(self, method, params=None, *, timeout=None):
        if self._closed:
            raise CodexError('会话连接池已关闭')
        params = params or {}
        tid = params.get('threadId') or params.get('conversationId')
        with self._thread_lock(tid) if tid else nullcontext():
            with self._lock:
                if self._closed:
                    raise CodexError('会话连接池已关闭')
                writer = self._writers.get(tid)
            creating = method in {'thread/start', 'thread/fork', 'thread/resume'} and (writer is None or method != 'thread/resume')
            if creating:
                writer = self._new_writer()
            backend = writer or self.metadata
            metadata_reads = {'server/diagnostics', 'thread/list', 'thread/read', 'thread/turns/list',
                'thread/goal/get', 'thread/loaded/list', 'project/list', 'project/read', 'model/list', 'account/read', 'account/rateLimits/read',
                'config/read', 'fs/readDirectory', 'fs/readFile', 'fs/getMetadata'}
            auxiliary = backend is self.metadata and method not in metadata_reads
            if method == 'thread/read' and params.get('includeTurns') is not False:
                auxiliary = backend is self.metadata
            # A query connection may inspect persisted metadata, never acquire a
            # writer implicitly. Unknown thread mutations fail rather than leak.
            if backend is self.metadata and (method.startswith('turn/') or method in {
                    'review/start', 'thread/settings/update', 'thread/goal/set', 'thread/goal/clear', 'thread/unsubscribe'}):
                raise CodexError('会话没有独立 writer，请先恢复操作', code='writer_unavailable', http_status=409)
            if auxiliary:
                backend = self._new_writer()
            elif backend is self.metadata:
                self._ensure_metadata()
            try:
                result = backend.request(method, params, timeout=timeout)
            except Exception:
                if creating:
                    # Failed/uncertain acquisition must not leave an untracked writer.
                    self._close_writer(writer)
                raise
            finally:
                if auxiliary:
                    self._close_writer(backend)
            if creating:
                selected = result.get('thread', {}).get('id') or (tid if method == 'thread/resume' else None)
                with self._lock:
                    invalid = (self._closed or not writer.status().get('connected')
                               or self._retired.get(writer) == writer.generation
                               or not selected or selected in self._writers
                               or (method == 'thread/resume' and selected != tid))
                    if not invalid:
                        self._writers[selected] = writer
                        self._children.add(writer)
                if invalid:
                    self._close_writer(writer)
                    raise CodexError('会话 writer 身份或连接已变化，请核对结果', code='handoff_changed',
                                     http_status=409, request_sent=True)
            return result

    @contextmanager
    def auxiliary_connection(self):
        """One operation, one isolated helper; never borrow a session writer."""
        writer = self._new_writer()
        try:
            yield writer
        finally:
            self._close_writer(writer)

    def release(self, thread_id, expected):
        with self._thread_lock(thread_id):
            with self._lock:
                writer = self._writers.get(thread_id)
            if writer is not expected:
                raise CodexError('会话 writer 已变化，请重新预览', code='handoff_changed', http_status=409)
            self._close_writer(writer)
            if WriterProcesses(writer.codex_home).busy(thread_id):
                raise CodexError('会话写锁仍被占用，释放尚未确认', code='writer_still_held', http_status=409)
            with self._lock:
                self._writers.pop(thread_id, None)

    def respond(self, request_id, result):
        with self._lock:
            entry = self._requests.get(request_id)
            tid = next((tid for tid, writer in self._writers.items()
                        if entry and writer is entry[0]), None)
        if entry is None:
            raise CodexError('原生交互已失效', code='interaction_expired', http_status=409)
        with self._thread_lock(tid or entry[1]):
            with self._lock:
                selected = self._requests.pop(request_id, None)
                if (selected != entry or self._closed or entry[0].generation != entry[1]
                        or self._retired.get(entry[0]) == entry[1]
                        or not entry[0].status().get('connected')):
                    raise CodexError('原生交互已失效', code='interaction_expired', http_status=409)
            try:
                return entry[0].respond(entry[2], result)
            except CodexError as error:
                with self._lock:
                    if (error.definite and not self._closed and entry[0].generation == entry[1]
                            and self._retired.get(entry[0]) != entry[1]
                            and entry[0].status().get('connected')):
                        self._requests[request_id] = entry
                raise

    def notify(self, method, params=None):
        # Initialization is handled by each transport itself. No unclassified
        # fire-and-forget operation may bypass thread-affine request routing.
        raise CodexError('会话通知必须绑定独立 writer', code='writer_unavailable', http_status=409)

    def _event(self, writer, event):
        if event.get('connectionId', writer.generation) != writer.generation:
            return
        forwarded = {**event, 'connectionId': self.generation}
        with self._lock:
            if self._closed and event.get('method') != 'relay/nativeDisconnected':
                return
            if self._retired.get(writer) == writer.generation:
                return
            tids = [tid for tid, value in self._writers.items() if value is writer]
            tid = event.get('params', {}).get('threadId')
            selected = self._writers.get(tid)
            if selected is not None and selected is not writer:
                return
            if writer is not self.metadata and getattr(writer, '_closed', False):
                if event.get('method') != 'relay/nativeDisconnected':
                    return
            if 'id' in event:
                key = f'{writer.generation}:{type(event["id"]).__name__}:{event["id"]}'
                self._requests[key] = (writer, writer.generation, event['id'])
                forwarded['id'] = key
            if event.get('method') in {'serverRequest/resolved', 'serverRequest/completed'}:
                native_id = event.get('params', {}).get('requestId')
                key = f'{writer.generation}:{type(native_id).__name__}:{native_id}'
                self._requests.pop(key, None)
                forwarded['params'] = {**event.get('params', {}), 'requestId': key}
            if event.get('method') == 'relay/nativeDisconnected':
                self._retired[writer] = writer.generation
                self._children.discard(writer)
                expired = [key for key, value in self._requests.items() if value[0] is writer]
                for key in expired:
                    self._requests.pop(key, None)
                for tid in tids:
                    self._writers.pop(tid, None)
                forwarded.update(method='relay/writerDisconnected', params={'threadIds': tids, 'requestIds': expired, 'ownedWriter': writer is not self.metadata})
        self.on_event(forwarded)

    def _close_writer(self, writer):
        writer.close()
        # Explicit retirement also covers failed acquisition, auxiliary RPCs,
        # and close() stopping the dispatcher before its EOF is delivered.
        self._event(writer, {'method': 'relay/nativeDisconnected', 'params': {},
                             'connectionId': writer.generation})

    def close(self):
        with self._lock:
            self._closed = True
            writers = set(self._writers.values()) | self._children
        errors = []
        for writer in writers:
            try:
                self._close_writer(writer)
            except Exception as error:
                errors.append(error)
        try:
            with self._metadata_start_lock:
                self.metadata.close()
        except Exception as error:
            errors.append(error)
        if errors:
            raise errors[0]
