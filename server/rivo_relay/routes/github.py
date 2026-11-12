"""Authenticated GitHub PR watch and device authorization routes."""
from .common import ApiError, required


def route(handler, path, query, body, connection):
    service = handler.app.github_watches
    owner = connection["connection_id"]
    method = handler.command
    if path == ["v1", "github", "auth"]:
        if method == "GET":
            return service.auth_status(owner)
        if method == "DELETE":
            return service.auth_delete(owner)
    if path == ["v1", "github", "auth", "start"] and method == "POST":
        return service.auth_start(owner)
    if len(path) == 5 and path[:4] == ["v1", "github", "auth", "flows"] and method == "GET":
        return service.auth_poll(owner, path[4])
    if path == ["v1", "github", "watches"]:
        if method == "GET":
            return service.list(owner)
        if method == "POST":
            if set(body) != {"repository", "branch"}:
                raise ApiError(400, "invalid_request", "需要仓库和目标分支")
            return {"watch": service.create(owner, required(body, "repository", 201), required(body, "branch", 200))}
    if path == ["v1", "github", "scan"] and method == "POST":
        return service.request_fast_scan(owner)
    if path == ["v1", "github", "foreground"] and method == "POST":
        if set(body) != {"active", "sessionId"} or type(body["active"]) is not bool or not isinstance(body["sessionId"], str) or not 1 <= len(body["sessionId"]) <= 64:
            raise ApiError(400, "invalid_request", "前台状态无效")
        return service.set_foreground(owner, body["active"], body["sessionId"])
    if len(path) == 4 and path[:3] == ["v1", "github", "watches"] and method == "DELETE":
        return service.delete(owner, path[3])
    if path == ["v1", "github", "alerts"] and method == "GET":
        try:
            after = int(query.get("after", ["0"])[0])
        except (ValueError, TypeError):
            raise ApiError(400, "invalid_cursor", "提醒游标无效") from None
        return service.alerts(owner, after)
    raise ApiError(404, "not_found", "Unknown endpoint")
