"""Capability-driven access to an existing QiGuang-compatible ASR service.

No models are downloaded or started. Streaming recordings belong to the paired
device that created them and never become Codex messages without a later send.
"""
from __future__ import annotations

import hashlib
import hmac
import ssl
import http.client
import json
import os
import re
import threading
import time
import urllib.parse
import uuid

from .media import MediaError

MAX_SPEECH_BYTES = 12 * 1024 * 1024
MAX_CHUNK_BYTES = 64 * 1024


class SpeechService:
    def __init__(self, url=None, token=None, *, requester=None, workspace=None):
        self.url = (url if url is not None else os.environ.get('RIVO_SPEECH_BACKEND_URL', '')).rstrip('/')
        self.token = token if token is not None else os.environ.get('RIVO_SPEECH_BACKEND_TOKEN', '')
        self.pin = None
        self.shared = False
        self._capability_cache = None
        self._capability_until = 0
        if url is None and workspace is not None:
            from pathlib import Path
            from .gateway.config import decode_key
            path = Path(workspace) / 'gateway-client.json'
            if path.exists():
                self.url, self.token = '', ''
                try:
                    key = decode_key(json.loads(path.read_text())['key'])
                    self.url = key['gateway'] + '/gateway/speech/' + key['slot']
                    self.token, self.pin, self.shared = key['secret'], key['pin'], True
                except (ValueError, KeyError, TypeError, OSError):
                    pass  # An invalid gateway config must not advertise working ASR.
        self.requester = requester or self._request
        self._lock = threading.RLock()
        self._streams = {}
        if self.url:
            parsed = urllib.parse.urlsplit(self.url)
            if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
                raise ValueError('RIVO_SPEECH_BACKEND_URL must be an explicit HTTP(S) service URL without credentials')

    def capabilities(self):
        if self.shared:
            with self._lock:
                if time.monotonic() >= self._capability_until:
                    try:
                        value = self._request('/capabilities', None, method='GET', timeout=3).get('speech', {})
                        available = (value.get('streaming') is True and value.get('sampleRate') == 16000
                                     and value.get('encoding') == 'pcm_s16le')
                    except (MediaError, ValueError, TypeError, AttributeError):
                        available = False
                    self._capability_cache = available
                    self._capability_until = time.monotonic() + (30 if available else 5)
                available = self._capability_cache
            return {'transcription': available, 'streaming': available, 'sampleRate': 16000,
                    'encoding': 'pcm_s16le', 'maxSeconds': 120, 'maxUploadBytes': MAX_SPEECH_BYTES,
                    'state': 'configured' if available else 'unavailable', 'backend': 'shared-vps'}
        return {'transcription': bool(self.url), 'streaming': bool(self.url), 'sampleRate': 16000,
                'encoding': 'pcm_s16le', 'maxSeconds': 120, 'maxUploadBytes': MAX_SPEECH_BYTES,
                'state': 'configured' if self.url else 'unconfigured'}

    def _request(self, path, body, content_type='application/json', *, method='POST', timeout=120):
        if not self.url:
            raise MediaError('此节点尚未配置语音识别服务', code='speech_unavailable', http_status=503)
        target = urllib.parse.urlsplit(self.url)
        cls = http.client.HTTPSConnection if target.scheme == 'https' else http.client.HTTPConnection
        options = {'timeout': timeout}
        if self.pin:
            if target.scheme != 'https':
                raise MediaError('共享语音需要 HTTPS', code='speech_unavailable', http_status=503)
            # Validate the exact enrolled certificate before transmitting a node secret.
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            options['context'] = context
        connection = cls(target.hostname, target.port, **options)
        try:
            if self.pin:
                connection.connect()
                actual = hashlib.sha256(connection.sock.getpeercert(binary_form=True)).hexdigest()
                if not hmac.compare_digest(actual, self.pin):
                    raise ValueError('Gateway certificate mismatch')
            headers = {'Content-Type': content_type, 'Accept': 'application/json'}
            if self.token: headers['Authorization'] = 'Bearer ' + self.token
            connection.request(method, target.path.rstrip('/') + path, body=body, headers=headers)
            response = connection.getresponse()
            raw = response.read(1024 * 1024 + 1)
            if not 200 <= response.status < 300 or len(raw) > 1024 * 1024:
                raise MediaError('语音识别服务暂不可用', code='speech_upstream', http_status=502)
            value = json.loads(raw)
            if not isinstance(value, dict): raise ValueError('invalid speech response')
            return value
        except (ValueError, OSError) as error:
            raise MediaError('语音识别请求失败，请重试', code='speech_upstream', http_status=502) from error
        finally: connection.close()

    def _context(self, value):
        if not isinstance(value, str) or len(value) > 400:
            raise MediaError('语音上下文不能超过 400 字符', code='invalid_speech_context', http_status=400)
        return value

    def transcribe(self, body, content_type):
        if not self.url: raise MediaError('此节点尚未配置语音识别服务', code='speech_unavailable', http_status=503)
        if not body or len(body) > MAX_SPEECH_BYTES + 64 * 1024:
            raise MediaError('录音为空或超过 12 MiB', code='speech_too_large', http_status=413)
        if not content_type.lower().startswith('multipart/form-data;'):
            raise MediaError('语音请求需要 multipart/form-data', code='invalid_speech', http_status=415)
        # Parse only the bounded request, strictly one file + optional small context.
        from email.parser import BytesParser
        from email import policy
        message = BytesParser(policy=policy.default).parsebytes(('Content-Type: ' + content_type + '\r\nMIME-Version: 1.0\r\n\r\n').encode('ascii') + body)
        if not message.is_multipart(): raise MediaError('录音表单无效', code='invalid_speech', http_status=400)
        parts = list(message.iter_parts())
        if len(parts) > 2: raise MediaError('录音表单字段无效', code='invalid_speech', http_status=400)
        files, contexts = [], []
        for part in parts:
            name = part.get_param('name', header='content-disposition')
            data = part.get_payload(decode=True) or b''
            if name == 'file': files.append((part, data))
            elif name == 'context': contexts.append(data.decode('utf-8'))
            else: raise MediaError('录音表单字段无效', code='invalid_speech', http_status=400)
        if len(files) != 1 or len(contexts) > 1 or not 0 < len(files[0][1]) <= MAX_SPEECH_BYTES:
            raise MediaError('需要一份有效录音', code='invalid_speech', http_status=400)
        self._context(contexts[0] if contexts else '')
        value = self.requester('/v1/speech/transcriptions', body, content_type)
        if not isinstance(value.get('text'), str): raise MediaError('转写响应无效', code='invalid_speech_response', http_status=502)
        return {'ok': True, 'text': value['text'], 'language': value.get('language'), 'backend': value.get('backend')}

    def _expire(self):
        now = time.monotonic()
        for identity in list(self._streams):
            if now - self._streams[identity]['touched'] > 30: self._streams.pop(identity)

    def start(self, owner, context=''):
        self._context(context)
        if not self.url: raise MediaError('此节点尚未配置语音识别服务', code='speech_unavailable', http_status=503)
        with self._lock:
            self._expire()
            if len(self._streams) >= 4: raise MediaError('实时语音会话已满', code='speech_busy', http_status=429)
            value = self.requester('/v1/speech/streams', json.dumps({'context': context}).encode())
            upstream = value.get('stream_id')
            if not isinstance(upstream, str) or not re.fullmatch('[A-Za-z0-9_-]{1,128}', upstream):
                raise MediaError('实时语音响应无效', code='invalid_speech_response', http_status=502)
            identity = uuid.uuid4().hex
            self._streams[identity] = {'owner': owner, 'upstream': upstream, 'bytes': 0, 'touched': time.monotonic()}
            return {**value, 'stream_id': identity}

    def stream(self, owner, identity, operation, raw=b''):
        if operation not in ('chunks', 'finish', 'cancel'): raise MediaError('语音操作无效', code='invalid_speech', http_status=400)
        with self._lock:
            self._expire()
            stream = self._streams.get(identity)
            if stream is None or stream['owner'] != owner:
                raise MediaError('实时语音已过期', code='speech_expired', http_status=404)
            if operation == 'chunks':
                if not raw or len(raw) > MAX_CHUNK_BYTES or len(raw) % 2:
                    raise MediaError('PCM 分片无效', code='invalid_speech_chunk', http_status=400)
                if stream['bytes'] + len(raw) > 16000 * 2 * 120:
                    self._streams.pop(identity)
                    raise MediaError('录音不能超过 120 秒', code='speech_too_long', http_status=413)
                # Reserve before dispatch: uncertain chunks are never automatically replayed.
                stream['bytes'] += len(raw)
            stream['touched'] = time.monotonic()
            if operation in ('finish', 'cancel'): self._streams.pop(identity)
            try:
                value = self.requester(f"/v1/speech/streams/{stream['upstream']}/{operation}", raw if operation == 'chunks' else b'{}',
                    'application/octet-stream' if operation == 'chunks' else 'application/json')
                if operation != 'cancel' and (not isinstance(value.get('text'), str) or not isinstance(value.get('stable_text'), str)):
                    raise MediaError('实时语音响应无效', code='invalid_speech_response', http_status=502)
            except Exception:
                # A lost response may already have appended PCM remotely. Retire
                # this identity so the same chunk cannot silently be appended twice.
                self._streams.pop(identity, None)
                raise
            return {**value, 'stream_id': identity}
