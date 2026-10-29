"""The native project catalog includes projects without conversations."""
from __future__ import annotations

import os
import uuid

from .connection import CodexError


def pages(connection, method, params=None):
    cursor = None
    seen = set()
    while True:
        result = connection.request(method, {**(params or {}), "cursor": cursor, "limit": 100})
        data = result.get("data")
        if not isinstance(data, list):
            raise CodexError("Codex 返回了无法识别的分页数据", code="native_protocol")
        yield from data
        cursor = result.get("nextCursor")
        if cursor is None:
            return
        if cursor in seen:
            raise CodexError("Codex 分页游标没有前进", code="native_protocol")
        seen.add(cursor)


class ProjectCatalog:
    def __init__(self, connection):
        self.connection = connection

    def list(self):
        projects = list(pages(self.connection, "project/list"))
        threads = list(pages(self.connection, "thread/list", {"archived": False,
                            "modelProviders": [], "sortKey": "updated_at", "sortDirection": "desc"}))
        result = []
        for project in projects:
            roots = [r.get("path") for r in project.get("roots", []) if r.get("path")]
            matches = [t for t in threads if t.get("projectId") == project["id"] or
                       (t.get("projectId") is None and t.get("cwd") in roots)]
            result.append({"id": project["id"], "name": project.get("name", ""),
                           "path": roots[0] if roots else "", "roots": roots,
                           "threadCount": len(matches),
                           # Active includes native approval/input waits. There
                           # is no Relay delivery queue or history scan here.
                           "outstandingWorkCount": sum(
                               1 for thread in matches
                               if isinstance(thread.get("status"), dict)
                               and thread["status"].get("type") == "active")})
        return result

    def get(self, project_id):
        result = self.connection.request("project/read", {"projectId": project_id})
        project = result.get("project")
        if not isinstance(project, dict):
            raise CodexError("Codex 项目数据不可用", code="native_protocol")
        return project

    def add(self, path, *, name=None, idempotency_key=None):
        # Validate syntax only: this may be a remote daemon's filesystem.
        if not isinstance(path, str) or not os.path.isabs(path) or "\x00" in path:
            raise CodexError("项目路径必须是绝对路径", code="invalid_path", http_status=400)
        result = self.connection.request("project/create", {
            "name": name or os.path.basename(path.rstrip("/")) or path,
            "roots": [{"path": path}], "idempotencyKey": idempotency_key or uuid.uuid4().hex,
        })
        project = result["project"]
        return {"id": project["id"], "name": project["name"], "path": path,
                "roots": [r["path"] for r in project.get("roots", [])], "threadCount": 0,
                "outstandingWorkCount": 0}
