"""Codex media transport and durable, identity-bound generated-image provenance.

Adapted from QiGuang's media proxy, bounded WebP profile and image ingest.
Native paths are always read by the connected daemon. Relay-owned copies are
never passed to that daemon as though they were native paths.
"""
from __future__ import annotations

import base64
from collections import OrderedDict
from contextlib import nullcontext, contextmanager, ExitStack
import hashlib
import http.client
import ipaddress
import json
import os
import re
import socket
import ssl
import threading
import time
import urllib.parse
import uuid
import warnings
from pathlib import Path

from PIL import Image, ImageOps

from .codex.connection import CodexError
from .operation_gates import ThreadGates

IMAGE_LIMIT = 64 * 1024 * 1024
PROXY_IMAGE_LIMIT = 40 * 1024 * 1024
MEDIA_LIMIT = 1024 * 1024 * 1024
THUMBNAIL_PROFILE = 'max-512-webp-q78-v3'
_FORMATS = {'PNG': ('image/png', '.png'), 'JPEG': ('image/jpeg', '.jpg'),
            'WEBP': ('image/webp', '.webp'), 'GIF': ('image/gif', '.gif'), 'AVIF': ('image/avif', '.avif')}


class MediaError(CodexError):
    def __init__(self, message, *, code='media_unavailable', http_status=503):
        super().__init__(message, code=code, http_status=http_status)


def atomic_json(path, value):
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with temporary.open('x', encoding='utf-8') as stream:
            json.dump(value, stream, ensure_ascii=False, allow_nan=False)
            stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def read_record(path):
    if not path.is_file() or path.stat().st_size > 92 * 1024 * 1024:
        return None
    try:
        value = json.loads(path.read_text())
        return value if isinstance(value, dict) else None
    except (OSError, ValueError):
        return None


def public_target(url):
    """Resolve once and pin the validated address; redirects repeat this check."""
    if not isinstance(url, str) or not 1 <= len(url) <= 8192 or any(ord(c) < 32 for c in url):
        raise MediaError('媒体地址无效', code='invalid_media_source', http_status=400)
    parsed = urllib.parse.urlsplit(url)
    host = (parsed.hostname or '').rstrip('.').lower()
    if parsed.scheme not in {'https', 'http'} or not host or parsed.username is not None or parsed.password is not None:
        raise MediaError('只允许不含账号信息的公开 HTTP(S) 地址', code='invalid_media_source', http_status=400)
    if host == 'localhost' or host.endswith(('.local', '.internal', '.lan')):
        raise MediaError('不能代理内网媒体地址', code='private_media_source', http_status=400)
    try:
        port = parsed.port or (443 if parsed.scheme == 'https' else 80)
        answers = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
        addresses = list(dict.fromkeys(answer[4][0] for answer in answers))
        def allowed(address):
            value = ipaddress.ip_address(address)
            if not value.is_global or value.is_multicast or value.is_reserved:
                return False
            # IPv6 transition prefixes can tunnel a nominally public destination
            # to a private IPv4 endpoint behind the proxy host's translator.
            if value.version == 6 and any(value in ipaddress.ip_network(prefix) for prefix in
                    ('64:ff9b::/96', '64:ff9b:1::/48', '2001::/32', '2002::/16')):
                return False
            return True
        if not addresses or any(not allowed(address) for address in addresses):
            raise ValueError('non-public address')
    except (ValueError, OSError) as error:
        raise MediaError('媒体地址不是可访问的公开地址', code='private_media_source', http_status=400) from error
    return parsed, host, port, addresses


class _PinnedHTTP(http.client.HTTPConnection):
    def __init__(self, host, port, addresses, secure):
        super().__init__(host, port, timeout=30)
        self.addresses, self.secure = addresses, secure

    def connect(self):
        last = None
        for address in self.addresses:
            try:
                sock = socket.create_connection((address, self.port), timeout=self.timeout)
                self.sock = ssl.create_default_context().wrap_socket(sock, server_hostname=self.host) if self.secure else sock
                return
            except OSError as error:
                last = error
                if 'sock' in locals(): sock.close()
        raise last or OSError('connection unavailable')


def public_media(url, target):
    """No node token/cookie is forwarded; validate redirects and bound the body."""
    for redirect in range(6):
        parsed, host, port, addresses = public_target(url)
        connection = _PinnedHTTP(host, port, addresses, parsed.scheme == 'https')
        try:
            path = urllib.parse.urlunsplit(('', '', parsed.path or '/', parsed.query, ''))
            connection.request('GET', path, headers={'Accept': 'image/*,audio/*,video/*',
                'Accept-Encoding': 'identity', 'User-Agent': 'RivoRelay-Media/1'})
            response = connection.getresponse()
            if response.status in (301, 302, 303, 307, 308):
                location = response.getheader('Location')
                if not location or redirect == 5:
                    raise MediaError('媒体重定向过多', code='media_redirect', http_status=502)
                url = urllib.parse.urljoin(url, location)
                continue
            if response.status != 200:
                raise MediaError('媒体来源返回错误', code='media_upstream', http_status=502)
            mime = (response.getheader('Content-Type') or '').split(';')[0].strip().lower()
            limit = PROXY_IMAGE_LIMIT if mime.startswith('image/') else MEDIA_LIMIT
            declared = response.getheader('Content-Length')
            if declared is not None and (not declared.isdigit() or int(declared) > limit):
                raise MediaError('媒体超过传输大小限制', code='media_too_large', http_status=413)
            if response.getheader('Content-Encoding') not in (None, '', 'identity'):
                raise MediaError('媒体编码不支持', code='media_encoding', http_status=415)
            count = 0
            with target.open('wb') as stream:
                while True:
                    block = response.read(65536)
                    if not block: break
                    count += len(block)
                    if count > limit:
                        raise MediaError('媒体超过传输大小限制', code='media_too_large', http_status=413)
                    stream.write(block)
                stream.flush(); os.fsync(stream.fileno())
            if count == 0 or (declared is not None and count != int(declared)):
                raise MediaError('媒体下载不完整', code='media_incomplete', http_status=502)
            return mime
        finally:
            connection.close()
    raise MediaError('媒体重定向过多', http_status=502)


def media_type(path, declared=''):
    with path.open('rb') as stream:
        prefix = stream.read(64)
    if prefix.startswith((b'\x89PNG', b'\xff\xd8', b'GIF87a', b'GIF89a')) or prefix[8:12] == b'WEBP' or prefix[4:12] in (b'ftypavif', b'ftypavis'):
        if path.stat().st_size > IMAGE_LIMIT:
            raise MediaError('图片超过 64 MiB', code='media_too_large', http_status=413)
        try:
            with warnings.catch_warnings():
                warnings.simplefilter('error', Image.DecompressionBombWarning)
                with Image.open(path) as image:
                    if image.width * image.height > 80_000_000:
                        raise ValueError('pixel limit')
                    result = _FORMATS.get(image.format)
                    image.verify()
                if result: return result
        except (OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
            raise MediaError('图片内容无法解码', code='invalid_media', http_status=415) from error
    # Active formats such as SVG/HTML never enter image or media previews.
    if prefix.startswith(b'OggS'): return ('video/ogg', '.ogv') if declared.startswith('video/') else ('audio/ogg', '.ogg')
    if prefix.startswith(b'fLaC'): return 'audio/flac', '.flac'
    if prefix.startswith(b'ID3') or (len(prefix) >= 2 and prefix[0] == 255 and prefix[1] & 0xe0 == 0xe0): return 'audio/mpeg', '.mp3'
    if prefix[:4] == b'RIFF' and prefix[8:12] == b'WAVE': return 'audio/wav', '.wav'
    if prefix[:4] == b'RIFF' and prefix[8:12] == b'AVI ': return 'video/x-msvideo', '.avi'
    if prefix[4:8] == b'ftyp': return ('audio/mp4', '.m4a') if declared.startswith('audio/') else ('video/mp4', '.mp4')
    if prefix.startswith(b'\x1a\x45\xdf\xa3'): return ('audio/webm', '.webm') if declared.startswith('audio/') else ('video/webm', '.webm')
    raise MediaError('此内容不是支持的图片、音频或视频', code='invalid_media', http_status=415)


class MediaService:
    def __init__(self, workspace, codex, *, fetch_public=public_media):
        self.codex, self.fetch_public = codex, fetch_public
        self.cache = Path(workspace) / 'cache' / 'codex-media'
        self.library = Path(workspace) / 'data' / 'codex-media'
        for path in (self.cache, self.library): path.mkdir(parents=True, exist_ok=True, mode=0o700)
        self._lock = threading.RLock()
        self._cache_lock = threading.RLock()
        self._resources = ThreadGates()
        self._pins = {}
        self._last_prune = float('-inf')
        self._verified_media = OrderedDict()
        self._record_cache = {}
        self._library_stamp = None
        self._library_scan_at = 0
        self._library_values = []
        self._library_threads = {}
        self._ingest_lock = threading.Lock()
        self._slots = threading.BoundedSemaphore(2)
        self._wake, self._stop = threading.Event(), threading.Event()
        self._worker = threading.Thread(target=self._run, daemon=True, name='rivo-image-ingest')
        self._worker.start()

    def close(self):
        self._stop.set(); self._wake.set(); self._worker.join(timeout=3)

    def _atomic_bytes(self, target, source):
        temporary = target.with_name(target.name + '.' + uuid.uuid4().hex + '.tmp')
        try:
            with (nullcontext(source) if hasattr(source, 'read') else source.open('rb')) as incoming, temporary.open('xb') as outgoing:
                while block := incoming.read(65536): outgoing.write(block)
                outgoing.flush(); os.fsync(outgoing.fileno())
            os.replace(temporary, target)
        finally: temporary.unlink(missing_ok=True)

    @contextmanager
    def _pin(self, *paths):
        with self._cache_lock:
            for path in paths: self._pins[path] = self._pins.get(path, 0) + 1
        try:
            yield
        finally:
            with self._cache_lock:
                for path in paths:
                    self._pins[path] -= 1
                    if not self._pins[path]: self._pins.pop(path)

    def _prune(self, protected=(), *, force=False):
        from stat import S_ISREG
        with self._cache_lock:
            now = time.time()
            if not force and now - self._last_prune < 5: return
            self._last_prune = now
            files = []
            for path in self.cache.iterdir():
                try:
                    stat = path.stat()
                except FileNotFoundError:
                    # A concurrent download/thumbnail can finish or remove its
                    # temporary file between directory enumeration and stat.
                    continue
                if S_ISREG(stat.st_mode):
                    files.append((path, stat))
            files.sort(key=lambda entry: entry[1].st_mtime)
            total = sum(stat.st_size for _, stat in files)
            for path, stat in files:
                if path in protected or path in self._pins: continue
                if now - stat.st_mtime > 86400 or total > 2 * MEDIA_LIMIT:
                    path.unlink(missing_ok=True); total -= stat.st_size

    def resolve(self, source, *, thumbnail=False, _open=False):
        if not isinstance(source, str) or not source or len(source) > 8192 or '\0' in source:
            raise MediaError('媒体来源无效', code='invalid_media_source', http_status=400)
        native = not source.startswith(('http://', 'https://'))
        generation = str(getattr(getattr(self.codex, 'connection', None), 'generation', ''))
        key = hashlib.sha256(('native:' + generation + source if native else source).encode()).hexdigest()
        # Coalesce one resource, not all resources. A slow origin cannot hold
        # the publication lock or delay an unrelated cached representation.
        with self._resources.hold(key), ExitStack() as leases:
            manifest = self.cache / (key + '.json')
            leases.enter_context(self._pin(manifest))
            cached = read_record(manifest)
            download = self.codex.prepare_download(source) if native else None
            valid = cached and (cached.get('etag') == download['etag'] if native else time.time() - cached.get('time', 0) < 3600)
            original = self.cache / str(cached.get('file', 'missing')) if valid else None
            with self._cache_lock:
                if original is not None and original.is_file():
                    leases.enter_context(self._pin(original))
                else: original = None
            if original is None:
                if not self._slots.acquire(False):
                    raise MediaError('媒体处理繁忙', code='media_busy', http_status=429)
                temporary = self.cache / (uuid.uuid4().hex + '.tmp')
                try:
                    with self._pin(temporary):
                        self._prune(force=True)
                        declared = ''
                        if native:
                            if download['size'] > MEDIA_LIMIT: raise MediaError('媒体过大', code='media_too_large', http_status=413)
                            with temporary.open('xb') as output:
                                for chunk in download['chunks']: output.write(chunk)
                                output.flush(); os.fsync(output.fileno())
                            if temporary.stat().st_size != download['size']: raise MediaError('媒体读取不完整', code='media_incomplete')
                        else: declared = self.fetch_public(source, temporary)
                        mime, suffix = media_type(temporary, declared)
                        if not native and mime.startswith('image/') and temporary.stat().st_size > PROXY_IMAGE_LIMIT:
                            raise MediaError('公开图片超过 40 MiB', code='media_too_large', http_status=413)
                        with temporary.open('rb') as stream: digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                        original = self.cache / (digest + suffix)
                        cached = {'file': original.name, 'mime': mime, 'etag': download['etag'] if native else '"sha256-' + digest + '"', 'time': time.time(), 'sha256': digest}
                        with self._cache_lock:
                            if not original.exists(): os.replace(temporary, original)
                            leases.enter_context(self._pin(original))
                            atomic_json(manifest, cached)
                finally:
                    temporary.unlink(missing_ok=True)
                    self._slots.release()
            original.touch()
            result = self._thumbnail(original, cached['sha256'], leases=leases) if thumbnail else (original, cached['mime'], '"sha256-' + cached['sha256'] + '"')
            self._prune(protected=(original, result[0], manifest))
            with self._cache_lock:
                return (result[0].open('rb'), result[1], result[2]) if _open else result

    def open_source(self, source, *, thumbnail=False):
        # Acquire the immutable representation before pruning can unlink it.
        # The HTTP response owns this descriptor and uses fstat, never reopens a
        # pathname after publishing its ETag/range headers.
        return self.resolve(source, thumbnail=thumbnail, _open=True)

    def open_stored(self, identity, *, thumbnail=False):
        with self._lock:
            record = self._record(identity)
            if record.get('status') != 'ready': raise MediaError('媒体正在登记', code='media_pending', http_status=409)
            path = self.library / record['file']
            if not path.is_file() or path.is_symlink(): raise MediaError('媒体已移除', code='media_not_found', http_status=404)
            stream = path.open('rb')
        try:
            stat = os.fstat(stream.fileno())
            fingerprint = (stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns, record['sha256'])
            with self._lock: verified = self._verified_media.get(identity) == fingerprint
            if not verified:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                after = os.fstat(stream.fileno())
                if digest != record['sha256'] or (after.st_size, after.st_mtime_ns, after.st_ctime_ns) != (stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns):
                    raise MediaError('媒体已变化', code='media_changed', http_status=409)
                with self._lock:
                    self._verified_media[identity] = fingerprint
                    self._verified_media.move_to_end(identity)
                    while len(self._verified_media) > 512: self._verified_media.popitem(last=False)
            stream.seek(0)
            if not thumbnail:
                return stream, record['mime'], '"sha256-' + record['sha256'] + '"'
            with ExitStack() as leases:
                target, mime, etag = self._thumbnail(stream, record['sha256'], leases=leases)
                with self._cache_lock: result = target.open('rb'), mime, etag
            stream.close()
            return result
        except BaseException:
            stream.close()
            raise

    def _thumbnail(self, original, digest, *, leases=None):
        target = self.cache / (digest + '-' + THUMBNAIL_PROFILE + '.webp')
        with self._resources.hold('thumbnail:' + digest), self._pin(target):
            if not target.is_file():
                if not self._slots.acquire(False):
                    raise MediaError('媒体处理繁忙', code='media_busy', http_status=429)
                temporary = self.cache / (uuid.uuid4().hex + '.tmp')
                try:
                    with self._pin(temporary), warnings.catch_warnings():
                        warnings.simplefilter('error', Image.DecompressionBombWarning)
                        with Image.open(original) as opened:
                            if opened.width * opened.height > 80_000_000: raise ValueError('pixel limit')
                            image = ImageOps.exif_transpose(opened)
                            image.thumbnail((512, 512), Image.Resampling.LANCZOS)
                            if image.mode not in ('RGB', 'RGBA'): image = image.convert('RGBA' if 'transparency' in image.info else 'RGB')
                            image.save(temporary, format='WEBP', quality=78, method=4)
                        with temporary.open('rb') as stream: os.fsync(stream.fileno())
                        with self._cache_lock: os.replace(temporary, target)
                except (OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
                    raise MediaError('此媒体无法生成图片预览', code='invalid_thumbnail', http_status=415) from error
                finally:
                    temporary.unlink(missing_ok=True)
                    self._slots.release()
            with target.open('rb') as stream: etag = '"sha256-' + hashlib.file_digest(stream, 'sha256').hexdigest() + '"'
            target.touch()
            if leases is not None: leases.enter_context(self._pin(target))
            return target, 'image/webp', etag

    @staticmethod
    def _identity(thread_id, turn_id, item_id):
        return hashlib.sha256(json.dumps([thread_id, turn_id, item_id], separators=(',', ':')).encode()).hexdigest()

    def observe_event(self, event):
        if event.get('method') != 'item/completed': return
        params = event.get('params') or {}
        self._enqueue(params.get('threadId'), params.get('turnId'), params.get('item') or {}, completed=True)

    def observe_thread(self, thread):
        for turn in thread.get('turns', []):
            if not isinstance(turn, dict): continue
            for item in turn.get('items', []):
                self._enqueue(thread.get('id'), turn.get('id'), item, completed=turn.get('status') in ('completed', 'interrupted', 'failed'))

    def _enqueue(self, thread_id, turn_id, item, *, completed):
        if not isinstance(item, dict) or item.get('type') not in ('imageGeneration', 'ImageGeneration'): return
        item_id = item.get('id')
        if not all(isinstance(v, str) and 0 < len(v) <= 256 for v in (thread_id, turn_id, item_id)): return
        status = str(item.get('status') or '').casefold().replace('_', '').replace('-', '')
        if item.get('failure') or status in ('failed', 'error', 'canceled', 'cancelled', 'inprogress', 'running', 'interrupted'): return
        if not completed and status not in ('completed', 'succeeded', 'success'): return
        source = item.get('savedPath') or item.get('path')
        source = source.strip() if isinstance(source, str) and 0 < len(source) <= 8192 else None
        result = item.get('result')
        result = result.strip() if isinstance(result, str) and 0 < len(result) <= 90 * 1024 * 1024 else None
        if result and (result.startswith(('http://', 'https://', '/', '\\\\')) or re.match(r'^[A-Za-z]:[\\/]', result)):
            source = result
            result = None
        if not source and not result: return
        identity = self._identity(thread_id, turn_id, item_id)
        with self._lock:
            manifest = self.library / (identity + '.json')
            if manifest.exists(): return
            facts = {key: item[key] for key in ('prompt', 'revisedPrompt', 'revised_prompt', 'model', 'size', 'quality', 'transparentBackground') if isinstance(item.get(key), (str, int, float, bool))}
            atomic_json(manifest, {'id': identity, 'threadId': thread_id, 'turnId': turn_id, 'itemId': item_id,
                'nativeType': 'imageGeneration', 'facts': facts, 'status': 'pending', 'createdAt': time.time(),
                'favorite': False, 'source': source if isinstance(source, str) else None,
                'result': result, 'materialized': False})
        self._wake.set()

    def _run(self):
        while not self._stop.is_set():
            try:
                self.drain()
            except Exception:
                # A temporary disk or transport failure must not kill retries.
                pass
            self._wake.wait(30); self._wake.clear()

    def drain(self, limit=20):
        with self._ingest_lock:
            self._drain(limit)

    def _drain(self, limit):
        processed = 0
        with self._lock:
            self._library_index()
            pending = [r['id'] for r in reversed(self._library_values) if r.get('status') == 'pending']
        for identity in pending:
            manifest = self.library / (identity + '.json')
            record = read_record(manifest)
            if not record or record.get('status') != 'pending': continue
            try:
                if record.get('materialized'):
                    # Once publication was intended, a missing output is a tombstone,
                    # never an excuse to recreate a user-deleted library object.
                    target = self.library / record['file']
                    if not target.is_file() or target.is_symlink():
                        record.update(status='deleted', error='已移除的媒体不会重新导入')
                    else:
                        with target.open('rb') as stream:
                            if hashlib.file_digest(stream, 'sha256').hexdigest() != record['sha256']:
                                raise ValueError('published media changed')
                        record.update(status='ready', syncedAt=time.time(), error='')
                        record.pop('result', None)
                    with self._lock:
                        latest = read_record(manifest)
                        if latest and latest.get('status') != 'deleted':
                            record['favorite'] = latest.get('favorite', False)
                            atomic_json(manifest, record)
                else:
                    temporary = self.library / (uuid.uuid4().hex + '.tmp')
                    try:
                        # Network/native reads do not hold the receipt lock: history,
                        # events, favorite and delete remain responsive during retry.
                        if not record.get('result') and record.get('source'):
                            original, mime, _ = self.open_source(record['source'])
                            with original:
                                self._atomic_bytes(temporary, original)
                        else:
                            raw = ''.join(record.get('result', '').split(',', 1)[-1].split())
                            data = base64.b64decode(raw, validate=True)
                            if not data or len(data) > IMAGE_LIMIT: raise ValueError('image byte limit')
                            temporary.write_bytes(data)
                        mime, suffix = media_type(temporary)
                        if not mime.startswith('image/'): raise ValueError('generation is not an image')
                        with temporary.open('rb') as stream: digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                        target = self.library / (record['id'] + suffix)
                        with self._lock:
                            latest = read_record(manifest)
                            if not latest or latest.get('status') == 'deleted': continue
                            record['favorite'] = latest.get('favorite', False)
                            record.update(materialized=True, file=target.name, sha256=digest, mime=mime)
                            atomic_json(manifest, record)
                            if not target.exists(): self._atomic_bytes(target, temporary)
                            with target.open('rb') as stream:
                                if hashlib.file_digest(stream, 'sha256').hexdigest() != digest: raise ValueError('published media changed')
                            record.update(status='ready', syncedAt=time.time(), error='')
                            record.pop('result', None)
                            atomic_json(manifest, record)
                    finally: temporary.unlink(missing_ok=True)
            except Exception:
                with self._lock:
                    latest = read_record(manifest)
                    if latest and latest.get('status') != 'deleted':
                        record['favorite'] = latest.get('favorite', False)
                        record['error'] = '生成图片登记暂不可用，将自动重试'
                        record['attempts'] = record.get('attempts', 0) + 1
                        atomic_json(manifest, record)
            processed += 1
            if processed >= limit: break

    def _record(self, identity):
        if not isinstance(identity, str) or not re.fullmatch('[a-f0-9]{64}', identity):
            raise MediaError('媒体不存在', code='media_not_found', http_status=404)
        value = read_record(self.library / (identity + '.json'))
        if value is None or value.get('status') == 'deleted':
            raise MediaError('媒体不存在', code='media_not_found', http_status=404)
        return value

    def _public(self, record):
        identity = record['id']
        facts = record.get('facts', {})
        return {k: record.get(k) for k in ('id', 'threadId', 'turnId', 'itemId', 'nativeType', 'favorite', 'createdAt', 'status', 'mime', 'sha256', 'error')} | {
            'source': record.get('source'), 'mimeType': record.get('mime'),
            'mediaType': (record.get('mime') or 'image/unknown').split('/')[0],
            'hasGenerationInfo': record.get('nativeType') == 'imageGeneration',
            'originalUrl': f'/v1/media/library/{identity}/content', 'thumbnailUrl': f'/v1/media/library/{identity}/thumbnail',
            'name': 'Codex 生成图片' if record.get('nativeType') else record.get('name', '已保存媒体'),
            'contentPath': f'/v1/media/library/{identity}/content', 'thumbnailPath': f'/v1/media/library/{identity}/thumbnail',
            'provenance': {'facts': dict(facts), 'promptKind': 'original' if facts.get('prompt') else 'revised' if facts.get('revisedPrompt') or facts.get('revised_prompt') else 'unknown',
                'requestInformation': 'only recorded native facts; unrecorded engine request remains unknown'}}

    def _library_index(self):
        stat = self.library.stat()
        stamp = (stat.st_mtime_ns, stat.st_ctime_ns)
        now = time.monotonic()
        # All Relay writes replace manifests atomically (directory stamp changes).
        # A periodic stat pass also discovers external in-place metadata edits.
        if stamp == self._library_stamp and now - self._library_scan_at < 1:
            return
        cache = {}
        for path in self.library.glob('*.json'):
            try: stat = path.stat()
            except FileNotFoundError: continue
            fingerprint = (stat.st_ino, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns)
            previous = self._record_cache.get(path)
            record = previous[1] if previous and previous[0] == fingerprint else read_record(path)
            # Never retain pending base64 image bytes in the listing index.
            if record is not None: record = {key: value for key, value in record.items() if key != 'result'}
            cache[path] = (fingerprint, record)
        self._record_cache = cache
        self._library_values = sorted((record for _, record in cache.values() if record and record.get('status') != 'deleted'),
                                      key=lambda record: record.get('createdAt', 0), reverse=True)
        self._library_threads = {}
        for record in self._library_values:
            self._library_threads.setdefault(record.get('threadId'), []).append(record)
        self._library_stamp, self._library_scan_at = stamp, now

    def list(self, thread_id=None, favorite=False, generated=False, source=None):
        with self._lock:
            self._library_index()
            records = self._library_threads.get(thread_id, []) if thread_id else self._library_values
            values = (r for r in records if (source is None or r.get('source') == source)
                      and (not favorite or r.get('favorite')) and (not generated or r.get('nativeType') == 'imageGeneration'))
            from itertools import islice
            return [self._public(r) for r in islice(values, 500)]

    def info(self, identity):
        with self._lock: return self._public(self._record(identity))

    def stored(self, identity, thumbnail=False):
        stream, mime, etag = self.open_stored(identity, thumbnail=thumbnail)
        with stream: return Path(stream.name), mime, etag

    def favorite(self, identity, value):
        if not isinstance(value, bool): raise MediaError('收藏状态无效', code='invalid_request', http_status=400)
        with self._lock:
            record = self._record(identity); record['favorite'] = value
            atomic_json(self.library / (identity + '.json'), record)
            return self._public(record)

    def delete(self, identity):
        with self._lock:
            record = self._record(identity); record['status'] = 'deleted'; record.pop('result', None)
            atomic_json(self.library / (identity + '.json'), record)
            if record.get('file'): (self.library / record['file']).unlink(missing_ok=True)
            return {'id': identity, 'deleted': True}

    def import_source(self, source, thread_id=None):
        if thread_id is not None and (not isinstance(thread_id, str) or not 0 < len(thread_id) <= 256):
            raise MediaError('会话标识无效', code='invalid_request', http_status=400)
        original, mime, _ = self.open_source(source)
        with original:
            digest = hashlib.file_digest(original, 'sha256').hexdigest()
            original.seek(0)
            identity = hashlib.sha256(('import:' + source + ':' + digest).encode()).hexdigest()
            with self._lock:
                existing = read_record(self.library / (identity + '.json'))
                if existing and existing.get('status') != 'deleted': return self._public(existing)
                target = self.library / (identity + Path(original.name).suffix)
                self._atomic_bytes(target, original)
                record = {'id': identity, 'threadId': thread_id, 'source': source, 'favorite': False, 'createdAt': time.time(),
                    'status': 'ready', 'mime': mime, 'sha256': digest, 'file': target.name,
                    'name': Path(urllib.parse.urlsplit(source).path).name or '已保存媒体', 'facts': {}, 'materialized': True}
                atomic_json(self.library / (identity + '.json'), record)
                return self._public(record)
