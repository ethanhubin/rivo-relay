#!/usr/bin/env python3
"""Read-only DSH installation/feed, local bridge, and Relay health verification.

No runtime/profile changes, pairing, app-data clearing, installation or restart.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import http.client
import ipaddress
import json
import os
from pathlib import Path
import plistlib
import re
import socket
import stat
import urllib.error
import urllib.parse
import urllib.request

MAX_REPLY = 64 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def read_url(url, timeout):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('Use an HTTP(S) URL without credentials, query, or fragment')
    if parsed.scheme == 'http':
        try:
            loopback = parsed.hostname == 'localhost' or ipaddress.ip_address(parsed.hostname).is_loopback
        except ValueError:
            loopback = False
        if not loopback:
            raise ValueError('HTTP verification is limited to loopback; use HTTPS for remote health checks')
    with urllib.request.build_opener(NoRedirect()).open(url, timeout=timeout) as response:
        data = response.read(MAX_REPLY + 1)
    if len(data) > MAX_REPLY:
        raise ValueError('Response exceeds 64 KiB')
    return data.decode('utf-8')


def scalar(document, key):
    match = re.search(r'^' + re.escape(key) + r':\s*([^\r\n]+)', document, re.MULTILINE)
    if not match:
        raise ValueError(f'Missing {key} in update metadata')
    return match.group(1).strip().strip('\"\'')


def desktop_metadata(app):
    with (app / 'Contents/Info.plist').open('rb') as stream:
        info = plistlib.load(stream)
    version = info.get('CFBundleShortVersionString')
    if not isinstance(version, str) or not version:
        raise ValueError('Desktop version missing from Info.plist')
    update = (app / 'Contents/Resources/app-update.yml').read_text(encoding='utf-8')
    feed = scalar(update, 'url').rstrip('/') + '/' + scalar(update, 'channel') + '-mac.yml'
    return version, feed


def official_feed(url, timeout):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.hostname != 'download.deepseek.com' or parsed.port not in (None, 443):
        raise ValueError('Expected the official https://download.deepseek.com desktop feed')
    return scalar(read_url(url, timeout), 'version')


def bridge_status(path, timeout):
    info = path.lstat()
    if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError('Bridge socket must be owned by the current user with mode 0600')
    parent = path.parent.lstat()
    if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.getuid() or parent.st_mode & 0o077:
        raise ValueError('Bridge socket parent must be owned by the current user with mode 0700')
    connection = http.client.HTTPConnection('localhost', timeout=timeout)
    try:
        connection.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.sock.settimeout(timeout)
        connection.sock.connect(str(path))
        connection.request('POST', '/rpc', json.dumps({'method': 'status', 'params': {}}), {'Content-Type': 'application/json'})
        response = connection.getresponse()
        data = response.read(MAX_REPLY + 1)
        if response.status != 200 or len(data) > MAX_REPLY:
            raise ValueError('Bridge status returned an invalid HTTP response')
        result = json.loads(data).get('result', {})
        if result.get('connected') is not True or result.get('protocol') != 1 or not result.get('generation'):
            raise ValueError('Bridge status is not ready or uses an unsupported protocol')
        return {'protocol': result['protocol'], 'supportedRuntimeVersion': result.get('supportedRuntimeVersion')}
    finally:
        connection.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, default=Path('/Applications/DeepSeek Harness.app'))
    parser.add_argument('--bridge-socket', type=Path, default=Path.home() / '.dsh/rivo/bridge.sock')
    parser.add_argument('--relay-url', default='http://127.0.0.1:18785', help='Verification node base URL; never supplies credentials')
    parser.add_argument('--timeout', type=float, default=10)
    args = parser.parse_args(argv)
    if not 0 < args.timeout <= 60:
        parser.error('--timeout must be in (0, 60] seconds')
    checks = []
    version = None
    feed = None

    def run(name, action):
        try:
            details = action()
            checks.append({'check': name, 'ok': True, **details})
            return details
        except (OSError, ValueError, TypeError, KeyError, http.client.HTTPException, plistlib.InvalidFileException) as error:
            checks.append({'check': name, 'ok': False, 'error': str(error)})
            return None

    installed = run('desktop', lambda: dict(zip(('installedVersion', 'officialFeed'), desktop_metadata(args.app.expanduser()))))
    if installed:
        version, feed = installed['installedVersion'], installed['officialFeed']
        current = run('officialFeed', lambda: {'latestVersion': official_feed(feed, args.timeout)})
        if current:
            matches = version == current['latestVersion']
            checks.append({'check': 'versionMatchesFeed', 'ok': matches, 'installedVersion': version,
                           'latestVersion': current['latestVersion'],
                           'note': '版本一致' if matches else '版本不一致；本脚本不执行升级或推断降级'})
    bridge = run('bridge', lambda: bridge_status(args.bridge_socket.expanduser(), args.timeout))
    if bridge and version:
        matches = bridge.get('supportedRuntimeVersion') == version
        checks.append({'check': 'bridgeCompatibilityTarget', 'ok': matches,
                       'note': '仅核对声明的适配目标；不等同于聊天或审批验收'})

    def health():
        payload = json.loads(read_url(args.relay_url.rstrip('/') + '/health', args.timeout))
        if payload.get('status') != 'ok' or payload.get('nativeConnected') is not True:
            raise ValueError('Relay or its runtime connection is not healthy')
        return {'status': payload['status'], 'nativeConnected': True,
                'note': '/health 不返回后端类型；启动命令须明确 --runtime dsh'}

    run('relayHealth', health)
    report = {'checkedAt': datetime.now(timezone(timedelta(hours=8))).isoformat(timespec='seconds'),
              'readOnly': True, 'checks': checks,
              'scope': 'Version and connectivity only; no pairing, chat, approval, or reconnect acceptance test.'}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if all(check['ok'] for check in checks) else 1


if __name__ == '__main__':
    raise SystemExit(main())
