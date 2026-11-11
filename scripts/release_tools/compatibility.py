"""Best-effort legacy download mirror; VPS publication never depends on it."""
import base64
import json
import shlex
import subprocess

from .common import load, save
from rivo_relay.release_management.configuration import settings


def mirror(release):
    target = settings(release.root).get('compatibility')
    if not target:
        return
    manifest = load(release.root / 'workspace/local-node/updates/latest.json')
    payload = {'root': target['root'], 'manifest': manifest,
               'apk': base64.b64encode(release.apk.read_bytes()).decode()}
    # Serialize literal data, never interpolate paths or notes as shell code.
    script = '''import base64,fcntl,hashlib,json,os,pathlib,tempfile
def publish(payload):
    root=pathlib.Path(payload['root'])/'workspace'
    with (root/'.release-cli.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        updates=root/'local-node/updates'; updates.mkdir(parents=True,exist_ok=True)
        data=base64.b64decode(payload['apk'],validate=True); item=payload['manifest']
        assert len(data)==item['size'] and hashlib.sha256(data).hexdigest()==item['sha256']
        latest=updates/'latest.json'
        if latest.exists():
            old=json.loads(latest.read_text())
            assert old['versionCode']<=item['versionCode']
            assert old['versionCode']!=item['versionCode'] or old['sha256']==item['sha256']
        def atomic(path,data):
            path.parent.mkdir(exist_ok=True)
            fd,name=tempfile.mkstemp(dir=path.parent)
            try:
                with os.fdopen(fd,'wb') as stream:
                    stream.write(data);stream.flush();os.fsync(stream.fileno())
                os.replace(name,path)
            finally:
                if os.path.exists(name): os.unlink(name)
        atomic(updates/'apks'/(item['sha256']+'.apk'),data)
        atomic(latest,(json.dumps(item,ensure_ascii=False)+'\\n').encode())
    print('mirrored')
'''
    try:
        result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10',
            '-o', 'StrictHostKeyChecking=yes', target['host'], shlex.join([target['python'], '-'])],
            input=script + '\npublish(' + repr(payload) + ')\n', text=True, capture_output=True, timeout=90)
        if result.returncode:
            raise RuntimeError('旧下载入口同步失败')
        from .common import verify_http
        report = verify_http(manifest)
        report['pending'] = False
    except Exception:
        report = {'pending': True, 'note': 'VPS 已完成发布；旧下载入口暂未同步，可 resume 重试，未重启旧节点'}
    save(release.directory / 'compatibility-mirror.json', report)
    release.state['steps']['compatibility'] = report
