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
    if path in (['v1', 'usage', 'reset'], ['v1', 'usage', 'reset', 'reconcile']) and method == 'POST':
        reconcile = path[-1] == 'reconcile'
        allowed = {'submissionId'} if reconcile else {'submissionId', 'creditId'}
        if 'submissionId' not in body or set(body) - allowed:
            raise ApiError(400, 'invalid_request', 'Expected submissionId and optional creditId')
        credit_id = required(body, 'creditId', 2048) if 'creditId' in body else None
        return handler.app.reset_usage(connection_id, required(body, 'submissionId'),
                                       credit_id=credit_id, reconcile=reconcile)
    if path == ["v1", "activity"] and method == "GET":
        return handler.app.sessions.activity()
    if path == ["v1", "interactions", "respond"] and method == "POST":
        sid = required(body, 'submissionId')
        session_id = required(body, 'sessionId')
        generation = required(body, 'runtimeGeneration', 1024)
        interaction_id = required(body, 'interactionId', 1024)
        revision = required(body, 'revision', 1024)
        response = body.get('response')
        if not isinstance(response, dict):
            raise ApiError(400, 'invalid_request', 'response must be an object')
        # Submission identity binds the entire immutable body, including scope
        # and generation. An accepted replay does not re-answer the interaction.
        return handler.app.submit(connection_id, sid, 'interaction.respond', session_id, body,
            lambda: {**handler.app.sessions.respond(session_id, generation, interaction_id, revision, response),
                     'kind': 'mutation-applied', 'submissionId': sid})
    if path == ["v1", "requests", "respond"] and method == "POST":
        key, sid = required(body, "key", 1024), required(body, "submissionId")
        response = body.get("response")
        if not isinstance(response, dict):
            raise ApiError(400, "invalid_request", "response must be an object")
        return handler.app.submit(connection_id, sid, "request.respond", key, body,
            lambda: handler.app.sessions.respond_legacy(key, response))
    if len(path) == 4 and path[:2] == ["v1", "submissions"] and path[3] == "reconcile" and method == "POST":
        handler.app.submissions.reconcile(path[2], connection_id)
        return public_submission(handler.app.submissions.recover_native(path[2], connection_id, handler.app.backend))
    if len(path) == 3 and path[:2] == ["v1", "submissions"] and method == "GET":
        return public_submission(handler.app.submissions.recover_native(path[2], connection_id, handler.app.backend))
    raise ApiError(404, "not_found", "Unknown endpoint")
