"""Connection-bound native requests. Never invent or automatically approve answers."""
from __future__ import annotations

import copy
import threading
from contextlib import contextmanager

from .connection import CodexError
from .interaction_contract import project_interaction, native_response
from ..runtime_state import revision_of


class Interactions:
    def __init__(self, connection):
        self.connection = connection
        self._lock = threading.RLock()
        self._pending = {}
        self._revision = 0
        self._request_revisions = {}
        self._claims = {}

    def receive(self, event):
        with self._lock:
            if event.get('connectionId', self.connection.generation) != self.connection.generation:
                return
            method = event.get("method", "")
            before = set(self._pending)
            before_claims = set(self._claims)
            before_revision = self._revision
            previous_sessions = {r['params'].get('threadId', r['params'].get('conversationId'))
                for r in [*self._pending.values(), *(c['request'] for c in self._claims.values())]}
            if method == "relay/nativeDisconnected":
                self._pending.clear()
                self._claims.clear()
            elif method == 'relay/writerDisconnected':
                expired = set(event.get('params', {}).get('requestIds', []))
                self._pending = {key: value for key, value in self._pending.items()
                                 if value['id'] not in expired}
                self._claims = {key: value for key, value in self._claims.items()
                                if value['request']['id'] not in expired}
            elif method == "item/tool/call":
                # Desktop executes dynamic tools. This RPC is not a human
                # approval/question and Relay must neither display it as one
                # nor answer it on behalf of the tool's owning client.
                return
            elif "id" in event:
                generation = event.get("connectionId", self.connection.generation)
                request_id = event["id"]
                key = f"{generation}:{type(request_id).__name__}:{request_id}"
                self._claims.pop(key, None)
                self._pending[key] = {"key": key, "id": request_id, "method": method,
                                      "params": copy.deepcopy(event.get("params", {})),
                                      "connectionId": generation}
                self._revision += 1
                self._request_revisions[key] = revision_of(str(generation), self._revision, self._pending[key])
            elif method in {"serverRequest/resolved", "serverRequest/completed"}:
                request_id = event.get("params", {}).get("requestId")
                self._pending = {k: v for k, v in self._pending.items()
                                 if type(v['id']) is not type(request_id) or v['id'] != request_id}
                self._claims = {k: c for k, c in self._claims.items()
                                if type(c['request']['id']) is not type(request_id) or c['request']['id'] != request_id}
            elif method == "turn/completed":
                params = event.get("params", {})
                turn_id = params.get("turn", {}).get("id")
                thread_id = params.get("threadId", params.get('conversationId'))
                self._pending = {k: v for k, v in self._pending.items()
                                 if not (v["params"].get("threadId", v['params'].get('conversationId')) == thread_id and
                                         (not turn_id or not v["params"].get("turnId") or v["params"].get("turnId") == turn_id))}
                self._claims = {k: c for k, c in self._claims.items()
                    if not (c['request']['params'].get('threadId', c['request']['params'].get('conversationId')) == thread_id and
                            (not turn_id or not c['request']['params'].get('turnId') or c['request']['params'].get('turnId') == turn_id))}
            if before != set(self._pending) or before_claims != set(self._claims):
                self._revision += 1
            self._request_revisions = {key: value for key, value in self._request_revisions.items() if key in self._pending}
            current_sessions = {r['params'].get('threadId', r['params'].get('conversationId')) for r in self._pending.values()}
            return {sid for sid in previous_sessions | current_sessions if isinstance(sid, str) and sid} if before_revision != self._revision else set()

    def discard_thread(self, thread_id):
        """Expire requests after the owning writer has stopped; never answer them."""
        with self._lock:
            before = set(self._pending)
            before_claims = set(self._claims)
            self._pending = {key: value for key, value in self._pending.items()
                if value['params'].get('threadId', value['params'].get('conversationId')) != thread_id}
            self._claims = {key: value for key, value in self._claims.items()
                if value['request']['params'].get('threadId', value['request']['params'].get('conversationId')) != thread_id}
            if before != set(self._pending) or before_claims != set(self._claims):
                self._revision += 1
            self._request_revisions = {key: value for key, value in self._request_revisions.items() if key in self._pending}

    def list(self, thread_id):
        with self._lock:
            return [copy.deepcopy(v) for v in self._pending.values()
                    if v["connectionId"] == self.connection.generation and
                    v["params"].get("threadId", v["params"].get("conversationId")) == thread_id]

    def session_for(self, key):
        with self._lock:
            params = (self._pending.get(key) or {}).get('params', {})
            return params.get('threadId', params.get('conversationId'))

    def snapshot(self, thread_id):
        with self._lock:
            requests = self.list(thread_id)
            return {'generation': str(self.connection.generation), 'revision': str(self._revision), 'requests': requests,
                    'interactions': [project_interaction(request, self._request_revisions[request['key']]) for request in requests]}

    @contextmanager
    def guard(self, snapshot):
        """No native I/O while held; prevent a late HTTP cut reviving requests."""
        with self._lock:
            if (snapshot['generation'] != str(self.connection.generation)
                    or snapshot['revision'] != str(self._revision)):
                raise CodexError('原生交互已变化，请重新读取会话', code='session_changed', http_status=409)
            yield

    def respond_interaction(self, session_id, generation, interaction_id, revision, response):
        with self._lock:
            request = self._pending.get(interaction_id)
            if (str(self.connection.generation) != generation or request is None
                    or str(request['connectionId']) != generation
                    or request['params'].get('threadId', request['params'].get('conversationId')) != session_id
                    or self._request_revisions.get(interaction_id) != revision):
                raise CodexError('此原生请求已失效，请刷新会话', code='interaction_expired', http_status=409)
            result = native_response(request, revision, response)
            claim = self._claim(interaction_id, result)
        receipt = self._dispatch(interaction_id, claim, result)
        return {**receipt, 'interactionId': interaction_id, 'runtimeGeneration': generation, 'revision': revision}

    def respond(self, key, result):
        with self._lock:
            claim = self._claim(key, result)
        return self._dispatch(key, claim, result)

    def _claim(self, key, result):
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
            self._revision += 1
            claim = {'request': request, 'revision': self._request_revisions.pop(key)}
            self._claims[key] = claim
            return claim

    def _dispatch(self, key, claim, result):
        request = claim['request']
        # Native writer retirement synchronously publishes invalidations. Never
        # hold the pending lock while waiting for that writer's operation lock.
        # The transport checks this generation again under its final send lock.
        try:
            self.connection.respond(request['id'], result, expected_generation=request['connectionId'])
        except CodexError as error:
            with self._lock:
                if (error.definite and self._claims.get(key) is claim
                        and request['connectionId'] == self.connection.generation and key not in self._pending):
                    self._pending[key] = request
                    self._request_revisions[key] = claim['revision']
                    self._revision += 1
            raise
        finally:
            with self._lock:
                if self._claims.get(key) is claim:
                    self._claims.pop(key, None)
        receipt = {'requestId': key}
        thread_id = request['params'].get('threadId') or request['params'].get('conversationId')
        if isinstance(thread_id, str) and thread_id:
            receipt['threadId'] = thread_id
        return receipt
