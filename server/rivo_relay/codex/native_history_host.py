"""Native-host journal inspection and isolated official projection repair.

Only compact lifecycle metadata leaves this process. Conversation content still
comes exclusively from the official persisted-history RPC.
"""
from __future__ import annotations

from contextlib import contextmanager
import io
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys

from . import native_history_support as locks
from .native_reprojection import (NativeHistoryReprojector, NativeHistoryReprojectionError,
                                  MAX_ROLLOUT_BYTES, MAX_RECORD_BYTES, MAX_RECORDS, THREAD_ID)


def _inside(path, root):
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def verify_runtime(info):
    pid = info.get('pid')
    if type(pid) is not int or pid <= 1 or info.get('processStartMethod') != 'ps-lstart':
        raise NativeHistoryReprojectionError('无法核实实际节点的 Codex 运行时身份')
    result = subprocess.run(['ps', '-p', str(pid), '-o', 'lstart='],
                            capture_output=True, text=True, timeout=5, check=False)
    expected_start = str(info.get('processStart') or '')
    if result.returncode or not expected_start or result.stdout.strip() != expected_start:
        raise NativeHistoryReprojectionError('原生运行时已更换，历史操作已取消')
    if sys.platform.startswith('linux'):
        executable = os.path.realpath(f'/proc/{pid}/exe')
    elif sys.platform == 'darwin':
        # ps comm may be only argv[0] ("codex") for a PATH-launched daemon.
        # Resolve the running image through the kernel, as WriterProcesses does;
        # resolving that short name against this helper's cwd invents a path.
        import ctypes
        buffer = ctypes.create_string_buffer(4096)
        size = ctypes.CDLL('/usr/lib/libproc.dylib').proc_pidpath(pid, buffer, len(buffer))
        if size <= 0 or size >= len(buffer):
            raise NativeHistoryReprojectionError('无法核实原生运行时可执行文件')
        executable = os.fsdecode(buffer.value)
    else:
        executable = subprocess.check_output(['ps', '-ww', '-p', str(pid), '-o', 'comm='], text=True, timeout=5).strip()
    if not Path(executable).is_absolute():
        raise NativeHistoryReprojectionError('无法核实原生运行时可执行文件')
    if Path(executable).resolve() != Path(str(info.get('executable') or '')).resolve():
        raise NativeHistoryReprojectionError('原生运行时可执行文件已变化')
    if Path(executable).name.casefold() not in ('codex', 'codex.exe'):
        raise NativeHistoryReprojectionError('历史重建只能使用已核实的 Codex 可执行文件')
    home = Path(str(info.get('codexHome') or ''))
    if not home.is_absolute() or not home.is_dir():
        raise NativeHistoryReprojectionError('无法核实实际节点的 CODEX_HOME')
    return home.resolve()


def thread_storage(home, thread_id):
    if not THREAD_ID.fullmatch(thread_id):
        raise NativeHistoryReprojectionError('Codex thread ID 无效')
    database = home / 'state_5.sqlite'
    if not database.is_file():
        return None
    connection = sqlite3.connect(database.as_uri() + '?mode=ro', uri=True, timeout=5)
    try:
        columns = {row[1] for row in connection.execute('PRAGMA table_info(threads)')}
        if not {'id', 'rollout_path', 'history_mode'}.issubset(columns):
            raise NativeHistoryReprojectionError('原生会话元数据协议不兼容')
        cwd_field = 'cwd' if 'cwd' in columns else 'NULL'
        row = connection.execute(f'SELECT rollout_path,history_mode,{cwd_field} FROM threads WHERE id=?', (thread_id,)).fetchone()
    finally:
        connection.close()
    if row is None:
        return None
    raw = str(row[0] or '')
    if raw.startswith('\\\\?\\UNC\\'):
        raw = '\\\\' + raw[8:]
    elif raw.startswith('\\\\?\\'):
        raw = raw[4:]
    path = Path(raw).resolve()
    roots = tuple((home / directory).resolve() for directory in ('sessions', 'archived_sessions'))
    if not any(_inside(path, root) for root in roots):
        raise NativeHistoryReprojectionError('Codex 历史路径不在原生会话目录中')
    if not path.is_file() and Path(str(path) + '.zst').is_file():
        path = Path(str(path) + '.zst').resolve()
    if not path.is_file() or thread_id not in path.name or not any(_inside(path, root) for root in roots):
        raise NativeHistoryReprojectionError('Codex 原生会话历史不存在')
    return path, str(row[1] or ''), str(row[2] or '')


@contextmanager
def _rollout_reader(path):
    if path.suffix != '.zst':
        with path.open('rb') as stream:
            yield stream
        return
    try:
        import zstandard
    except ImportError:
        try:
            from compression import zstd
        except ImportError:
            try:
                process = subprocess.Popen(['zstd', '-dc', '--', str(path)], stdout=subprocess.PIPE,
                                           stderr=subprocess.DEVNULL, **locks.background_process_options())
            except OSError as error:
                raise NativeHistoryReprojectionError('实际节点缺少压缩历史读取支持（zstandard 或 zstd）') from error
            try:
                yield process.stdout
                if process.wait(timeout=5):
                    raise NativeHistoryReprojectionError('压缩的原生历史无法解码')
            finally:
                process.stdout.close()
                if process.poll() is None:
                    process.kill(); process.wait(timeout=5)
            return
        with zstd.open(path, 'rb') as stream:
            yield stream
        return
    with path.open('rb') as raw, zstandard.ZstdDecompressor().stream_reader(raw) as reader:
        yield io.BufferedReader(reader)


def _fingerprint(stat):
    # ctime detects same-size rewrites even when a caller restores mtime.
    return [stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns]


def _prefix_digest(stream, length):
    """Validate every old byte before trusting cached lifecycle state.

    JSON decoding is incremental; byte verification intentionally remains O(n).
    Inode/size and a tail sample alone cannot detect an in-place middle rewrite
    followed by an append. Bound memory while retaining that correctness guard.
    """
    digest = hashlib.sha256()
    stream.seek(0)
    while length:
        chunk = stream.read(min(length, 1024 * 1024))
        if not chunk:
            raise NativeHistoryReprojectionError('Codex 历史在检查时已截断，请重试')
        digest.update(chunk)
        length -= len(chunk)
    return digest


def inspect_journal(home, thread_id, known=None):
    storage = thread_storage(home, thread_id)
    if storage is None:
        return {'available': False, 'journal': None, 'writerBusy': writer_busy(home, thread_id)}
    path, history_mode, cwd = storage
    before = path.stat()
    if before.st_size > MAX_ROLLOUT_BYTES:
        raise NativeHistoryReprojectionError('Codex 历史超过本次安全检查大小限制')
    fingerprint = _fingerprint(before)
    known = known if isinstance(known, dict) and not known.get('changing') else {}
    scan = known.get('scan') or {}
    same_source = (scan.get('path') == str(path) and scan.get('historyMode') == history_mode
                   and scan.get('threadId') == thread_id)
    if same_source and known.get('fingerprint') == fingerprint:
        return {'available': True, 'journal': known.get('journal'), 'writerBusy': writer_busy(home, thread_id),
                'changing': False, 'historyMode': history_mode, 'compressed': path.suffix == '.zst',
                'workspaceRoot': cwd, 'fingerprint': fingerprint, 'scan': scan,
                'historyEpoch': known.get('historyEpoch')}
    latest = None
    partial = False
    total = record_count = 0
    digest = hashlib.sha256()
    epoch = ''
    previous = known.get('fingerprint') or []
    offset = scan.get('offset')
    incremental = (same_source and path.suffix != '.zst' and len(previous) == 5
                   and previous[:2] == fingerprint[:2] and type(offset) is int
                   and offset == previous[2] and 0 < offset < before.st_size
                   and isinstance(scan.get('digest'), str)
                   and type(scan.get('records')) is int and 0 < scan['records'] <= MAX_RECORDS)
    with _rollout_reader(path) as stream:
        if path.suffix != '.zst' and _fingerprint(os.fstat(stream.fileno())) != fingerprint:
            raise NativeHistoryReprojectionError('Codex 历史在打开时已变化，请重试')
        if incremental:
            digest = _prefix_digest(stream, offset)
            incremental = digest.hexdigest() == scan['digest']
            if not incremental:
                digest = hashlib.sha256()
                stream.seek(0)
        if incremental:
            total, record_count = offset, scan['records']
            latest = dict(known['journal']) if known.get('journal') else None
            epoch = known.get('historyEpoch') or epoch
        while True:
            line = stream.readline(MAX_RECORD_BYTES + 1)
            if not line:
                break
            total += len(line); record_count += 1
            digest.update(line)
            if len(line) > MAX_RECORD_BYTES or total > MAX_ROLLOUT_BYTES or record_count > MAX_RECORDS:
                raise NativeHistoryReprojectionError('Codex 历史记录超过安全检查大小限制')
            if not line.endswith(b'\n'):
                partial = True
                break
            try:
                record = json.loads(line)
            except (ValueError, UnicodeError) as error:
                raise NativeHistoryReprojectionError('Codex 原生历史含损坏的完整记录') from error
            if not isinstance(record, dict):
                raise NativeHistoryReprojectionError('Codex 原生历史记录格式不兼容')
            if not isinstance(record.get('type'), str) or not record['type']:
                raise NativeHistoryReprojectionError('Codex 原生历史记录缺少类型')
            payload = record.get('payload')
            if record_count == 1:
                if record.get('type') != 'session_meta' or not isinstance(payload, dict) or str(payload.get('id') or payload.get('session_id')) != thread_id:
                    raise NativeHistoryReprojectionError('rollout 与 Codex thread ID 不匹配')
            if record.get('type') == 'event_msg' and not isinstance(payload, dict):
                raise NativeHistoryReprojectionError('Codex 原生历史事件格式不兼容')
            if record.get('type') != 'event_msg':
                continue
            kind, turn_id = payload.get('type'), payload.get('turn_id')
            if kind in ('thread_rolled_back', 'turn_rolled_back', 'history_rewritten'):
                epoch = hashlib.sha256((epoch + digest.hexdigest()).encode()).hexdigest()
            if kind in ('task_started', 'task_complete', 'turn_aborted') and (not isinstance(turn_id, str) or not THREAD_ID.fullmatch(turn_id)):
                raise NativeHistoryReprojectionError('Codex 原生历史缺少可核对的 turn ID')
            if kind == 'task_started' and isinstance(turn_id, str) and THREAD_ID.fullmatch(turn_id):
                latest = {'turnId': turn_id, 'status': 'inProgress'}
            elif kind in ('task_complete', 'turn_aborted') and latest and turn_id == latest['turnId']:
                latest['status'] = ('interrupted' if kind == 'turn_aborted' else
                                    'failed' if payload.get('error') is not None else 'completed')
    after = path.stat()
    if not record_count:
        raise NativeHistoryReprojectionError('Codex 原生历史缺少 session_meta')
    changed = fingerprint != _fingerprint(after)
    if not incremental:
        # Concurrent cold readers of the same immutable bytes get one epoch;
        # replacement, truncation or same-inode mutation still changes it.
        epoch = hashlib.sha256(json.dumps([str(path), history_mode, thread_id,
            fingerprint, digest.hexdigest()]).encode()).hexdigest()
    # No raw content crosses the helper boundary. Partial/racing reads are not
    # cacheable. Compressed files use a full decode whenever their identity changes.
    scan = {'path': str(path), 'threadId': thread_id, 'historyMode': history_mode,
            'offset': total, 'records': record_count, 'digest': digest.hexdigest()}
    return {'available': True, 'journal': latest, 'writerBusy': writer_busy(home, thread_id),
            'changing': changed or partial, 'historyMode': history_mode, 'compressed': path.suffix == '.zst',
            'workspaceRoot': cwd, 'fingerprint': _fingerprint(after), 'scan': scan, 'historyEpoch': epoch}


def writer_busy(home, thread_id):
    if not THREAD_ID.fullmatch(thread_id):
        raise NativeHistoryReprojectionError('Codex thread ID 无效')
    path = home / 'thread-writer-locks' / f'{thread_id}.lock'
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    except FileNotFoundError:
        return False
    try:
        try:
            locks.flock(descriptor, locks.LOCK_EX | locks.LOCK_NB)
        except BlockingIOError:
            return True
        locks.flock(descriptor, locks.LOCK_UN)
        return False
    finally:
        os.close(descriptor)


def dispatch(request):
    home = verify_runtime(request['runtime'])
    if request['operation'] == 'activity':
        states, inspections = {}, {}
        known = request.get('known') or {}
        for thread_id in request['threadIds']:
            try:
                inspected = inspect_journal(home, thread_id, known.get(thread_id))
            except (OSError, NativeHistoryReprojectionError):
                # One missing/corrupt rollout must not discard healthy siblings.
                # An unavailable result also evicts this thread's cached watermark.
                inspections[thread_id] = {'available': False}
                continue
            inspections[thread_id] = inspected
            journal = inspected.get('journal') or {}
            # A lock alone only proves ownership; idle Desktop sessions hold it too.
            if not inspected.get('available') or inspected.get('changing'):
                continue
            if journal.get('status') == 'inProgress' and inspected.get('writerBusy'):
                states[thread_id] = 'running'
            elif journal.get('status') in ('completed', 'interrupted', 'failed'):
                states[thread_id] = 'idle'
        verify_runtime(request['runtime'])
        return {'states': states, 'inspections': inspections}
    thread_id = request['threadId']
    before = inspect_journal(home, thread_id, request.get('known') if request['operation'] == 'inspect' else None)
    if request['operation'] == 'inspect':
        verify_runtime(request['runtime'])
        return before
    if request['operation'] != 'repair':
        raise NativeHistoryReprojectionError('未知的原生历史操作')
    if before['writerBusy'] or before.get('changing'):
        return {**before, 'reprojected': False}
    if not before['available']:
        return {**before, 'reprojected': False}
    if before.get('compressed') or before.get('historyMode') != 'paginated':
        raise NativeHistoryReprojectionError('该原生历史格式只能读取，不能原地重建')
    if before['fingerprint'] != request.get('fingerprint'):
        raise NativeHistoryReprojectionError('Codex 历史已更新，请重新核对')
    path, _mode, stored_cwd = thread_storage(home, thread_id)
    cwd = Path(request.get('workspaceRoot') or stored_cwd)
    if not cwd.is_absolute() or not cwd.is_dir():
        raise NativeHistoryReprojectionError('原工作目录已不存在，请先明确重绑工作目录')
    # Isolated repair gets no credential environment and no user config/auth files.
    environment = {key: value for key, value in os.environ.items()
                   if key in {'PATH', 'SystemRoot', 'WINDIR', 'LANG', 'LC_ALL', 'TMPDIR', 'TEMP', 'TMP'}}
    result = NativeHistoryReprojector(codex_home=home,
        repair_dir=home / 'rivo-history-repairs', executable=Path(request['runtime']['executable']),
        environment=environment, before_publish=lambda: verify_runtime(request['runtime'])).reproject(thread_id, path, cwd)
    verify_runtime(request['runtime'])
    return {**inspect_journal(home, thread_id), 'reprojected': True,
            'reasons': list(result.reasons), 'recordCount': result.record_count, 'turnCount': result.turn_count}
