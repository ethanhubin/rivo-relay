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
            if event.get('connectionId', self.connection.generation) != self.connection.generation:
                return
            method = event.get("method", "")
            if method == "relay/nativeDisconnected":
                self._pending.clear()
            elif method == 'relay/writerDisconnected':
                expired = set(event.get('params', {}).get('requestIds', []))
                self._pending = {key: value for key, value in self._pending.items()
                                 if value['id'] not in expired}
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
                self._pending = {k: v for k, v in self._pending.items()
                                 if type(v['id']) is not type(request_id) or v['id'] != request_id}
            elif method == "turn/completed":
                params = event.get("params", {})
                turn_id = params.get("turn", {}).get("id")
                thread_id = params.get("threadId", params.get('conversationId'))
                self._pending = {k: v for k, v in self._pending.items()
                                 if not (v["params"].get("threadId", v['params'].get('conversationId')) == thread_id and
                                         (not turn_id or not v["params"].get("turnId") or v["params"].get("turnId") == turn_id))}

    def discard_thread(self, thread_id):
        """Expire requests after the owning writer has stopped; never answer them."""
        with self._lock:
            self._pending = {key: value for key, value in self._pending.items()
                if value['params'].get('threadId', value['params'].get('conversationId')) != thread_id}

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
            if request['method'].endswith('requestUserInput'):
                questions = request['params'].get('questions')
                answers = result.get('answers')
                ids = [q.get('id') for q in questions] if isinstance(questions, list) and all(isinstance(q, dict) for q in questions) else []
                if (not ids or not all(isinstance(value, str) and value for value in ids) or len(ids) != len(set(ids))
                        or not isinstance(answers, dict) or set(answers) != set(ids)):
                    raise CodexError('请完整回答当前问题', code='invalid_response', http_status=400)
                for answer in answers.values():
                    values = answer.get('answers') if isinstance(answer, dict) else None
                    if (not isinstance(values, list) or not values or len(values) > 20
                            or any(not isinstance(value, str) or not value.strip() or len(value) > 200000 for value in values)):
                        raise CodexError('问题答案无效', code='invalid_response', http_status=400)
            # Remove before sending. A lost send must not be automatically repeated.
            del self._pending[key]
            try:
                self.connection.respond(request["id"], result)
            except CodexError as error:
                if error.definite and request['connectionId'] == self.connection.generation:
                    self._pending[key] = request
                raise
            return {"requestId": key, "threadId": request["params"].get("threadId", request['params'].get('conversationId'))}
