"""Synchronize the independent ASR worker before publishing update manifests."""
import base64
import hashlib
import json
import subprocess
from pathlib import Path

from .common import load, require, save
from .asr_remote import FILES
from rivo_relay.release_management.configuration import settings


def cache_path(release):
    return Path(release.state.get('managerRoot', release.root)) / 'workspace/asr-verified.json'


def mark_published(release):
    path = cache_path(release)
    if path.exists():
        baseline = load(path)
        if baseline.get('healthy') is True:
            save(path, {**baseline, 'publishedNodeCode': release.state['versionCode']})


def deploy(release):
    release.source_unchanged()
    def source(name):
        return subprocess.check_output(['git', '-C', str(release.root), 'show',
                                        release.state['commit'] + ':scripts/asr/' + name])
    files = {name: source(name) for name in FILES}
    expected = {name: hashlib.sha256(raw).hexdigest() for name, raw in files.items()}
    requirements = hashlib.sha256(source('requirements.lock')).hexdigest()
    host = settings(release.root).get('asrHost', 'e2-mac-mini')
    cache = cache_path(release)
    baseline = load(cache) if cache.exists() else {}
    current_receipt = (baseline.get('commit') == release.state['commit']
                       and baseline.get('versionCode') == release.state['versionCode'])
    previous_publication = (baseline.get('publishedNodeCode', baseline.get('versionCode'))
                            == release.state.get('previousNodeCode', release.state['versionCode']))
    if ((current_receipt or previous_publication)
            and baseline.get('healthy') is True and baseline.get('host') == host
            and baseline.get('sha256') == expected and baseline.get('requirementsSha256') == requirements):
        release.state['steps']['asr'] = {'skipped': 'unchanged', 'baseline': baseline}
        release.record('asr-unchanged')
        return
    release.record('deploying-asr')
    # A transport failure can conceal whether the remote switch took effect.
    # Require a fresh successful receipt before using the shortcut again.
    save(cache, {})
    payload = {'commit': release.state['commit'], 'versionCode': release.state['versionCode'],
               'files': {name: base64.b64encode(raw).decode() for name, raw in files.items()},
               'requirementsSha256': requirements}
    worker = Path(__file__).with_name('asr_remote.py').read_text()
    script = worker + '\nmain(json.loads(' + repr(json.dumps(payload)) + '))\n'
    # No secrets are transmitted: the health token remains on E2.
    command = (['/Users/e2/Services/rivo-asr/.venv/bin/python', '-'] if host == 'local' else
               ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', host,
                '/Users/e2/Services/rivo-asr/.venv/bin/python -'])
    result = subprocess.run(command, input=script, text=True,
                            capture_output=True, timeout=180)
    (release.directory / 'asr-deployment.log').write_text(result.stdout + result.stderr)
    require(result.returncode == 0, 'E2 ASR 同步失败，停止发布；查看 asr-deployment.log，修复后 resume')
    report = json.loads(result.stdout)
    require(report.get('healthy') is True and report.get('commit') == payload['commit']
            and report.get('versionCode') == payload['versionCode'] and report.get('sha256') == expected,
            'E2 ASR 同步验收不一致')
    save(cache, {**report, 'host': host, 'requirementsSha256': requirements})
    save(release.directory / 'asr-verification.json', report)
    release.state['steps']['asr'] = report
    release.record('asr-deployed')
