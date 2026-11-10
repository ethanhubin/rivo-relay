"""Authenticated JSON routes for projects resources."""

from .common import ApiError, required


def route(handler, path, query, body, connection):
    method = handler.command
    codex = handler.app.codex
    connection_id = connection["connection_id"]
    if path == ['v1', 'project-directories'] and method == 'GET':
        return codex.project_directory(query.get('path', [None])[0])
    if len(path) == 4 and path[:2] == ['v1', 'projects'] and path[3] == 'git' and method == 'GET':
        return {'git': codex.project_git(path[2])}
    if path == ["v1", "projects"]:
        if method == "GET":
            view = query.get('view', ['full'])[0]
            if view not in {'full', 'summary'}:
                raise ApiError(400, 'invalid_request', 'Unknown project view')
            return {"projects": codex.list_projects(summary=True) if view == 'summary' else codex.list_projects()}
        if method == "POST":
            project_path = required(body, "path", 4096)
            sid = required(body, "submissionId")
            if 'trustConfirmed' in body and not isinstance(body['trustConfirmed'], bool):
                raise ApiError(400, 'invalid_request', 'trustConfirmed must be boolean')
            options = {'trust_confirmed': body['trustConfirmed']} if 'trustConfirmed' in body else {}
            return handler.app.submit(connection_id, sid, "project.add", project_path, body,
                lambda: codex.add_project(project_path, idempotency_key=sid, folder_name=body.get('folderName'), **options))
    raise ApiError(404, "not_found", "Unknown endpoint")
