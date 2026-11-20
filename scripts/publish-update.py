#!/usr/bin/env python3
"""Publish a built APK atomically to a Relay workspace; GitHub requires --github-release."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
from rivo_relay.compatibility import apk_compatibility


def publish(apk, workspace, version, code, notes):
    root = workspace / 'updates'
    root.mkdir(parents=True, exist_ok=True)
    with (root / '.publish.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return publish_locked(apk, workspace, version, code, notes)


def publish_locked(apk, workspace, version, code, notes):
    if not re.fullmatch(r'\d+\.\d+\.\d+', version) or code <= 0:
        raise ValueError('Invalid version')
    if not 0 < apk.stat().st_size <= 256 * 1024 * 1024 or not zipfile.is_zipfile(apk):
        raise ValueError('Invalid APK')
    with zipfile.ZipFile(apk) as archive:
        if 'AndroidManifest.xml' not in archive.namelist():
            raise ValueError('Missing Android manifest')
    root = workspace / 'updates'
    (root / 'apks').mkdir(parents=True, exist_ok=True)
    hasher = hashlib.sha256()
    with apk.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            hasher.update(block)
    digest = hasher.hexdigest()
    manifest = {'version': version, 'versionCode': code, 'sha256': digest,
                'size': apk.stat().st_size, 'notes': notes, 'compatibility': apk_compatibility(apk)}
    previous = root / 'latest.json'
    if previous.exists():
        old = json.loads(previous.read_text())
        if old['versionCode'] > code or (old['versionCode'] == code and old['sha256'] != digest):
            raise ValueError('Refusing downgrade or replacement of an existing version')
    def atomic_write(target, writer):
        fd, temporary = tempfile.mkstemp(dir=target.parent)
        try:
            with os.fdopen(fd, 'wb') as stream:
                writer(stream)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, target)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    def copy(stream):
        with apk.open('rb') as source:
            shutil.copyfileobj(source, stream)
    atomic_write(root / 'apks' / (digest + '.apk'), copy)
    atomic_write(previous, lambda stream: stream.write((json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()))
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--notes', type=Path, required=True)
    parser.add_argument('--github-release', action='store_true', help='Explicitly also publish a GitHub Release')
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
    candidates = sorted((sdk / 'build-tools').glob('*/aapt'), reverse=True)
    aapt = shutil.which('aapt') or (str(candidates[0]) if candidates else None)
    if not aapt:
        parser.error('aapt is required to verify APK identity and version; set ANDROID_HOME')
    badging = subprocess.check_output([aapt, 'dump', 'badging', str(args.apk)], text=True)
    identity = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not identity or identity.groups() != ('dev.rivo.relay', str(args.version_code), args.version):
        parser.error('APK package/version does not match the requested publication')
    result = publish(args.apk, args.workspace, args.version, args.version_code, args.notes.read_text())
    print(json.dumps(result, ensure_ascii=False))
    if args.github_release:
        subprocess.run(['gh', 'release', 'create', 'v' + args.version, str(args.apk),
                        '--target', subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                        '--title', 'Android ' + args.version + ' 测试版', '--notes-file', str(args.notes), '--prerelease'], check=True)
