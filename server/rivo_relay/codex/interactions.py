"""Connection-bound native requests. Never invent or automatically approve answers."""
from __future__ import annotations

from collections import OrderedDict
import copy
import threading

from .connection import CodexError


class Interactions:
    def __init__(self, connection):
        self.connection = connection
        self._lock = threading.RLock()
        self._pending = {}
        self._inflight = {}
        self._generation = connection.generation
        self._resolved = OrderedDict()
        self._terminal = OrderedDict()
        self._turns = {}
        self._excluded = set()

    @staticmethod
    def _remember(values, key):
        values[key] = True
        values.move_to_end(key)
        while len(values) > 4096:
            values.popitem(last=False)

    def _sync_generation(self):
        if self._generation != self.connection.generation:
            self._generation = self.connection.generation
            self._pending.clear()
            self._inflight.clear()
            self._resolved.clear()
            self._terminal.clear()
            self._turns.clear()
            self._excluded.clear()

    def _drop(self, predicate):
        for key, request in list({**self._pending, **self._inflight}.items()):
            if predicate(request):
                self._remember(self._resolved, key)
                self._pending.pop(key, None)

    def _key(self, request_id):
        return f"{self._generation}:{type(request_id).__name__}:{request_id}"

    @staticmethod
    def _thread(params):
        return params.get('threadId') or params.get('conversationId')


    def receive(self, event):
        with self._lock:
            if event.get('connectionId', self.connection.generation) != self.connection.generation:
                return
            self._sync_generation()
            method = event.get("method", "")
            params = event.get('params') or {}
            thread_id = self._thread(params)
            turn_id = params.get('turnId') or (params.get('turn') or {}).get('id')
            if method == "relay/nativeDisconnected":
                self._drop(lambda _: True)
                self._turns.clear()
            elif method == 'relay/writerDisconnected':
                expired = {self._key(value) for value in params.get('requestIds', [])}
                threads = set(params.get('threadIds', []))
                self._drop(lambda r: r['key'] in expired or self._thread(r['params']) in threads)
            elif method == "item/tool/call":
                return False
            elif "id" in event:
                key = self._key(event['id'])
                if (key in self._resolved or key in self._inflight or thread_id in self._excluded or
                        (thread_id, turn_id) in self._terminal or
                        turn_id and self._turns.get(thread_id) not in (None, turn_id) and
                        (thread_id, self._turns[thread_id]) not in self._terminal):
                    return False
                self._pending[key] = {"key": key, "id": event['id'], "method": method,
                                      "params": copy.deepcopy(params), "connectionId": self._generation}
            elif method in {"serverRequest/resolved", "serverRequest/completed"}:
                key = self._key(params.get('requestId'))
                self._remember(self._resolved, key)
                self._pending.pop(key, None)
            elif method == 'turn/started' and thread_id and turn_id:
                if (thread_id, turn_id) in self._terminal:
                    return False
                previous = self._turns.get(thread_id)
                if previous and previous != turn_id:
                    self._remember(self._terminal, (thread_id, previous))
                self._turns[thread_id] = turn_id
                self._drop(lambda r: self._thread(r['params']) == thread_id and
                           r['params'].get('turnId') != turn_id)
            elif method in ("turn/completed", "turn/failed", "turn/interrupted"):
                if thread_id and turn_id:
                    self._remember(self._terminal, (thread_id, turn_id))
                self._drop(lambda r: self._thread(r['params']) == thread_id and
                           (not turn_id or not r['params'].get('turnId') or r['params']['turnId'] == turn_id))
            elif method == 'thread/closed':
                # Closing a subscription does not resolve the native request.
                # A later resume can replay its original ID on this connection.
                self._pending = {k: r for k, r in self._pending.items()
                                 if self._thread(r['params']) != thread_id}
            elif method in ('thread/archived', 'thread/deleted'):
                self._excluded.add(thread_id)
                self._drop(lambda r: self._thread(r['params']) == thread_id)
            elif method in ('thread/unarchived', 'thread/started'):
                self._excluded.discard(thread_id or (params.get('thread') or {}).get('id'))
            return True

    def discard_thread(self, thread_id):
        """Expire requests after the owning writer has stopped; never answer them."""
        with self._lock:
            self._sync_generation()
            self._drop(lambda r: self._thread(r['params']) == thread_id)

    def list(self, thread_id):
        with self._lock:
            self._sync_generation()
            return [copy.deepcopy(v) for v in self._pending.values()
                    if v["connectionId"] == self.connection.generation and
                    v["params"].get("threadId", v["params"].get("conversationId")) == thread_id]

    def respond(self, key, result):
        if not isinstance(result, dict):
            raise CodexError("原生交互响应必须是对象", code="invalid_response", http_status=400)
        with self._lock:
            self._sync_generation()
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
            # In-flight requests cannot be replayed or answered twice. Do not hold
            # the state lock across transport IO: events must still resolve/cancel
            # requests while an answer is being sent.
            del self._pending[key]
            self._inflight[key] = request
        try:
            self.connection.respond(request["id"], result)
        except Exception as error:
            with self._lock:
                if (getattr(error, 'definite', False) and request['connectionId'] == self.connection.generation and
                        key not in self._resolved and
                        (self._thread(request['params']), request['params'].get('turnId')) not in self._terminal):
                    self._pending[key] = request
                else:
                    self._remember(self._resolved, key)
            raise
        else:
            with self._lock:
                self._remember(self._resolved, key)
        finally:
            with self._lock:
                self._inflight.pop(key, None)
        receipt = {"requestId": key}
        thread_id = self._thread(request["params"])
        if isinstance(thread_id, str) and thread_id:
            receipt["threadId"] = thread_id
        return receipt
