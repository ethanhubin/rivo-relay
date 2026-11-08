#!/usr/bin/env python3
"""Explicit Windows acceptance: creates an isolated project and two fixed model turns.

Uses the logged-in Codex home without copying credentials. Retains its test project,
thread and redacted report. No existing project is mutated; no retries of writes.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--codex-executable', type=Path, required=True)
    parser.add_argument('--codex-home', type=Path, default=Path.home() / '.codex')
    parser.add_argument('--output', type=Path, required=True, help='New directory for this acceptance run')
    args = parser.parse_args()
    if os.name != 'nt': parser.error('Run on Windows')
    root = args.output.resolve(); root.mkdir(parents=True, exist_ok=False)
    report = {'ok': False, 'checks': {}, 'python': sys.version.split()[0]}
    from rivo_relay.codex.windows_process import spawn_owned
    from rivo_relay.database import Database
    from rivo_relay.pairing import PairingStore
    spec = importlib.util.spec_from_file_location('acceptance', Path(__file__).with_name('verify-service.py'))
    verify = importlib.util.module_from_spec(spec); spec.loader.exec_module(verify)
    repo = root / '测试 workspace'; repo.mkdir()
    (repo / 'README.md').write_text('Windows node acceptance\n', encoding='utf-8')
    def git(*argv):
        return subprocess.check_output(['git', '-C', str(repo), *argv], stderr=subprocess.STDOUT, text=True, creationflags=subprocess.CREATE_NO_WINDOW).strip()
    git('init'); git('add', 'README.md')
    git('-c', 'user.name=Rivo Verification', '-c', 'user.email=rivo-test@localhost', 'commit', '-m', 'fixture')
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    url = f'http://127.0.0.1:{port}'
    workspace = root / 'relay'
    log = (root / 'relay.log').open('wb')
    child = spawn_owned([sys.executable, '-m', 'rivo_relay', 'serve', '--workspace', str(workspace),
                         '--url', url, '--port', str(port), '--codex-runtime', 'stdio',
                         '--codex-executable', str(args.codex_executable), '--codex-home', str(args.codex_home)],
                        stdout=log, stderr=log)
    try:
        deadline = time.monotonic() + 30
        while True:
            if child.poll() is not None: raise RuntimeError('Relay exited before health')
            try:
                with urllib.request.urlopen(url + '/health', timeout=2) as response:
                    assert json.load(response)['status'] == 'ok'
                break
            except OSError:
                if time.monotonic() > deadline: raise
                time.sleep(.2)
        report['checks']['http_start'] = True
        # Second process must fail on workspace lock before acquiring any runtime.
        duplicate = subprocess.run([sys.executable, '-m', 'rivo_relay', 'serve', '--workspace', str(workspace),
                                     '--port', str(port + 1 if port < 65535 else port - 1)], capture_output=True, text=True, timeout=10, creationflags=subprocess.CREATE_NO_WINDOW)
        assert duplicate.returncode == 1 and 'already owns' in duplicate.stderr
        report['checks']['workspace_lock'] = True
        db = Database(workspace / 'data' / 'relay.sqlite3')
        try: code = PairingStore(db).create(300)['code']
        finally: db.close()
        body = json.dumps({'code': code, 'deviceName': 'Windows acceptance'}).encode()
        with urllib.request.urlopen(urllib.request.Request(url + '/v1/pair', data=body, headers={'Content-Type': 'application/json'})) as response:
            token = json.load(response)['token']
        report['checks']['pair_exchange'] = True
        client = verify.Client(url, token, timeout=180)
        report['runtime'] = subprocess.check_output([str(args.codex_executable), '--version'], text=True, creationflags=subprocess.CREATE_NO_WINDOW).strip()
        report['executable'] = str(args.codex_executable)
        project = verify.accepted(client.request('POST', '/v1/projects', {'submissionId': uuid.uuid4().hex, 'path': str(repo), 'trustConfirmed': True}), 'projectId')['projectId']
        report['checks']['project_create'] = True
        directory = client.request('GET', '/v1/project-directories?' + urllib.parse.urlencode({'path': str(repo)}))
        assert directory['parentPath'] == str(root)
        report['checks']['unicode_directory'] = True
        assert client.request('GET', '/v1/projects/' + project + '/git')['git']['head'] == git('rev-parse', 'HEAD')
        report['checks']['git'] = True
        for size in (17, 9 * 1024 * 1024):
            payload = (b'Rivo Windows data\0' * ((size // 18) + 1))[:size]
            connection = client.connect()
            connection.request('POST', client.path('/v1/projects/' + project + '/attachments?' + urllib.parse.urlencode({'name': '文件 sample.bin'})),
                               body=payload, headers={**client.headers(), 'Content-Type': 'application/octet-stream'})
            response = connection.getresponse(); raw = response.read(); connection.close()
            if response.status != 200: raise RuntimeError('upload failed: ' + raw.decode()[:500])
            uploaded = json.loads(raw)
            assert Path(uploaded['path']).read_bytes() == payload
            # Use the native streaming reader (same one used by HTTP downloads).
            from rivo_relay.codex.runtime import RuntimeConnection
            from rivo_relay.codex.native_download import prepare_download
            native = RuntimeConnection(mode='stdio', codex_executable=args.codex_executable, codex_home=args.codex_home)
            try:
                native.start()
                download = prepare_download(native, uploaded['path'])
                downloaded = b''.join(download['chunks'])
                assert download['size'] == len(payload) and downloaded == payload
            finally: native.close()
            report['checks'][f'upload_download_{size}'] = True
        # Exercise a real Git worktree and the Python-backed directory fingerprint.
        from contextlib import nullcontext
        from types import SimpleNamespace
        from rivo_relay.codex.developer import Workspaces, directory_fingerprint, worktree_presence
        native = RuntimeConnection(mode='stdio', codex_executable=args.codex_executable, codex_home=args.codex_home)
        db = Database(workspace / 'data' / 'relay.sqlite3')
        try:
            native.start()
            manager = Workspaces(db, SimpleNamespace(connection=native, operation_gate=lambda *_: nullcontext()))
            identity, path = manager.create(project, str(repo), git('rev-parse', 'HEAD'))
            assert worktree_presence(native, str(repo), path) == (True, True)
            before = directory_fingerprint(native, path)
            test_file = Path(path) / '检查.txt'; test_file.write_text('test', encoding='utf-8')
            assert before != directory_fingerprint(native, path)
            test_file.unlink()
            assert manager.abandon(identity)['status'] == 'removed'
            report['checks']['worktree_create_fingerprint_cleanup'] = True
        finally:
            native.close(); db.close()
        verify.verify(client, project, report)
        thread = report['test_ids']['threadId']
        from rivo_relay.codex.writers import WriterProcesses
        locks = WriterProcesses(args.codex_home)
        assert locks.busy(thread)
        report['checks']['native_writer_lock'] = True
        for operation in ('release', 'resume'):
            preview = client.request('GET', f'/v1/threads/{thread}/ownership?operation={operation}')
            result = client.request('POST', f'/v1/threads/{thread}/ownership', {'operation': operation, 'submissionId': uuid.uuid4().hex, 'ticket': preview['ticket'], 'confirmed': True})
            verify.accepted(result, 'threadId')
            assert locks.busy(thread) == (operation == 'resume')
            report['checks']['writer_' + operation] = True
        assert len(verify.history(client, thread)) == 1
        report['checks']['resume_preserves_history'] = True
        # A no-tool response alone cannot validate a coding node's sandbox.
        receipt = verify.accepted(client.request('POST', f'/v1/threads/{thread}/turns', {
            'submissionId': uuid.uuid4().hex,
            'text': '请只用工具执行一次 PowerShell 命令读取当前目录 README.md 的第一行，不写文件。成功回复 RIVO_WINDOWS_TOOL_OK；如果工具失败，直接回复 RIVO_WINDOWS_TOOL_FAILED，不重试、不申请提升权限、不换用其他工具。'}), 'turnId')
        report['native_writes'] = 'one_new_thread_two_fixed_messages'
        report['test_ids']['toolTurnId'] = receipt['turnId']
        deadline = time.monotonic() + 180
        while True:
            turns = verify.history(client, thread)
            turn = next((t for t in turns if t['id'] == receipt['turnId']), None)
            if turn and turn.get('status') != 'inProgress':
                report['native_turn_count'] = len(turns)
                commands = [i for i in turn.get('items', []) if i.get('type') == 'commandExecution']
                assert turn['status'] == 'completed' and commands and all(i.get('exitCode') == 0 for i in commands), 'sandbox tool execution failed'
                assert any(i.get('type') == 'agentMessage' and 'RIVO_WINDOWS_TOOL_OK' in i.get('text', '') for i in turn['items'])
                report['checks']['model_sandbox_command'] = True
                break
            if time.monotonic() >= deadline:
                raise TimeoutError('Model command verification timed out')
            time.sleep(.5)
        report['ok'] = True
    except Exception as error:
        report['error'] = type(error).__name__ + ': ' + str(error)[:1000]
        raise
    finally:
        try:
            child._rivo_job.terminate(); child.wait(timeout=10)
            report['checks']['service_tree_shutdown'] = True
        finally:
            child._rivo_job.close(); log.close()
            (root / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps(report, ensure_ascii=True, indent=2), flush=True)


if __name__ == '__main__': main()
