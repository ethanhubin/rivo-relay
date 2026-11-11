"""Existing-runtime DSH backend. No runtime spawning or canonical history copies."""
from __future__ import annotations
import threading
import json
import time
from ..codex.connection import CodexError
from ..operation_gates import ThreadGates
from ..submission_dispatch import dispatch_phase, creation_phase
from .transport import BridgeTransport
from .projection import project, digest, turn_id
from .receipts import accepted_receipt
from .interaction_contract import project_interaction, native_decision
from ..runtime_state import session_state, invalidated


class DshService:
    def __init__(self, socket_path, on_event=None, *, transport=None):
        self.transport = transport or BridgeTransport(socket_path)
        self.on_event = on_event or (lambda event: None)
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._worker = None
        self._generation = ''
        self._connected = False
        self._cursor = 0
        self._version = 0
        self._control_epoch = 0
        self._requests = {}
        self._interaction_views = {}
        self._live = {}
        self._known = set()
        self._session_states = {}
        self.gates = ThreadGates()
        self.workspaces = None

    def operation_gate(self, thread_id):
        return self.gates.hold(thread_id)

    def start(self):
        with self._lock:
            if self._worker is None and not self._stop.is_set():
                self._worker = threading.Thread(target=self._run, name='dsh-events', daemon=True)
                self._worker.start()

    def close(self):
        self._stop.set()
        self._disconnected()
        if self._worker and self._worker is not threading.current_thread():
            self._worker.join(timeout=1)

    def _emit(self, method, params=None):
        self.on_event({'method': method, 'params': params or {}})

    def _disconnected(self):
        with self._lock:
            sessions = self._known | {r.get('sessionId') for r in self._requests.values()}
            generation = self._generation
            changed = self._connected
            self._connected = False
            self._requests.clear()
            self._interaction_views.clear()
            self._live.clear()
            self._known.clear()
            self._session_states.clear()
            self._version += 1
            self._control_epoch += 1
        if changed:
            self._emit('relay/nativeDisconnected')
        self._invalidate_sessions(sessions, generation, 'control')

    def _invalidate_sessions(self, sessions, generation, scope):
        for sid in sessions:
            if isinstance(sid, str) and sid and generation:
                self.on_event(invalidated(sid, generation, scope))

    def _call(self, method, params=None):
        try:
            return self.transport.call(method, params)
        except CodexError as error:
            if error.code in ('dsh_disconnected', 'dsh_protocol', 'dsh_socket_untrusted'):
                self._disconnected()
            raise

    def _admit(self, generation):
        if not isinstance(generation, str) or not generation:
            raise CodexError('Missing DSH generation', code='dsh_protocol')
        with self._lock:
            changed = self._generation != generation or not self._connected
            sessions = set()
            if self._generation != generation:
                sessions = self._known | {r.get('sessionId') for r in self._requests.values()}
                self._control_epoch += 1
                self._cursor = 0
                self._requests.clear()
                self._interaction_views.clear()
                self._live.clear()
                self._known.clear()
                self._session_states.clear()
            if changed:
                self._version += 1
            self._generation = generation
            self._connected = True
        if changed:
            self._emit('relay/historyInvalidated')
        self._invalidate_sessions(sessions, generation, 'control')

    def _refresh_status(self):
        with self._lock:
            requested_generation = self._generation
            requested_epoch = self._control_epoch
        result = self._call('status')
        with self._lock:
            if (self._generation != requested_generation
                    or self._control_epoch != requested_epoch):
                return None
            self._admit(result['generation'])
            return result

    def _run(self):
        while not self._stop.is_set():
            try:
                if not self._connected:
                    self._refresh_status()
                    if not self._connected:
                        continue
                with self._lock:
                    requested_generation = self._generation
                    requested_epoch = self._control_epoch
                    requested_cursor = self._cursor
                batch = self.transport.poll(requested_generation, requested_cursor)
                # A status read or transport failure may supersede this poll while
                # it waits. Check and publish atomically so an old response cannot
                # restore approvals, rewind the cursor, or resurrect its runtime.
                with self._lock:
                    if self._stop.is_set():
                        return
                    if (self._generation != requested_generation
                            or self._control_epoch != requested_epoch):
                        continue
                    # A new generation returned by this still-current poll is a
                    # valid runtime restart; the Host's reset owns its new cursor.
                    self._admit(batch['generation'])
                    if batch.get('reset'):
                        sessions = self._known | {r.get('sessionId') for r in self._requests.values()}
                        self._control_epoch += 1
                        self._requests.clear()
                        self._interaction_views.clear()
                        self._live.clear()
                        self._known.clear()
                        self._session_states.clear()
                        self._version += 1
                        self._emit('relay/nativeDisconnected')
                        self._emit('relay/historyInvalidated')
                        self._invalidate_sessions(sessions, self._generation, 'control')
                    for event in batch.get('events', []):
                        self._event(event)
                    self._cursor = batch['cursor']
            except Exception:
                self._disconnected()
                self._stop.wait(1)

    def _event(self, event):
        kind, sid = event.get('type'), event.get('sessionId')
        with self._lock:
            self._version += 1
            if kind != 'assistant-stream':
                self._control_epoch += 1
        if kind == 'assistant-stream':
            frame = event.get('frame', {})
            with self._lock:
                if frame.get('type') == 'start':
                    self._live[sid] = {**frame, 'text': '', 'nextIndex': 0}
                live = self._live.get(sid)
                if frame.get('type') == 'chunk' and live and frame.get('attemptId') == live.get('attemptId'):
                    if frame.get('index') != live['nextIndex']:
                        self._live.pop(sid, None)
                    else:
                        live['nextIndex'] += 1
                        live['revision'] = frame.get('revision', 0)
                        chunk = frame.get('chunk', {})
                        if chunk.get('type') == 'text-delta':
                            delta = chunk.get('text', '')
                            offset = len(live['text'])
                            live['text'] += delta
                            self._emit('item/agentMessage/delta', {'threadId': sid, 'turnId': turn_id(live['turn']),
                                'itemId': 'live-' + live['attemptId'], 'delta': delta, 'relayOffset': offset})
                            return
                if frame.get('type') == 'end':
                    self._live.pop(sid, None)
        elif kind == 'status':
            with self._lock:
                if sid in self._known:
                    self._session_states[sid] = event.get('running') is True
        elif kind in ('session', 'catalog'):
            # Journal changes invalidate the previous control cut. Only a new
            # authoritative snapshot may confirm this session again.
            with self._lock:
                if sid:
                    self._known.discard(sid)
                    self._session_states.pop(sid, None)
                else:
                    self._known.clear()
                    self._session_states.clear()
        elif kind == 'approval':
            req = event.get('request', event)
            with self._lock:
                self._requests[req['key']] = req
                self._interaction_views.pop(req['key'], None)
        elif kind == 'approval-resolved':
            with self._lock:
                self._requests.pop(event.get('key'), None)
                self._interaction_views.pop(event.get('key'), None)
            self._emit('serverRequest/resolved', {'threadId': sid, 'requestId': event.get('key')})
        if sid:
            self._invalidate_sessions([sid], self._generation, 'interactions' if kind in ('approval', 'approval-resolved') else
                                      'history' if kind == 'assistant-stream' else 'control')
            self._emit('relay/historyInvalidated', {'threadId': sid})
        else:
            self._emit('relay/historyInvalidated')

    def status(self):
        self.start()
        try:
            result = self._refresh_status() or {}
        except CodexError:
            result = {}
        return {'runtime': 'dsh', 'displayName': 'DSH', 'connected': self._connected, 'generation': self._generation,
                'supportedRuntimeVersion': result.get('supportedRuntimeVersion'), 'capabilities': {'runtime': 'dsh', 'handoffAvailable': False,
                    'queue': False, 'goals': False, 'attachments': False}}

    def runtime_capabilities(self, *, snapshot=None, connected=None):
        from .capabilities import capabilities
        return capabilities(connected=self._connected if connected is None else connected, snapshot=snapshot)

    def list_projects(self):
        result = self._call('projects')
        return [{'id': item['workspaceId'], 'name': item.get('title') or item.get('path') or 'DSH',
                 'path': item.get('path', ''), 'cwd': item.get('path', ''), 'runtime': 'dsh'} for item in result['items']]

    def list_sessions(self, project_id, archived=False, summary=False):
        placement = self._call('projects')
        workspace = next((p for p in placement['items'] if p['workspaceId'] == project_id), None)
        if workspace is None:
            raise CodexError('DSH workspace not found', code='not_found', http_status=404)
        archive = set(placement.get('archivedSessionIds', []))
        pinned = set(placement.get('pinnedSessionIds', []))
        ids = set(workspace.get('sessionIds', []))
        sessions = self._call('sessions')['items']
        result = []
        for row in sessions:
            sid = row['sessionId']
            if sid not in ids or (sid in archive) != archived:
                continue
            values = (row.get('projections') or {}).get('values', row.get('projections') or {})
            title = values.get('title')
            if isinstance(title, dict):
                title = title.get('title')
            result.append({'id': sid, 'name': title or 'DSH 会话', 'preview': '', 'cwd': row.get('cwd', ''),
                           'projectId': project_id, 'pinned': sid in pinned, 'updatedAt': row.get('updatedAt', 0) / 1000,
                           'status': 'running' if row.get('running') else 'idle'})
        return result

    def create_session(self, project_id, **options):
        if any(value not in (None, False) for value in options.values()):
            self._unsupported()
        creation_phase(project_id, 'preparing')
        workspace = next((p for p in self._call('projects')['items'] if p['workspaceId'] == project_id), None)
        if workspace is None:
            raise CodexError('DSH workspace not found', code='not_found', http_status=404)
        creation_phase(project_id, 'dispatching')
        result = self._call('create', {'workspaceId': project_id})
        receipt = {'threadId': result['sessionId']}
        creation_phase(project_id, 'received', receipt)
        return receipt

    def _snapshot(self, sid, attempt=0):
        with self._lock:
            epoch, generation = self._control_epoch, self._generation
        first = self._call('snapshot', {'sessionId': sid, 'maxMessages': 100})
        with self._lock:
            if self._generation != generation:
                raise CodexError('DSH runtime changed during read', code='history_changed', http_status=409)
            if generation and generation != first['generation']:
                self._disconnected()
                raise CodexError('DSH runtime changed; reconnect required', code='history_changed', http_status=409)
            self._admit(first['generation'])
            if not generation:
                epoch = self._control_epoch
        first['_controlEpoch'] = epoch
        opening = first['page']
        if first.get('state', {}).get('cursor') != opening['cursor']:
            if attempt < 2:
                return self._snapshot(sid, attempt + 1)
            raise CodexError('DSH state changed during snapshot', code='history_changed', http_status=409)
        records, page = list(opening['records']), opening
        cut = opening['cursor']
        total_bytes = len(json.dumps(records, ensure_ascii=False))
        # Bound total work; refuse incomplete history instead of fabricating turn boundaries.
        for _ in range(64):
            if not page.get('hasMore'):
                break
            before = min(record['event']['seq'] for record in page['records'])
            older = self._call('snapshot', {'sessionId': sid, 'beforeSeq': before, 'throughSeq': cut, 'maxMessages': 100})
            if older['generation'] != first['generation']:
                raise CodexError('DSH runtime changed during history read', code='history_changed', http_status=409)
            page = older['page']
            total_bytes += len(json.dumps(page['records'], ensure_ascii=False))
            if total_bytes > 64 * 1024 * 1024:
                raise CodexError('DSH history exceeds validation byte limit', code='dsh_history_limit')
            records.extend(page['records'])
        else:
            raise CodexError('DSH history exceeds validation window', code='dsh_history_limit')
        events = sorted([record['event'] for record in records], key=lambda e: e['seq'])
        if len({e['seq'] for e in events}) != len(events):
            raise CodexError('DSH history pages overlap', code='dsh_protocol')
        turns = project(events)
        active = opening.get('assistantStream', {}).get('activeAttempt')
        if active:
            stream_text = ''.join(''.join(r.get('texts', [])) if r.get('type') == 'text-chunks'
                                 else r.get('chunk', {}).get('text', '') if r.get('type') == 'chunk' and r.get('chunk', {}).get('type') == 'text-delta'
                                 else '' for r in active.get('stream', []))
            turn = next((t for t in turns if t['id'] == turn_id(active['turn'])), None)
            if turn and turn['status'] == 'inProgress':
                turn['items'].append({'id': 'live-' + active['attemptId'], 'type': 'agentMessage', 'text': stream_text})
        with self._lock:
            changed = self._control_epoch != epoch
            if not changed:
                if active:
                    current = self._live.get(sid)
                    revision = opening['assistantStream'].get('revision', 0)
                    if current is None or current.get('revision', 0) <= revision:
                        self._live[sid] = {**active, 'text': stream_text, 'revision': revision}
        if changed:
            if attempt < 2:
                return self._snapshot(sid, attempt + 1)
            raise CodexError('DSH control changed during read', code='history_changed', http_status=409)
        return first, events, turns

    def get_session(self, thread_id, cursor=None, limit=8, native_revision=None):
        if not 1 <= limit <= 100:
            raise ValueError('Invalid history limit')
        first, events, turns = self._snapshot(thread_id)
        # Structural revision stays stable during append-only live token updates.
        revision = digest([first['generation'], [(e['seq'], e['type']) for e in events]])
        if native_revision and native_revision != revision:
            raise CodexError('DSH history changed', code='history_changed', http_status=409)
        end = len(turns) if cursor is None else int(cursor)
        if end < 0 or end > len(turns):
            raise ValueError('Invalid history cursor')
        begin = max(0, end - limit)
        selected = turns[begin:end]
        state = first.get('state') or {}
        requests = [self._request(r, events) for r in first.get('requests', [])]
        current = next((t['id'] for t in reversed(turns) if t['status'] == 'inProgress'), None)
        header = first['page'].get('header', {})
        status = 'waiting' if requests else 'running' if state.get('running') else 'idle'
        result = {'thread': {'id': thread_id, 'turns': selected, 'cwd': header.get('cwd', ''),
                            'updatedAt': (events[-1].get('time', 0) if events else 0) / 1000},
                  'requests': requests, 'settings': {}, 'status': status, 'activeTurnId': current if state.get('running') else None,
                  'control': {'state': 'writable', 'canOperate': True, 'action': None}, 'readOnly': False,
                  'nativeSync': {'state': 'ready'}, 'nativeRuntime': {'runtime': 'dsh', 'displayName': 'DSH', 'handoffAvailable': False},
                  'nativeRevision': revision, 'nativeHeadRevision': digest([revision, selected, first['page'].get('assistantStream')]),
                  'nextCursor': str(begin) if begin else None, 'backwardsCursor': str(begin) if begin else None}
        with self._lock:
            if not self._connected or self._generation != first['generation']:
                raise CodexError('DSH disconnected during snapshot', code='dsh_disconnected')
            if self._control_epoch != first['_controlEpoch']:
                raise CodexError('DSH control changed during read', code='history_changed', http_status=409)
            for key in [k for k, r in self._requests.items() if r.get('sessionId') == thread_id]:
                self._requests.pop(key, None)
                self._interaction_views.pop(key, None)
            self._requests.update({r['key']: r for r in first.get('requests', [])})
            views = [project_interaction(request, first['generation']) for request in requests]
            self._interaction_views.update({view['id']: view for view in views})
            self._known.add(thread_id)
            self._session_states[thread_id] = state.get('running') is True
            self._version += 1
            result['activity'] = self.active_tasks()
            result['sessionState'] = session_state(session_id=thread_id, generation=first['generation'],
                control_revision=first['_controlEpoch'], execution_status=status, execution_id=result['activeTurnId'],
                synchronization=result['nativeSync'], control=result['control'], interactions=views)
        return result

    def history(self, thread_id, **options):
        result = self.get_session(thread_id, **options)
        return {key: value for key, value in result.items() if key in ('nativeRevision', 'nativeHeadRevision', 'nextCursor', 'nativeSync')} | {
            'threadId': thread_id, 'turns': result['thread']['turns']}

    def send(self, thread_id, text, client_message_id=None, input_items=None, **options):
        dispatch_phase(client_message_id, thread_id, 'preparing')
        if not client_message_id or options.get('resume_turn_id') or options.get('require_goal_support'):
            self._unsupported()
        if any(options.get(k) is not None for k in ('model', 'effort', 'service_tier')) or options.get('mode') not in (None, 'default'):
            self._unsupported()
        if input_items is not None:
            if any(item.get('type') != 'text' for item in input_items):
                self._unsupported()
            text = '\n'.join(item.get('text', '') for item in input_items)
        if not text.strip():
            raise CodexError('Text input required', code='invalid_request', http_status=400)
        dispatch_phase(client_message_id, thread_id, 'dispatching')
        result = self._call('prompt', {'sessionId': thread_id, 'requestId': client_message_id, 'text': text})
        receipt = accepted_receipt(result, thread_id, client_message_id)
        if receipt is None:
            raise CodexError('DSH acceptance identity missing', code='dsh_protocol', request_sent=True)
        dispatch_phase(client_message_id, thread_id, 'received', receipt)
        return receipt

    def recover_submission(self, record):
        if record['operation'] != 'thread.turns':
            return None
        thread_id, request_id = record['target'], record['id']
        result = self._call('receipt', {'sessionId': thread_id, 'requestId': request_id})
        return accepted_receipt(result, thread_id, request_id)

    def interrupt(self, thread_id, turn_id=None):
        snapshot = self.get_session(thread_id, limit=2)
        if not turn_id or snapshot['activeTurnId'] != turn_id:
            raise CodexError('DSH turn changed', code='thread_changed', http_status=409)
        number = int(turn_id.removeprefix('dsh-turn-'))
        self._call('cancel', {'sessionId': thread_id, 'expectedTurn': number})
        return {'threadId': thread_id, 'turnId': turn_id}

    @staticmethod
    def _request(request, events=()):
        tool = next((e['data'] for e in reversed(events) if e['type'] == 'tool/call'
                     and e['data'].get('callId') == request.get('callId')), {})
        detail = request.get('toolName', '')
        if tool.get('arguments'):
            detail += '\n' + tool['arguments']
        return {'key': request['key'], 'method': 'runtime/approval', 'params': {
            'runtime': 'dsh', 'threadId': request['sessionId'], 'title': 'DSH 请求操作授权',
            'reason': request.get('reason', ''), 'detail': detail,
            'availableDecisions': ['accept', 'decline', 'cancel']}}

    def respond(self, key, response):
        decision = {'accept': 'allowed-once', 'decline': 'rejected', 'cancel': 'cancelled'}.get(response.get('decision'))
        with self._lock:
            request = self._requests.get(key)
            connected = self._connected
        if not decision or not request or not connected:
            raise CodexError('DSH approval expired or invalid', code='request_expired', http_status=409)
        self._call('respond', {'key': key, 'outcome': decision})
        with self._lock:
            self._requests.pop(key, None)
            self._interaction_views.pop(key, None)
            self._version += 1
            self._control_epoch += 1
        self._emit('serverRequest/resolved', {'threadId': request['sessionId'], 'requestId': key})
        self._invalidate_sessions([request['sessionId']], self._generation, 'interactions')
        return {'threadId': request['sessionId'], 'requestId': key}

    def respond_interaction(self, session_id, generation, interaction_id, revision, response):
        with self._lock:
            request = self._requests.get(interaction_id)
            view = self._interaction_views.get(interaction_id)
            if (not self._connected or generation != self._generation or not request or not view
                    or request.get('sessionId') != session_id or view['revision'] != revision):
                raise CodexError('此请求已失效，请刷新会话', code='interaction_expired', http_status=409)
            decision = native_decision(view, response)
            # Keep this exact local view until dispatch; the Host independently
            # checks the generation-bound key and live polling lease at respond.
            receipt = self.respond(interaction_id, decision)
            return {**receipt, 'interactionId': interaction_id, 'runtimeGeneration': generation, 'revision': revision}

    def capabilities(self, refresh=False):
        return {'models': [], 'catalog': {'stale': False, 'source': 'dsh', 'updatedAt': time.time()},
                'modelSelectionAvailable': False}

    def usage(self):
        return {}

    def goal_capabilities(self):
        return {'goalSupported': False}

    def active_tasks(self):
        with self._lock:
            waiting = {r.get('sessionId') for r in self._requests.values()}
            return {'generation': self._generation or 'dsh-unavailable', 'version': self._version,
                    'complete': self._connected and bool(self._known),
                    'tasks': [{'threadId': s, 'status': 'waiting' if s in waiting else 'running'}
                              for s in sorted(self._known) if self._connected and (s in waiting or self._session_states.get(s))],
                    'knownThreadIds': sorted(self._known) if self._connected else [],
                    'unknownThreadIds': []}

    @staticmethod
    def _unsupported(*args, **kwargs):
        raise CodexError('此 DSH 验证节点暂不支持该操作', code='runtime_unsupported', http_status=409)

    project_git = project_directory = add_project = update_settings = pin_session = archive_session = _unsupported
    fork_session = delete_session = preview_handoff = handoff = predict_message = update_goal = _unsupported
    answer_questions = steer = validate_queue_target = send_queued = review_context = diff = _unsupported

    def external_workspace(self, thread_id):
        return None
