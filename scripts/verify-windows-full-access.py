#!/usr/bin/env python3
"""Opt-in real coding probe against a deployed Windows node, in a new fixture.

Creates one danger-full-access/never thread and one model turn. The fixture and
thread are retained; pairing is revoked. No existing thread settings are changed.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if os.name != 'nt': parser.error('Windows only')
    root = args.output.resolve(); root.mkdir(exist_ok=False, parents=True)
    repo = root / '编码 验证'; repo.mkdir()
    (repo / 'calculator.py').write_text('def add(a, b):\n    return a - b\n', encoding='utf-8')
    test = 'import unittest\nfrom calculator import add\n\nclass Addition(unittest.TestCase):\n    def test_positive(self): self.assertEqual(add(2, 3), 5)\n    def test_negative(self): self.assertEqual(add(-2, 3), 1)\n\nif __name__ == "__main__": unittest.main()\n'
    (repo / 'test_calculator.py').write_text(test, encoding='utf-8')
    def run(argv):
        return subprocess.run(argv, cwd=repo, capture_output=True, text=True, encoding='utf-8', errors='replace', creationflags=subprocess.CREATE_NO_WINDOW, timeout=30)
    def git(*args):
        result = run(['git', *args]); assert result.returncode == 0, result.stderr
        return result.stdout
    git('init'); git('add', '.')
    git('-c', 'user.name=Rivo Verification', '-c', 'user.email=rivo-test@localhost', 'commit', '-m', 'isolated fixture')
    assert run([sys.executable, '-m', 'unittest', '-v']).returncode != 0
    spec = importlib.util.spec_from_file_location('probe', Path(__file__).with_name('verify-service.py'))
    probe = importlib.util.module_from_spec(spec); spec.loader.exec_module(probe)
    from rivo_relay.database import Database
    from rivo_relay.pairing import PairingStore
    import urllib.request
    db = Database(args.workspace / 'data' / 'relay.sqlite3'); store = PairingStore(db)
    token = None; observer = None
    report = {'ok': False, 'checks': {'fixture_initially_fails': True}, 'requestedPermissions': {'sandbox': 'danger-full-access', 'approvalPolicy': 'never'}}
    try:
        code = store.create(300)['code']
        req = urllib.request.Request('http://127.0.0.1:18765/v1/pair', data=json.dumps({'code': code, 'deviceName': 'Full access acceptance'}).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=10) as response: token = json.load(response)['token']
        client = probe.Client('http://127.0.0.1:18765', token, timeout=240)
        report['runtime'] = client.request('GET', '/v1/node')['runtime']['version']
        project = probe.accepted(client.request('POST', '/v1/projects', {'submissionId': uuid.uuid4().hex, 'path': str(repo), 'trustConfirmed': True}), 'projectId')['projectId']
        tid = probe.accepted(client.request('POST', '/v1/threads', {'submissionId': uuid.uuid4().hex, 'projectId': project, 'sandbox': 'danger-full-access', 'approvalPolicy': 'never'}), 'threadId')['threadId']
        report['threadId'] = tid
        snapshot = client.request('GET', '/v1/threads/' + tid)
        settings = snapshot.get('settings', {})
        (root / 'thread-settings.json').write_text(json.dumps(settings, ensure_ascii=False, indent=2), encoding='utf-8')
        assert settings.get('sandbox', {}).get('type') == 'dangerFullAccess' and settings.get('approvalPolicy') == 'never'
        report['checks']['effective_permissions'] = True
        observer = probe.EventObserver(client, tid); observer.start()
        prompt = ('这是已授权的隔离测试项目。只在当前项目内操作，不访问其他项目。读取 calculator.py 和 test_calculator.py，修复 add 函数使测试通过，保持测试文件不变。'
                  '必须使用工具实际执行以下 Python 的 unittest 测试，并执行 git diff -- calculator.py 和 git status --short；不要提交。'
                  f'Python 路径：{sys.executable}。完成后回复 RIVO_FULL_ACCESS_OK；任何命令失败且不能完成则如实回复失败，不要仅声称成功。')
        body = {'submissionId': uuid.uuid4().hex, 'text': prompt}
        route = f'/v1/threads/{tid}/turns'
        receipt = probe.accepted(client.request('POST', route, body), 'turnId'); report['turnId'] = receipt['turnId']
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            turns = probe.history(client, tid)
            turn = next((t for t in turns if t['id'] == receipt['turnId']), None)
            if turn and turn.get('status') != 'inProgress': break
            time.sleep(1)
        else: raise TimeoutError('Model turn timeout; not replayed')
        (root / 'turn.json').write_text(json.dumps(turn, ensure_ascii=False, indent=2), encoding='utf-8')
        assert turn['status'] == 'completed', turn['status']
        commands = [item for item in turn['items'] if item.get('type') == 'commandExecution']
        report['commandResults'] = [{'id': item.get('id'), 'exitCode': item.get('exitCode')} for item in commands]
        assert commands and all(item.get('exitCode') == 0 for item in commands), 'Model command failed'
        assert any(item.get('type') == 'agentMessage' and 'RIVO_FULL_ACCESS_OK' in item.get('text', '') for item in turn['items'])
        report['checks']['model_commands'] = True
        assert (repo / 'test_calculator.py').read_text(encoding='utf-8') == test
        assert (repo / 'calculator.py').read_text(encoding='utf-8') == 'def add(a, b):\n    return a + b\n'
        verification = run([sys.executable, '-m', 'unittest', '-v'])
        (root / 'tests.log').write_text(verification.stdout + verification.stderr, encoding='utf-8')
        assert verification.returncode == 0
        report['checks']['independent_tests'] = True
        (root / 'change.diff').write_text(git('diff', '--', 'calculator.py'), encoding='utf-8')
        assert git('diff', '--name-only').strip() == 'calculator.py'
        report['checks']['isolated_file_change'] = True
        repeat = probe.accepted(client.request('POST', route, body), 'turnId')
        assert repeat['turnId'] == receipt['turnId'] and len(probe.history(client, tid)) == 1
        report['checks']['deduplication'] = True
        with observer.lock: report['checks']['sse_completion'] = receipt['turnId'] in observer.completed
        assert report['checks']['sse_completion']
        for operation in ('release', 'resume'):
            preview = client.request('GET', f'/v1/threads/{tid}/ownership?operation={operation}')
            probe.accepted(client.request('POST', f'/v1/threads/{tid}/ownership', {'operation': operation, 'submissionId': uuid.uuid4().hex, 'ticket': preview['ticket'], 'confirmed': True}), 'threadId')
            report['checks']['writer_' + operation] = True
        assert len(probe.history(client, tid)) == 1
        report['ok'] = True
    except Exception as error:
        report['error'] = type(error).__name__ + ': ' + str(error)[:500]
        raise
    finally:
        if observer: observer.close()
        if token:
            connection = store.authenticate(token)
            if connection: store.revoke(connection['connection_id'])
        db.close()
        (root / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(report, ensure_ascii=True), flush=True)


if __name__ == '__main__': main()
