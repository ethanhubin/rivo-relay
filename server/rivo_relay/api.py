from __future__ import annotations

import base64
import secrets
import json
import time
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, unquote, urlsplit, quote

from .app import public_submission, public_todo
from .codex.files import MAX_TRANSFER_BYTES, native_path


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status, self.code = status, code
        super().__init__(message)


def required(body: dict, key: str, limit: int = 200) -> str:
    value = body.get(key)
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise ApiError(400, "invalid_request", f"{key} is required")
    return value


def native_input(body: dict) -> list[dict] | None:
    items = body.get("input")
    if items is None:
        return None
    if not isinstance(items, list) or not 1 <= len(items) <= 64:
        raise ApiError(400, "invalid_input", "input must contain 1–64 native items")
    for item in items:
        if not isinstance(item, dict):
            raise ApiError(400, "invalid_input", "Invalid native input item")
        kind = item.get("type")
        if kind == "text":
            required(item, "text", 200000)
        elif kind == "image":
            if not (isinstance(item.get("url"), str) and item["url"] or isinstance(item.get("fileId"), str) and item["fileId"]):
                raise ApiError(400, "invalid_input", "Image needs a URL or native file ID")
        elif kind == "audio":
            required(item, "url", 8 * 1024 * 1024)
        elif kind in {"localImage", "localAudio", "mention", "skill"}:
            required(item, "path", 4096)
            if kind not in {"localImage", "localAudio"}:
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

    @property
    def app(self):
        return self.server.app

    def send_json(self, status: int, data: dict):
        raw = json.dumps(data, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(raw)
        self.close_connection = True

    def send_file(self, path):
        if not self.app.file_transfer_slots.acquire(blocking=False):
            raise ApiError(429, "transfer_busy", "文件传输繁忙，请稍后重试")
        try:
            self.connection.settimeout(120)
            size, chunks = self.app.codex.stream_download(path)
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            name = path.replace("\\", "/").rsplit("/", 1)[-1]
            self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + quote(name, safe=""))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Connection", "close")
            self.end_headers()
            self._file_started = True
            self.close_connection = True
            for data in chunks:
                self.wfile.write(data)
        finally:
            self.app.file_transfer_slots.release()

    def receive_file(self, thread_id, query):
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
            return self.app.codex.upload_attachment_stream(thread_id, name, self.rfile, size)
        finally:
            self.app.file_transfer_slots.release()

    def read_body(self) -> dict:
        if self.headers.get("Transfer-Encoding"):
            raise ApiError(400, "invalid_request", "Chunked requests are not supported")
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) != 1:
            raise ApiError(411, "invalid_request", "One Content-Length is required")
        try:
            length = int(lengths[0])
        except ValueError:
            raise ApiError(400, "invalid_request", "Invalid Content-Length")
        if not 0 < length <= 8 * 1024 * 1024:
            raise ApiError(413, "request_too_large", "Request must be at most 8 MiB")
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
        try:
            url = urlsplit(self.path)
            if url.scheme or url.netloc:
                raise ApiError(400, "invalid_request", "Origin-form request required")
            path = [unquote(part) for part in url.path.strip("/").split("/")]
            query = parse_qs(url.query)
            if any("/" in part or "\x00" in part for part in path):
                raise ApiError(400, "invalid_request", "Invalid path")
            if self.command == "GET" and path == ["health"]:
                self.send_json(200, {"status": "ok"})
                return
            if path == ["v1", "pair"] and self.command == "POST":
                body = self.read_body()
                result = self.app.pairing.exchange(required(body, "code", 512),
                    required(body, "deviceName", 100), client_key=self.client_address[0])
                self.send_json(200, {"token": result["token"], "deviceId": result["connection_id"],
                    "node": {"id": self.app.config.node_id, "name": self.app.config.name}})
                return
            if self.command == "GET" and len(path) == 3 and path[:2] == ["v1", "download-links"]:
                with self.app.download_lock:
                    ticket = self.app.download_links.get(path[2])
                if ticket is None or ticket[1] < time.time():
                    raise ApiError(404, "expired_download", "下载链接已过期")
                self.send_file(ticket[0])
                return
            token, connection = self.authenticate()
            if self.command == "GET" and path == ["v1", "files", "download"]:
                self.send_file(required({"path": query.get("path", [None])[0]}, "path", 4096))
                return
            if (self.command == "POST" and len(path) == 4 and path[:2] == ["v1", "threads"]
                    and path[3] == "attachments" and self.headers.get_content_type() == "application/octet-stream"):
                self.send_json(200, self.receive_file(path[2], query))
                return
            if self.command == "GET" and path == ["v1", "events"]:
                self.stream_events(token)
                return
            body = self.read_body() if self.command in {"POST", "PATCH"} else {}
            result = self.route(path, query, body, connection)
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
            message = str(error) if isinstance(error, ApiError) else {
                400: "请求内容无效", 401: "配对码无效或已过期", 404: "记录不存在",
                409: "操作状态已变化，请刷新", 429: "尝试过于频繁，请稍后再试",
                501: "当前节点版本暂不支持此功能",
                413: "文件超过此操作的大小限制",
            }.get(status, "Desktop 连接暂不可用，请检查电脑端连接")
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
        if path == ["v1", "models"] and method == "GET":
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
        if path == ["v1", "projects"]:
            if method == "GET":
                return {"projects": codex.list_projects()}
            if method == "POST":
                project_path = required(body, "path", 4096)
                sid = required(body, "submissionId")
                return self.app.submit(connection_id, sid, "project.add", project_path, body,
                    lambda: codex.add_project(project_path, idempotency_key=sid, folder_name=body.get('folderName')))
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
                return self.app.submit(connection_id, sid, "thread.create", project, body,
                    lambda: codex.create_session(project, worktree_ref=body['worktreeRef']) if body.get('worktreeRef') else codex.create_session(project))
        if len(path) >= 3 and path[:2] == ["v1", "threads"]:
            thread_id = path[2]
            if len(path) == 3 and method == "GET":
                result = codex.get_session(thread_id, cursor=query.get("cursor", [None])[0], limit=int(query.get("limit", ["30"])[0]))
                result["queue"] = self.app.queue.list(thread_id)
                return result
            if len(path) == 4 and path[3] == "history" and method == "GET":
                return codex.history(thread_id, cursor=query.get("cursor", [None])[0],
                                     limit=int(query.get("limit", ["30"])[0]))
            if len(path) == 4 and method == 'GET':
                if path[3] == 'diff':
                    return {'diff': codex.diff(thread_id)}
                if path[3] == 'review-context':
                    return codex.review_context(thread_id)
                if path[3] == 'workspace':
                    return {'workspace': codex.workspaces.inspect(thread_id) if codex.workspaces else None}
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
                    fn = lambda: self.app.queue.enqueue(thread_id, sid, connection_id, {"input": items, "model": body.get("model"), "effort": body.get("effort"), 'mode': body.get('mode')})
                elif operation == "reorder":
                    ids = body.get("ids")
                    if not isinstance(ids, list) or not all(isinstance(i, str) for i in ids):
                        raise ApiError(400, "invalid_request", "ids required")
                    fn = lambda: self.app.queue.reorder(thread_id, ids, body.get("expectedIds"))
                elif operation in {"edit", "remove", "steer"}:
                    item_id = required(body, "itemId")
                    revision = body.get("revision")
                    if not isinstance(revision, int) or isinstance(revision, bool):
                        raise ApiError(400, "invalid_request", "revision required")
                    if operation == "steer":
                        turn_id = required(body, "expectedTurnId")
                        fn = lambda: self.app.queue.steer(thread_id, item_id, revision, turn_id)
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
                    if items is not None:
                        kwargs["input_items"] = items
                    fn = lambda: codex.send(thread_id, text, **kwargs)
                elif action == "steer":
                    items = native_input(body)
                    text = required(body, "text", 200000) if items is None else body.get("text", "")
                    turn_id = required(body, "expectedTurnId")
                    kwargs = {"expected_turn_id": turn_id, "client_message_id": sid}
                    if items is not None:
                        kwargs["input_items"] = items
                    fn = lambda: codex.steer(thread_id, text, **kwargs)
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
