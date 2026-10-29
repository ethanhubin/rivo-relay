"""Connection-bound native requests. Never invent or automatically approve answers."""
from __future__ import annotations

import copy
import threading

from .connection import CodexError


class Interactions:
    def __init__(self, connection):
        self.connection = connection
        self._lock = threading.RLock()
        self._pending = {}

    def receive(self, event):
        with self._lock:
            method = event.get("method", "")
            if method == "relay/nativeDisconnected":
                self._pending.clear()
            elif method == "item/tool/call":
                # Desktop executes dynamic tools. This RPC is not a human
                # approval/question and Relay must neither display it as one
                # nor answer it on behalf of the tool's owning client.
                return
            elif "id" in event:
                generation = event.get("connectionId", self.connection.generation)
                request_id = event["id"]
                key = f"{generation}:{type(request_id).__name__}:{request_id}"
                self._pending[key] = {"key": key, "id": request_id, "method": method,
                                      "params": copy.deepcopy(event.get("params", {})),
                                      "connectionId": generation}
            elif method in {"serverRequest/resolved", "serverRequest/completed"}:
                request_id = event.get("params", {}).get("requestId")
                self._pending = {k: v for k, v in self._pending.items() if v["id"] != request_id}
            elif method == "turn/completed":
                params = event.get("params", {})
                turn_id = params.get("turn", {}).get("id")
                thread_id = params.get("threadId")
                self._pending = {k: v for k, v in self._pending.items()
                                 if not (v["params"].get("threadId") == thread_id and
                                         (not turn_id or v["params"].get("turnId") == turn_id))}

    def list(self, thread_id):
        with self._lock:
            return [copy.deepcopy(v) for v in self._pending.values()
                    if v["connectionId"] == self.connection.generation and
                    v["params"].get("threadId", v["params"].get("conversationId")) == thread_id]

    def respond(self, key, result):
        if not isinstance(result, dict):
            raise CodexError("原生交互响应必须是对象", code="invalid_response", http_status=400)
        with self._lock:
            request = self._pending.get(key)
            if request is None or request["connectionId"] != self.connection.generation:
                raise CodexError("此原生请求已失效，请刷新会话", code="interaction_expired", http_status=409)
            # Remove before sending. A lost send must not be automatically repeated.
            del self._pending[key]
            self.connection.respond(request["id"], result)
            return {"requestId": key, "threadId": request["params"].get("threadId")}
