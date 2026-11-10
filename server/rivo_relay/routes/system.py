"""Authenticated JSON routes for system resources."""

from ..app import public_submission
from .common import ApiError, required


def route(handler, path, query, body, connection):
    method = handler.command
    codex = handler.app.codex
    connection_id = connection["connection_id"]
    if path == ["v1", "node"] and method == "GET":
        return handler.app.node()
    if path == ["v1", "goals", "capabilities"] and method == "GET":
        return codex.goal_capabilities()
    if path == ["v1", "models"] and method == "GET":
        return codex.capabilities(refresh=query.get('refresh', ['0'])[0] == '1')
    if path == ['v1', 'usage'] and method == 'GET':
        return {'usage': codex.usage()}
    if path == ["v1", "activity"] and method == "GET":
        return codex.active_tasks()
    if path == ["v1", "requests", "respond"] and method == "POST":
        key, sid = required(body, "key", 1024), required(body, "submissionId")
        response = body.get("response")
        if not isinstance(response, dict):
            raise ApiError(400, "invalid_request", "response must be an object")
        return handler.app.submit(connection_id, sid, "request.respond", key, body,
            lambda: codex.respond(key, response))
    if len(path) == 4 and path[:2] == ["v1", "submissions"] and path[3] == "reconcile" and method == "POST":
        return public_submission(handler.app.submissions.reconcile(path[2], connection_id))
    if len(path) == 3 and path[:2] == ["v1", "submissions"] and method == "GET":
        return public_submission(handler.app.submissions.get(path[2], connection_id))
    raise ApiError(404, "not_found", "Unknown endpoint")
