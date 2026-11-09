from __future__ import annotations

from contextlib import nullcontext
from .read_budget import read_budget

import base64
import gzip
import secrets
import json
import os
import time
import sys
import datetime
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, unquote, urlsplit, quote

from .observability import event, request_id, route_kind

from .app import public_submission, public_todo
from .codex.files import MAX_TRANSFER_BYTES, native_path
from .codex.native_download import byte_range
from .speech import MAX_SPEECH_BYTES, MAX_CHUNK_BYTES
from .media import MediaError
from .updates import latest_update, update_apk
from .client_projection import client_history, client_session


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status, self.code = status, code
        super().__init__(message)


def accepts_gzip(value: str) -> bool:
    qualities = {}
    for entry in value.lower().split(","):
        name, *parameters = entry.strip().split(";")
        quality = 1.0
        for parameter in parameters:
            key, _, raw = parameter.strip().partition("=")
            if key == "q":
                try:
                    quality = float(raw)
                except ValueError:
                    quality = 0.0
        qualities[name.strip()] = quality if 0 <= quality <= 1 else 0.0
    return qualities.get("gzip", qualities.get("*", 0)) > 0


def required(body: dict, key: str, limit: int = 200) -> str:
    value = body.get(key)
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise ApiError(400, "invalid_request", f"{key} is required")
    return value


def permission_options(body: dict) -> dict:
    options = {}
    for key, arg, allowed in (
        ('sandbox', 'sandbox', ('read-only', 'workspace-write', 'danger-full-access')),
        ('approvalPolicy', 'approval_policy', ('on-request', 'untrusted', 'never', 'on-failure', 'preset-request', 'preset-auto', 'preset-full')),
    ):
        value = body.get(key)
        if value is not None:
            if value not in allowed:
                raise ApiError(400, 'invalid_request', f'Invalid {key}')
            options[arg] = value
    return options


def native_input(body: dict) -> list[dict] | None:
    items = body.get("input")
    if items is None:
        return None
    if not isinstance(items, list) or not 1 <= len(items) <= 64:
        raise ApiError(400, "invalid_input", "input must contain 1–64 native items")
    if sum(isinstance(item, dict) and item.get('type') in ('image', 'localImage') for item in items) > 10:
        raise ApiError(400, 'invalid_input', '最多添加 10 张图片')
    for item in items:
        if not isinstance(item, dict):
            raise ApiError(400, "invalid_input", "Invalid native input item")
        kind = item.get("type")
        if kind == "text":
            required(item, "text", 200000)
        elif kind == "image":
            if not (isinstance(item.get("url"), str) and item["url"] or isinstance(item.get("fileId"), str) and item["fileId"]):
                raise ApiError(400, "invalid_input", "Image needs a URL or native file ID")
            if isinstance(item.get('url'), str):
                limit = ((20 * 1024 * 1024 + 2) // 3) * 4 + 64 if item['url'].startswith('data:') else 8192
                if len(item['url']) > limit:
                    raise ApiError(413, 'image_too_large', '图片数据或地址超过大小限制')
            if isinstance(item.get('fileId'), str) and len(item['fileId']) > 512:
                raise ApiError(400, 'invalid_input', 'Image file ID is too long')
        elif kind in {"localImage", "mention", "skill"}:
            required(item, "path", 4096)
            if kind != "localImage":
                required(item, "name", 1000)
        else:
            raise ApiError(400, "invalid_input", "Unknown native input type")
    return items


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "RivoRelay/0.1"
    sys_version = ""

    def log_message(self, *_):
        # URLs/headers/body may contain credentials. No default request logging.
        pass

    def log_request(self, code="-", size="-"):
        self._response_status = code
        event("http_response", request_id=getattr(self, "_request_id", None),
              method=self.command if self.command in {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"} else "OTHER",
              status=code,
              headers_ms=round((time.monotonic() - self._request_started) * 1000, 2) if hasattr(self, "_request_started") else None)


    @property
    def app(self):
        return self.server.app

    def send_json(self, status: int, data: dict):
        raw = json.dumps(data, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
        # Compress large read-only catalogs as well as history. Keep credentials,
        # mutation responses and streaming/binary endpoints out of this policy.
        path = urlsplit(self.path).path
        compressible = (
            self.command == "GET" and status == 200
            and (path in {"/v1/projects", "/v1/threads", "/v1/models", "/v1/todos"}
                 or path.startswith(("/v1/threads/", "/v1/projects/")))
        )
        compressed = False
        if compressible and len(raw) >= 4096 and accepts_gzip(self.headers.get("Accept-Encoding", "")):
            encoded = gzip.compress(raw, compresslevel=1, mtime=0)
            if len(encoded) < len(raw):
                raw = encoded
                compressed = True
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        if compressible:
            self.send_header("Vary", "Accept-Encoding")
        if compressed:
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        # Large histories can be backpressured by the VPS/mobile link. The
        # 10-second request-header timeout must not truncate a valid response.
        self.connection.settimeout(120)
        self.end_headers()
        try:
            for offset in range(0, len(raw), 65536):
                self.wfile.write(raw[offset:offset + 65536])
        finally:
            self.close_connection = True

    def send_file(self, path):
        if not self.app.file_transfer_slots.acquire(blocking=False):
            raise ApiError(429, "transfer_busy", "文件传输繁忙，请稍后重试")
        try:
            self.connection.settimeout(120)
            try:
                download = self.app.codex.prepare_download(path, self.headers.get('Range'), self.headers.get('If-Range'))
            except Exception as error:
                if getattr(error, 'http_status', None) != 416 or not hasattr(error, 'range_size'):
                    raise
                self.send_response(416)
                self.send_header('Content-Range', 'bytes */' + str(error.range_size))
                self.send_header('ETag', error.range_etag)
                self.send_header('Accept-Ranges', 'bytes')
                self.send_header('Content-Length', '0')
                self.send_header('Connection', 'close')
                self.end_headers()
                self.close_connection = True
                return
            size, start, end = download['size'], download['start'], download['end']
            self.send_response(206 if download['partial'] else 200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(max(0, end - start + 1)))
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('ETag', download['etag'])
            if download['partial']:
                self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
            name = path.replace("\\", "/").rsplit("/", 1)[-1]
            self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + quote(name, safe=""))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Connection", "close")
            self.end_headers()
            self._file_started = True
            self.close_connection = True
            for data in download['chunks']:
                self.wfile.write(data)
        finally:
            self.app.file_transfer_slots.release()

    def send_update_apk(self, name):
        try:
            path = update_apk(self.app.config.workspace, name)
        except FileNotFoundError as error:
            raise ApiError(404, 'update_not_found', str(error))
        if not self.app.file_transfer_slots.acquire(blocking=False):
            raise ApiError(429, 'transfer_busy', '下载繁忙，请稍后重试')
        try:
            self.connection.settimeout(120)
            with path.open('rb') as stream:
                size = os.fstat(stream.fileno()).st_size
                start, end, partial = byte_range(self.headers.get('Range'), size)
                self.send_response(206 if partial else 200)
                self.send_header('Content-Type', 'application/vnd.android.package-archive')
                self.send_header('Content-Length', str(end - start + 1))
                self.send_header('Content-Disposition', 'attachment; filename="rivo-relay-update.apk"')
                self.send_header('Accept-Ranges', 'bytes')
                if partial:
                    self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
                self.send_header('Cache-Control', 'public, max-age=31536000, immutable')
                self.send_header('X-Content-Type-Options', 'nosniff')
                self.send_header('Connection', 'close')
                self.end_headers()
                self._file_started = True
                self.close_connection = True
                stream.seek(start)
                remaining = end - start + 1
                while remaining:
                    chunk = stream.read(min(1024 * 1024, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        finally:
            self.app.file_transfer_slots.release()

    def receive_file(self, target_id, query, *, project=False):
        if self.headers.get("Transfer-Encoding"):
            raise ApiError(400, "invalid_request", "不支持无长度上传")
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) != 1:
            raise ApiError(411, "invalid_request", "上传需要文件长度")
        try:
            size = int(lengths[0])
        except ValueError:
            raise ApiError(400, "invalid_request", "文件长度无效")
        if not 0 <= size <= MAX_TRANSFER_BYTES:
            raise ApiError(413, "upload_too_large", "手机文件最大支持 1 GiB")
        name = required({"name": query.get("name", [None])[0]}, "name", 200)
        if not self.app.file_transfer_slots.acquire(blocking=False):
            raise ApiError(429, "transfer_busy", "文件传输繁忙，请稍后重试")
        try:
            self.connection.settimeout(120)
            if target_id is None:
                return self.app.codex.upload_todo_attachment_stream(name, self.rfile, size)
            upload = self.app.codex.upload_project_attachment_stream if project else self.app.codex.upload_attachment_stream
            return upload(target_id, name, self.rfile, size)
        finally:
            self.app.file_transfer_slots.release()

    def read_bounded(self, limit):
        lengths = self.headers.get_all('Content-Length', [])
        if self.headers.get('Transfer-Encoding') or len(lengths) != 1:
            raise ApiError(411, 'invalid_request', '需要明确的请求长度')
        try: length = int(lengths[0])
        except ValueError: raise ApiError(400, 'invalid_request', '请求长度无效')
        if not 0 < length <= limit: raise ApiError(413, 'request_too_large', '请求超过大小限制')
        raw = self.rfile.read(length)
        if len(raw) != length: raise ApiError(400, 'invalid_request', '请求不完整')
        return raw

    def send_media(self, value):
        stream, mime, etag = value
        if self.headers.get('If-Match') not in (None, etag):
            stream.close()
            raise ApiError(412, 'media_changed', '图片已变化，请重新加载')
        with stream:
            size = os.fstat(stream.fileno()).st_size
            effective_range = self.headers.get('Range') if self.headers.get('If-Range') in (None, etag) else None
            try: start, end, partial = byte_range(effective_range, size)
            except Exception as error:
                if getattr(error, 'http_status', None) != 416: raise
                self.send_response(416)
                self.send_header('Content-Range', f'bytes */{size}')
                self.send_header('ETag', etag)
                self.send_header('Accept-Ranges', 'bytes')
                self.send_header('Content-Length', '0')
                self.send_header('Connection', 'close'); self.end_headers(); self.close_connection = True
                return
            if not effective_range and self.headers.get('If-None-Match') == etag:
                self.send_response(304); self.send_header('ETag', etag)
                self.send_header('Content-Length', '0'); self.send_header('Connection', 'close')
                self.end_headers(); self.close_connection = True; return
            self.send_response(206 if partial else 200)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(max(0, end - start + 1)))
            self.send_header('ETag', etag); self.send_header('Accept-Ranges', 'bytes')
            if partial: self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
            self.send_header('Cache-Control', 'private, no-cache')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Connection', 'close'); self.end_headers()
            self._file_started = True; self.close_connection = True
            stream.seek(start); remaining = max(0, end - start + 1)
            while remaining:
                block = stream.read(min(65536, remaining))
                if not block: raise OSError('media representation truncated')
                self.wfile.write(block); remaining -= len(block)

    def read_body(self, limit=64 * 1024 * 1024) -> dict:
        if self.headers.get("Transfer-Encoding"):
            raise ApiError(400, "invalid_request", "Chunked requests are not supported")
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) != 1:
            raise ApiError(411, "invalid_request", "One Content-Length is required")
        try:
            length = int(lengths[0])
        except ValueError:
            raise ApiError(400, "invalid_request", "Invalid Content-Length")
        if not 0 < length <= limit:
            raise ApiError(413, "request_too_large", "Request exceeds the size limit")
        if self.headers.get_content_type() != "application/json":
            raise ApiError(415, "invalid_request", "JSON required")
        raw = self.rfile.read(length)
        if len(raw) != length:
            raise ApiError(400, "invalid_request", "Incomplete request")
        try:
            body = json.loads(raw, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        except (ValueError, UnicodeError):
            raise ApiError(400, "invalid_json", "Invalid JSON")
        if not isinstance(body, dict):
            raise ApiError(400, "invalid_json", "JSON object required")
        return body

    def authenticate(self):
        authorizations = self.headers.get_all("Authorization", [])
        if len(authorizations) != 1 or not authorizations[0].startswith("Bearer "):
            raise ApiError(401, "unauthorized", "请重新连接此节点")
        token = authorizations[0][7:]
        connection = self.app.pairing.authenticate(token)
        if connection is None:
            raise ApiError(401, "unauthorized", "连接已失效，请重新授权")
        return token, connection

    def do_GET(self):
        self.handle_api()

    def do_POST(self):
        self.handle_api()

    def do_PATCH(self):
        self.handle_api()

    def do_DELETE(self):
        self.handle_api()

    def handle_api(self):
        self._request_id = request_id(self.headers.get("X-Rivo-Request-Id"))
        self._response_status = None
        started = time.monotonic()
        self._request_started = started
        outcome = "handler_returned"
        path = urlsplit(self.path).path
        gated = path not in ('/health', '/internal/update-lease', '/internal/update-stop', '/internal/update-activate', '/v1/events')
        if gated and not self.app.update_gate.enter():
            self.close_connection = True
            self.send_json(503, {'error': {'code': 'node_updating', 'message': '节点正在更新，请稍后重试'}})
            return
        try:
            return self._handle_api()
        except BaseException as error:
            outcome = type(error).__name__
            raise
        finally:
            if gated:
                self.app.update_gate.leave()
            event("node_request_finished", request_id=self._request_id,
                  status=self._response_status, outcome=outcome, route=route_kind(path),
                  duration_ms=round((time.monotonic() - started) * 1000, 2))

    def _handle_api(self):
        try:
            url = urlsplit(self.path)
            if url.scheme or url.netloc:
                raise ApiError(400, "invalid_request", "Origin-form request required")
            path = [unquote(part) for part in url.path.strip("/").split("/")]
            query = parse_qs(url.query)
            if any("/" in part or "\x00" in part for part in path):
                raise ApiError(400, "invalid_request", "Invalid path")
            if self.command == 'POST' and path in (['internal', 'update-lease'], ['internal', 'update-stop'], ['internal', 'update-activate']):
                expected = os.environ.get('RIVO_UPDATE_TOKEN', '')
                if (self.client_address[0] not in ('127.0.0.1', '::1') or not expected
                        or not secrets.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + expected)):
                    raise ApiError(403, 'forbidden', 'Local update agent required')
                self.read_body(limit=4096)
                if path[-1] == 'update-activate':
                    with self.app.update_gate.lock:
                        self.app.update_gate.until = 0
                    self.send_json(200, {'ready': True})
                    return
                ready = self.app.update_gate.acquire(self.app)
                self.send_json(200 if ready else 409, {'ready': ready})
                if ready and path[-1] == 'update-stop':
                    import threading
                    threading.Thread(target=self.server.shutdown, daemon=True).start()
                return
            if self.command == "GET" and path == ["health"]:
                self.send_json(200, {"status": "ok", "nodeId": self.app.config.node_id, "releaseCode": int(os.environ.get("RIVO_RELEASE_CODE", "0")), "nativeConnected": bool(self.app.codex.status().get("connected"))})
                return
            if self.command == 'GET' and path == ['v1', 'app-updates', 'latest']:
                try:
                    result = latest_update(self.app.config.workspace)
                except FileNotFoundError as error:
                    raise ApiError(404, 'update_not_found', str(error))
                except (ValueError, KeyError, TypeError):
                    raise ApiError(503, 'update_unavailable', '节点更新文件暂不可用')
                self.send_json(200, result)
                return
            if self.command == 'GET' and len(path) == 4 and path[:3] == ['v1', 'app-updates', 'apks']:
                self.send_update_apk(path[3])
                return
            if path == ["v1", "pair"] and self.command == "POST":
                body = self.read_body(limit=4096)
                from .gateway.config import pairing_route_binding
                result = self.app.pairing.exchange(required(body, "code", 512),
                    required(body, "deviceName", 100), client_key=self.client_address[0],
                    resume=body.get("resume"), node_id=self.app.config.node_id,
                    route_binding=pairing_route_binding(self.app.config.workspace))
                self.send_json(200, {"token": result["token"], "deviceId": result["connection_id"], "reuseProof": result.get("reuseProof", ""),
                    "node": {"id": self.app.config.node_id, "name": self.app.node_name.get()}})
                return
            if self.command == "GET" and len(path) == 3 and path[:2] == ["v1", "download-links"]:
                with self.app.download_lock:
                    ticket = self.app.download_links.get(path[2])
                if ticket is None or ticket[1] < time.time():
                    raise ApiError(404, "expired_download", "下载链接已过期")
                self.send_file(ticket[0])
                return
            if self.command == "POST" and path == ["v1", "todo-completion"]:
                headers = self.headers.get_all("Authorization", [])
                if len(headers) != 1 or not headers[0].startswith("Bearer "):
                    raise ApiError(401, "unauthorized", "Missing completion credential")
                self.read_body(limit=4096)
                try:
                    todo = self.app.todo_completion.complete(headers[0][7:])
                except PermissionError:
                    raise ApiError(401, "unauthorized", "Invalid completion credential")
                self.send_json(200, {"todo": {"id": todo["id"], "completed": todo["completed"]}})
                return
            token, connection = self.authenticate()
            if self.command == 'GET' and path == ['v1', 'media', 'versions']:
                source = required({'source': query.get('source', [None])[0]}, 'source', 8192)
                parts = source.strip('/').split('/')
                stored = len(parts) == 5 and parts[:3] in (['v1', 'media', 'library'], ['v1', 'media', 'generated']) and parts[4] in ('content', 'thumbnail')
                versions = {}
                for thumbnail, name in ((False, 'original'), (True, 'thumbnail')):
                    stream, _, etag = (self.app.media.open_stored(parts[3], thumbnail=thumbnail) if stored
                        else self.app.media.open_source(source, thumbnail=thumbnail))
                    stream.close()
                    versions[name] = etag
                self.send_json(200, versions)
                return
            if self.command == 'GET' and path in (['v1', 'media', 'content'], ['v1', 'media', 'thumbnail']):
                source = required({'source': query.get('source', [None])[0]}, 'source', 8192)
                self.send_media(self.app.media.open_source(source, thumbnail=path[-1] == 'thumbnail'))
                return
            if self.command == 'GET' and len(path) == 5 and path[:3] in (['v1', 'media', 'library'], ['v1', 'media', 'generated']) and path[4] in ('content', 'thumbnail'):
                self.send_media(self.app.media.open_stored(path[3], thumbnail=path[4] == 'thumbnail'))
                return
            if self.command == 'POST' and path == ['v1', 'speech', 'transcriptions']:
                self.send_json(200, self.app.speech.transcribe(self.read_bounded(MAX_SPEECH_BYTES + 64 * 1024), self.headers.get('Content-Type', '')))
                return
            if self.command == 'POST' and len(path) == 5 and path[:3] == ['v1', 'speech', 'streams'] and path[-1] == 'chunks':
                if self.headers.get_content_type() != 'application/octet-stream': raise ApiError(415, 'invalid_speech', '语音分片需要 PCM 数据')
                self.send_json(200, self.app.speech.stream(connection.get('id', connection.get('connection_id')), path[3], 'chunks', self.read_bounded(MAX_CHUNK_BYTES)))
                return
            if self.command == "GET" and path == ["v1", "files", "download"]:
                self.send_file(required({"path": query.get("path", [None])[0]}, "path", 4096))
                return
            if self.command == "POST" and path == ["v1", "todos", "attachments"]:
                if self.headers.get_content_type() != "application/octet-stream":
                    raise ApiError(415, "invalid_request", "附件需要二进制上传")
                self.send_json(200, self.receive_file(None, query))
                return
            if (self.command == "POST" and len(path) == 4 and path[:2] in (["v1", "threads"], ["v1", "projects"])
                    and path[3] == "attachments" and self.headers.get_content_type() == "application/octet-stream"):
                self.send_json(200, self.receive_file(path[2], query, project=path[1] == 'projects'))
                return
            if self.command == "GET" and path == ["v1", "events"]:
                self.stream_events(token)
                return
            body = self.read_body() if self.command in {"POST", "PATCH"} else {}
            with read_budget(self.connection) if self.command == "GET" else nullcontext():
                result = self.route(path, query, body, connection)
            if self.command == 'GET' and isinstance(result, dict) and isinstance(result.get('thread'), dict):
                try:
                    self.app.media.observe_thread(result['thread'])
                except Exception:
                    pass  # Derived media state cannot prevent native history delivery.
            self.send_json(200, result)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True
        except Exception as error:
            if getattr(self, '_file_started', False):
                self.close_connection = True
                return
            status = getattr(error, "status", getattr(error, "http_status", 400 if isinstance(error, ValueError) else 503))
            if not isinstance(status, int) or not 400 <= status <= 599:
                status = 503
            code = str(getattr(error, "code", "invalid_request" if status == 400 else "native_unavailable"))
            # RPC messages may contain private paths/content. Only expose our
            # own validation messages; native diagnostics stay out of responses.
            message = str(error) if isinstance(error, (ApiError, MediaError)) else {
                400: "请求内容无效", 401: "配对码无效或已过期", 404: "记录不存在",
                409: "操作状态已变化，请刷新", 429: "尝试过于频繁，请稍后再试",
                501: "当前节点版本暂不支持此功能",
                413: "文件超过此操作的大小限制",
            }.get(status, "原生服务连接暂不可用，请检查节点服务")
            if code in ('native_host_unverified', 'handoff_host_unverified', 'handoff_host_mismatch', 'handoff_host_changed'):
                message = '无法核验接管所需的原生主机身份，请检查节点运行时连接'
            if code == 'handoff_desktop_shared':
                message = '共享 Desktop 运行时不支持结束进程接管'
            if code == 'read_deadline':
                message = '读取等待超时或连接已断开，请稍后刷新'
            if code == 'native_history_unavailable':
                message = '原生历史暂不可用，请稍后重试'
            if code == "file_too_large":
                message = ("文件超过预览大小，请选择保存到手机" if path == ["v1", "files"]
                           else "文件最大支持 1 GiB")
            if code == "directory_too_large":
                message = "目录条目过多，请选择较小的子目录"
            try:
                self.send_json(status, {"error": {"code": code, "message": message}})
            except OSError:
                self.close_connection = True

    def route(self, path, query, body, connection):
        method = self.command
        codex = self.app.codex
        connection_id = connection.get("id", connection.get("connection_id"))
        if path == ["v1", "node"] and method == "GET":
            return self.app.node()
        if path == ['v1', 'media', 'capabilities'] and method == 'GET':
            return self.app.media_capabilities()
        if path == ['v1', 'speech', 'streams'] and method == 'POST':
            return self.app.speech.start(connection_id, body.get('context', ''))
        if len(path) == 5 and path[:3] == ['v1', 'speech', 'streams'] and path[4] in ('finish', 'cancel') and method == 'POST':
            return self.app.speech.stream(connection_id, path[3], path[4])
        if path in (['v1', 'media', 'library'], ['v1', 'media', 'generated']) and method == 'GET':
            return {'items': self.app.media.list(thread_id=query.get('threadId', [None])[0],
                favorite=query.get('favorite', ['false'])[0] == 'true', generated=path[-1] == 'generated', source=query.get('source', [None])[0])}
        if path == ['v1', 'media', 'library'] and method == 'POST':
            return self.app.media.import_source(required(body, 'source', 8192), body.get('threadId'))
        if len(path) in (4, 5) and path[:3] in (['v1', 'media', 'library'], ['v1', 'media', 'generated']):
            if method == 'GET' and (len(path) == 4 or path[4] == 'provenance'): return self.app.media.info(path[3])
            if method == 'PATCH' and len(path) == 4: return self.app.media.favorite(path[3], body.get('favorite'))
            if method == 'DELETE' and len(path) == 4: return self.app.media.delete(path[3])
        if path == ["v1", "download-links"] and method == "POST":
            target = native_path(required(body, "path", 4096))
            key = secrets.token_urlsafe(32)
            expiry = time.time() + 3600
            with self.app.download_lock:
                self.app.download_links = {k: v for k, v in self.app.download_links.items() if v[1] > time.time()}
                if len(self.app.download_links) >= 128:
                    raise ApiError(429, "too_many_downloads", "下载链接过多")
                self.app.download_links[key] = (target, expiry)
            return {"url": self.app.config.public_url.rstrip("/") + "/v1/download-links/" + key, "expiresAt": expiry}
        if path == ["v1", "goals", "capabilities"] and method == "GET":
            return codex.goal_capabilities()
        if path == ["v1", "models"] and method == "GET":
            if hasattr(codex, 'capabilities'):
                return codex.capabilities(refresh=query.get('refresh', ['0'])[0] == '1')
            return {"models": codex.models()}
        if path == ['v1', 'usage'] and method == 'GET':
            return {'usage': codex.usage()}
        if path == ['v1', 'project-directories'] and method == 'GET':
            return codex.project_directory(query.get('path', [None])[0])
        if len(path) == 4 and path[:2] == ['v1', 'projects'] and path[3] == 'git' and method == 'GET':
            return {'git': codex.project_git(path[2])}
        if path == ["v1", "files"] and method == "GET":
            file_path = required({"path": query.get("path", [None])[0]}, "path", 4096)
            return codex.read_file(file_path)
        if path == ["v1", "directories"] and method == "GET":
            return codex.read_directory(required({"path": query.get("path", [None])[0]}, "path", 4096))
        if len(path) == 4 and path[:2] == ["v1", "threads"] and path[3] == "attachments" and method == "POST":
            return codex.upload_attachment(path[2], required(body, "name", 200), body.get("dataBase64"))
        if len(path) == 4 and path[:2] == ["v1", "projects"] and path[3] == "attachments" and method == "POST":
            return codex.upload_project_attachment(path[2], required(body, "name", 200), body.get("dataBase64"))
        if path == ["v1", "projects"]:
            if method == "GET":
                return {"projects": codex.list_projects()}
            if method == "POST":
                project_path = required(body, "path", 4096)
                sid = required(body, "submissionId")
                if 'trustConfirmed' in body and not isinstance(body['trustConfirmed'], bool):
                    raise ApiError(400, 'invalid_request', 'trustConfirmed must be boolean')
                options = {'trust_confirmed': body['trustConfirmed']} if 'trustConfirmed' in body else {}
                return self.app.submit(connection_id, sid, "project.add", project_path, body,
                    lambda: codex.add_project(project_path, idempotency_key=sid, folder_name=body.get('folderName'), **options))
        if path == ["v1", "threads"]:
            if method == "GET":
                project = query.get("projectId", [""])[0]
                if not project:
                    raise ApiError(400, "invalid_request", "projectId is required")
                archived = query.get('archived', ['false'])[0]
                if archived not in ('true', 'false'):
                    raise ApiError(400, 'invalid_request', 'archived must be true or false')
                return {"threads": codex.list_sessions(project, archived=True) if archived == 'true' else codex.list_sessions(project)}
            if method == "POST":
                project, sid = required(body, "projectId"), required(body, "submissionId")
                options = permission_options(body)
                if body.get('worktreeRef'):
                    options['worktree_ref'] = body['worktreeRef']
                return self.app.submit(connection_id, sid, "thread.create", project, body,
                    lambda: codex.create_session(project, **options))
        if len(path) >= 3 and path[:2] == ["v1", "threads"]:
            if len(path) == 4 and path[3] == "ownership" and method == "GET":
                return codex.preview_handoff(path[2], query.get("operation", ["takeover"])[0], connection_id)
            thread_id = path[2]
            if len(path) == 3 and method == "GET":
                options = {'native_revision': query['nativeRevision'][0]} if query.get('nativeRevision') else {}
                limit = int(query.get("limit", ["8"])[0])
                cursor = query.get("cursor", [None])[0]
                result = codex.get_session(thread_id, cursor=cursor, limit=limit, **options)
                result["queue"] = self.app.queue.list(thread_id)
                return client_session(result, known_head_revision=query.get("knownHeadRevision", [None])[0],
                                      limit=limit, cursor=cursor)
            if len(path) == 4 and path[3] == "history" and method == "GET":
                options = {'native_revision': query['nativeRevision'][0]} if query.get('nativeRevision') else {}
                return client_history(codex.history(thread_id, cursor=query.get("cursor", [None])[0],
                                     limit=int(query.get("limit", ["30"])[0]), **options))
            if len(path) == 4 and method == 'GET':
                if path[3] == 'diff':
                    from .codex.diff_projection import diff_page
                    offset = int(query.get('offset', ['0'])[0])
                    if offset > 0 and not query.get('revision'):
                        raise ApiError(400, 'invalid_request', 'revision required for pagination')
                    return {'diff': diff_page(codex.diff(thread_id), revision=query.get('revision', [None])[0],
                        file_id=query.get('fileId', [None])[0], offset=offset,
                        limit=int(query.get('limit', ['400'])[0]))}
                if path[3] == 'review-context':
                    return codex.review_context(thread_id)
                if path[3] == 'workspace':
                    codex.start()
                    return {'workspace': codex.workspaces.inspect(thread_id) if codex.workspaces else None,
                            'externalWorkspace': codex.external_workspace(thread_id)}
            if len(path) == 5 and path[3:] == ['workspace', 'cleanup'] and method == 'POST':
                sid, revision = required(body, 'submissionId'), required(body, 'revision')
                discard = body.get('discard', False)
                if not isinstance(discard, bool):
                    raise ApiError(400, 'invalid_request', 'discard must be boolean')
                if self.app.queue.list(thread_id):
                    raise ApiError(409, 'workspace_busy', '请先处理此会话的执行队列')
                return self.app.submit(connection_id, sid, 'workspace.cleanup', thread_id, body,
                    lambda: codex.workspaces.cleanup(thread_id, revision, discard))
            if len(path) == 4 and path[3] == "queue" and method == "POST":
                sid, operation = required(body, "submissionId"), required(body, "operation")
                if operation == "enqueue":
                    items = native_input(body)
                    if not items: raise ApiError(400, "invalid_request", "input required")
                    for key in ("model", "effort"):
                        if body.get(key) is not None and (not isinstance(body[key], str) or len(body[key]) > 200):
                            raise ApiError(400, "invalid_request", "Invalid model settings")
                    if body.get('mode') not in (None, 'default', 'plan'):
                        raise ApiError(400, 'invalid_request', 'Invalid mode')
                    if body.get('serviceTier') not in (None, 'default', 'priority'):
                        raise ApiError(400, 'invalid_request', 'Invalid serviceTier')
                    fn = lambda: self.app.queue.enqueue(thread_id, sid, connection_id, {"input": items, "model": body.get("model"), "effort": body.get("effort"), 'mode': body.get('mode'), 'serviceTier': body.get('serviceTier')})
                elif operation == "reorder":
                    ids = body.get("ids")
                    if not isinstance(ids, list) or not all(isinstance(i, str) for i in ids):
                        raise ApiError(400, "invalid_request", "ids required")
                    fn = lambda: self.app.queue.reorder(thread_id, ids, body.get("expectedIds"))
                elif operation in {"edit", "remove", "steer", "recover", "dismiss"}:
                    item_id = required(body, "itemId")
                    revision = body.get("revision")
                    if not isinstance(revision, int) or isinstance(revision, bool):
                        raise ApiError(400, "invalid_request", "revision required")
                    if operation == "steer":
                        turn_id = required(body, "expectedTurnId")
                        fn = lambda: self.app.queue.steer(thread_id, item_id, revision, turn_id)
                    elif operation == 'recover':
                        fn = lambda: self.app.queue.recover(thread_id, item_id, revision, sid, connection_id)
                    else:
                        fn = lambda: self.app.queue.change(thread_id, item_id, revision, operation, body.get("text"))
                else:
                    raise ApiError(400, "invalid_request", "Unknown queue operation")
                return self.app.submit(connection_id, sid, "thread.queue." + operation, thread_id, body, fn)
            if len(path) == 4 and method == "POST":
                action, sid = path[3], required(body, "submissionId")
                if action == "pin":
                    if not isinstance(body.get("pinned"), bool):
                        raise ApiError(400, "invalid_request", "pinned must be boolean")
                    fn = lambda: codex.pin_session(thread_id, body["pinned"])
                elif action == 'goal':
                    operation = body.get('operation')
                    if operation not in ('set', 'pause', 'resume', 'clear'):
                        raise ApiError(400, 'invalid_request', 'Invalid goal operation')
                    objective = required(body, 'objective', 200000) if operation == 'set' else None
                    budget = body.get('tokenBudget')
                    if budget is not None and (type(budget) is not int or not 0 < budget <= 9223372036854775807):
                        raise ApiError(400, 'invalid_request', 'tokenBudget must be a positive integer')
                    fn = lambda: codex.update_goal(thread_id, operation, objective=objective, token_budget=budget)
                elif action == 'settings':
                    if not any(body.get(key) is not None for key in ('model', 'effort', 'serviceTier', 'sandbox', 'approvalPolicy')):
                        raise ApiError(400, 'invalid_request', 'Settings required')
                    for key in ('model', 'effort'):
                        if body.get(key) is not None:
                            required(body, key, 200)
                    if body.get('serviceTier') not in (None, 'default', 'priority'):
                        raise ApiError(400, 'invalid_request', 'Invalid serviceTier')
                    options = permission_options(body)
                    fn = lambda: codex.update_settings(thread_id, model=body.get('model'), effort=body.get('effort'), service_tier=body.get('serviceTier'), **options)
                elif action == 'archive':
                    if not isinstance(body.get('archived'), bool):
                        raise ApiError(400, 'invalid_request', 'archived must be boolean')
                    if body['archived'] and self.app.queue.list(thread_id):
                        raise ApiError(409, 'thread_busy', '请先处理此会话的执行队列')
                    fn = lambda: codex.archive_session(thread_id, body['archived'])
                elif action == 'review':
                    from .codex.developer import validate_review
                    target = validate_review(body.get('target'))
                    fn = lambda: self.app.queue.enqueue(thread_id, sid, connection_id,
                        {'input': [{'type': 'text', 'text': '代码审查 · ' + target['type']}], 'review': target})
                elif action == "turns":
                    items = native_input(body)
                    text = required(body, "text", 200000) if items is None else body.get("text", "")
                    for key in ("model", "effort"):
                        if key in body and body[key] is not None and (not isinstance(body[key], str) or len(body[key]) > 200):
                            raise ApiError(400, "invalid_request", f"Invalid {key}")
                    kwargs = {"model": body.get("model"), "effort": body.get("effort"), "client_message_id": sid}
                    if body.get('mode') not in (None, 'default', 'plan'):
                        raise ApiError(400, 'invalid_request', 'Invalid mode')
                    if body.get('mode') is not None:
                        kwargs['mode'] = body['mode']
                    if body.get('serviceTier') not in (None, 'default', 'priority'):
                        raise ApiError(400, 'invalid_request', 'Invalid serviceTier')
                    if body.get('serviceTier') is not None:
                        kwargs['service_tier'] = body['serviceTier']
                    if items is not None:
                        kwargs["input_items"] = items
                    if 'requireGoalSupport' in body:
                        if type(body['requireGoalSupport']) is not bool:
                            raise ApiError(400, 'invalid_request', 'requireGoalSupport must be boolean')
                        kwargs['require_goal_support'] = body['requireGoalSupport']
                    fn = lambda: codex.send(thread_id, text, acquire_released=True, **kwargs)
                elif action == "steer":
                    items = native_input(body)
                    text = required(body, "text", 200000) if items is None else body.get("text", "")
                    turn_id = required(body, "expectedTurnId")
                    kwargs = {"expected_turn_id": turn_id, "client_message_id": sid}
                    if items is not None:
                        kwargs["input_items"] = items
                    fn = lambda: codex.steer(thread_id, text, **kwargs)
                elif action == "ownership":
                    operation = body.get("operation")
                    if operation not in ("takeover", "release", "resume"):
                        raise ApiError(400, "invalid_request", "Invalid ownership operation")
                    ticket = required(body, "ticket", 128)
                    options = {'rebind_workspace': required(body, 'rebindWorkspace', 4096)} if 'rebindWorkspace' in body else {}
                    def fn():
                        result = codex.handoff(thread_id, operation, request_id=self._request_id, owner=connection_id, ticket=ticket, confirmed=body.get("confirmed") is True, **options)
                        # Submission receipts contain identities only; release
                        # metadata belongs to the preview/control projection.
                        return {"threadId": result["threadId"]}
                elif action == "interrupt":
                    turn_id = required(body, "turnId")
                    fn = lambda: codex.interrupt(thread_id, turn_id=turn_id)
                else:
                    raise ApiError(404, "not_found", "Unknown operation")
                return self.app.submit(connection_id, sid, f"thread.{action}", thread_id, body, fn)
        if path == ["v1", "requests", "respond"] and method == "POST":
            key, sid = required(body, "key", 1024), required(body, "submissionId")
            response = body.get("response")
            if not isinstance(response, dict):
                raise ApiError(400, "invalid_request", "response must be an object")
            return self.app.submit(connection_id, sid, "request.respond", key, body,
                lambda: codex.respond(key, response))
        if len(path) == 4 and path[:2] == ["v1", "submissions"] and path[3] == "reconcile" and method == "POST":
            return public_submission(self.app.submissions.reconcile(path[2], connection_id))
        if len(path) == 3 and path[:2] == ["v1", "submissions"] and method == "GET":
            return public_submission(self.app.submissions.get(path[2], connection_id))
        if path == ["v1", "todos"]:
            if method == "GET":
                return {"todos": [public_todo(todo) for todo in self.app.todos.list()]}
            if method == "POST":
                todo = self.app.todos.create(required(body, "title", 1000),
                    notes=body.get("notes", ""), todo_id=body.get("id"), details=body.get("details", {}))
                self.app.events.publish({"method": "relay/todos", "params": {}})
                return {"todo": public_todo(todo)}
        if len(path) >= 3 and path[:2] == ["v1", "todos"]:
            todo_id = path[2]
            if len(path) == 4 and path[3] == "dispatch" and method == "POST":
                required(body, "projectId")
                required(body, "submissionId")
                if body.get("threadId") is not None:
                    required(body, "threadId")
                if "uploadedAttachments" in body:
                    uploads = body["uploadedAttachments"]
                    if not isinstance(uploads, list) or not 1 <= len(uploads) <= 16:
                        raise ApiError(400, "invalid_request", "本机附件数量无效")
                    for item in uploads:
                        if not isinstance(item, dict) or set(item) != {"name", "path"}:
                            raise ApiError(400, "invalid_request", "本机附件引用无效")
                        required(item, "name", 200)
                        native_path(required(item, "path", 4096))
                return self.app.dispatch_todo(todo_id, connection_id, body)
            if len(path) == 3:
                if method == "GET":
                    return {"todo": public_todo(self.app.todos.get(todo_id))}
                if method == "PATCH":
                    allowed = {"title", "notes", "completed", "details"}
                    if not body or not set(body) <= allowed:
                        raise ApiError(400, "invalid_request", "Unknown or empty todo fields")
                    todo = self.app.todos.update(todo_id, **body)
                    self.app.events.publish({"method": "relay/todos", "params": {}})
                    return {"todo": public_todo(todo)}
                if method == "DELETE":
                    self.app.todos.delete(todo_id)
                    self.app.events.publish({"method": "relay/todos", "params": {}})
                    return {"deleted": True}
        raise ApiError(404, "not_found", "Unknown endpoint")

    def stream_events(self, token: str):
        if not self.server.stream_slots.acquire(blocking=False):
            raise ApiError(429, "stream_limit", "连接过多，请稍后重连")
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache, no-store")
            self.send_header("X-Accel-Buffering", "no")
            self.send_header("Connection", "close")
            self.end_headers()
            cursor = self.headers.get("Last-Event-ID")
            heartbeat = time.monotonic()
            while not self.app.events.closed:
                if self.app.pairing.authenticate(token) is None:
                    self.wfile.write(b'event: revoked\ndata: {}\n\n')
                    self.wfile.flush()
                    break
                cursor, events, reset = self.app.events.read(cursor, timeout=1)
                if self.app.pairing.authenticate(token) is None:
                    self.wfile.write(b'event: revoked\ndata: {}\n\n')
                    self.wfile.flush()
                    break
                if reset:
                    self.wfile.write(f"id: {cursor}\nevent: reset\ndata: {{}}\n\n".encode())
                for index, event in enumerate(events):
                    # The batch cursor is attached only to the last message,
                    # so reconnecting midway cannot skip unconsumed events.
                    prefix = f"id: {cursor}\n" if index == len(events) - 1 else ""
                    raw = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
                    self.wfile.write(f"{prefix}event: native\ndata: {raw}\n\n".encode("utf-8"))
                if time.monotonic() - heartbeat >= 15:
                    self.wfile.write(b": heartbeat\n\n")
                    heartbeat = time.monotonic()
                self.wfile.flush()
        except OSError:
            pass
        finally:
            self.close_connection = True
            self.server.stream_slots.release()
