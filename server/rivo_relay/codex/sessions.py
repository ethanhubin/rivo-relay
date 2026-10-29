"""Native threads are the sole history and execution authority.

Only live native notifications are held in memory here. Relay waiting tasks are
stored separately; no writer takeover, history database or permission override.
"""
from __future__ import annotations

import copy
import threading
from collections import OrderedDict

from .connection import CodexError, NativeConnection
from .history import NativeHistoryAdapter, NativeHistoryProtocolError
from .interactions import Interactions
from .projects import ProjectCatalog, pages
from .files import read_file, read_directory, upload_attachment, download_file, upload_attachment_stream, stream_download


def thread_status(thread):
    status = thread.get("status", {})
    kind = status.get("type") if isinstance(status, dict) else status
    if kind == "active":
        return "running"
    if kind == "idle":
        return "idle"
    return "unknown"


class CodexService:
    def __init__(self, socket_path=None, on_event=None, codex_home=None, *, connection=None):
        self.on_event = on_event or (lambda event: None)
        self._lock = threading.RLock()
        self._operations = threading.RLock()
        self._subscriptions = threading.RLock()
        self._live = OrderedDict()
        self._active = {}
        self._settings = {}
        self._created = {}
        self._joined = set()
        self._generation = None
        self.connection = connection or NativeConnection(socket_path, self._event, codex_home)
        if connection is not None:
            connection.on_event = self._event
        self.projects = ProjectCatalog(self.connection)
        self.interactions = Interactions(self.connection)
        self.history_adapter = NativeHistoryAdapter()

    def start(self):
        status = self.connection.start()
        with self._lock:
            if self._generation != self.connection.generation:
                self._settings.clear()
                self._created.clear()
                self._joined.clear()
                self._active.clear()
                self._live.clear()
                self._generation = self.connection.generation
        return status

    def close(self):
        # Closing this client never stops the shared daemon or its turns.
        self.connection.close()

    def status(self):
        return self.connection.status()

    def list_projects(self):
        self.start()
        return self.projects.list()

    def add_project(self, path, name=None, idempotency_key=None):
        self.start()
        project = self.projects.add(path, name=name, idempotency_key=idempotency_key)
        return {"projectId": project["id"]}

    def list_sessions(self, project_id):
        self.start()
        project = self.projects.get(project_id)
        roots = [r["path"] for r in project.get("roots", [])]
        # Older Desktop threads may lack projectId. Exact root matching adds them
        # without assigning or rewriting their native metadata.
        threads = list(pages(self.connection, "thread/list", {
            "cwd": roots, "archived": False, "modelProviders": [],
            "sortKey": "updated_at", "sortDirection": "desc",
        })) if roots else []
        by_id = {t["id"]: t for t in threads
                 if t.get("projectId") in (None, project_id)}
        for thread in pages(self.connection, "thread/list", {
            "projectId": project_id, "archived": False, "modelProviders": [],
            "sortKey": "updated_at", "sortDirection": "desc",
        }):
            by_id[thread["id"]] = thread
        return [{"id": t["id"], "name": t.get("name") or t.get("preview") or "新会话",
                 "preview": t.get("preview", ""), "status": thread_status(t),
                 "pinned": str((t.get("section") or {}).get("name", "")).casefold() == "pinned",
                 "updatedAt": t.get("updatedAt"), "cwd": t.get("cwd"),
                 "projectId": t.get("projectId") or project_id}
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
        return {"threadId": thread_id}

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

    def _resume(self, thread_id):
        with self._subscriptions:
            with self._lock:
                # Codex's first turn must use the connection that started the
                # not-yet-materialized thread. Resuming it can fail before rollout
                # creation even on this same daemon.
                if thread_id in self._created:
                    return copy.deepcopy(self._created[thread_id])
                joined = thread_id in self._joined
            if joined:
                thread = self.connection.request("thread/read", {
                    "threadId": thread_id, "includeTurns": False,
                })["thread"]
                if thread.get("status", {}).get("type") != "notLoaded":
                    return thread
                with self._lock:
                    self._joined.discard(thread_id)
            # Joining emits native notifications, even for an idle thread.
            # Repeat reads must not resume and trigger their own SSE refresh.
            response = self.connection.request("thread/resume", {"threadId": thread_id, "excludeTurns": True})
            with self._lock:
                self._settings[thread_id] = self._settings_from(response)
                self._joined.add(thread_id)
            return response["thread"]

    def get_session(self, thread_id, cursor=None, limit=30):
        self.start()
        # Join once per connection, then read metadata without new notifications.
        # No cwd/model/permission values are supplied.
        read_only = False
        try:
            thread = self._resume(thread_id)
        except CodexError as error:
            if error.code != "native_rejected" or "already has an active writer" not in str(error):
                raise
            # Persisted history is readable without acquiring another runtime's writer.
            thread = self.connection.request("thread/read", {"threadId": thread_id, "includeTurns": False})["thread"]
            read_only = True
        history = self.history(thread_id, cursor=cursor, limit=limit)
        thread = {**thread, "turns": history["turns"]}
        requests = self.interactions.list(thread_id)
        with self._lock:
            settings = copy.deepcopy(self._settings.get(thread_id, {}))
            active = self._active.get(thread_id)
        if thread.get("cwd"):
            settings.setdefault("cwd", thread["cwd"])
        if active is None:
            active = next((t["id"] for t in reversed(thread["turns"])
                           if t.get("status") == "inProgress"), None)
        status = "waiting" if requests else ("running" if active else thread_status(thread))
        return {"thread": thread, "requests": requests, "settings": settings,
                "activeTurnId": active, "status": status, "readOnly": read_only, "nextCursor": history["nextCursor"],
                "backwardsCursor": history["backwardsCursor"]}

    def history(self, thread_id, cursor=None, limit=30):
        self.start()
        with self._lock:
            if thread_id in self._created:
                return {"turns": [], "nextCursor": None, "backwardsCursor": None}
        try:
            page = self.history_adapter.read_page(self.connection, thread_id,
                    limit=max(1, min(100, int(limit))), cursor=cursor)
            turns = copy.deepcopy(list(page.turns))
            result = {"turns": turns, "nextCursor": page.next_cursor, "backwardsCursor": page.backwards_cursor}
        except CodexError as error:
            if thread_id in self._created and ("not materialized" in str(error).lower() or
                                                "no rollout" in str(error).lower()):
                result = {"turns": [], "nextCursor": None, "backwardsCursor": None}
            else:
                raise
        except NativeHistoryProtocolError as error:
            raise CodexError(str(error), code="native_protocol") from error
        if cursor is None:
            result["turns"] = self._merge_live(thread_id, result["turns"])
        return result

    def create_session(self, project_id, title=""):
        self.start()
        project = self.projects.get(project_id)
        roots = [r["path"] for r in project.get("roots", [])]
        if not roots:
            raise CodexError("此项目没有原生工作目录", http_status=409)
        with self._operations:
            result = self.connection.request("thread/start", {
                "cwd": roots[0], "projectId": project_id, "historyMode": "paginated",
            })
            thread_id = result["thread"]["id"]
            with self._lock:
                self._created[thread_id] = copy.deepcopy(result["thread"])
                self._joined.add(thread_id)
                self._settings[thread_id] = self._settings_from(result)
            receipt = {"threadId": thread_id}
            if title:
                try:
                    self.connection.request("thread/name/set", {"threadId": thread_id, "name": title})
                except CodexError:
                    # The thread exists. A title failure must never imply it is
                    # safe to issue another thread/start.
                    self.on_event({"method": "relay/titlePending", "params": {"threadId": thread_id}})
            return receipt

    @staticmethod
    def _input(text):
        if not isinstance(text, str) or not text.strip():
            raise CodexError("请输入消息", code="invalid_message", http_status=400)
        return [{"type": "text", "text": text, "text_elements": []}]

    def send(self, thread_id, text, model=None, effort=None, client_message_id=None, *, input_items=None):
        self.start()
        with self._operations:
            self._resume(thread_id)
            params = {"threadId": thread_id, "input": input_items if input_items is not None else self._input(text)}
            if model is not None:
                params["model"] = model
            if effort is not None:
                params["effort"] = effort
            if client_message_id is not None:
                params["clientUserMessageId"] = client_message_id
            try:
                result = self.connection.request("turn/start", params)
            except CodexError as error:
                if not error.definite:
                    with self._lock:
                        self._created.pop(thread_id, None)
                raise
            turn = result.get("turn", {})
            with self._lock:
                self._created.pop(thread_id, None)
            if not isinstance(turn.get("id"), str) or not turn["id"]:
                raise CodexError("Codex 没有返回可核对的 turn ID", code="native_protocol", request_sent=True)
            return {"threadId": thread_id, "turnId": turn.get("id")}

    def validate_queue_target(self, thread_id):
        self.start()
        with self._lock:
            if thread_id in self._created:
                return
        self.connection.request("thread/read", {"threadId": thread_id, "includeTurns": False})

    def send_queued(self, thread_id, **kwargs):
        with self._operations:
            snapshot = self.get_session(thread_id)
            if snapshot.get("readOnly") or snapshot.get("activeTurnId") or snapshot["status"] != "idle":
                return None
            return self.send(thread_id, "", **kwargs)

    def steer(self, thread_id, text, expected_turn_id, client_message_id=None, *, input_items=None):
        self.start()
        with self._operations:
            self._resume(thread_id)
            params = {"threadId": thread_id,
                      "input": input_items if input_items is not None else self._input(text),
                      "expectedTurnId": expected_turn_id}
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

    def models(self):
        self.start()
        return list(pages(self.connection, "model/list", {"includeHidden": False}))

    def read_file(self, path):
        self.start()
        return read_file(self.connection, path)

    def stream_download(self, path):
        self.start()
        return stream_download(self.connection, path)

    def read_directory(self, path):
        self.start()
        return read_directory(self.connection, path)

    def upload_attachment_stream(self, thread_id, name, stream, size):
        self.start()
        return upload_attachment_stream(self.connection, self._attachment_cwd(thread_id), name, stream, size)

    def _attachment_cwd(self, thread_id):
        self.start()
        with self._lock:
            thread = copy.deepcopy(self._created.get(thread_id))
        if thread is None:
            thread = self.connection.request("thread/read", {"threadId": thread_id, "includeTurns": False})["thread"]
        return thread.get("cwd")

    def upload_attachment(self, thread_id, name, encoded):
        return upload_attachment(self.connection, self._attachment_cwd(thread_id), name, encoded)

    def update_settings(self, thread_id, *, model=None, effort=None):
        self.start()
        params = {"threadId": thread_id}
        if model is not None:
            params["model"] = model
        if effort is not None:
            params["effort"] = effort
        return self.connection.request("thread/settings/update", params)

    def _event(self, event):
        if event.get("connectionId", self.connection.generation) != self.connection.generation:
            return
        self.interactions.receive(event)
        method, params = event.get("method"), event.get("params", {})
        thread_id, turn_id = params.get("threadId"), params.get("turnId")
        with self._lock:
            if method == "relay/nativeDisconnected":
                self._active.clear()
                self._live.clear()
                self._settings.clear()
                self._created.clear()
                self._joined.clear()
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
            elif method in {"turn/started", "turn/completed"} and thread_id:
                turn = copy.deepcopy(params.get("turn", {}))
                turn_id = turn.get("id")
                if turn_id:
                    self._live[(thread_id, turn_id)] = turn
                    if method == "turn/started":
                        self._active[thread_id] = turn_id
                    elif self._active.get(thread_id) == turn_id:
                        self._active.pop(thread_id, None)
            elif thread_id and turn_id:
                key = (thread_id, turn_id)
                turn = self._live.setdefault(key, {"id": turn_id, "status": "inProgress", "items": []})
                items = turn.setdefault("items", [])
                if method in {"item/started", "item/completed"}:
                    item = copy.deepcopy(params.get("item", {}))
                    if item.get("id"):
                        items[:] = [i for i in items if i.get("id") != item["id"]]
                        items.append(item)
                elif method in {"item/agentMessage/delta", "item/commandExecution/outputDelta"}:
                    item_id = params.get("itemId")
                    item = next((i for i in items if i.get("id") == item_id), None)
                    if item is None and item_id:
                        item = {"id": item_id, "type": "agentMessage" if "agentMessage" in method else "commandExecution"}
                        items.append(item)
                    if item is not None:
                        field = "text" if "agentMessage" in method else "aggregatedOutput"
                        item[field] = str(item.get(field) or "") + str(params.get("delta") or "")
                while len(self._live) > 128:
                    self._live.popitem(last=False)
        # Account / remote-control notifications can carry personal account
        # metadata and do not belong in a chat event stream.
        if ("id" in event or method.startswith(("thread/", "turn/", "item/", "serverRequest/", "relay/"))
                or (method in {"error", "warning"} and thread_id)):
            self.on_event({k: v for k, v in event.items() if k != "connectionId"})

    def _merge_live(self, thread_id, turns):
        with self._lock:
            live = [copy.deepcopy(t) for (tid, _), t in self._live.items() if tid == thread_id]
        by_id = {t["id"]: t for t in turns}
        for current in live:
            if current["id"] not in by_id:
                # Only a live in-progress turn belongs after the latest page.
                if current.get("status") == "inProgress":
                    turns.append(current)
                continue
            target = by_id[current["id"]]
            if target.get("status") not in {"inProgress", "notStarted", "interrupted"}:
                continue
            existing = {i["id"]: i for i in target.get("items", [])}
            for item in current.get("items", []):
                old = existing.get(item["id"])
                if old is None:
                    target["items"].append(item)
                elif len(item.get("text", "")) >= len(old.get("text", "")) and (
                        not old.get("text") or item.get("text", "").startswith(old.get("text", ""))):
                    old.update(item)
            if current.get("status") == "inProgress":
                target["status"] = "inProgress"
        return turns
