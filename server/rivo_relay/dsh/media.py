"""References to images in authoritative DSH events, never arbitrary host paths."""
import base64
import binascii
import hashlib
import re
from urllib.parse import quote, unquote, urlsplit

from ..codex.connection import CodexError
from ..codex.native_download import byte_range

HOST = 'dsh-attachment.invalid'
MAX_IMAGE_BYTES = 20 * 1024 * 1024
_EXTENSIONS = {'png', 'jpg', 'jpeg', 'webp', 'gif', 'avif', 'bmp', 'ico', 'svg'}
_MIMES = {'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp',
          'image/gif': 'gif', 'image/avif': 'avif', 'image/bmp': 'bmp',
          'image/x-icon': 'ico', 'image/vnd.microsoft.icon': 'ico', 'image/svg+xml': 'svg'}


def source_url(session_id, seq, block, image=0, *, media_type=None, name=None):
    extension = _MIMES.get(media_type)
    if extension is None and isinstance(name, str):
        candidate = name.split('?', 1)[0].rsplit('.', 1)[-1].lower()
        if candidate in _EXTENSIONS:
            extension = candidate
    return (f'https://{HOST}/v1/{quote(session_id, safe="")}/{seq}/{block}/{image}/'
            f'image.{extension or "png"}')


def parse_source(source):
    try:
        parsed = urlsplit(source)
        parts = parsed.path.split('/')
        if (not isinstance(source, str) or len(source) > 8192 or parsed.scheme != 'https'
                or parsed.netloc != HOST or parsed.query or parsed.fragment
                or source != 'https://' + HOST + parsed.path
                or len(parts) != 7 or parts[:2] != ['', 'v1']):
            raise ValueError()
        session_id = unquote(parts[2], errors='strict')
        if (not session_id or len(session_id) > 512 or any(ord(c) < 32 for c in session_id)
                or quote(session_id, safe='') != parts[2]
                or not all(re.fullmatch(r'0|[1-9][0-9]*', value) for value in parts[3:6])
                or parts[6] not in {'image.' + ext for ext in _EXTENSIONS}):
            raise ValueError()
        numbers = [int(value) for value in parts[3:6]]
        if any(value > 2 ** 53 - 1 for value in numbers):
            raise ValueError()
        return {'sessionId': session_id, 'eventSeq': numbers[0], 'blockIndex': numbers[1],
                'imageIndex': numbers[2]}
    except (TypeError, ValueError, UnicodeError, AttributeError) as error:
        raise CodexError('DSH 图片来源无效', code='invalid_media_source', http_status=400) from error


def is_media_source(source):
    try:
        parse_source(source)
        return True
    except CodexError:
        return False


def download(result, *, generation, range_header=None, if_range=None):
    """Validate one immutable native image before exposing the shared download API."""
    try:
        encoded, size, digest = result['data'], result['size'], result['sha256']
        if (result.get('generation') != generation or not isinstance(encoded, str)
                or type(size) is not int or not 0 < size <= MAX_IMAGE_BYTES
                or len(encoded) > ((MAX_IMAGE_BYTES + 2) // 3) * 4
                or not isinstance(digest, str) or not re.fullmatch('[a-f0-9]{64}', digest)):
            raise ValueError()
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) != size or hashlib.sha256(raw).hexdigest() != digest:
            raise ValueError()
    except (TypeError, KeyError, ValueError, binascii.Error) as error:
        raise CodexError('DSH 图片响应不完整或已变化', code='dsh_protocol') from error
    etag = '"sha256-' + digest + '"'
    effective_range = None if if_range is not None and if_range != etag else range_header
    try:
        start, end, partial = byte_range(effective_range, size)
    except CodexError as error:
        error.range_size, error.range_etag = size, etag
        raise
    return {'size': size, 'start': start, 'end': end, 'partial': partial, 'etag': etag,
            'mediaType': result.get('mediaType'), 'name': result.get('name'),
            'chunks': (raw[offset:min(end + 1, offset + 1024 * 1024)]
                       for offset in range(start, end + 1, 1024 * 1024))}
