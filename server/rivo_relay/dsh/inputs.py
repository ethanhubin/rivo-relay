"""Translate existing APP input into DSH's native content blocks."""
import base64
import binascii
import io
import warnings

from PIL import Image

from ..codex.connection import CodexError

MAX_IMAGE_BYTES = 20 * 1024 * 1024
MAX_TOTAL_IMAGE_BYTES = 40 * 1024 * 1024
_FORMATS = {'image/jpeg': 'JPEG', 'image/png': 'PNG', 'image/webp': 'WEBP'}


def content(text, items=None):
    """Validate the entire batch before any file upload or prompt dispatch."""
    if items is None:
        items = [{'type': 'text', 'text': text}]
    if not isinstance(items, list) or not 1 <= len(items) <= 64:
        raise CodexError('请输入消息或添加附件', code='invalid_request', http_status=400)
    result, image_count, image_bytes = [], 0, 0
    for item in items:
        if not isinstance(item, dict):
            raise CodexError('输入格式无效', code='invalid_request', http_status=400)
        kind = item.get('type')
        if kind == 'text':
            value = item.get('text')
            if not isinstance(value, str) or not value.strip() or len(value) > 200000:
                raise CodexError('消息文本无效', code='invalid_request', http_status=400)
            result.append({'type': 'text', 'text': value})
        elif kind == 'mention':
            if not isinstance(item.get('path'), str) or not item['path']:
                raise CodexError('附件引用无效', code='invalid_attachment', http_status=400)
            # Replaced with the native receipt only after full input validation.
            result.append({'type': 'file', '_source': item['path']})
        elif kind == 'image':
            url = item.get('url')
            if not isinstance(url, str):
                raise CodexError('DSH 图片需要手机图片数据', code='invalid_image', http_status=400)
            header, separator, encoded = url.partition(',')
            mime = header.removeprefix('data:').removesuffix(';base64')
            if not separator or mime not in _FORMATS or header != 'data:' + mime + ';base64':
                raise CodexError('仅支持 JPEG、PNG、WebP 图片数据', code='invalid_image', http_status=415)
            if len(encoded) > ((MAX_IMAGE_BYTES + 2) // 3) * 4:
                raise CodexError('每张图片不能超过 20 MiB', code='image_too_large', http_status=413)
            try:
                raw = base64.b64decode(encoded, validate=True)
            except (ValueError, binascii.Error) as error:
                raise CodexError('图片编码无效', code='invalid_image', http_status=400) from error
            image_count += 1
            image_bytes += len(raw)
            if not raw or len(raw) > MAX_IMAGE_BYTES or image_count > 10 or image_bytes > MAX_TOTAL_IMAGE_BYTES:
                raise CodexError('最多 10 张图片，每张 20 MiB、合计 40 MiB', code='image_too_large', http_status=413)
            try:
                with warnings.catch_warnings():
                    warnings.simplefilter('error', Image.DecompressionBombWarning)
                    with Image.open(io.BytesIO(raw)) as image:
                        if image.format != _FORMATS[mime] or image.width * image.height > 80_000_000:
                            raise ValueError()
                        image.verify()
            except (OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
                raise CodexError('图片内容与格式不符或无法解码', code='invalid_image', http_status=415) from error
            result.append({'type': 'image', 'mediaType': mime, 'data': encoded})
        else:
            raise CodexError('此 DSH 输入类型暂不支持', code='runtime_unsupported', http_status=409)
    return result
