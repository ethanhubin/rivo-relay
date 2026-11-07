"""Stage phone image bytes on the selected native host before turn RPCs.

QiGuang's composer limits are ten images, 20 MiB per image and 40 MiB in
aggregate. Decoded bytes stay bounded; native RPCs carry small localImage paths.
No Relay-local file path is ever substituted for a native-host path.
"""
from __future__ import annotations

import base64
import binascii
import io
import ntpath
import posixpath
import warnings

from PIL import Image

from .connection import CodexError
from .files import fs_request, upload_attachment_stream

MAX_IMAGE_BYTES = 20 * 1024 * 1024
MAX_TOTAL_IMAGE_BYTES = 40 * 1024 * 1024
MAX_IMAGES = 10
_FORMATS = {'image/jpeg': ('JPEG', '.jpg'), 'image/png': ('PNG', '.png'), 'image/webp': ('WEBP', '.webp')}


def needs_image_staging(inputs):
    return any(item.get('type') == 'image' and isinstance(item.get('url'), str)
               and item['url'].startswith('data:') for item in inputs)


def prepare_image_inputs(connection, cwd, inputs):
    """Validate the full batch before uploading. Preserve input order and callers."""
    if sum(item.get('type') in ('image', 'localImage') for item in inputs) > MAX_IMAGES:
        raise CodexError('最多添加 10 张图片', code='image_count_limit', http_status=400)
    staged = {}
    total = 0
    for index, item in enumerate(inputs):
        if item.get('type') != 'image' or not isinstance(item.get('url'), str) or not item['url'].startswith('data:'):
            continue
        header, separator, encoded = item['url'].partition(',')
        mime = header.removeprefix('data:').removesuffix(';base64')
        if not separator or mime not in _FORMATS or header != 'data:' + mime + ';base64':
            raise CodexError('仅支持 JPEG、PNG、WebP 图片', code='invalid_image', http_status=415)
        if len(encoded) > ((MAX_IMAGE_BYTES + 2) // 3) * 4:
            raise CodexError('每张图片不能超过 20 MiB', code='image_too_large', http_status=413)
        try:
            raw = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError) as error:
            raise CodexError('图片编码无效', code='invalid_image', http_status=400) from error
        if not raw or len(raw) > MAX_IMAGE_BYTES:
            raise CodexError('每张图片须为 1 字节至 20 MiB', code='image_too_large', http_status=413)
        total += len(raw)
        if total > MAX_TOTAL_IMAGE_BYTES:
            raise CodexError('本轮图片合计不能超过 40 MiB', code='images_too_large', http_status=413)
        try:
            with warnings.catch_warnings():
                warnings.simplefilter('error', Image.DecompressionBombWarning)
                with Image.open(io.BytesIO(raw)) as image:
                    if image.format != _FORMATS[mime][0] or image.width * image.height > 80_000_000:
                        raise ValueError('image format or dimensions do not match')
                    image.verify()
        except (OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
            raise CodexError('图片内容与格式不符或无法解码', code='invalid_image', http_status=415) from error
        staged[index] = (raw, _FORMATS[mime][1])
    result = [dict(item) for item in inputs]
    uploaded = []
    try:
        for index, (raw, suffix) in staged.items():
            upload = upload_attachment_stream(connection, cwd, 'image' + suffix, io.BytesIO(raw), len(raw))
            uploaded.append(upload['path'])
            result[index] = {'type': 'localImage', 'path': upload['path']}
    except Exception:
        # Only newly generated upload directories belong to this failed attempt.
        for path in uploaded:
            paths = ntpath if ntpath.splitdrive(path)[0] or path.startswith('\\\\') else posixpath
            try:
                fs_request(connection, 'fs/remove', {'path': paths.dirname(path), 'recursive': True})
            except Exception:
                pass
        raise
    return result
