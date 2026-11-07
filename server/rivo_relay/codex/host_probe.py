"""Read runtime identity on the selected native host, never the Relay host."""
from __future__ import annotations

import json

from .connection import CodexError


# Kept self-contained because command/exec executes this on a potentially remote
# host. Only the selected process's identity and home are returned, never env.
HOST_PROBE = r'''
import json, os, pathlib, socket, subprocess, sys
pid = int(sys.argv[1])
def ps(field, selected=pid):
    return subprocess.check_output(['ps', '-ww', '-p', str(selected), '-o', field+'='], text=True, timeout=3).strip()
before = ps('lstart')
if not before:
    raise RuntimeError('native process unavailable')
proc = pathlib.Path('/proc') / str(pid)
if proc.is_dir():
    executable = os.path.realpath(proc / 'exe')
    env = {}
    for entry in (proc / 'environ').read_bytes().split(b'\0'):
        key, _, value = entry.partition(b'=')
        if key in (b'CODEX_HOME', b'HOME'):
            env[key.decode()] = os.fsdecode(value)
    home = env.get('CODEX_HOME') or str(pathlib.Path(env['HOME']) / '.codex')
    proof = 'process-environment'
else:
    executable = os.path.realpath(ps('comm'))
    # command/exec must be descended from the selected daemon before its
    # inherited environment can establish that daemon's default home.
    parent, seen = os.getpid(), set()
    while parent > 1 and parent != pid and parent not in seen:
        seen.add(parent)
        parent = int(ps('ppid', parent))
    if parent != pid:
        raise RuntimeError('native host identity is not verifiable')
    home = os.environ.get('CODEX_HOME') or str(pathlib.Path(os.environ['HOME']) / '.codex')
    proof = 'daemon-child-environment'
    # An open state database is stronger evidence than inherited defaults.
    try:
        opened = subprocess.check_output(['lsof', '-nP', '-a', '-p', str(pid), '-Fn'], text=True, timeout=3)
        homes = {str(pathlib.Path(line[1:]).parent) for line in opened.splitlines()
                 if line.startswith('n/') and pathlib.Path(line[1:]).name.startswith('state_')
                 and pathlib.Path(line[1:]).suffix in {'.sqlite', '.sqlite3'}}
        if len(homes) == 1:
            home = homes.pop()
            proof = 'open-state-database'
    except (OSError, subprocess.SubprocessError):
        pass
home = os.path.realpath(home)
if before != ps('lstart') or not os.path.isabs(executable) or not pathlib.Path(executable).is_file():
    raise RuntimeError('native process changed during discovery')
print(json.dumps({'pid': pid, 'processStart': before, 'processStartMethod': 'ps-lstart',
    'executable': executable, 'codexHome': home, 'platform': sys.platform,
    'identityMethod': proof, 'hostName': socket.gethostname()}))
'''


def native_host_info(connection):
    """Fail closed when the daemon cannot attest its own host identity."""
    diagnostics = connection.request('server/diagnostics', {}, timeout=5)
    process = diagnostics.get('process', {})
    try:
        pid = int(process.get('id'))
        if pid <= 1:
            raise ValueError()
        result = connection.request('command/exec', {
            'command': ['python3', '-c', HOST_PROBE, str(pid)],
            'timeoutMs': 10000, 'outputBytesCap': 8192,
        }, timeout=15)
        if result.get('exitCode') != 0:
            raise ValueError()
        descriptor = json.loads(result.get('stdout', ''))
        if (descriptor.get('pid') != pid or not descriptor.get('processStart')
                or not descriptor.get('executable', '').startswith('/')
                or not descriptor.get('codexHome', '').startswith('/')):
            raise ValueError()
    except (TypeError, ValueError, KeyError) as error:
        raise CodexError('无法核对原生主机身份，已停用本机路径推断',
                         code='native_host_unverified', http_status=409) from error
    descriptor['generation'] = connection.generation
    return descriptor


def local_native_host_info(connection):
    """Attest a local daemon through socket ownership and its open state DB.

    Never infer locality from the diagnostics PID or inherited environment.
    A forwarded socket must fail ownership verification before inspecting home.
    """
    import socket
    import sys
    from pathlib import Path
    from .writers import WriterProcesses

    pid = connection.request('server/diagnostics', {}, timeout=5).get('process', {}).get('id')
    writers = WriterProcesses(connection.codex_home)
    writers.verify_local_runtime(pid, connection.socket_path)
    before = writers.identity(int(pid))
    output = writers.run(['lsof', '-nP', '-a', '-p', str(pid), '-Fn'])
    homes = {str(Path(line[1:]).parent.resolve()) for line in output.splitlines()
             if line.startswith('n/') and Path(line[1:]).name.startswith('state_')
             and Path(line[1:]).suffix in {'.sqlite', '.sqlite3'}}
    if len(homes) != 1 or before is None or before != writers.identity(int(pid)):
        raise CodexError('无法核对本机原生进程的状态数据库', code='native_host_unverified', http_status=409)
    writers.verify_local_runtime(pid, connection.socket_path)
    return {'pid': int(pid), 'processStart': before['start'], 'processStartMethod': 'ps-lstart',
            'executable': str(Path(before['executable']).resolve()), 'codexHome': homes.pop(),
            'platform': sys.platform, 'hostName': socket.gethostname(),
            'identityMethod': 'local-socket-and-open-state-database', 'generation': connection.generation}
