#!/usr/bin/env python3
"""Publish one verified local release to the VPS; never pushes Git or creates tags."""
import argparse
import asyncio
import hashlib
import io
import json
import os
import re
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'server'))
from rivo_relay.release_management.configuration import gateway_connection
from rivo_relay.gateway.config import decode_key
from rivo_relay.compatibility import apk_compatibility
from rivo_relay.gateway.update_store import sha256, validate


def bundle(root, commit, apk, manifest, destination, node_version=None, node_code=None, node_notes=None):
    node_only = node_version is not None
    if sha256(apk) != manifest['sha256'] or apk.stat().st_size != manifest['size']:
        raise ValueError('APK differs from verified local publication')
    # Export exactly the release commit, not credentials, workspace or working edits.
    build = subprocess.check_output(['git', '-C', str(root), 'show', commit + ':client/android/app/build.gradle.kts'], text=True)
    if node_version is None and (re.search(r'versionCode\s*=\s*(\d+)', build)[1] != str(manifest['versionCode'])
            or re.search(r'versionName\s*=\s*"([^"]+)"', build)[1] != manifest['version']):
        raise ValueError('Commit version differs from published APK')
    node_version = node_version or manifest['version']
    node_code = node_code or manifest['versionCode']
    node_notes = node_notes if node_notes is not None else manifest['notes']
    if node_code < manifest['versionCode']:
        raise ValueError('Node code cannot precede the app code')
    raw = subprocess.check_output(['git', '-C', str(root), 'archive', '--format=zip', commit, 'server'])
    package = destination / 'node.zip'
    with zipfile.ZipFile(io.BytesIO(raw)) as source, zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED) as output:
        for name in sorted(source.namelist()):
            if not name.endswith('/'):
                info = zipfile.ZipInfo(name, (2020, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                output.writestr(info, source.read(name))
        info = zipfile.ZipInfo('release.json', (2020, 1, 1, 0, 0, 0))
        output.writestr(info, json.dumps({'version': node_version, 'versionCode': node_code}, sort_keys=True))
    compatibility = apk_compatibility(apk)
    if compatibility != manifest.get('compatibility'):
        raise ValueError('APK compatibility differs from publication')
    item = {'version': node_version, 'versionCode': node_code, 'notes': node_notes,
            'schema': 1, 'compatibility': compatibility}
    for kind, path, suffix in [('app', apk, 'apk'), ('node', package, 'zip')]:
        digest = sha256(path)
        name = digest + '.' + suffix
        if kind != 'app' or not node_only:
            shutil.copyfile(path, destination / name)
        item[kind] = {'sha256': digest, 'size': path.stat().st_size, 'path': '/v1/releases/files/' + name}
    item['node']['agentProtocol'] = 1
    item['app'].update({k: manifest[k] for k in ('version', 'versionCode', 'notes')})
    validate(item)
    (destination / 'latest.json').write_text(json.dumps(item, ensure_ascii=False))
    package.unlink()
    return item


async def verify(item, key):
    import aiohttp
    connector = aiohttp.TCPConnector(ssl=aiohttp.Fingerprint(bytes.fromhex(key['pin'])))
    async with aiohttp.ClientSession(connector=connector, timeout=aiohttp.ClientTimeout(total=300)) as client:
        async with client.get(key['gateway'] + '/v1/releases/latest', allow_redirects=False) as response:
            if response.status != 200 or await response.json() != item:
                raise ValueError('VPS release manifest mismatch')
        for kind in ('app', 'node'):
            asset = item[kind]
            digest, size = hashlib.sha256(), 0
            async with client.get(key['gateway'] + asset['path'], allow_redirects=False) as response:
                if response.status != 200: raise ValueError('VPS download failed')
                async for chunk in response.content.iter_chunked(65536):
                    size += len(chunk); digest.update(chunk)
                    if size > asset['size']: raise ValueError('VPS download exceeds size')
            if size != asset['size'] or digest.hexdigest() != asset['sha256']:
                raise ValueError('VPS download integrity mismatch')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, default=ROOT / 'workspace/local-node')
    parser.add_argument('--commit', required=True)
    parser.add_argument('--node-version')
    parser.add_argument('--node-code', type=int)
    parser.add_argument('--node-notes', type=Path)
    default_host, default_identity = gateway_connection(ROOT)
    parser.add_argument('--host', default=default_host)
    parser.add_argument('--identity', type=Path, default=default_identity)
    parser.add_argument('--gateway-url')
    parser.add_argument('--gateway-pin')
    parser.add_argument('--remote-workspace', default='/var/lib/rivo-gateway')
    parser.add_argument('--remote-python', default='/opt/rivo-relay/.venv/bin/python')
    parser.add_argument('--remote-source', default='/opt/rivo-relay/current/server')
    args = parser.parse_args()
    if any(value is not None for value in (args.node_version, args.node_code, args.node_notes)) and not all(
            value is not None for value in (args.node_version, args.node_code, args.node_notes)):
        parser.error('节点版本、版本码和说明必须同时提供')
    manifest = json.loads((args.workspace / 'updates/latest.json').read_text())
    apk = args.workspace / 'updates/apks' / (manifest['sha256'] + '.apk')
    if args.gateway_url or args.gateway_pin:
        if not args.gateway_url or not re.fullmatch('[a-f0-9]{64}', args.gateway_pin or ''):
            parser.error('--gateway-url/--gateway-pin 必须同时提供；证书指纹必须是 SHA-256')
        from rivo_relay.gateway.config import gateway_url
        key = {'gateway': gateway_url(args.gateway_url), 'pin': args.gateway_pin}
    else:
        key = decode_key(json.loads((args.workspace / 'gateway-client.json').read_text())['key'])
    identity = ['-i', str(args.identity), '-o', 'IdentitiesOnly=yes'] if args.identity else []
    ssh = ['ssh', *identity, '-o', 'BatchMode=yes', args.host]
    remote = args.remote_workspace + '/incoming-' + uuid.uuid4().hex
    def command(parts, **kwargs):
        return subprocess.run(ssh + [shlex.join(parts)], check=True, **kwargs)
    with tempfile.TemporaryDirectory(prefix='rivo-release-') as temp:
        directory = Path(temp)
        item = bundle(ROOT, args.commit, apk, manifest, directory, args.node_version, args.node_code,
                      args.node_notes.read_text() if args.node_notes else None)
        required = sum(p.stat().st_size for p in directory.iterdir()) * 2 + 1024 ** 3
        # Check BEFORE upload: stage + immutable files + at least 1 GiB spare.
        check = 'import pathlib,shutil,sys,time,re; p=pathlib.Path(sys.argv[1]); [shutil.rmtree(d) for d in p.parent.glob("incoming-*") if re.fullmatch("incoming-[a-f0-9]{32}",d.name) and d.is_dir() and not d.is_symlink() and time.time()-d.stat().st_mtime>86400]; assert shutil.disk_usage(p.parent).free>=int(sys.argv[2]), "Insufficient VPS space"; p.mkdir(mode=0o700)'
        command([args.remote_python, '-c', check, remote, str(required)])
        try:
            subprocess.run(['scp', *identity, '-o', 'BatchMode=yes',
                            *[str(p) for p in directory.iterdir()], args.host + ':' + remote + '/'], check=True)
            # Match the existing gateway's owner; its protected systemd service can read all assets.
            command(['chown', '-R', 'rivo-gateway:rivo-gateway', remote])
            command(['runuser', '-u', 'rivo-gateway', '--', 'env', 'PYTHONPATH=' + args.remote_source, args.remote_python, '-m', 'rivo_relay.gateway.publish',
                     '--incoming', remote, '--workspace', args.remote_workspace])
            asyncio.run(verify(item, key))
            print(json.dumps({'versionCode': item['versionCode'], 'vpsVerified': True}))
        finally:
            command([args.remote_python, '-c', 'import shutil,sys; shutil.rmtree(sys.argv[1])', remote])


if __name__ == '__main__': main()
