"""The native project catalog includes projects without conversations."""
from __future__ import annotations

import copy
import json
import threading
import time
from collections import defaultdict

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
        self._read_lock = threading.Lock()
        self._cache_lock = threading.Lock()
        self._revision = 0
        self._cached = None
        self._expires = 0.0
        self.queue_provider = lambda: {}
        self.goal_provider = lambda thread_id: None
        self.execution_provider = lambda threads: {}
        self.external_worktrees = None
        self._trust_lock = threading.Lock()

    def invalidate(self):
        with self._cache_lock:
            self._revision += 1
            self._cached = None

    def list(self):
        # Share concurrent reads; lifecycle events invalidate without waiting on RPCs.
        with self._read_lock:
            with self._cache_lock:
                if self._cached is not None and time.monotonic() < self._expires:
                    return copy.deepcopy(self._cached)
                revision = self._revision
            result = self._list()
            with self._cache_lock:
                if revision == self._revision:
                    self._cached = copy.deepcopy(result)
                    self._expires = time.monotonic() + 2.0
            return result

    def _list(self):
        projects = list(pages(self.connection, "project/list"))
        threads = list(pages(self.connection, "thread/list", {"archived": False,
                            "modelProviders": [], "sortKey": "updated_at", "sortDirection": "desc"}))
        by_project, by_root = defaultdict(list), defaultdict(list)
        # Native project IDs already establish catalog membership; only legacy
        # unassigned threads need additional read-only Git discovery here.
        associations = self.external_worktrees.associations([thread for thread in threads if not thread.get('projectId')], projects) if self.external_worktrees else {}
        for thread in threads:
            if thread['id'] in associations:
                by_project[associations[thread['id']]['projectId']].append(thread)
            elif thread.get("projectId") is None:
                by_root[thread.get("cwd")].append(thread)
            else:
                by_project[thread["projectId"]].append(thread)
        result = []
        queues = self.queue_provider()
        execution = self.execution_provider(threads)
        # Goals continue between turns and can belong to another native writer.
        # Query native goal state independently of thread/list's loaded status.
        active_goals = {thread['id'] for thread in threads
                        if (self.goal_provider(thread['id']) or {}).get('status') == 'active'}
        for project in projects:
            roots = [r.get("path") for r in project.get("roots", []) if r.get("path")]
            matches = by_project[project["id"]] + [t for root in set(roots) for t in by_root[root]]
            result.append({"id": project["id"], "name": project.get("name", ""),
                           "path": roots[0] if roots else "", "roots": roots,
                           "threadCount": len(matches),
                           "outstandingWorkCount": sum(
                               1 for thread in matches if queues.get(thread['id']) or thread['id'] in active_goals or execution.get(thread['id']) == 'running' or
                               (isinstance(thread.get("status"), dict) and thread['status'].get('type') == 'active')),
                           "runningTurnCount": sum(1 for thread in matches if execution.get(thread['id']) == 'running' or (isinstance(thread.get('status'), dict) and thread['status'].get('type') == 'active')),
                           "activeGoalCount": sum(thread['id'] in active_goals for thread in matches),
                           "queuedTaskCount": sum(sum(item['status'] == 'waiting' for item in queues.get(thread['id'], [])) for thread in matches),
                           "recoveryCount": sum(any(item.get('recoveryRequired') for item in queues.get(thread['id'], [])) for thread in matches)})
        return result

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
                "roots": [r["path"] for r in project.get("roots", [])], "threadCount": 0,
                "outstandingWorkCount": 0}
