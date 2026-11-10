"""Short-lived, speech-only device grants signed by the paired node."""
import base64
import hashlib
import hmac
import json
import secrets
import time

from .config import decode_key
from ..media import MediaError

TTL = 180
PREFIX = b'rivo-speech-v1\0'


def issue(workspace, device, *, now=None):
    try:
        key = decode_key(json.loads((workspace / 'gateway-client.json').read_text())['key'])
    except (OSError, ValueError, KeyError, TypeError):
        raise MediaError('语音直连需要已配置的 VPS', code='speech_unavailable', http_status=503) from None
    issued = int(time.time() if now is None else now)
    claims = {'slot': key['slot'], 'device': device, 'iat': issued, 'exp': issued + TTL,
              'nonce': secrets.token_hex(16)}
    payload = base64.urlsafe_b64encode(json.dumps(claims, separators=(',', ':')).encode()).decode().rstrip('=')
    signing_key = hashlib.sha256(key['secret'].encode()).digest()
    signature = hmac.new(signing_key, PREFIX + payload.encode(), hashlib.sha256).hexdigest()
    return {'token': 'speech1.' + payload + '.' + signature, 'expiresAt': claims['exp']}


def verify(token, slot, keys, *, now=None):
    try:
        if not isinstance(token, str) or len(token) > 2048:
            raise ValueError()
        version, payload, signature = token.split('.')
        key = keys[slot]
        expected = hmac.new(bytes.fromhex(key), PREFIX + payload.encode(), hashlib.sha256).hexdigest()
        if version != 'speech1' or not hmac.compare_digest(signature, expected):
            raise ValueError()
        claims = json.loads(base64.b64decode(payload + '=' * (-len(payload) % 4), altchars=b'-_', validate=True))
        current = time.time() if now is None else now
        if (claims['slot'] != slot or type(claims['iat']) is not int or type(claims['exp']) is not int
                or not claims['iat'] <= current + 5 or not current < claims['exp']
                or claims['exp'] - claims['iat'] != TTL
                or not isinstance(claims['device'], str) or not 1 <= len(claims['device']) <= 128
                or not isinstance(claims['nonce'], str) or len(claims['nonce']) != 32):
            raise ValueError()
        # Namespace prevents collision with node-authenticated shared streams.
        return 'device:' + slot + ':' + hashlib.sha256(token.encode()).hexdigest()
    except (ValueError, KeyError, TypeError, UnicodeError):
        raise ValueError('Invalid or expired speech authorization') from None
