from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request


BASE = 'http://100.124.150.1:18767'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def atomic(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def save(path, value):
    atomic(path, (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode())


def load(path):
    return json.loads(Path(path).read_text())


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


@contextmanager
def locked(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a') as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError('另一个发布进程仍在执行；请先查看 status') from None
        yield stream


def output(args, *, cwd=None, env=None):
    return subprocess.check_output([str(arg) for arg in args], cwd=cwd, env=env, text=True).strip()


def git(root, *args):
    return output(['git', '-C', root, *args])


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise RuntimeError('更新源返回了重定向，停止验收')


def opener():
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def get_json(url):
    with opener().open(url, timeout=20) as response:
        return json.load(response)


def verify_http(expected, *, base=BASE):
    manifest = get_json(base + '/v1/app-updates/latest')
    for key in ('version', 'versionCode', 'size', 'sha256', 'notes'):
        require(manifest.get(key) == expected[key], 'HTTP 清单与发布产物不一致：' + key)
    path = '/v1/app-updates/apks/' + expected['sha256'] + '.apk'
    require(manifest.get('apkPath') == path, 'HTTP 清单 APK 路径不正确')
    hasher, size = hashlib.sha256(), 0
    with opener().open(base + path, timeout=30) as response:
        require(int(response.headers['Content-Length']) == expected['size'], 'APK Content-Length 不正确')
        for block in iter(lambda: response.read(1024 * 1024), b''):
            size += len(block)
            require(size <= expected['size'], 'APK 超出清单大小')
            hasher.update(block)
    require(size == expected['size'] and hasher.hexdigest() == expected['sha256'], 'APK 下载完整性校验失败')
    return {'version': expected['version'], 'versionCode': expected['versionCode'],
            'size': size, 'sha256': hasher.hexdigest(), 'httpDownloadVerified': True}
