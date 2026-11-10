"""The native project catalog includes projects without conversations."""
from __future__ import annotations

import json
import threading
import time

import os
import uuid

from .connection import CodexError
from .thread_catalog import ThreadCatalog
from ..observability import event
from ..read_budget import checkpoint


RECENT_PROJECT_ID = "__rivo_recent__"


def unlisted_threads(threads, projects, associations):
    ids = {project["id"] for project in projects}
    roots = {root.get("path") for project in projects for root in project.get("roots", []) if root.get("path")}
    return [thread for thread in threads if thread.get("projectId") not in ids and not (
        not thread.get("projectId") and (thread.get("cwd") in roots or thread["id"] in associations))]


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


def thread_pages(connection, params):
    """Use the same indexed catalog as Desktop, never scan rollout files here.

    The native disk scan can take seconds per page and omits some indexed
    agent-created chats. History reconciliation belongs to opening a thread,
    not navigation. Preserve every native query filter and exhaust DB cursors.
    """
    return list(pages(connection, 'thread/list', {**params, 'useStateDbOnly': True}))


class ProjectCatalog:
    def __init__(self, connection, *, thread_reader=None):
        self.connection = connection
        self.thread_catalog = ThreadCatalog(connection, thread_reader or (lambda params: thread_pages(connection, params)))
        self.external_worktrees = None
        self._trust_lock = threading.Lock()

    def invalidate(self):
        self.thread_catalog.invalidate()


    def list(self):
        """Navigation metadata never waits for thread scans or counter refreshes."""
        projects = self._stage('project_summary', lambda: list(pages(self.connection, 'project/list')))
        result = []
        for project in projects:
            roots = [root['path'] for root in project.get('roots', []) if root.get('path')]
            result.append({'id': project['id'], 'name': project.get('name', ''),
                           'path': roots[0] if roots else '', 'roots': roots})
        result.append({'id': RECENT_PROJECT_ID, 'name': '最近', 'path': '', 'roots': []})
        return result


    def _stage(self, stage, read):
        started = time.monotonic()
        outcome, count = 'interrupted', None
        try:
            checkpoint()
            result = read()
            count = len(result)
            checkpoint()
            outcome = 'completed'
            return result
        finally:
            event('project_catalog_stage', stage=stage, outcome=outcome, count=count,
                  duration_ms=round((time.monotonic() - started) * 1000, 2))

    def get(self, project_id):
        result = self.connection.request("project/read", {"projectId": project_id})
        project = result.get("project")
        if not isinstance(project, dict):
            raise CodexError("Codex 项目数据不可用", code="native_protocol")
        return project

    def trust(self, path):
        """Persist only the explicitly selected node directory's trust choice."""
        def trusted(response):
            config = response.get('config')
            if not isinstance(config, dict):
                raise CodexError('Codex 配置不可用', code='native_protocol')
            projects = config.get('projects')
            if projects is None:
                projects = {}
            if not isinstance(projects, dict):
                raise CodexError('Codex 项目信任配置无效', code='native_protocol')
            project = projects.get(path)
            if project is None:
                project = {}
            if not isinstance(project, dict):
                raise CodexError('Codex 项目信任配置无效', code='native_protocol')
            return project.get('trust_level') == 'trusted'
        with self._trust_lock:
            response = self.connection.request('config/read', {'includeLayers': True})
            if trusted(response):
                return
            user = next((layer for layer in response.get('layers') or []
                         if isinstance(layer, dict) and (layer.get('name') or {}).get('type') == 'user'), {})
            # Omitted filePath uses the connected node's user config. A remote
            # node must never receive the Relay host's CODEX_HOME path.
            result = self.connection.request('config/batchWrite', {
                'edits': [{'keyPath': 'projects.' + json.dumps(path, ensure_ascii=False) + '.trust_level',
                           'value': 'trusted', 'mergeStrategy': 'replace'}],
                'expectedVersion': user.get('version'),
            })
            if result.get('status') != 'ok' or not trusted(self.connection.request('config/read', {'includeLayers': False})):
                raise CodexError('无法确认文件夹信任，项目尚未登记', code='project_trust_unconfirmed', http_status=409)

    def add(self, path, *, name=None, idempotency_key=None, trust_confirmed=False):
        # Validate syntax only: this may be a remote daemon's filesystem.
        if not isinstance(path, str) or not os.path.isabs(path) or "\x00" in path:
            raise CodexError("项目路径必须是绝对路径", code="invalid_path", http_status=400)
        if not isinstance(trust_confirmed, bool):
            raise CodexError('文件夹信任确认必须是布尔值', code='invalid_request', http_status=400)
        if trust_confirmed:
            # fs/readDirectory verifies this path on the node, not the Relay host.
            result = self.connection.request('fs/readDirectory', {'path': path})
            canonical = result.get('path')
            if isinstance(canonical, str) and os.path.isabs(canonical):
                path = canonical
            self.trust(path)
        result = self.connection.request("project/create", {
            "name": name or os.path.basename(path.rstrip("/")) or path,
            "roots": [{"path": path}], "idempotencyKey": idempotency_key or uuid.uuid4().hex,
        })
        project = result["project"]
        self.invalidate()
        return {"id": project["id"], "name": project["name"], "path": path,
                "roots": [r["path"] for r in project.get("roots", [])]}
