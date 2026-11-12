"""Native threads are the sole history and execution authority.

Only live native notifications are held in memory here. Relay waiting tasks are
stored separately; explicit handoff terminates only previewed native lock holders.
"""
from __future__ import annotations

import copy
import hashlib
import json
import logging
import threading
import time
from pathlib import Path
from .writers import WriterProcesses
from collections import OrderedDict

from ..operation_gates import ThreadGates
from .connection import CodexError, NativeConnection
from .history import NativeHistoryAdapter, NativeHistoryProtocolError
from .host_probe import read_identity
from .live_items import ITEM_EVENTS, TERMINAL, project_item, merge_item
from .native_journal import NativeJournal
from .interactions import Interactions
from .projects import ProjectCatalog, pages, thread_pages, RECENT_PROJECT_ID, unlisted_threads
from .files import read_file, read_directory, upload_attachment_stream, stream_download
from .developer import (project_directory, project_git, review_context, workspace_diff,
                        diff_summary, validate_review, Workspaces)
from .observations import Observations
from .projection import execution_projection, control_projection
from .models import ModelCatalog
from .attachment_context import attachment_context
from .image_inputs import needs_image_staging, prepare_image_inputs
from .external_worktrees import ExternalWorktrees
from .native_download import prepare_download
from .review_guard import ReviewGuard
from .session_handoff import SessionHandoffMixin


def thread_status(thread):
    status = thread.get("status", {})
    kind = status.get("type") if isinstance(status, dict) else status
    if kind == "active":
        return "running"
    if kind == "idle":
        return "idle"
    return "unknown"


def goal_running(thread, goal, external):
    # An interrupted/unloaded writer can leave a persisted active goal behind.
    # Preserve between-turn execution for loaded writers, but do not override
    # authoritative idle evidence from the native journal for an unloaded one.
    return ((goal or {}).get('status') == 'active'
            and not (thread_status(thread) == 'unknown'
                     and external.get(thread['id']) == 'idle'))


def thread_waiting(thread):
    status = thread.get('status')
    return (isinstance(status, dict) and status.get('type') == 'active'
            and any(flag in ('waitingOnApproval', 'waitingOnUserInput')
                    for flag in (status.get('activeFlags') or [])))


def awaiting_rollout(error):
    text = str(error).lower()
    return error.code == 'native_rejected' and (any(phrase in text for phrase in
        ('not materialized', 'no rollout', 'missing source rollout')) or ('rollout' in text and 'is empty' in text))


def missing_workspace(error):
    text = str(error).lower()
    return error.code == 'native_rejected' and any(word in text for word in ('cwd', 'working directory', 'workspace')) and any(
        word in text for word in ('no such file', 'does not exist', 'not found', 'missing'))


class CodexService(SessionHandoffMixin):
    def __init__(self, socket_path=None, on_event=None, codex_home=None, *, connection=None,
                 runtime_mode='app_server', desktop_bridge_dir=None, codex_executable=None):
        self.on_event = on_event or (lambda event: None)
        self._lock = threading.RLock()
        self._gates = ThreadGates()
        self.operation_gate = self._gates.hold
        self._prediction_lock = threading.Lock()
        self._prediction_cache = {}
        self._prediction_inflight = set()
        self._prediction_epochs = {}
        self._live = OrderedDict()
        self._pending_terminal = set()
        self._completed_items = {}
        self._active = {}
        self._settings = {}
        self._created = {}
        self._materializing = {}
        self._unmaterialized = set()
        self._joined = set()
        self._handoff_workspaces = OrderedDict()
        self._handoff_hosts = OrderedDict()
        self._subscription_releases = OrderedDict()
        self._handoff_capabilities = OrderedDict()
        self._generation = None
        self.observations = None
        self.workspaces = None
        self._observed = OrderedDict()
        if connection is None:
            from .runtime import RuntimeConnection
            connection = RuntimeConnection(socket_path, self._event, codex_home,
                                           mode=runtime_mode, bridge_directory=desktop_bridge_dir, codex_executable=codex_executable)
        self.connection = connection or NativeConnection(socket_path, self._event, codex_home)
        if connection is not None:
            connection.on_event = self._event
        self.writers = WriterProcesses(getattr(self.connection, 'codex_home', codex_home or Path.home() / '.codex'))
        self._handoff_log = self.writers.home / 'rivo-handoff-runtime.log'
        self.projects = ProjectCatalog(self.connection, thread_reader=lambda params: thread_pages(self.connection, params))
        self.projects.external_worktrees = ExternalWorktrees(self.connection, self._observe, self.observations_for)
        self.review_guard = ReviewGuard(self.connection, self._observe, self.observations_for, self._forget_observation)
        self.interactions = Interactions(self.connection)
        from .questions import QuestionClaims
        self.question_claims = QuestionClaims()
        self.history_adapter = NativeHistoryAdapter()
        self.native_journal = NativeJournal(self.connection, self.history_adapter, self._history_repaired)
        from .activity import ActivityRegistry
        self._activity_discovery = None
        self.activity = ActivityRegistry(self._load_activity, lambda: str(self.connection.generation))
        self.model_catalog = ModelCatalog(self._load_models, lambda: str(self.connection.generation))

    def _history_repaired(self, thread_id, result):
        # Keep the native body/IDs authoritative and force clients to discard
        # cursors from the projection that was replaced while its writer was idle.
        self.on_event({'method': 'relay/historyInvalidated', 'params': {
            'threadId': thread_id, 'reason': 'native_reprojection'}})

    def _native_history_state(self, thread_id):
        with self._lock:
            cwd = self._settings.get(thread_id, {}).get('cwd', '')
        state = self.native_journal.ensure(thread_id, workspace_root=cwd)
        first_rollout_pending = self._materializing_thread(thread_id) is not None and (
            awaiting_rollout(CodexError(state.error, code='native_rejected'))
            or state.error == 'Codex 原生会话历史不存在')
        if state.state == 'unavailable' and not first_rollout_pending:
            raise CodexError(state.error or 'Codex 原生历史尚未完成同步，请稍后重试',
                             code='native_history_unavailable', http_status=503)
        return state

    def configure_storage(self, db):
        self._handoff_log = Path(db.path).parent / "handoff-runtime.log"
        self.observations = Observations(db)
        from .questions import QuestionClaims
        self.question_claims = QuestionClaims(db)
        self.model_catalog.configure(self.observations)
        self.workspaces = Workspaces(db, self)

    def _observe(self, thread_id, kind, turn_id, payload):
        with self._lock:
            key = (thread_id, kind, turn_id or '')
            self._observed[key] = copy.deepcopy(payload)
            self._observed.move_to_end(key)
            while len(self._observed) > 1000:
                self._observed.popitem(last=False)
            if self.observations:
                try:
                    self.observations.put(thread_id, kind, turn_id, payload)
                except Exception:
                    # Derived display metadata must not hide native acceptance
                    # or stop processing the rest of a native notification.
                    logging.getLogger(__name__).warning('Native observation persistence unavailable')

    def _forget_observation(self, thread_id, kind, turn_id=''):
        with self._lock:
            self._observed.pop((thread_id, kind, turn_id), None)
            if self.observations:
                try:
                    self.observations.delete(thread_id, kind, turn_id)
                except Exception:
                    logging.getLogger(__name__).warning('Native observation cleanup unavailable')

    def observations_for(self, thread_id):
        with self._lock:
            values = self.observations.get(thread_id) if self.observations else {}
            values.update({(kind, turn): copy.deepcopy(value) for (tid, kind, turn), value in self._observed.items() if tid == thread_id})
            return values

    def start(self):
        status = self.connection.start()
        with self._lock:
            if self._generation != self.connection.generation:
                self.projects.invalidate()
                self._settings.clear()
                self._created.clear()
                self._materializing.clear()
                self._joined.clear()
                self._unmaterialized.clear()
                self._active.clear()
                self._live.clear()
                self._pending_terminal.clear()
                self._generation = self.connection.generation
        return status

    def close(self):
        # Close only Relay-owned session writers; never terminate the metadata daemon.
        self.activity.close()
        self.connection.close()

    def idle_for_update(self):
        # Metadata only: never resume a writer or repair history to decide whether
        # an update may stop Relay. All reads share one short budget.
        from ..read_budget import read_budget, read_lock
        try:
            with read_budget(seconds=2):
                if not self.status().get('connected'):
                    return False
                with read_lock(self._lock):
                    if self._unmaterialized:
                        return False
                    threads = set(self._joined) | set(self._active) | {tid for tid, _ in self._live}
                threads.update(getattr(self.connection, 'update_threads', lambda: [])())
                for thread_id in threads:
                    if self.interactions.list(thread_id):
                        return False
                    thread = self.connection.request('thread/read',
                        {'threadId': thread_id, 'includeTurns': False}, timeout=2)['thread']
                    if thread_status(thread) != 'idle' or thread_waiting(thread):
                        return False
                    if (self.read_goal(thread_id).get('goal') or {}).get('status') == 'active':
                        return False
                return True
        except Exception:
            return False

    def status(self):
        return self.connection.status()

    def _thread_connection(self, thread_id):
        bind = getattr(self.connection, 'history_host_connection', None)
        return bind(thread_id) if bind else self.connection

    def _thread_runtime(self, thread_id):
        connection = self._thread_connection(thread_id)
        status = connection.status()
        bridge = status.get('bridge') or {}
        available = False
        check_available = False
        reason = '实际运行节点尚未确认，不能执行进程级交接'
        if not status.get('connected'):
            reason = '原生运行时尚未连接，不能执行进程级交接'
        elif status.get('ownedWriter'):
            available = status.get('platform') not in ('windows', 'win32')
            reason = '' if available else 'Windows 自有会话支持停止、释放与恢复；不支持强制接管外部 writer'
        elif status.get('runtime') == 'desktop' or status.get('desktopShared') is True:
            reason = '共享 Desktop 不支持进程级接管；当前会话可以正常停止或释放订阅'
        else:
            descriptor = self.native_journal.cached_host(thread_id)
            if descriptor is None and callable(getattr(connection, 'native_host_info', None)):
                # Probe on explicit preview rather than requiring a history-cache hit.
                check_available = True
                reason = '接管前将重新核验实际运行主机和持锁进程'
            if descriptor:
                key = json.dumps(descriptor, sort_keys=True)
                with self._lock:
                    cached = self._handoff_capabilities.get(key)
                if cached is None or time.monotonic() - cached[2] >= 5:
                    try:
                        self.writers.verify_native_host(descriptor, status.get('runtimeId'), getattr(connection, 'socket_path', None))
                        cached = (True, '', time.monotonic())
                    except Exception as error:
                        cached = (False, str(error), time.monotonic())
                    with self._lock:
                        self._handoff_capabilities[key] = cached
                        while len(self._handoff_capabilities) > 16:
                            self._handoff_capabilities.popitem(last=False)
                available, reason = cached[:2]
        with self._lock:
            unmaterialized = (thread_id in self._unmaterialized or thread_id in self._created
                              or thread_id in self._materializing)
        release_blocked = bool(status.get('ownedWriter') and unmaterialized)
        return {'runtime': status.get('runtime', 'unknown'), 'connected': bool(status.get('connected')),
                'releaseAvailable': not release_blocked,
                'releaseUnavailableReason': ('首条消息尚未确认持久化，暂不能释放；会话和草稿会保留，历史持久化后可释放'
                                             if release_blocked else None),
                'desktopShared': status.get('desktopShared'),
                'desktopVerification': status.get('desktopVerification'),
                'handoffAvailable': available, 'handoffCheckAvailable': check_available, 'handoffUnavailableReason': reason or None,
                'bridge': {**{key: bridge.get(key) for key in ('available', 'writerReady', 'connectionState')},
                           'error': bridge.get('error') or status.get('error')}}

    def list_projects(self):
        self.start()
        return self.projects.list()

    def active_tasks(self):
        self.activity.start()
        return self.activity.snapshot()

    def _load_activity(self, activity_token):
        generation = str(self.connection.generation)
        with self.activity.lock:
            active_ids = set(self.activity.tasks)
        discovered = self._activity_discovery
        full = discovered is None or discovered[0] != generation or time.monotonic() >= discovered[1]
        projects = list(pages(self.connection, 'project/list')) if full else discovered[2]
        # Idle threads can start in Desktop without a notification on this
        # connection. Refresh cheap state-DB metadata every reconciliation;
        # caching only active/unknown IDs misses restarts and new threads.
        threads = list(pages(self.connection, "thread/list", {"archived": False, "modelProviders": [],
                "sortKey": "updated_at", "sortDirection": "desc", "useStateDbOnly": True}))
        # Index discovery may omit rollout-only or newly created active threads.
        # Read those known live IDs directly before treating absence as completion.
        indexed = {t['id'] for t in threads}
        for tid in active_ids - indexed:
            threads.append(self.connection.request('thread/read', {'threadId': tid, 'includeTurns': False})['thread'])
        roots = {r['path']: p['id'] for p in projects for r in p.get('roots', []) if r.get('path')}
        self.activity.observe_metadata(threads, roots, activity_token)
        associations = self.projects.external_worktrees.associations(
            [t for t in threads if not t.get('projectId')], projects)
        external = self._external_running(threads)
        external.update(self._external_execution([t for t in threads if t['id'] in active_ids]))
        queues = self.execution_queue.by_thread() if hasattr(self, 'execution_queue') else {}
        tasks, unknown = [], []
        for thread in threads:
            tid = thread['id']
            # Unloaded threads without running evidence must not trigger a goal RPC
            # for every historical conversation before publishing live activity.
            goal = (self.read_goal(tid).get('goal') if thread_status(thread) != 'unknown'
                    or external.get(tid) == 'running' else None)
            status = ('waiting' if self.interactions.list(tid) or thread_waiting(thread) else
                      'running' if goal_running(thread, goal, external) else
                      external.get(tid, thread_status(thread)))
            projection = execution_projection(status, queues.get(tid, []))
            if status == 'unknown':
                unknown.append(tid)
            if projection['hasOutstandingWork']:
                tasks.append({'threadId': tid, 'projectId': associations.get(tid, {}).get('projectId') or
                              thread.get('projectId') or roots.get(thread.get('cwd'), RECENT_PROJECT_ID),
                              'status': status, 'goalActive': (goal or {}).get('status') == 'active', **projection})
        if generation != str(self.connection.generation):
            raise CodexError('原生运行时已变化，请重新同步任务', code='native_unavailable')
        self._activity_discovery = (generation, time.monotonic() + 60 if full else discovered[1], projects)
        return {'tasks': tasks, 'unknownThreadIds': unknown, 'knownThreadIds': [t['id'] for t in threads], 'generation': generation,
                'projectByThread': {t['id']: associations.get(t['id'], {}).get('projectId') or t.get('projectId') or
                    roots.get(t.get('cwd'), RECENT_PROJECT_ID) for t in threads}}

    def add_project(self, path, name=None, idempotency_key=None, folder_name=None, trust_confirmed=False):
        self.start()
        if not isinstance(trust_confirmed, bool):
            raise CodexError('文件夹信任确认必须是布尔值', code='invalid_request', http_status=400)
        if folder_name is not None:
            import posixpath, ntpath
            if not isinstance(folder_name, str) or not folder_name.strip() or folder_name in ('.', '..') or any(c in folder_name for c in '/\\\0'):
                raise CodexError('目录名称无效', code='invalid_path', http_status=400)
            path = (ntpath if ntpath.splitdrive(path)[0] else posixpath).join(path, folder_name)
            self.connection.request('fs/createDirectory', {'path': path, 'recursive': False})
        project = self.projects.add(path, name=name, idempotency_key=idempotency_key, trust_confirmed=trust_confirmed)
        return {"projectId": project["id"]}

    def _external_execution(self, threads):
        return self.native_journal.execution_states([t['id'] for t in threads if thread_status(t) == 'unknown'])

    def _external_running(self, threads):
        # Project counters need running evidence only; session detail/list keeps
        # the complete idle/unknown semantics of _external_execution.
        return self.native_journal.execution_states(
            [t['id'] for t in threads if thread_status(t) == 'unknown'], running_only=True)

    def list_sessions(self, project_id, archived=False, *, summary=False):
        self.start()
        if summary:
            # A provisional metadata view: complete indexed membership, Git
            # discovery and execution inspection remain in the full response.
            generation = self.connection.generation
            params = {"archived": archived, "modelProviders": [], "sortKey": "updated_at",
                      "sortDirection": "desc", "useStateDbOnly": True}
            threads = list(pages(self.connection, 'thread/list', params))
            # Desktop creation may arrive through the state DB without an SSE
            # event. Do not let the subsequent full read reuse a pre-create scan.
            if self.projects.thread_catalog.metadata_changed(
                    {key: value for key, value in params.items() if key != 'useStateDbOnly'}, threads):
                self.projects.invalidate()
            associations = {}
            if project_id == RECENT_PROJECT_ID:
                projects = list(pages(self.connection, 'project/list'))
                by_id = {t['id']: t for t in unlisted_threads(threads, projects, {})}
            else:
                project = self.projects.get(project_id)
                roots = {r['path'] for r in project.get('roots', [])}
                by_id = {t['id']: t for t in threads if t.get('projectId') == project_id or
                         (not t.get('projectId') and t.get('cwd') in roots)}
                # Native filters may carry membership not represented by cwd.
                for thread in pages(self.connection, 'thread/list', {**params, 'projectId': project_id}):
                    by_id[thread['id']] = thread
            if generation != self.connection.generation:
                raise CodexError('目录读取时运行时已变化，请刷新', code='native_unavailable')
        elif project_id == RECENT_PROJECT_ID:
            projects = list(pages(self.connection, "project/list"))
            threads = self.projects.thread_catalog.list({
                "archived": archived, "modelProviders": [],
                "sortKey": "updated_at", "sortDirection": "desc",
            })
            associations = self.projects.external_worktrees.associations(
                [t for t in threads if not t.get("projectId")], projects)
            by_id = {t["id"]: t for t in unlisted_threads(threads, projects, associations)}
        else:
            project = self.projects.get(project_id)
            roots = [r["path"] for r in project.get("roots", [])]
            # Include legacy threads associated by root or Git worktree.
            threads = list(self.projects.thread_catalog.list({
                "archived": archived, "modelProviders": [],
                "sortKey": "updated_at", "sortDirection": "desc",
            })) if roots else []
            associations = self.projects.external_worktrees.associations(threads, [project])
            by_id = {t["id"]: t for t in threads
                     if t.get("projectId") == project_id or (not t.get('projectId') and
                         (t.get('cwd') in roots or t['id'] in associations))}
            for thread in self.projects.thread_catalog.list({
                "projectId": project_id, "archived": archived, "modelProviders": [],
                "sortKey": "updated_at", "sortDirection": "desc",
            }):
                by_id[thread["id"]] = thread
        queues = self.execution_queue.by_thread() if hasattr(self, 'execution_queue') else {}
        managed = {}
        if self.workspaces:
            with self.workspaces.db.read() as c:
                managed = {row['thread_id']: dict(row) for row in c.execute('SELECT * FROM workspaces WHERE project_id=?', (project_id,))}
        shared = self.activity.current() if not summary and not archived else None
        if shared and shared.get('generation') != str(self.connection.generation):
            shared = None
        active = {t['threadId']: t for t in (shared or {}).get('tasks', [])}
        unknown = set((shared or {}).get('unknownThreadIds', []))
        external = {} if summary or shared else self._external_execution(list(by_id.values()))
        statuses = {t["id"]: ('unknown' if shared and (t['id'] in unknown or t['id'] not in shared.get('knownThreadIds', [])) else
                    active[t['id']]['status'] if t['id'] in active else 'idle' if shared else
                    "waiting" if self.interactions.list(t["id"]) or thread_waiting(t) else
                    "running" if goal_running(t, None if summary else self.read_goal(t["id"]).get("goal"), external)
                    else external.get(t['id'], thread_status(t))) for t in by_id.values()}
        return [{"id": t["id"], "name": t.get("name") or t.get("preview") or "新会话",
                 "preview": t.get("preview", ""), "status": statuses[t["id"]],
                 "pinned": str((t.get("section") or {}).get("name", "")).casefold() == "pinned",
                 "updatedAt": t.get("updatedAt"), "cwd": t.get("cwd"),
                 "projectId": t.get("projectId") or (None if project_id == RECENT_PROJECT_ID else project_id),
                 "source": t.get('source'), "gitInfo": t.get('gitInfo'),
                 "externalWorkspace": associations.get(t['id']) if t['id'] not in managed else None,
                 "executionMode": 'worktree' if t['id'] in managed else 'local',
                 "workspace": ({'id': managed[t['id']]['id'], 'path': managed[t['id']]['path'],
                     'commit': managed[t['id']]['commit_id'], 'status': managed[t['id']]['status']}
                     if t['id'] in managed else None),
                 **execution_projection(statuses[t["id"]], queues.get(t['id'], []))}
                for t in sorted(by_id.values(), key=lambda t: t.get("updatedAt", 0), reverse=True)]

    def pin_session(self, thread_id, pinned):
        self.start()
        section_id = None
        if pinned:
            section_id = next((s["id"] for s in pages(self.connection, "threadSection/list")
                               if str(s.get("name", "")).casefold() == "pinned"), None)
            if section_id is None:
                raise CodexError("Native Pinned section missing", code="native_protocol")
        self.connection.request("thread/section/move", {"threadId": thread_id,
            "sectionId": section_id, "beforeThreadId": None})
        self.projects.invalidate()
        return {"threadId": thread_id}

    def delete_session(self, thread_id):
        self.start()
        with self.operation_gate(thread_id):
            if hasattr(self, 'execution_queue') and self.execution_queue.list(thread_id):
                raise CodexError('请先处理此会话的执行队列', code='thread_busy', http_status=409)
            if self.interactions.list(thread_id):
                raise CodexError('请先处理此会话的待回答请求', code='thread_busy', http_status=409)
            self.connection.request('thread/delete', {'threadId': thread_id})
            self.projects.invalidate()
            with self._lock:
                self._joined.discard(thread_id)
            return {'threadId': thread_id}

    def archive_session(self, thread_id, archived):
        self.start()
        with self.operation_gate(thread_id):
            self.connection.request('thread/archive' if archived else 'thread/unarchive', {'threadId': thread_id})
            if archived and hasattr(self, 'execution_queue'):
                self.execution_queue.cancel_waiting(thread_id)
            self.projects.invalidate()
            with self._lock:
                self._joined.discard(thread_id)
            return {'threadId': thread_id}

    @staticmethod
    def _settings_from(response):
        keys = ("model", "modelProvider", "reasoningEffort", "serviceTier", "cwd",
                "runtimeWorkspaceRoots", "approvalPolicy", "approvalsReviewer",
                "sandbox", "activePermissionProfile", "collaborationMode")
        settings = {k: copy.deepcopy(response[k]) for k in keys if k in response}
        # Resume and settings notifications use different native field names.
        if "effort" in response:
            settings["reasoningEffort"] = copy.deepcopy(response["effort"])
        if "sandboxPolicy" in response:
            settings["sandbox"] = copy.deepcopy(response["sandboxPolicy"])
        return settings

    def _assert_workspace(self, thread_id):
        row = self.workspaces.row(thread_id) if self.workspaces else None
        if row and row['status'] != 'ready':
            raise CodexError('工作树已清理或状态待核对，不能在来源项目中继续执行', code='workspace_unavailable', http_status=409)

    def _materializing_thread(self, thread_id):
        with self._lock:
            pending = self._materializing.get(thread_id)
            if pending and pending[1] >= time.monotonic():
                return copy.deepcopy(pending[0])
            self._materializing.pop(thread_id, None)
            return None

    def _resume(self, thread_id, *, acquire_released=False):
        with self.operation_gate(thread_id):
            released = self.observations_for(thread_id).get(('ownership', ''), {}).get('released')
            if released and not acquire_released:
                raise CodexError("会话已主动释放", code="writer_released", http_status=409)
            with self._lock:
                # Codex's first turn must use the connection that started the
                # not-yet-materialized thread. Resuming it can fail before rollout
                # creation even on this same daemon.
                if thread_id in self._created:
                    return copy.deepcopy(self._created[thread_id])
                joined = thread_id in self._joined and not released
            if joined:
                try:
                    thread = self.connection.request("thread/read", {
                        "threadId": thread_id, "includeTurns": False,
                    })["thread"]
                except CodexError as error:
                    creating = self._materializing_thread(thread_id)
                    if creating is not None and awaiting_rollout(error):
                        return creating
                    raise
                if thread.get("status", {}).get("type") != "notLoaded":
                    return thread
                with self._lock:
                    self._joined.discard(thread_id)
            # Joining emits native notifications, even for an idle thread.
            # Repeat reads must not resume and trigger their own SSE refresh.
            try:
                response = self.connection.request("thread/resume", {"threadId": thread_id, "excludeTurns": True})
            except CodexError as error:
                if acquire_released and error.code == 'native_rejected' and 'already has an active writer' in str(error):
                    self._observe(thread_id, 'ownership', '', {'released': True, 'externalWriter': True})
                    self.on_event({'method': 'relay/ownershipChanged', 'params': {'threadId': thread_id}})
                raise
            if released:
                self._forget_observation(thread_id, 'ownership')
                self.on_event({'method': 'relay/ownershipChanged', 'params': {'threadId': thread_id}})
            with self._lock:
                self._settings[thread_id] = self._settings_from(response)
                self._joined.add(thread_id)
            return response["thread"]

    @read_identity
    def get_session(self, thread_id, cursor=None, limit=30, native_revision=None):
        self.start()
        activity_token = self.activity.read_token()
        # Reconcile the idle persisted history before resume takes a writer lock.
        # A live Desktop/CLI writer remains authoritative and is never released.
        history = self.history(thread_id, cursor=cursor, limit=limit, native_revision=native_revision)
        # Join once per connection, then read metadata without new notifications.
        # No cwd/model/permission values are supplied.
        read_only = False
        workspace_unavailable = False
        writer_owner = None
        try:
            workspace = self.workspaces.row(thread_id) if self.workspaces else None
            if workspace and workspace['status'] != 'ready':
                # A removed managed worktree does not erase its native history.
                # Reading must not resume the thread into a missing/rebound cwd.
                raise CodexError('工作树不可执行', code='workspace_unavailable', http_status=409)
            ownership = self.observations_for(thread_id).get(('ownership', ''), {})
            inspect_owner = getattr(self.connection, 'writer_owner', None)
            writer_owner = inspect_owner(thread_id) if inspect_owner else None
            if ownership.get('released'):
                raise CodexError('会话已主动释放', code='writer_released', http_status=409)
            if writer_owner in ('none', 'external', 'unknown'):
                # Viewing an idle thread must not acquire its writer. The first
                # explicit execution/resume acquires and rechecks ownership.
                thread = self.connection.request('thread/read', {'threadId': thread_id, 'includeTurns': False})['thread']
                read_only = writer_owner != 'none'
            else:
                thread = self._resume(thread_id)
        except CodexError as error:
            workspace_unavailable = error.code == 'workspace_unavailable' or missing_workspace(error)
            if not workspace_unavailable and error.code != 'writer_released' and (error.code != "native_rejected" or "already has an active writer" not in str(error)):
                raise
            # Persisted history is readable without acquiring another runtime's writer.
            thread = self.connection.request("thread/read", {"threadId": thread_id, "includeTurns": False})["thread"]
            read_only = True
        thread = {**thread, "turns": history["turns"]}
        requests = self.interactions.list(thread_id)
        with self._lock:
            settings = copy.deepcopy(self._settings.get(thread_id, {}))
            active = self._active.get(thread_id)
            if active and any(t['id'] == active and t.get('status') in ('completed', 'failed', 'interrupted', 'canceled') for t in thread['turns']):
                self._active.pop(thread_id, None)
                active = None
        if thread.get("cwd"):
            settings.setdefault("cwd", thread["cwd"])
        if active is None:
            active = next((t["id"] for t in reversed(thread["turns"])
                           if t.get("status") == "inProgress"), None)
        goal_state = self.read_goal(thread_id)
        external = self._external_execution([thread]) if not active else {}
        goal_active = goal_running(thread, goal_state.get("goal"), external)
        status = "waiting" if requests or thread_waiting(thread) else ("running" if active or goal_active else external.get(thread_id, thread_status(thread)))
        observations = self.observations_for(thread_id)
        latest_turn = thread['turns'][-1] if thread['turns'] else {}
        plan = observations.get(('plan', active or latest_turn.get('id', '')))
        if plan:
            plan = {**plan, 'status': next((t.get('status') for t in thread['turns'] if t['id'] == plan.get('turnId')), 'inProgress' if active else 'unknown')}
        usage = next((value for (kind, _), value in reversed(list(observations.items())) if kind == 'usage'), None)
        changes = [{'turnId': turn_id, **value} for (kind, turn_id), value in observations.items() if kind == 'changes']
        turn_settings = [{'turnId': turn_id, **value} for (kind, turn_id), value in observations.items() if kind == 'turnSettings']
        queue = self.execution_queue.list(thread_id) if hasattr(self, 'execution_queue') else []
        review_diagnostics = self.review_guard.diagnostics(thread_id, thread['turns'])
        rebind = None
        if workspace_unavailable:
            try:
                rebind = self._workspace_rebind(thread)
            except CodexError:
                # Missing/revoked source metadata cannot hide native history.
                pass
        runtime = self._thread_runtime(thread_id)
        control = control_projection(read_only=read_only,
            released=bool(observations.get(('ownership', ''), {}).get('released')),
            workspace_unavailable=workspace_unavailable, runtime=runtime,
            ownership_unverified=writer_owner == 'unknown')
        if writer_owner == 'none' and not read_only:
            control['action'] = None
        ownership = observations.get(('ownership', ''), {})
        if ownership.get('released') and not runtime.get('desktopShared'):
            phase = ownership.get('releaseState', 'unverified')
            if phase in ('pending', 'failed', 'unverified') and not ownership.get('externalWriter'):
                control = {'state': 'release_pending', 'canOperate': False, 'action': 'release',
                           'unavailableReason': ownership.get('releaseError') or '释放尚未确认，请继续释放并核验实际写锁'}
        if control.get('action') == 'resume' and observations.get(('ownership', ''), {}).get('externalWriter'):
            control = control_projection(read_only=True, released=False,
                workspace_unavailable=workspace_unavailable, runtime=runtime)
        # Release intent pauses work; current ownership decides which action is shown.
        if ownership.get('released') and writer_owner in ('external', 'unknown'):
            control = control_projection(read_only=True, released=False,
                workspace_unavailable=workspace_unavailable, runtime=runtime,
                ownership_unverified=writer_owner == 'unknown')
        elif ownership.get('released') and writer_owner == 'none' and not workspace_unavailable:
            control = control_projection(read_only=True, released=True,
                workspace_unavailable=False, runtime=runtime)
        if ownership.get('releaseState') == 'takeover_failed':
            control['handoffError'] = ownership.get('releaseError')
            control['handoffStage'] = ownership.get('takeoverStage')
            if not control.get('unavailableReason'):
                control['unavailableReason'] = ownership.get('releaseError') or '接管失败，请核验持锁状态后重试'
        from .questions import pending_questions
        question_reply_available = False
        question_turn = next((turn for turn in thread['turns'] if turn['id'] == active), latest_turn)
        questions = pending_questions(question_turn)
        answer_states = {key: value for key, value in self.question_claims.states(thread_id).items() if key in questions}
        unanswered = set(questions) - {key for key, value in answer_states.items() if value == 'accepted'}
        if control.get('state') in ('writable', 'external') and (active or status == 'idle') and unanswered and not any(answer_states.get(key) in ('pending', 'unknown') for key in unanswered):
            try:
                question_reply_available = getattr(self.connection, 'can_answer_questions', lambda _: False)(thread_id)
            except CodexError:
                pass
        if cursor is None and (history['nativeSync'].get('historyReady') or history['nativeSync'].get('state') == 'ready'):
            self.activity.observe_execution(thread_id, status, token=activity_token,
                project_id=thread.get('projectId'), turn_id=active,
                settled_turn_id=latest_turn.get('id') if latest_turn.get('status') in
                    ('completed', 'failed', 'interrupted', 'canceled') else None,
                goal_active=goal_active, queue=queue)
        return {"activity": self.activity.snapshot(), "thread": thread, "requests": requests, "settings": settings, 'control': control, 'plan': plan,
                'canAnswerQuestions': question_reply_available, 'questionAnswerStates': answer_states,
                **goal_state,
                'tokenUsage': usage, 'changes': changes, 'turnSettings': turn_settings,
                "resumeTurnId": latest_turn.get('id') if status == 'idle' and not active
                    and latest_turn.get('status') in ('interrupted', 'failed', 'canceled') else None,
                "activeTurnId": active, "status": status, "readOnly": read_only, "nextCursor": history["nextCursor"],
                "backwardsCursor": history["backwardsCursor"],
                "nativeRevision": history['nativeRevision'], "nativeHeadRevision": history.get('nativeHeadRevision'), "nativeSync": history['nativeSync'],
                'nativeRuntime': runtime,
                "writerOwner": 'none' if observations.get(('ownership', ''), {}).get('released') or (workspace and workspace['status'] != 'ready') else writer_owner or ('external' if read_only else 'relay'),
                'workspaceUnavailable': workspace_unavailable, 'workspaceRebind': rebind,
                'reviewDiagnostics': review_diagnostics,
                **execution_projection(status, queue, read_only=read_only, requests=requests,
                                       ownership_unverified=writer_owner == 'unknown')}

    def _history_revision(self, thread_id, turns, *, content=False):
        # Streaming text AND newly appended tool items stay within the same
        # turn; neither relocates older turn cursors. Keep content in the head
        # token only. Terminal corrections, turn boundaries and repair/rollback
        # still invalidate pagination.
        running_turn = self.native_journal.inspected_running_turn(thread_id) if not content else None
        head = [turn if content or not (turn.get('status') in ('inProgress', 'notStarted') or
                    turn['id'] == running_turn and turn.get('status') == 'interrupted') else
                {'id': turn['id'], 'status': 'inProgress'}
                for turn in turns[-2:]]
        encoded = json.dumps([self.connection.generation, self.native_journal.connection.generation,
            self.native_journal.generation(thread_id),
            self.native_journal.history_epoch(thread_id), thread_id, head],
            sort_keys=True, ensure_ascii=True, separators=(',', ':')).encode()
        return hashlib.sha256(encoded).hexdigest()

    @read_identity
    def history(self, thread_id, cursor=None, limit=30, native_revision=None):
        self.start()
        head_limit = 2 if cursor is not None else max(2, min(100, int(limit)))
        with self.native_journal.read_scope(thread_id, head_limit=head_limit):
            with self._lock:
                if thread_id in self._created:
                    return {"turns": [], "nextCursor": None, "backwardsCursor": None,
                            'nativeRevision': self._history_revision(thread_id, []),
                            'nativeHeadRevision': self._history_revision(thread_id, [], content=True),
                            'nativeSync': {'state': 'ready'}}
            native_state = self._native_history_state(thread_id)
            # A verified live writer may leave its current persisted turn marked
            # interrupted. This blocks head convergence, but not older cursors.
            running_turn = self.native_journal.inspected_running_turn(thread_id)
            active_head_lag = (native_state.state == 'converging' and running_turn is not None
                and native_state.journal_turn_id == running_turn
                and native_state.projection_turn_id == running_turn
                and not native_state.reprojected)
            if active_head_lag:
                persisted_head = self.history_adapter.read_head(self.native_journal.connection, thread_id).turns
                active_head_lag = bool(persisted_head and persisted_head[-1]['id'] == running_turn
                    and persisted_head[-1]['status'] == 'interrupted')
            history_ready = native_state.ready or active_head_lag
            if cursor is not None and (not history_ready or native_state.reprojected):
                raise CodexError('原生历史已变化，请重新读取最近消息', code='history_changed', http_status=409)
            try:
                if cursor is not None:
                    head = self.history_adapter.read_page(self.native_journal.connection, thread_id, limit=2)
                    revision = self._history_revision(thread_id, list(head.turns))
                    if native_revision is not None and native_revision != revision:
                        raise CodexError('原生历史已变化，请重新读取最近消息', code='history_changed', http_status=409)
                page = self.history_adapter.read_page(self.native_journal.connection, thread_id,
                        limit=max(1, min(100, int(limit))), cursor=cursor)
                canonical = list(page.turns)
                turns = copy.deepcopy(canonical)
                result = {"turns": turns, "nextCursor": page.next_cursor, "backwardsCursor": page.backwards_cursor}
                revision_turns = (list(head.turns) if cursor is not None else
                                  list(self.history_adapter.read_head(self.native_journal.connection, thread_id).turns)
                                  if int(limit) < 2 else turns)
                result['nativeRevision'] = revision if cursor is not None else self._history_revision(thread_id, revision_turns)
                if turns:
                    with self._lock:
                        self._materializing.pop(thread_id, None)
                        self._unmaterialized.discard(thread_id)
            except CodexError as error:
                if self._materializing_thread(thread_id) is not None and awaiting_rollout(error):
                    canonical = []
                    revision_turns = []
                    result = {"turns": [], "nextCursor": None, "backwardsCursor": None,
                              'nativeRevision': self._history_revision(thread_id, [])}
                else:
                    raise
            except NativeHistoryProtocolError as error:
                raise CodexError(str(error), code="native_protocol") from error
            if cursor is None:
                # A live external writer's persisted projection can mark its
                # unfinished turn interrupted. Correct only that exact conflict;
                # merge newer native events afterwards so terminal events win.
                running_turn = self.native_journal.inspected_running_turn(thread_id)
                for turn in result['turns']:
                    if turn['id'] == running_turn and turn.get('status') == 'interrupted':
                        turn['status'] = 'inProgress'
                        for field in ('error', 'completedAt', 'durationMs'):
                            turn.pop(field, None)
                result["turns"] = self._merge_live(thread_id, result["turns"])
                converging = not native_state.ready or canonical != result['turns']
                # Live deltas may lead persisted items throughout a running
                # turn. They affect head/control convergence, not old cursors.
                if not history_ready:
                    result['nextCursor'] = None
                    result['backwardsCursor'] = None
                result['nativeSync'] = {**native_state.public(), 'state': 'converging' if converging else 'ready'}
            else:
                result['nativeSync'] = native_state.public()
            result['nativeSync']['historyReady'] = history_ready
            # Presentation metadata must not make converged native history look stale.
            self._mark_completed_items(thread_id, result['turns'])
            result['nativeHeadRevision'] = self._history_revision(thread_id,
                result['turns'] if cursor is None else revision_turns, content=True)
            return result

    @staticmethod
    def _permission_options(sandbox, approval_policy):
        presets = {
            'preset-request': ('workspace-write', 'on-request', 'user'),
            'preset-auto': ('workspace-write', 'on-request', 'auto_review'),
            'preset-full': ('danger-full-access', 'never', 'user'),
        }
        if isinstance(approval_policy, str) and approval_policy in presets:
            mode, policy, reviewer = presets[approval_policy]
            return {'sandbox': mode, 'approvalPolicy': policy, 'approvalsReviewer': reviewer}
        if sandbox is not None and sandbox not in ('read-only', 'workspace-write', 'danger-full-access'):
            raise CodexError('文件访问权限无效', code='invalid_settings', http_status=400)
        if approval_policy is not None and approval_policy not in ('on-request', 'untrusted', 'never', 'on-failure'):
            raise CodexError('审批策略无效', code='invalid_settings', http_status=400)
        return {key: value for key, value in (('sandbox', sandbox), ('approvalPolicy', approval_policy)) if value is not None}

    def create_session(self, project_id, title="", worktree_ref=None, *, sandbox=None, approval_policy=None):
        from ..submission_dispatch import creation_phase
        creation_phase(project_id, 'preparing')
        if project_id == RECENT_PROJECT_ID:
            raise CodexError("请先选择项目再新建会话", code="invalid_request", http_status=400)
        permissions = self._permission_options(sandbox, approval_policy)
        self.start()
        project = self.projects.get(project_id)
        roots = [r["path"] for r in project.get("roots", [])]
        if not roots:
            raise CodexError("此项目没有原生工作目录", http_status=409)
        with self.operation_gate("project:" + project_id):
            workspace_id, cwd = None, roots[0]
            if worktree_ref is not None:
                if self.workspaces is None:
                    raise CodexError('工作树存储不可用')
                workspace_id, cwd = self.workspaces.create(project_id, cwd, worktree_ref)
            try:
                creation_phase(project_id, 'dispatching')
                result = self.connection.request("thread/start", {
                    "cwd": cwd, "projectId": project_id, "historyMode": "paginated", **permissions,
                })
                thread_id = (result.get('thread') or {}).get('id')
                if not isinstance(thread_id, str) or not thread_id:
                    raise CodexError('原生没有返回可核对的会话身份', code='native_protocol', request_sent=True)
                creation_phase(project_id, 'received', {'threadId': thread_id})
            except Exception as error:
                if workspace_id:
                    uncertain = not isinstance(error, CodexError) or not error.definite
                    retained = self.workspaces.abandon(workspace_id, uncertain=uncertain)
                    if retained.get('status') != 'removed':
                        raise CodexError('会话创建未确认，工作树已保留：' + cwd,
                            code='workspace_thread_unknown' if uncertain else 'workspace_thread_failed',
                            http_status=409, request_sent=uncertain) from error
                raise
            with self._lock:
                self._created[thread_id] = copy.deepcopy(result["thread"])
                self._unmaterialized.add(thread_id)
                self._joined.add(thread_id)
                self._settings[thread_id] = self._settings_from(result)
            receipt = {"threadId": thread_id}
            if workspace_id:
                self.workspaces.link(workspace_id, thread_id)
            if title:
                try:
                    self.connection.request("thread/name/set", {"threadId": thread_id, "name": title})
                except CodexError:
                    # The thread exists. A title failure must never imply it is
                    # safe to issue another thread/start.
                    self.on_event({"method": "relay/titlePending", "params": {"threadId": thread_id}})
            self.projects.invalidate()
            return receipt

    def fork_session(self, thread_id, last_turn_id, worktree_ref=None):
        from ..submission_dispatch import creation_phase
        creation_phase(thread_id, 'preparing')
        self.start()
        with self.operation_gate(thread_id):
            source = self.connection.request('thread/read', {'threadId': thread_id, 'includeTurns': False})['thread']
            cwd = source.get('cwd')
            if not cwd:
                raise CodexError('来源会话没有工作目录', code='invalid_request', http_status=409)
            workspace_id = None
            shared = None
            if worktree_ref is not None:
                project_id = source.get('projectId')
                if not project_id or self.workspaces is None:
                    raise CodexError('来源会话没有可用的工作树项目', code='invalid_request', http_status=409)
                workspace_id, cwd = self.workspaces.create(project_id, cwd, worktree_ref)
            elif self.workspaces is not None:
                shared = self.workspaces.reserve_shared_directory(cwd)
            try:
                creation_phase(thread_id, 'dispatching')
                result = self.connection.request('thread/fork', {
                    'threadId': thread_id, 'lastTurnId': last_turn_id,
                    'cwd': cwd, 'excludeTurns': True,
                })
                selected = (result.get('thread') or {}).get('id')
                if not isinstance(selected, str) or not selected or selected == thread_id:
                    raise CodexError('分叉未返回新的会话身份', code='native_protocol', request_sent=True)
                creation_phase(thread_id, 'received', {'threadId': selected})
            except Exception as error:
                if shared is not None and isinstance(error, CodexError) and error.definite:
                    self.workspaces.cancel_shared_directory(shared)
                if workspace_id:
                    uncertain = not isinstance(error, CodexError) or not error.definite
                    retained = self.workspaces.abandon(workspace_id, uncertain=uncertain)
                    if retained.get('status') != 'removed':
                        raise CodexError('分叉未确认，工作树已保留：' + cwd,
                            code='workspace_thread_unknown' if uncertain else 'workspace_thread_failed',
                            http_status=409, request_sent=uncertain) from error
                raise
            with self._lock:
                self._joined.add(selected)
                self._settings[selected] = self._settings_from(result)
            if workspace_id:
                try:
                    self.workspaces.link(workspace_id, selected)
                except Exception as error:
                    raise CodexError('分叉已创建但工作树关联未确认，请核对新会话：' + selected,
                        code='workspace_thread_unknown', http_status=409, request_sent=True) from error
            self.projects.invalidate()
            return {'threadId': selected}

    @staticmethod
    def _input(text):
        if not isinstance(text, str) or not text.strip():
            raise CodexError("请输入消息", code="invalid_message", http_status=400)
        return [{"type": "text", "text": text, "text_elements": []}]

    def send(self, thread_id, text, model=None, effort=None, client_message_id=None, *, input_items=None, mode=None, service_tier=None, acquire_released=False, require_goal_support=False, resume_turn_id=None):
        from ..submission_dispatch import dispatch_phase
        dispatch_phase(client_message_id, thread_id, 'preparing')
        self.start()
        with self.operation_gate(thread_id):
            if require_goal_support and not self.goal_capabilities()["goalSupported"]:
                raise CodexError("此节点不支持持续目标，未发送首条消息", code="goal_unsupported", http_status=409)
            self._assert_workspace(thread_id)
            self._assert_submission_ready(thread_id, allow_recovery=acquire_released)
            self._resume(thread_id, acquire_released=acquire_released)
            with self._lock:
                inherited_settings = copy.deepcopy(self._settings.get(thread_id, {}))
            params = {"threadId": thread_id, "input": input_items if input_items is not None else self._input(text)}
            if resume_turn_id is not None:
                snapshot = self.get_session(thread_id, limit=2)
                turns = snapshot['thread'].get('turns', [])
                latest = turns[-1] if turns else {}
                if (snapshot.get('activeTurnId') or snapshot.get('status') != 'idle'
                        or latest.get('id') != resume_turn_id
                        or latest.get('status') not in ('interrupted', 'failed', 'canceled')):
                    raise CodexError('任务状态已变化，请刷新后重试', code='resume_conflict', http_status=409)
                params['input'] = []
                params['turnTrigger'] = 'resume_interrupted_task'
            context = attachment_context(self.connection, params['input'])
            if context:
                params['additionalContext'] = context
            if needs_image_staging(params['input']):
                params['input'] = prepare_image_inputs(self.connection, self._attachment_cwd(thread_id), params['input'])
            if model is not None:
                params["model"] = model
            if effort is not None:
                params["effort"] = effort
            if service_tier is not None:
                if service_tier not in ('default', 'priority'):
                    raise CodexError('处理模式无效', code='invalid_settings', http_status=400)
                params['serviceTier'] = service_tier
            elif inherited_settings.get('serviceTier') is not None:
                params['serviceTier'] = inherited_settings['serviceTier']
            if client_message_id is not None:
                params["clientUserMessageId"] = client_message_id
            saved_baseline = False
            if mode is not None:
                if mode not in ('plan', 'default'):
                    raise CodexError('讨论模式无效', code='invalid_mode', http_status=400)
                with self._lock:
                    settings = copy.deepcopy(self._settings.get(thread_id, {}))
                selected_model = model or settings.get('model')
                if not selected_model:
                    raise CodexError('请先选择可用模型', code='model_required', http_status=409)
                params['collaborationMode'] = {'mode': mode, 'settings': {'model': selected_model,
                    'reasoning_effort': effort or settings.get('reasoningEffort'), 'developer_instructions': None}}
                sandbox = settings.get('sandbox', {})
                baseline = self.observations_for(thread_id).get(('modeBaseline', ''))
                if mode == 'plan':
                    network = sandbox.get('networkAccess') is True or sandbox.get('type') == 'dangerFullAccess'
                    params['sandboxPolicy'] = {'type': 'readOnly', 'networkAccess': network}
                    if sandbox.get('type') and sandbox.get('type') != 'readOnly':
                        self._observe(thread_id, 'modeBaseline', '', {'sandbox': sandbox, 'planSandbox': params['sandboxPolicy']})
                        saved_baseline = True
                elif (baseline and sandbox.get('type') == 'readOnly' and baseline.get('sandbox')
                      and (settings.get('collaborationMode') or {}).get('mode') == 'plan'):
                    params['sandboxPolicy'] = baseline['sandbox']
            # Persist before sending: a crash after this point is uncertain.
            dispatch_phase(client_message_id, thread_id, 'dispatching')
            try:
                result = self.connection.request("turn/start", params)
            except CodexError as error:
                if error.definite and saved_baseline:
                    self._forget_observation(thread_id, 'modeBaseline')
                if not error.definite:
                    with self._lock:
                        self._created.pop(thread_id, None)
                raise
            turn = result.get("turn", {})
            if not isinstance(turn.get("id"), str) or not turn["id"]:
                with self._lock:
                    self._created.pop(thread_id, None)
                raise CodexError("Codex 没有返回可核对的 turn ID", code="native_protocol", request_sent=True)
            # Preserve the native receipt before any derived-state work can fail.
            dispatch_phase(client_message_id, thread_id, 'received',
                           {'threadId': thread_id, 'turnId': turn['id']})
            with self._lock:
                created = self._created.pop(thread_id, None)
                if created is not None:
                    self._materializing[thread_id] = (created, time.monotonic() + 30)
                key = (thread_id, turn['id'])
                self._live.setdefault(key, copy.deepcopy(turn))
                if self._live[key].get('status') == 'inProgress':
                    self._active[thread_id] = turn['id']
                settings = self._settings.setdefault(thread_id, {})
                for key, source in (('sandbox', 'sandboxPolicy'), ('collaborationMode', 'collaborationMode'), ('model', 'model'), ('reasoningEffort', 'effort'), ('serviceTier', 'serviceTier')):
                    if source in params:
                        settings[key] = copy.deepcopy(params[source])
                selected_model = (params.get('collaborationMode') or {}).get('settings', {}).get('model') or model or inherited_settings.get('model')
                observed = self.observations_for(thread_id).get(('turnSettings', turn['id']), {})
                if selected_model and not observed.get('rerouted'):
                    turn_settings = {'model': selected_model,
                        'effort': effort if effort is not None else inherited_settings.get('reasoningEffort')}
                    if params.get('serviceTier') is not None:
                        turn_settings['serviceTier'] = params['serviceTier']
                    self._observe(thread_id, 'turnSettings', turn['id'], turn_settings)
            if mode == 'default':
                self._forget_observation(thread_id, 'modeBaseline')
            return {"threadId": thread_id, "turnId": turn.get("id")}

    def _assert_submission_ready(self, thread_id, *, allow_active_queue=False, allow_recovery=False):
        with self._lock:
            created = thread_id in self._created
        if not created:
            self._native_history_state(thread_id)
        if self.interactions.list(thread_id):
            raise CodexError('请先回答当前问题或处理审批', code='interaction_pending', http_status=409)
        queue = self.execution_queue.list(thread_id) if hasattr(self, 'execution_queue') else []
        # An explicit new message can continue interrupted work without replaying it.
        # Keep the old record; automatic queue dispatch still requires resolution.
        if not allow_recovery and any(item.get('recoveryRequired') for item in queue):
            if allow_active_queue:
                # A newer explicit Desktop/mobile turn owns the input while it
                # runs. Preserve the old recovery record without seizing that
                # active turn's answers or subsequent queued instructions.
                thread = self.connection.request('thread/read', {'threadId': thread_id, 'includeTurns': False})['thread']
                with self._lock:
                    active = self._active.get(thread_id)
                if (thread_status(thread) == 'running' or active
                        or (self.read_goal(thread_id).get('goal') or {}).get('status') == 'active'):
                    return
            raise CodexError('请先继续或忽略待恢复任务', code='recovery_required', http_status=409)

    def validate_queue_target(self, thread_id):
        self.start()
        self._assert_workspace(thread_id)
        self._assert_submission_ready(thread_id, allow_active_queue=True)
        with self._lock:
            if thread_id in self._created:
                return
        self.connection.request("thread/read", {"threadId": thread_id, "includeTurns": False})

    def send_queued(self, thread_id, **kwargs):
        with self.operation_gate(thread_id):
            snapshot = self.get_session(thread_id)
            if snapshot.get("readOnly") or snapshot.get("activeTurnId") or snapshot["status"] != "idle":
                return None
            return self.send(thread_id, "", **kwargs)

    def predict_message(self, thread_id, turn_id):
        from .predictions import predict_message
        return predict_message(self, thread_id, turn_id)

    def answer_questions(self, thread_id, expected_turn_id, answers, client_message_id):
        from .questions import answer_input
        self.start()
        with self.operation_gate(thread_id):
            self._assert_workspace(thread_id)
            snapshot = self.get_session(thread_id)
            turn = next((value for value in snapshot['thread']['turns'] if value['id'] == expected_turn_id), None)
            active = snapshot.get('activeTurnId')
            latest = snapshot['thread']['turns'][-1] if snapshot['thread']['turns'] else {}
            start_turn = not active and snapshot.get('status') == 'idle' and latest.get('id') == expected_turn_id
            if (not turn or (active != expected_turn_id and not start_turn)
                    or not snapshot.get('canAnswerQuestions') or snapshot.get('requests')):
                raise CodexError('问题或桌面连接状态已变化，请刷新后重试', code='interaction_expired', http_status=409)
            params = {'threadId': thread_id, 'expectedTurnId': expected_turn_id,
                      'input': answer_input(turn, answers), 'clientUserMessageId': client_message_id}
            # Intentionally bypass _resume: the question's existing writer owns execution.
            if start_turn:
                params.pop('expectedTurnId')
            ids = [answer['questionItemId'] for answer in answers]
            self.question_claims.claim(thread_id, ids, client_message_id)
            try:
                result = self.connection.answer_questions(params, start_turn=start_turn)
                turn_id = result.get('turn', {}).get('id') if start_turn else result.get('turnId')
                if not isinstance(turn_id, str) or not turn_id or (not start_turn and turn_id != expected_turn_id):
                    raise CodexError('回答结果待核对', code='native_protocol', request_sent=True)
            except Exception as error:
                self.question_claims.finish(thread_id, ids, client_message_id, None if getattr(error, 'definite', False) else 'unknown')
                raise
            self.question_claims.finish(thread_id, ids, client_message_id, 'accepted')
            return {'threadId': thread_id, 'turnId': turn_id}

    def steer(self, thread_id, text, expected_turn_id, client_message_id=None, *, input_items=None):
        self.start()
        with self.operation_gate(thread_id):
            self._assert_workspace(thread_id)
            self._resume(thread_id)
            params = {"threadId": thread_id,
                      "input": input_items if input_items is not None else self._input(text),
                      "expectedTurnId": expected_turn_id}
            context = attachment_context(self.connection, params['input'])
            if context:
                params['additionalContext'] = context
            if needs_image_staging(params['input']):
                params['input'] = prepare_image_inputs(self.connection, self._attachment_cwd(thread_id), params['input'])
            if client_message_id is not None:
                params["clientUserMessageId"] = client_message_id
            result = self.connection.request("turn/steer", params)
            if not isinstance(result.get("turnId"), str) or not result["turnId"]:
                raise CodexError("Codex 没有返回可核对的 turn ID", code="native_protocol", request_sent=True)
            return {"threadId": thread_id, "turnId": result.get("turnId")}

    def interrupt(self, thread_id, turn_id):
        self.start()
        if not turn_id:
            raise CodexError("停止操作需要当前原生 turn ID", http_status=409)
        self.connection.request("turn/interrupt", {"threadId": thread_id, "turnId": turn_id})
        return {"threadId": thread_id, "turnId": turn_id}

    def respond(self, key, response):
        result = self.interactions.respond(key, response)
        self.on_event({"method": "relay/interactionAnswered", "params": {"key": key}})
        return result


    def capabilities(self, refresh=False):
        return self.model_catalog.public(refresh=refresh)

    def _load_models(self):
        self.start()
        return list(pages(self.connection, "model/list", {"includeHidden": False}))

    def usage(self):
        self.start()
        result = self.connection.request('account/rateLimits/read', {})
        return {**{key: result[key] for key in ('rateLimits', 'rateLimitsByLimitId', 'ordinaryUsageAllowed', 'rateLimitResetCredits') if key in result},
                'resetSupported': isinstance(result.get('rateLimitResetCredits'), dict)}

    def usage_reset_scope(self):
        """Bind recovery to the native account without exposing its identity to phones."""
        self.start()
        account = self.connection.request('account/read', {'refreshToken': False}).get('account')
        if (not isinstance(account, dict) or account.get('type') not in ('chatgpt', 'chatgptAuthTokens')
                or not isinstance(account.get('email'), str) or not account['email'].strip()):
            raise CodexError('无法确认当前 ChatGPT 账户，未重置额度', code='usage_reset_account_unverified', http_status=409)
        identity = {key: account[key] for key in ('email', 'id', 'accountId', 'organizationId', 'workspaceId') if key in account}
        return hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

    def reset_usage(self, submission_id, account_scope):
        if self.usage_reset_scope() != account_scope:
            raise CodexError('登录账户已变化，请核对原操作', code='usage_reset_account_changed', http_status=409)
        try:
            result = self.connection.request('account/rateLimitResetCredit/consume', {'idempotencyKey': submission_id})
        except CodexError as error:
            if error.code == 'native_method_unsupported' or error.native_code == -32601:
                raise CodexError('此节点不支持额度重置，请更新节点', code='usage_reset_unsupported', http_status=409) from error
            raise
        outcome = result.get('outcome')
        if outcome not in {'reset', 'alreadyRedeemed', 'nothingToReset', 'noCredit'}:
            raise CodexError('重置结果待核对', code='usage_reset_unknown', request_sent=True)
        return {'requestId': submission_id, 'resetOutcome': outcome}

    def project_directory(self, path=None):
        self.start()
        return project_directory(self.connection, path)

    def project_git(self, project_id):
        self.start()
        project = self.projects.get(project_id)
        roots = project.get('roots') or []
        if not roots:
            raise CodexError('项目没有工作目录', code='invalid_project', http_status=409)
        return project_git(self.connection, roots[0]['path'])

    def diff(self, thread_id):
        return workspace_diff(self.connection, self._attachment_cwd(thread_id))

    def review_context(self, thread_id):
        return review_context(self.connection, self._attachment_cwd(thread_id))

    def external_workspace(self, thread_id):
        self.start()
        if self.workspaces and self.workspaces.row(thread_id):
            return None
        thread = self.connection.request('thread/read', {'threadId': thread_id, 'includeTurns': False})['thread']
        projects = [self.projects.get(thread['projectId'])] if thread.get('projectId') else list(pages(self.connection, 'project/list'))
        return self.projects.external_worktrees.associations([thread], projects).get(thread_id)

    def review(self, thread_id, target):
        target = validate_review(target)
        self.start()
        with self.operation_gate(thread_id):
            self._assert_workspace(thread_id)
            self._assert_submission_ready(thread_id)
            snapshot = self.get_session(thread_id)
            if snapshot.get('readOnly') or snapshot.get('activeTurnId') or snapshot.get('requests'):
                return None
            cwd = snapshot['thread'].get('cwd') or snapshot.get('settings', {}).get('cwd')
            baseline = self.review_guard.begin(thread_id, cwd)
            result = self.connection.request('review/start', {'threadId': thread_id, 'target': target, 'delivery': 'inline'})
            turn_id = (result.get('turn') or {}).get('id')
            if not turn_id:
                raise CodexError('原生审查没有返回执行身份', code='native_protocol', request_sent=True)
            self._created.pop(thread_id, None)
            self.review_guard.bind(thread_id, turn_id, baseline)
            return {'threadId': thread_id, 'turnId': turn_id}

    def read_file(self, path):
        self.start()
        return read_file(self.connection, path)

    def stream_download(self, path):
        self.start()
        return stream_download(self.connection, path)

    def prepare_download(self, path, range_header=None, if_range=None):
        self.start()
        return prepare_download(self.connection, path, range_header, if_range)

    def read_directory(self, path):
        self.start()
        return read_directory(self.connection, path)

    def upload_attachment_stream(self, thread_id, name, stream, size):
        self.start()
        return upload_attachment_stream(self.connection, self._attachment_cwd(thread_id), name, stream, size)

    def upload_todo_attachment_stream(self, name, stream, size):
        self.start()
        return upload_attachment_stream(self.connection, None, name, stream, size,
                                        directory_name='rivo-relay-todo-media')

    def _project_attachment_cwd(self, project_id):
        self.start()
        project = self.projects.get(project_id)
        roots = project.get('roots') or []
        if not roots or not isinstance(roots[0], dict) or not roots[0].get('path'):
            raise CodexError('项目没有工作目录', code='invalid_project', http_status=409)
        return roots[0]['path']

    def upload_project_attachment_stream(self, project_id, name, stream, size):
        # Staging is scoped to the selected native project; a pending composer
        # must not create an empty thread simply to obtain an upload location.
        return upload_attachment_stream(self.connection, self._project_attachment_cwd(project_id), name, stream, size)


    def _attachment_cwd(self, thread_id):
        self.start()
        with self._lock:
            thread = copy.deepcopy(self._created.get(thread_id))
        if thread is None:
            thread = self.connection.request("thread/read", {"threadId": thread_id, "includeTurns": False})["thread"]
        return thread.get("cwd")


    def goal_capabilities(self):
        # Read-only sentinel: never start/resume a thread merely to discover support.
        self.start()
        sentinel = "00000000-0000-0000-0000-000000000000"
        try:
            self.connection.request("thread/goal/get", {"threadId": sentinel})
        except CodexError as error:
            if error.code == "native_method_unsupported" or error.native_code == -32601:
                return {"goalSupported": False}
            if error.native_code == -32600 and str(error) == f"thread not found: {sentinel}":
                return {"goalSupported": True}
            raise
        return {"goalSupported": True}

    def read_goal(self, thread_id):
        # Goal RPC is independent of history and does not resume/acquire a writer.
        try:
            result = self.connection.request("thread/goal/get", {"threadId": thread_id})
            return {"goal": result.get("goal"), "goalSupported": True}
        except CodexError as error:
            if error.code == "native_method_unsupported" or error.native_code == -32601:
                return {"goal": None, "goalSupported": False}
            raise

    def update_goal(self, thread_id, operation, *, objective=None, token_budget=None):
        self.start()
        self._assert_workspace(thread_id)
        if operation not in {"set", "pause", "resume", "clear"}:
            raise CodexError("目标操作无效", code="invalid_request", http_status=400)
        params = {"threadId": thread_id, "origin": "user"}
        if operation == "set":
            if not isinstance(objective, str) or not objective.strip() or len(objective) > 200000:
                raise CodexError("请填写目标", code="invalid_request", http_status=400)
            if token_budget is not None and (type(token_budget) is not int or not 0 < token_budget <= 9223372036854775807):
                raise CodexError("Token 预算必须为正整数", code="invalid_request", http_status=400)
            params.update(objective=objective.strip(), status="active")
            if token_budget is not None:
                params["tokenBudget"] = token_budget
        elif operation != "clear":
            params["status"] = "paused" if operation == "pause" else "active"
        with self.operation_gate(thread_id):
            self._resume(thread_id)
            self.connection.request("thread/goal/clear" if operation == "clear" else "thread/goal/set", params)
        # Submission receipts contain identities only. Goal contents are read
        # through the session snapshot, never persisted as receipt fields.
        return {"threadId": thread_id}

    def update_settings(self, thread_id, *, model=None, effort=None, service_tier=None, sandbox=None, approval_policy=None):
        permissions = self._permission_options(sandbox, approval_policy)
        sandbox = permissions.get('sandbox')
        self.start()
        self._assert_workspace(thread_id)
        params = {"threadId": thread_id}
        if model is not None:
            params["model"] = model
        if effort is not None:
            params["effort"] = effort
        if service_tier is not None:
            if service_tier not in ('default', 'priority'):
                raise CodexError('处理模式无效', code='invalid_settings', http_status=400)
            params['serviceTier'] = service_tier
        with self.operation_gate(thread_id):
            self._resume(thread_id)
            if 'approvalPolicy' in permissions:
                params['approvalPolicy'] = permissions['approvalPolicy']
            if 'approvalsReviewer' in permissions:
                params['approvalsReviewer'] = permissions['approvalsReviewer']
            if sandbox is not None:
                current = self._settings.get(thread_id, {})
                if (current.get('collaborationMode') or {}).get('mode') == 'plan':
                    raise CodexError('请先退出讨论模式再修改文件访问权限', code='invalid_settings', http_status=409)
                types = {'read-only': 'readOnly', 'workspace-write': 'workspaceWrite', 'danger-full-access': 'dangerFullAccess'}
                params['sandboxPolicy'] = {'type': types[sandbox]}
                if sandbox != 'danger-full-access':
                    params['sandboxPolicy']['networkAccess'] = False if approval_policy and approval_policy.startswith('preset-') else (current.get('sandbox') or {}).get('networkAccess') is True
            response = self.connection.request("thread/settings/update", params)
            if sandbox is not None:
                self._forget_observation(thread_id, 'modeBaseline')
            with self._lock:
                settings = self._settings.setdefault(thread_id, {})
                settings.update(self._settings_from(params))
                settings.update(self._settings_from(response.get('threadSettings') or response))
            if model is not None or effort is not None:
                for kind, turn_id in list(self.observations_for(thread_id)):
                    if kind == 'usage':
                        self._forget_observation(thread_id, 'usage', turn_id)
        return {'threadId': thread_id}

    def _event(self, event):
        if not isinstance(event, dict) or not isinstance(event.get('method'), str) or not isinstance(event.get('params', {}), dict):
            return
        if event.get("connectionId", self.connection.generation) != self.connection.generation:
            return
        self.interactions.receive(event)
        method, params = event.get("method"), event.get("params", {})
        thread_id, turn_id = params.get("threadId", params.get('conversationId')), params.get("turnId")
        if (thread_id is not None and not isinstance(thread_id, str)) or (turn_id is not None and not isinstance(turn_id, str)):
            return
        if method in {'turn/started', 'thread/settings/updated'} and thread_id:
            self._prediction_epochs[thread_id] = self._prediction_epochs.get(thread_id, 0) + 1
        # Token usage does not change project membership or activity counters.
        # Invalidating on every usage notification discards expensive in-flight
        # catalog refreshes while an unrelated chat is generating.
        if (method.startswith(("thread/", "project/")) and method != 'thread/tokenUsage/updated') or method in {
                "turn/started", "turn/completed", "relay/nativeDisconnected", "relay/writerDisconnected"}:
            self.projects.invalidate()
        with self._lock:
            if thread_id and turn_id and method == 'turn/plan/updated':
                self._observe(thread_id, 'plan', turn_id, {'turnId': turn_id, 'plan': params.get('plan', []), 'explanation': params.get('explanation')})
            elif thread_id and turn_id and method == 'turn/diff/updated':
                self._observe(thread_id, 'changes', turn_id, {'files': diff_summary(params.get('diff', ''))})
            elif thread_id and method == 'thread/tokenUsage/updated':
                self._observe(thread_id, 'usage', turn_id, params.get('tokenUsage', {}))
            elif thread_id and turn_id and method == 'model/rerouted' and params.get('toModel'):
                previous = self.observations_for(thread_id).get(('turnSettings', turn_id), {})
                self._observe(thread_id, 'turnSettings', turn_id, {**previous, 'model': params['toModel'], 'rerouted': True})
            if method == "relay/nativeDisconnected":
                self._unmaterialized.clear()
                self._active.clear()
                self._live.clear()
                self._pending_terminal.clear()
                self._settings.clear()
                self._created.clear()
                self._materializing.clear()
                self._joined.clear()
            elif method == 'relay/writerDisconnected':
                lost = set(params.get('threadIds', []))
                for tid in lost:
                    self._unmaterialized.discard(tid)
                    if params.get('ownedWriter') and not self.observations_for(tid).get(('ownership', ''), {}).get('released'):
                        self._observe(tid, 'ownership', '', {'released': True, 'releaseState': 'disconnected'})
                    self._active.pop(tid, None)
                    self._settings.pop(tid, None)
                    self._created.pop(tid, None)
                    self._materializing.pop(tid, None)
                    self._joined.discard(tid)
                self._live = OrderedDict((key, value) for key, value in self._live.items() if key[0] not in lost)
                self._pending_terminal = {key for key in self._pending_terminal if key[0] not in lost}
            elif method == "thread/settings/updated" and thread_id:
                settings = params.get("threadSettings")
                if isinstance(settings, dict):
                    self._settings[thread_id] = self._settings_from(settings)
                else:
                    # An unrecognized settings event requires a fresh native
                    # subscription response, never guessed defaults.
                    self._joined.discard(thread_id)
            elif thread_id and (method in {"thread/closed", "thread/deleted", "thread/archived"}
                                or (method == "thread/status/changed" and
                                    params.get("status", {}).get("type") == "notLoaded")):
                self._joined.discard(thread_id)
                self._created.pop(thread_id, None)
                self._materializing.pop(thread_id, None)
            elif method in {"turn/started", "turn/completed"} and thread_id:
                turn = copy.deepcopy(params.get("turn", {}))
                if not isinstance(turn, dict):
                    return
                turn_id = turn.get("id")
                if isinstance(turn_id, str) and turn_id:
                    previous = self._live.get((thread_id, turn_id))
                    if method == 'turn/started' and previous and previous.get('status') in TERMINAL:
                        return
                    if previous and not turn.get('items'):
                        turn['items'] = copy.deepcopy(previous.get('items', []))
                    if method == 'turn/completed' and (previous is not None or self._active.get(thread_id) == turn_id):
                        self._pending_terminal.add((thread_id, turn_id))
                    self._live[(thread_id, turn_id)] = turn
                    if method == "turn/started":
                        self._active[thread_id] = turn_id
                    elif self._active.get(thread_id) == turn_id:
                        self._active.pop(thread_id, None)
            elif method in {"thread/goal/updated", "thread/goal/cleared"}:
                # A goal update can reference a completed turn; it is not a new live turn.
                pass
            elif thread_id and turn_id and method in ITEM_EVENTS:
                key = (thread_id, turn_id)
                turn = self._live.get(key) or {"id": turn_id, "status": "inProgress", "items": []}
                if turn.get('status') not in TERMINAL:
                    completed = self._completed_items.setdefault(key, set())
                    offset = project_item(turn.setdefault('items', []), method, params, completed)
                    if turn['items']:
                        self._live[key] = turn
                    if offset is not None:
                        event = {**event, 'params': {**params, 'relayOffset': offset}}
            while len(self._live) > 128:
                key, _ = self._live.popitem(last=False)
                self._pending_terminal.discard(key)
            self._completed_items = {key: value for key, value in self._completed_items.items() if key in self._live}
        # Apply native interaction/live state before waking reconciliation, and
        # update the table before publishing the event to HTTP subscribers.
        if hasattr(self, 'activity'):
            self.activity.receive(event)
        # Account / remote-control notifications can carry personal account
        # metadata and do not belong in a chat event stream.
        if ("id" in event or method.startswith(("thread/", "turn/", "item/", "serverRequest/", "relay/"))
                or (method in {"error", "warning", "model/rerouted"} and thread_id)):
            self.on_event({k: v for k, v in event.items() if k != "connectionId"})

    def _mark_completed_items(self, thread_id, turns):
        """Annotate only the response copy, after checking native convergence."""
        with self._lock:
            completed = {turn_id: set(ids) for (tid, turn_id), ids in self._completed_items.items()
                         if tid == thread_id}
        for turn in turns:
            for item in turn.get('items', []):
                if (item.get('id') in completed.get(turn['id'], set())
                        and item.get('type') in {'agentMessage', 'agent_message'}):
                    item['relayCompleted'] = True

    def _merge_live(self, thread_id, turns):
        settled_turn = self.native_journal.settled_interruption(thread_id)
        with self._lock:
            live = [copy.deepcopy(t) for (tid, _), t in self._live.items() if tid == thread_id]
            pending_terminal = set(self._pending_terminal)
            completed_items = {key: set(value) for key, value in self._completed_items.items()}
        by_id = {t["id"]: t for t in turns}
        for current in live:
            if current['id'] == settled_turn and current.get('status') == 'inProgress':
                # An old writer's deltas cannot revive an interruption confirmed
                # by the current owner. New turns and terminal events still win.
                continue
            if current["id"] not in by_id:
                # A just-completed observed turn can still be ahead of persisted
                # history. Keep its temporary projection until native catches up;
                # otherwise a terminal event could publish a false ready/empty page.
                pending = (thread_id, current['id']) in pending_terminal
                started = current.get('startedAt')
                newer = isinstance(started, (int, float)) and any(
                    isinstance(turn.get('startedAt'), (int, float)) and turn['startedAt'] > started for turn in turns)
                if current.get("status") == "inProgress" or pending and not newer:
                    turns.append(current)
                continue
            target = by_id[current["id"]]
            if target.get("status") not in {"inProgress", "notStarted", "interrupted"}:
                with self._lock:
                    self._pending_terminal.discard((thread_id, current['id']))
                continue
            existing = {i["id"]: i for i in target.get("items", [])}
            for item in current.get("items", []):
                old = existing.get(item["id"])
                if old is None:
                    target["items"].append(item)
                else:
                    merged = merge_item(old, item, completed=item['id'] in completed_items.get((thread_id, current['id']), set()))
                    old.clear()
                    old.update(merged)
            if current.get("status") == "inProgress" or (thread_id, current['id']) in pending_terminal:
                target["status"] = current['status']
                if current.get('status') in TERMINAL:
                    for field in ('error', 'completedAt', 'durationMs'):
                        if field in current:
                            target[field] = copy.deepcopy(current[field])
        return turns
