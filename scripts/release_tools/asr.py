"""Synchronize the independent ASR worker before publishing update manifests."""
import base64
import hashlib
import json
import subprocess
from pathlib import Path

from .common import require, save
from .asr_remote import FILES
from rivo_relay.release_management.configuration import settings


def deploy(release):
    release.source_unchanged()
    release.record('deploying-asr')
    def source(name):
        return subprocess.check_output(['git', '-C', str(release.root), 'show',
                                        release.state['commit'] + ':scripts/asr/' + name])
    payload = {'commit': release.state['commit'], 'versionCode': release.state['versionCode'],
               'files': {name: base64.b64encode(source(name)).decode() for name in FILES},
               'requirementsSha256': hashlib.sha256(source('requirements.lock')).hexdigest()}
    worker = Path(__file__).with_name('asr_remote.py').read_text()
    script = worker + '\nmain(json.loads(' + repr(json.dumps(payload)) + '))\n'
    # No secrets are transmitted: the health token remains on E2.
    host = settings(release.root).get('asrHost', 'e2-mac-mini')
    command = (['/Users/e2/Services/rivo-asr/.venv/bin/python', '-'] if host == 'local' else
               ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', host,
                '/Users/e2/Services/rivo-asr/.venv/bin/python -'])
    result = subprocess.run(command, input=script, text=True,
                            capture_output=True, timeout=180)
    (release.directory / 'asr-deployment.log').write_text(result.stdout + result.stderr)
    require(result.returncode == 0, 'E2 ASR 同步失败，停止发布；查看 asr-deployment.log，修复后 resume')
    report = json.loads(result.stdout)
    expected = {name: hashlib.sha256(base64.b64decode(raw)).hexdigest()
                for name, raw in payload['files'].items()}
    require(report.get('healthy') is True and report.get('commit') == payload['commit']
            and report.get('versionCode') == payload['versionCode'] and report.get('sha256') == expected,
            'E2 ASR 同步验收不一致')
    save(release.directory / 'asr-verification.json', report)
    release.state['steps']['asr'] = report
    release.record('asr-deployed')
