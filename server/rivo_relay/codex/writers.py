"""Explicit, previewed termination of native thread-lock holders (local POSIX).

No PID is accepted from the HTTP client. A short-lived preview is bound to an
identity and revalidated before signalling. Unrelated processes are never killed.
"""
from __future__ import annotations

from . import native_history_support as fcntl
import os
from pathlib import Path
import re
import secrets
import shlex
import signal
import socket
import subprocess
import sys
import threading
import time

from .connection import CodexError

THREAD_ID = re.compile(r'^[0-9a-fA-F-]{36}$')


def conflict(message, code='handoff_changed'):
    return CodexError(message, code=code, http_status=409)


class WriterProcesses:
    def __init__(self, codex_home, *, clock=time.monotonic):
        self.home = Path(codex_home).resolve()
        self.directory = self.home / 'thread-writer-locks'
        self.clock = clock
        self._tickets = {}
        self._lock = threading.Lock()

    def path(self, thread_id):
        if not THREAD_ID.fullmatch(thread_id):
            raise conflict('无效的原生会话 ID')
        return self.directory / (thread_id + '.lock')

    def busy(self, thread_id):
        path = self.path(thread_id)
        try:
            fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
        except FileNotFoundError:
            return False
        try:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                return True
            fcntl.flock(fd, fcntl.LOCK_UN)
            return False
        finally:
            os.close(fd)

    @staticmethod
    def run(args):
        result = subprocess.run(args, text=True, capture_output=True, timeout=5)
        if result.returncode not in (0, 1):
            raise conflict('无法核实持锁进程，未结束任何进程')
        return result.stdout

    def identity(self, pid):
        if os.name == 'nt':
            return fcntl.windows_identity(pid)
        start = self.run(['ps', '-p', str(pid), '-o', 'lstart=']).strip()
        command = self.run(['ps', '-ww', '-p', str(pid), '-o', 'args=']).strip()
        executable = self.run(['ps', '-ww', '-p', str(pid), '-o', 'comm=']).strip()
        if sys.platform == 'darwin':
            import ctypes
            buffer = ctypes.create_string_buffer(4096)
            size = ctypes.CDLL('/usr/lib/libproc.dylib').proc_pidpath(pid, buffer, len(buffer))
            executable = os.fsdecode(buffer.value) if size > 0 else ''
        if sys.platform.startswith('linux'):
            executable = os.path.realpath(f'/proc/{pid}/exe')
        if not start or not command or not executable:
            return None
        return {'pid': pid, 'start': start, 'command': command, 'executable': executable}

    def validate_executable(self, identity, *, inspecting_runtime=False):
        if Path(identity['executable']).name != 'codex' or identity['pid'] in (os.getpid(), 1) or (identity['pid'] == os.getppid() and not inspecting_runtime):
            raise conflict('持锁者不是可核实的 Codex 进程，未结束任何进程')

    def open_threads(self, pid):
        output = self.run(['lsof', '-nP', '-F', 'n', '-a', '-p', str(pid), '+D', str(self.directory)])
        paths = {Path(line[1:]) for line in output.splitlines() if line.startswith('n')}
        return sorted(p.stem for p in paths if p.parent == self.directory and p.suffix == '.lock' and THREAD_ID.fullmatch(p.stem))

    def descendants(self, pid):
        rows = self.run(['ps', '-axo', 'pid=,ppid=']).splitlines()
        parents = {int(parts[0]): int(parts[1]) for row in rows if len(parts := row.split()) == 2}
        found = set()
        frontier = {pid}
        while frontier:
            frontier = {child for child, parent in parents.items() if parent in frontier and child not in found}
            found.update(frontier)
        identities = [self.identity(child) for child in sorted(found)]
        return [identity for identity in identities if identity is not None]

    def snapshot(self, thread_id):
        if os.name == 'nt':
            raise conflict('Windows 外部 writer 请在其所属客户端释放；Relay 自有会话支持释放与恢复', 'handoff_external_unsupported')
        if not self.busy(thread_id):
            return {'threadId': thread_id, 'holders': []}
        output = self.run(['lsof', '-t', '--', str(self.path(thread_id))])
        pids = sorted({int(line) for line in output.splitlines() if line.isdigit()})
        # lsof lists open handles, not exclusively the lock owner. An explicit
        # preview may include every verified Codex candidate and its full scope.
        if not pids:
            raise conflict('无法识别持锁进程，未结束任何进程')
        holders = []
        for pid in pids:
            identity = self.identity(pid)
            if identity is None:
                raise conflict('持锁进程已变化，请重新操作')
            self.validate_executable(identity)
            threads = self.open_threads(pid)
            if thread_id not in threads:
                raise conflict('会话锁已变化，请重新操作')
            holders.append({**identity, 'threads': threads, 'children': self.descendants(pid)})
        return {'threadId': thread_id, 'holders': holders}

    def preview(self, thread_id, owner, action):
        if action not in ('takeover', 'release'):
            raise conflict('无效的交接操作')
        snapshot = self.snapshot(thread_id)
        affected = sorted({thread_id, *(t for h in snapshot['holders'] for t in h['threads'])})
        ticket = secrets.token_urlsafe(32)
        with self._lock:
            self._tickets = {k: v for k, v in self._tickets.items() if self.clock() < v['expires']}
            if len(self._tickets) >= 128:
                raise conflict('交接请求过多，请稍后重试')
            self._tickets[ticket] = {'snapshot': snapshot, 'owner': owner, 'action': action, 'expires': self.clock() + 120}
        return {'ticket': ticket, 'threadId': thread_id, 'operation': action,
                'requiresConfirmation': len(affected) > 1 or len(snapshot['holders']) > 1, 'affectedThreadIds': affected,
                'processIds': [h['pid'] for h in snapshot['holders']], 'childProcessCount': sum(len(h['children']) for h in snapshot['holders'])}

    @staticmethod
    def compatible(expected, current, *, releasing=False):
        """Allow natural shutdown within the preview; never widen its scope."""
        if current['threadId'] != expected['threadId']:
            return False
        old = {h['pid']: h for h in expected['holders']}
        for holder in current['holders']:
            previous = old.get(holder['pid'])
            if previous is None:
                return False
            if any(holder[key] != previous[key] for key in ('pid', 'start', 'command', 'executable')):
                return False
            if not set(holder['threads']) <= set(previous['threads']):
                return False
            if any(child not in previous['children'] for child in holder['children']):
                return False
        return True

    def preview_scope(self, thread_id, owner, action, ticket):
        """Select operation gates only; this does not authorize or consume a ticket."""
        with self._lock:
            item = self._tickets.get(ticket)
            if (item and self.clock() < item['expires'] and item['owner'] == owner
                    and item['action'] == action and item['snapshot']['threadId'] == thread_id):
                return sorted({thread_id, *(t for h in item['snapshot']['holders'] for t in h['threads'])})
        # Subscription/owned-writer tickets have exactly one thread. Invalid
        # process tickets are still rejected by consume before any operation.
        return [thread_id]

    def consume(self, thread_id, owner, action, ticket, confirmed):
        with self._lock:
            item = self._tickets.pop(ticket, None)
        if not item or self.clock() >= item['expires'] or item['owner'] != owner or item['action'] != action or item['snapshot']['threadId'] != thread_id:
            raise conflict('交接预览已失效，请重新操作')
        expected = item['snapshot']
        current = self.snapshot(thread_id)
        if not self.compatible(expected, current, releasing=action == 'release'):
            raise conflict('持锁进程或关联会话已变化，请重新确认')
        affected = {thread_id, *(t for h in expected['holders'] for t in h['threads'])}
        if (len(affected) > 1 or len(expected['holders']) > 1) and confirmed is not True:
            raise conflict('将结束多个进程或影响其他会话，需要确认影响范围', 'handoff_confirmation_required')
        return expected

    def terminate(self, snapshot):
        # Revalidate after other preflight work, immediately before sending a signal.
        current = self.snapshot(snapshot['threadId'])
        if not self.compatible(snapshot, current, releasing=True):
            raise conflict('持锁进程已变化，未结束任何进程')
        approved_children = [child for holder in snapshot['holders'] for child in holder['children']]
        for holder in current['holders']:
            pid = holder['pid']
            identity = self.identity(pid)
            if identity is None:
                continue
            if identity != {k: holder[k] for k in ('pid', 'start', 'command', 'executable')}:
                raise conflict('持锁进程已变化，未结束该进程')
            try:
                os.kill(pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            deadline = self.clock() + 2
            while self.identity(pid) == {k: holder[k] for k in ('pid', 'start', 'command', 'executable')} and self.clock() < deadline:
                time.sleep(.05)
            current = self.identity(pid)
            if current == {k: holder[k] for k in ('pid', 'start', 'command', 'executable')}:
                try:
                    os.kill(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
        # Preserve the preview's descendants even if their parent exits naturally.
        # Reap only the same verified processes, never reused PIDs or new children.
        for child in reversed(approved_children):
            if self.identity(child['pid']) == child:
                try:
                    os.kill(child['pid'], signal.SIGKILL)
                except ProcessLookupError:
                    pass
        deadline = self.clock() + 5
        while self.busy(snapshot['threadId']):
            if self.clock() >= deadline:
                raise conflict('原进程已结束，但会话锁仍被持有；未宣称接管成功', 'writer_still_held')
            time.sleep(.05)

    def verify_local_runtime(self, runtime_id, socket_path):
        # A forwarded Unix socket can expose a remote PID. Never interpret that
        # number as a local process or report a remote lock as locally released.
        try:
            pid = int(runtime_id)
        except (TypeError, ValueError):
            raise conflict('无法核实本机原生运行时，未结束任何进程')
        identity = self.identity(pid)
        if identity is None:
            raise conflict('进程级交接需要连接本机原生运行时')
        self.validate_executable(identity, inspecting_runtime=True)
        output = self.run(['lsof', '-nP', '-a', '-p', str(pid), '-U', '-F', 'n'])
        sockets = {Path(line[1:]).resolve() for line in output.splitlines() if line.startswith('n/')}
        if Path(socket_path).resolve() not in sockets:
            raise conflict('此节点可能通过远程转发连接，不能在本机结束持锁进程')

    def verify_native_host(self, descriptor, runtime_id, socket_path):
        if os.name == 'nt':
            raise conflict('Windows 外部 writer 请在其所属客户端释放', 'handoff_external_unsupported')
        # Matching PID numbers or home paths alone do not establish locality.
        # Require the remote attestation, exact local process identity, and
        # selected socket ownership before examining/terminating local holders.
        if (descriptor.get('hostName') != socket.gethostname()
                or descriptor.get('platform') != sys.platform
                or Path(str(descriptor.get('codexHome') or '')).resolve() != self.home):
            raise conflict('所选原生主机或 CODEX_HOME 与本机不一致，不能执行进程级交接', 'handoff_host_mismatch')
        if str(descriptor.get('pid')) != str(runtime_id) or descriptor.get('processStartMethod') != 'ps-lstart':
            raise conflict('原生运行时身份无法核对，不能执行进程级交接', 'handoff_host_unverified')
        identity = self.identity(int(runtime_id))
        if (identity is None or identity['start'] != descriptor.get('processStart')
                or Path(identity['executable']).resolve() != Path(str(descriptor.get('executable') or '')).resolve()):
            raise conflict('原生运行时身份已变化，未结束任何进程', 'handoff_host_changed')
        self.verify_local_runtime(runtime_id, socket_path)

    def restart_command(self, snapshot, runtime_id, socket_path):
        holder = next((h for h in snapshot['holders'] if str(h['pid']) == str(runtime_id)), None)
        if holder is None:
            return None
        args = shlex.split(holder['command'])
        # ps preserves argv[0], which may be a bare command resolved by the
        # launcher's PATH. Use the OS-verified executable, not Relay's cwd/PATH.
        if args and args[0] == Path(holder['executable']).name:
            args[0] = holder['executable']
        if not args or Path(args[0]).resolve() != Path(holder['executable']).resolve() or 'app-server' not in args or '--listen' not in args:
            raise conflict('无法重建当前原生运行时，未结束进程')
        listen = args[args.index('--listen') + 1]
        default_socket = self.home / 'app-server-control' / 'app-server-control.sock'
        if listen != 'unix://' + str(socket_path) and not (listen == 'unix://' and Path(socket_path) == default_socket):
            raise conflict('原生运行时监听地址不匹配，未结束进程')
        return args

    def restart(self, args, log_path):
        # Exact verified binary/arguments, no shell, no original user instruction replay.
        env = os.environ.copy()
        env['CODEX_HOME'] = str(self.home)
        with open(log_path, 'ab') as log:
            return subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                    env=env, cwd=self.home, start_new_session=True)
