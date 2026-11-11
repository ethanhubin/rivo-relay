"""Standalone E2 worker, sent over SSH by the release CLI (stdlib only)."""
import base64
import fcntl
import hashlib
import json
import os
import plistlib
from pathlib import Path
import subprocess
import sys
import time
import urllib.request

FILES = ('mac_asr.py', 'server.py')


def write(path, data):
    temporary = path.with_name(path.name + '.new')
    with temporary.open('wb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.chmod(0o600)
    temporary.replace(path)


def restart():
    subprocess.run(['launchctl', 'kickstart', '-k', f'gui/{os.getuid()}/local.rivo-asr'],
                   check=True, timeout=20, stdout=subprocess.DEVNULL)


def health(root):
    request = urllib.request.Request('http://127.0.0.1:19092/health', headers={
        'Authorization': 'Bearer ' + (root / 'token').read_text().strip()})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 60
    while True:
        try:
            with opener.open(request, timeout=3) as response:
                if json.load(response).get('ok') is True:
                    return
        except (OSError, ValueError):
            pass
        if time.monotonic() >= deadline:
            raise RuntimeError('ASR health check failed')
        time.sleep(1)


def apply(root, payload, restart_service=restart, check_health=health):
    files = {name: base64.b64decode(payload['files'][name], validate=True) for name in FILES}
    for name, raw in files.items():
        compile(raw, name, 'exec')
    expected = {name: hashlib.sha256(raw).hexdigest() for name, raw in files.items()}
    if hashlib.sha256((root / 'requirements.lock').read_bytes()).hexdigest() != payload['requirementsSha256']:
        raise RuntimeError('ASR dependency lock differs; provision dependencies before releasing')
    active_path, journal_path = root / 'release-active.json', root / 'release-pending.json'
    active = json.loads(active_path.read_text()) if active_path.exists() else {}
    if active.get('versionCode', 0) > payload['versionCode']:
        raise RuntimeError('Refusing ASR downgrade')
    if active.get('versionCode') == payload['versionCode'] and active.get('sha256') != expected:
        raise RuntimeError('ASR version already has different content')

    def restore(journal):
        for name in FILES:
            write(root / name, base64.b64decode(journal['before'][name]))
        restart_service()
        check_health(root)
        if journal['active']:
            write(active_path, json.dumps(journal['active']).encode())
        else:
            active_path.unlink(missing_ok=True)
        journal_path.unlink()

    # A lost SSH connection or process crash never becomes an unverified success.
    if journal_path.exists():
        restore(json.loads(journal_path.read_text()))
        active = json.loads(active_path.read_text()) if active_path.exists() else {}
    unchanged = (active.get('sha256') == expected and all(
        hashlib.sha256((root / name).read_bytes()).hexdigest() == expected[name] for name in FILES))
    report = {'commit': payload['commit'], 'versionCode': payload['versionCode'],
              'sha256': expected, 'healthy': True, 'restarted': not unchanged}
    if unchanged:
        check_health(root)
        write(active_path, json.dumps(report).encode())
        return report
    journal = {'active': active, 'before': {
        name: base64.b64encode((root / name).read_bytes()).decode() for name in FILES}}
    write(journal_path, json.dumps(journal).encode())
    try:
        for name, raw in files.items():
            write(root / name, raw)
        restart_service()
        check_health(root)
        for name in FILES:
            if hashlib.sha256((root / name).read_bytes()).hexdigest() != expected[name]:
                raise RuntimeError('ASR deployed content changed')
        write(active_path, json.dumps(report).encode())
        # Keep the last successful switch backup for manual recovery.
        write(root / 'release-backup.json', json.dumps(journal).encode())
        journal_path.unlink()
    except Exception:
        restore(journal)  # On failure the journal stays for the next resume.
        raise
    return report


def main(payload):
    root = Path('/Users/e2/Services/rivo-asr')
    plist = Path.home() / 'Library/LaunchAgents/local.rivo-asr.plist'
    configuration = plistlib.loads(plist.read_bytes())
    arguments = configuration.get('ProgramArguments', [])
    if configuration.get('Label') != 'local.rivo-asr' or str(root / 'server.py') not in arguments:
        raise RuntimeError('Unexpected ASR LaunchAgent target')
    with (root / '.release.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        print(json.dumps(apply(root, payload)), flush=True)
