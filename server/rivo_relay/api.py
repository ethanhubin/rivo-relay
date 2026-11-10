from __future__ import annotations

from contextlib import nullcontext
from .read_budget import ReadStopped, read_budget

import gzip
import secrets
import select
import socket
import ssl
import json
import os
import time
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, unquote, urlsplit, quote

from .observability import error_code, event, request_id, request_trace, route_kind

from .codex.files import MAX_TRANSFER_BYTES
from .codex.native_download import byte_range
from .speech import MAX_SPEECH_BYTES, MAX_CHUNK_BYTES
from .media import MediaError
from .updates import latest_update, update_apk
from .routes import dispatch
# Preserve the existing import path for shared validation helpers.
from .routes.common import ApiError, required, permission_options, native_input


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
              error_code=getattr(self, '_error_code', None),
              read_stop_reason=getattr(self, '_read_stop_reason', None),
              headers_ms=round((time.monotonic() - self._request_started) * 1000, 2) if hasattr(self, "_request_started") else None)


    @property
    def app(self):
        return self.server.app

    def observe_history_media(self, thread):
        if not isinstance(thread, dict):
            return False
        try:
            self.app.media.observe_thread(thread)
            return True
        except Exception:
            # Failed registration must retain inline bytes as the fallback.
            return False

    def send_json(self, status: int, data: dict):
        if status >= 400:
            failure = data.get('error')
            self._error_code = error_code(failure.get('code')) if isinstance(failure, dict) else 'other'
        raw = json.dumps(data, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
        # Compress large read-only catalogs as well as history. Keep credentials,
        # mutation responses and streaming/binary endpoints out of this policy.
        path = urlsplit(self.path).path
        compressible = (
            self.command == "GET" and status == 200
            and (path in {"/v1/projects", "/v1/threads", "/v1/models", "/v1/todos", "/v1/activity"}
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
        self._error_code = None
        self._read_stop_reason = None
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
            with request_trace(self._request_id):
                return self._handle_api()
        except BaseException as error:
            outcome = type(error).__name__
            raise
        finally:
            if gated:
                self.app.update_gate.leave()
            if self._read_stop_reason:
                outcome = self._read_stop_reason
            elif isinstance(self._response_status, int) and self._response_status >= 400:
                outcome = 'http_error'
            event("node_request_finished", request_id=self._request_id,
                  status=self._response_status, outcome=outcome, route=route_kind(path),
                  error_code=self._error_code, read_stop_reason=self._read_stop_reason,
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
                update_body = self.read_body(limit=4096)
                if path[-1] == 'update-activate':
                    with self.app.update_gate.lock:
                        self.app.update_gate.until = 0
                        self.app.update_gate.lease = None
                    self.send_json(200, {'ready': True})
                    return
                gate = self.app.update_gate
                if path[-1] == 'update-stop' and 'lease' in update_body:
                    ready = gate.consume(update_body['lease'])
                else:
                    # Legacy agents may still use single-step stop. Preserve the
                    # protocol while preventing an expired/failed write from stopping.
                    ready = gate.acquire(self.app)
                    if ready and path[-1] == 'update-stop':
                        ready = gate.consume(gate.lease)
                try:
                    self.send_json(200 if ready else 409, {'ready': ready, 'reason': gate.reason,
                                   'lease': gate.lease if ready else None})
                except OSError:
                    if ready:
                        with gate.lock:
                            gate.until = 0
                            gate.lease = None
                    raise
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
                    route_binding=pairing_route_binding(self.app.config.workspace, self.app.config.public_url))
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
            if self.command == 'GET' and path == ['v1', 'sites']:
                from .codex.sites import list_sites
                self.send_json(200, list_sites(self.app.codex.connection, query.get('cursor', [None])[0]))
                return

            if self.command == 'GET' and path == ['v1', 'node-updates', 'status']:
                from .gateway.update_status import public_status
                self.send_json(200, public_status(self.app.config.workspace))
                return
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
                self.send_json(200, self.app.speech.stream(connection['connection_id'], path[3], 'chunks', self.read_bounded(MAX_CHUNK_BYTES)))
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
                    and path[3] == "attachments"):
                if self.headers.get_content_type() != "application/octet-stream":
                    raise ApiError(415, "invalid_request", "附件需要二进制上传")
                self.send_json(200, self.receive_file(path[2], query, project=path[1] == 'projects'))
                return
            if self.command == "GET" and path == ["v1", "events"]:
                self.stream_events(token)
                return
            body = self.read_body() if self.command in {"POST", "PATCH"} else {}
            with read_budget(self.connection) if self.command == "GET" or url.path.endswith("/prediction") else nullcontext():
                result = self.route(path, query, body, connection)
            self.send_json(200, result)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True
        except Exception as error:
            if isinstance(error, ReadStopped):
                self._read_stop_reason = error.reason
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
        return dispatch(self, path, query, body, connection)

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
                # The connector closes this plaintext socket as soon as the
                # phone leaves. Reclaim its slot without waiting for the next
                # heartbeat write (which can itself succeed after a TCP FIN).
                if not isinstance(self.connection, ssl.SSLSocket):
                    if select.select([self.connection], [], [], 0)[0]:
                        if not self.connection.recv(1, socket.MSG_PEEK):
                            break
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
