from __future__ import annotations

import base64
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import secrets
import ssl
import subprocess
from urllib.parse import urlsplit


def save_private(path: Path, value: dict):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_suffix('.tmp')
    with open(temporary, 'w', opener=lambda p, flags: os.open(p, flags, 0o600)) as stream:
        json.dump(value, stream)
    temporary.chmod(0o600)
    temporary.replace(path)


def initialize(directory: Path, address: str):
    ipaddress.ip_address(address)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    cert, key = directory / 'server.pem', directory / 'server.key'
    if not cert.exists() and not key.exists():
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-sha256', '-nodes',
                        '-keyout', str(key), '-out', str(cert), '-days', '3650',
                        '-subj', '/CN=Rivo Gateway', '-addext', f'subjectAltName=IP:{address}'],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        key.chmod(0o600)
    if not cert.exists() or not key.exists():
        raise ValueError('Incomplete gateway TLS identity; restore the original certificate and key')
    if not (directory / 'keys.json').exists():
        save_private(directory / 'keys.json', {})
    return hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest()


def issue_key(directory: Path, fingerprint: str):
    slot, secret = secrets.token_hex(16), secrets.token_urlsafe(32)
    path = directory / 'keys.json'
    keys = json.loads(path.read_text())
    keys[slot] = hashlib.sha256(secret.encode()).hexdigest()
    save_private(path, keys)
    bundle = {'v': 1, 'slot': slot, 'secret': secret, 'pin': fingerprint}
    return 'rivo1.' + base64.urlsafe_b64encode(json.dumps(bundle, separators=(',', ':')).encode()).decode().rstrip('=')


def decode_key(value: str):
    if not value.startswith('rivo1.') or len(value) > 2048:
        raise ValueError('Invalid gateway access key')
    try:
        raw = value.split('.', 1)[1]
        bundle = json.loads(base64.urlsafe_b64decode(raw + '=' * (-len(raw) % 4)))
        assert bundle['v'] == 1
        assert len(bytes.fromhex(bundle['pin'])) == 32
        assert len(bytes.fromhex(bundle['slot'])) == 16
        assert 32 <= len(bundle['secret']) <= 128
        return bundle
    except (ValueError, KeyError, AssertionError, TypeError) as error:
        raise ValueError('Invalid gateway access key') from error


def gateway_url(address: str):
    parsed = urlsplit(address if '://' in address else 'https://' + address)
    if parsed.scheme != 'https' or parsed.username or parsed.password or parsed.path not in ('', '/') or parsed.query or parsed.fragment:
        raise ValueError('Enter the VPS IP, optionally followed by :port')
    host = ipaddress.ip_address(parsed.hostname)
    authority = f'[{host}]' if host.version == 6 else str(host)
    return f'https://{authority}:{parsed.port or 80}'


def discover_direct_url(local_port: int) -> str:
    """Reuse an existing Tailscale Serve mapping; never guess or alter its routes."""
    import shutil
    executable = shutil.which('tailscale') or next((p for p in (
        '/usr/local/bin/tailscale', '/opt/homebrew/bin/tailscale',
        '/Applications/Tailscale.app/Contents/MacOS/Tailscale') if Path(p).is_file()), None)
    if not executable:
        return ''
    try:
        result = subprocess.run([executable, 'serve', 'status', '--json'], capture_output=True,
                                text=True, check=True, timeout=3)
        serve = json.loads(result.stdout)
        target = f'http://127.0.0.1:{local_port}'
        for authority, site in serve.get('Web', {}).items():
            if site.get('Handlers', {}).get('/', {}).get('Proxy', '').rstrip('/') == target:
                port = authority.rsplit(':', 1)[-1]
                if serve.get('TCP', {}).get(port, {}).get('HTTPS'):
                    return 'https://' + authority
    except (OSError, subprocess.SubprocessError, ValueError, TypeError):
        pass
    return ''


def pairing_route_binding(workspace: Path) -> str:
    try:
        config = json.loads((workspace / 'gateway-client.json').read_text())
        if config.get('mode') == 'direct':
            relay, pin = '', ''
        else:
            key = decode_key(config['key'])
            relay = config['gateway'].rstrip('/') + '/relay/' + key['slot']
            pin = key['pin']
        direct = '' if config.get('mode') == 'relay' else config.get('direct', '')
        return hashlib.sha256(('\n'.join([direct, relay, pin])).encode()).hexdigest()
    except (OSError, ValueError, KeyError, TypeError):
        return ''
