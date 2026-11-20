"""Bounded static SVG thumbnails; original download bytes remain untouched."""
import base64
import binascii
import io
import math
import re
import xml.etree.ElementTree as ET

from PIL import Image

from .codex.connection import CodexError

MAX_SVG_BYTES = 20 * 1024 * 1024
MAX_ELEMENTS = 10000
MAX_DEPTH = 64
_SVG = 'http://www.w3.org/2000/svg'
_BLOCKED = {'script', 'foreignObject', 'animate', 'animateMotion', 'animateTransform', 'set', 'discard'}
_RASTER_DATA = {'image/png': 'PNG', 'image/jpeg': 'JPEG', 'image/webp': 'WEBP',
                'image/gif': 'GIF', 'image/avif': 'AVIF', 'image/bmp': 'BMP', 'image/x-icon': 'ICO'}
_URL = re.compile(r'url\s*\(\s*(?:"([^"]*)"|\'([^\']*)\'|([^)]*))\s*\)', re.I)


def _error(message='SVG 图片无效或超出预览限制'):
    return CodexError(message, code='invalid_media', http_status=415)


def _parse(data):
    if not isinstance(data, bytes) or not 0 < len(data) <= MAX_SVG_BYTES:
        raise _error()
    try:
        text = data.decode('utf-8-sig')
        # Reject declarations before the XML parser sees any entity expansion.
        if re.search(r'<!\s*(?:DOCTYPE|ENTITY)\b', text, re.I):
            raise ValueError()
        root = ET.fromstring(text)
        if root.tag not in ('svg', '{' + _SVG + '}svg'):
            raise ValueError()
        stack, count = [(root, 1)], 0
        while stack:
            element, depth = stack.pop()
            count += 1
            if count > MAX_ELEMENTS or depth > MAX_DEPTH:
                raise ValueError()
            stack.extend((child, depth + 1) for child in element)
        return root
    except (UnicodeError, ET.ParseError, ValueError) as error:
        raise _error() from error


def is_svg(data):
    """Recognize an inert downloadable SVG without rendering or resolving paths."""
    try:
        _parse(data)
        return True
    except CodexError:
        return False


def _safe_reference(value, embedded):
    value = value.strip()
    if re.fullmatch(r'#[^\s\'"()<>\\]+', value):
        return True
    if not value.startswith('data:'):
        return False
    header, separator, encoded = value.partition(',')
    mime = header.removeprefix('data:').removesuffix(';base64')
    # Nested SVG data could contain another set of resource references. Keep
    # embedded rasters, which cannot load files or execute script themselves.
    if not separator or mime not in _RASTER_DATA or header != 'data:' + mime + ';base64':
        return False
    try:
        raw = base64.b64decode(encoded, validate=True)
        embedded['bytes'] += len(raw)
        if not raw or embedded['bytes'] > MAX_SVG_BYTES:
            raise _error()
        with Image.open(io.BytesIO(raw)) as image:
            if image.format != _RASTER_DATA[mime]:
                return False
            embedded['pixels'] += image.width * image.height
            if embedded['pixels'] > 80_000_000:
                raise _error()
            image.verify()
        return True
    except (ValueError, binascii.Error, OSError, Image.DecompressionBombError):
        return False


def _style(value, embedded):
    # CSS escapes can disguise imports or URL tokens; don't send such CSS to
    # the native renderer. Comments are removed before checking token spelling.
    if '\\' in value:
        return ''
    value = re.sub(r'/\*.*?\*/', '', value, flags=re.S)
    if re.search(r'@(?:import|namespace)\b', value, re.I):
        return ''
    def replace(match):
        reference = next(part for part in match.groups() if part is not None).strip()
        return match.group() if _safe_reference(reference, embedded) else 'none'
    result = _URL.sub(replace, value)
    # Any unmatched URL function is malformed; never let a second parser give
    # it a different interpretation that could expose a native host path.
    if re.search(r'url\s*\(', _URL.sub('', result), re.I):
        return ''
    return result


def _sanitize(root):
    embedded = {'bytes': 0, 'pixels': 0}
    for parent in root.iter():
        for child in list(parent):
            local = child.tag.rsplit('}', 1)[-1]
            if local in _BLOCKED or (child.tag.startswith('{') and not child.tag.startswith('{' + _SVG + '}')):
                parent.remove(child)
        for key, value in list(parent.attrib.items()):
            local = key.rsplit('}', 1)[-1]
            if local.lower().startswith('on') or local == 'base':
                del parent.attrib[key]
            elif local in ('href', 'src'):
                if not _safe_reference(value, embedded):
                    del parent.attrib[key]
            else:
                parent.attrib[key] = _style(value, embedded)
        if parent.tag.rsplit('}', 1)[-1] == 'style':
            parent.text = _style(parent.text or '', embedded)
    return root


def _size(root, maximum):
    dimensions = None
    values = []
    units = {'': 1, 'px': 1, 'pt': 96 / 72, 'pc': 16, 'in': 96, 'cm': 96 / 2.54, 'mm': 96 / 25.4}
    for key in ('width', 'height'):
        match = re.fullmatch(r'\s*([0-9]+(?:\.[0-9]+)?)(px|pt|pc|in|cm|mm)?\s*', root.get(key, ''))
        values.append(float(match[1]) * units[match[2] or ''] if match else 0)
    if all(math.isfinite(value) and value > 0 for value in values):
        dimensions = values
    viewbox = root.get('viewBox')
    if dimensions is None and viewbox:
        try:
            x, y, width, height = map(float, re.split(r'[\s,]+', viewbox.strip()))
            if all(math.isfinite(v) for v in (x, y, width, height)) and width > 0 and height > 0:
                dimensions = (width, height)
        except ValueError:
            pass
    width, height = dimensions or (300, 150)
    scale = maximum / max(width, height)
    return max(1, round(width * scale)), max(1, round(height * scale))


def thumbnail_png(data, *, max_size=512):
    if type(max_size) is not int or not 1 <= max_size <= 512:
        raise _error()
    root = _sanitize(_parse(data))
    width, height = _size(root, max_size)
    try:
        import resvg_py
    except ImportError as error:
        raise CodexError('SVG 预览组件暂不可用', code='media_preview_unavailable', http_status=503) from error
    try:
        # No svg_path, resources_dir, font_files or font_dirs supplied by input.
        # ElementTree drops XML stylesheet PIs; all URI-bearing SVG/CSS values
        # have already been limited to internal fragments and safe data images.
        result = resvg_py.svg_to_bytes(svg_string=ET.tostring(root, encoding='unicode'),
                                      width=width, height=height, dpi=96.0)
        with Image.open(io.BytesIO(result)) as image:
            if image.format != 'PNG' or not 0 < image.width <= 512 or not 0 < image.height <= 512:
                raise ValueError()
            image.verify()
        return result
    except (OSError, ValueError, RuntimeError, TypeError) as error:
        raise _error('SVG 图片无法生成静态预览') from error
