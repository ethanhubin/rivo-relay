"""Authenticated JSON routes for todos resources."""

from ..app import public_todo
from ..codex.files import native_path
from .common import ApiError, required


def route(handler, path, query, body, connection):
    method = handler.command
    connection_id = connection["connection_id"]
    if path == ["v1", "todos"]:
        if method == "GET":
            return {"todos": [public_todo(todo) for todo in handler.app.todos.list()]}
        if method == "POST":
            todo = handler.app.todos.create(required(body, "title", 1000),
                notes=body.get("notes", ""), todo_id=body.get("id"), details=body.get("details", {}))
            handler.app.events.publish({"method": "relay/todos", "params": {}})
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
            return handler.app.dispatch_todo(todo_id, connection_id, body)
        if len(path) == 3:
            if method == "GET":
                return {"todo": public_todo(handler.app.todos.get(todo_id))}
            if method == "PATCH":
                allowed = {"title", "notes", "completed", "details"}
                if not body or not set(body) <= allowed:
                    raise ApiError(400, "invalid_request", "Unknown or empty todo fields")
                todo = handler.app.todos.update(todo_id, **body)
                handler.app.events.publish({"method": "relay/todos", "params": {}})
                return {"todo": public_todo(todo)}
            if method == "DELETE":
                handler.app.todos.delete(todo_id)
                handler.app.events.publish({"method": "relay/todos", "params": {}})
                return {"deleted": True}
    raise ApiError(404, "not_found", "Unknown endpoint")
