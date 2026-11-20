"""Existing-runtime DSH backend. No runtime spawning or canonical history copies."""
from __future__ import annotations
import threading
import json
import time
import base64
import uuid
from ..codex.connection import CodexError
from ..operation_gates import ThreadGates
from ..submission_dispatch import dispatch_phase, creation_phase, creation_intent
from .transport import BridgeTransport
from .projection import project, digest, turn_id, stream_text as projected_stream_text
from .receipts import accepted_receipt
from .interaction_contract import project_interaction, native_decision
from ..runtime_state import session_state, invalidated, interaction


class DshService:
    def __init__(self, socket_path, on_event=None, *, transport=None):
        self.transport = transport or BridgeTransport(socket_path)
        self.on_event = on_event or (lambda event: None)
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._worker = None
        self._generation = ''
        self._connected = False
        self._features = set()
        self._features_generation = None
        self._cursor = 0
        self._version = 0
        self._control_epoch = 0
        self._requests = {}
        self._interaction_views = {}
        self._live = {}
        self._known = set()
        self._session_states = {}
        self._catalog_running = set()
        self._catalog_ids = set()
        self._session_projects = {}
        self._native_waiting = {}
        self.gates = ThreadGates()
        self.workspaces = None
        self._uploads = None

    def configure_attachments(self, root):
        from .uploads import UploadStore
        self._uploads = UploadStore(root)

    def _require_feature(self, name):
        with self._lock:
            if not self._connected or name not in self._features:
                self._unsupported()

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
            self._features.clear()
            self._features_generation = None
            self._requests.clear()
            self._interaction_views.clear()
            self._live.clear()
            self._known.clear()
            self._session_states.clear()
            self._catalog_running.clear()
            self._catalog_ids.clear()
            self._session_projects.clear()
            self._native_waiting.clear()
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
                self._features.clear()
                self._features_generation = None
                sessions = self._known | {r.get('sessionId') for r in self._requests.values()}
                self._control_epoch += 1
                self._cursor = 0
                self._requests.clear()
                self._interaction_views.clear()
                self._live.clear()
                self._known.clear()
                self._session_states.clear()
                self._catalog_running.clear()
                self._catalog_ids.clear()
                self._session_projects.clear()
                self._native_waiting.clear()
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
            features = result.get('capabilities', [])
            self._features = set(features) if isinstance(features, list) and all(isinstance(f, str) for f in features) else set()
            self._features_generation = self._generation
            return result

    def _run(self):
        while not self._stop.is_set():
            try:
                if not self._connected or self._features_generation != self._generation:
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
                        self._catalog_running.clear()
                        self._catalog_ids.clear()
                        self._session_projects.clear()
                        self._native_waiting.clear()
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
                    self._live[sid] = {**frame, 'text': '', 'reasoning': '', 'nextIndex': 0}
                live = self._live.get(sid)
                if frame.get('type') == 'chunk' and live and frame.get('attemptId') == live.get('attemptId'):
                    if frame.get('index') != live['nextIndex']:
                        self._live.pop(sid, None)
                    else:
                        live['nextIndex'] += 1
                        live['revision'] = frame.get('revision', 0)
                        chunk = frame.get('chunk', {})
                        if chunk.get('type') in ('text-delta', 'reasoning-delta'):
                            reasoning = chunk['type'] == 'reasoning-delta'
                            field = 'reasoning' if reasoning else 'text'
                            delta = chunk.get('text', '')
                            offset = len(live.get(field, ''))
                            live[field] = live.get(field, '') + delta
                            params = {'threadId': sid, 'turnId': turn_id(live['turn']),
                                'itemId': ('live-reasoning-' if reasoning else 'live-') + live['attemptId'],
                                'delta': delta, 'relayOffset': offset}
                            if reasoning:
                                params['contentIndex'] = 0
                            self._emit('item/reasoning/textDelta' if reasoning else 'item/agentMessage/delta', params)
                            return
                if frame.get('type') == 'end':
                    self._live.pop(sid, None)
        elif kind == 'status':
            with self._lock:
                if isinstance(sid, str) and sid and event.get('running') is True:
                    self._catalog_running.add(sid)
                else:
                    self._catalog_running.discard(sid)
                if sid in self._known:
                    self._session_states[sid] = event.get('running') is True
        elif kind in ('session', 'catalog'):
            # Journal changes invalidate the previous control cut. Only a new
            # authoritative snapshot may confirm this session again.
            with self._lock:
                if sid:
                    self._known.discard(sid)
                    self._session_states.pop(sid, None)
                    self._catalog_running.discard(sid)
                    self._native_waiting.pop(sid, None)
                else:
                    self._known.clear()
                    self._session_states.clear()
                    self._catalog_running.clear()
                    self._catalog_ids.clear()
                    self._session_projects.clear()
                    self._native_waiting.clear()
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

    def pairing_status(self, generation: str) -> str:
        try:
            live = self.transport.call('status', timeout=1)
        except CodexError:
            return 'bridge_disconnected'
        observed = live.get('generation') if isinstance(live, dict) else None
        if not observed:
            return 'bridge_disconnected'
        if observed != generation:
            return 'wrong_bridge'
        with self._lock:
            return 'ready' if self._connected and self._generation == observed else 'bridge_disconnected'

    def status(self):
        self.start()
        try:
            result = self._refresh_status() or {}
        except CodexError:
            result = {}
        return {'runtime': 'dsh', 'displayName': 'DSH', 'connected': self._connected, 'generation': self._generation,
                'supportedRuntimeVersion': result.get('supportedRuntimeVersion'), 'capabilities': {'runtime': 'dsh', 'handoffAvailable': False,
                    'queue': False, 'goals': False, 'attachments': 'attachments.images' in self._features}}

    def runtime_capabilities(self, *, snapshot=None, connected=None):
        from .capabilities import capabilities
        return capabilities(connected=self._connected if connected is None else connected,
                            snapshot=snapshot, features=self._features)

    def update_blocker(self):
        return None if self.idle_for_update() else 'native_state_unknown'

    def idle_for_update(self):
        # Cached activity is incomplete after invalidation. Ask the bridge for
        # passive native state; never follow history, answer or cancel to update.
        from ..read_budget import read_budget, read_lock
        try:
            with read_budget(seconds=2):
                with read_lock(self._lock):
                    if self._stop.is_set() or not self._connected or self._requests or self._live:
                        return False
                    generation, epoch, cursor = self._generation, self._control_epoch, self._cursor
                result = self.transport.call('updateStatus', timeout=2)
                with read_lock(self._lock):
                    return (isinstance(result, dict) and result.get('idle') is True
                            and result.get('generation') == generation
                            and type(result.get('cursor')) is int and result['cursor'] == cursor
                            and (self._generation, self._control_epoch, self._cursor) == (generation, epoch, cursor)
                            and self._connected and not self._stop.is_set()
                            and not self._requests and not self._live)
        except Exception:
            # Old bridge versions, timeouts and uncertain state all defer update.
            return False

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
                           'status': 'waiting' if row.get('pendingInput') or self._response_kinds(sid) else 'running' if row.get('running') else 'idle',
                           'responseKinds': sorted(set(row.get('pendingInput') or []) & {'question', 'approval'} | set(self._response_kinds(sid)))})
        return result

    def create_session(self, project_id, **options):
        if any(value not in (None, False) for value in options.values()):
            self._unsupported()
        creation_phase(project_id, 'preparing')
        workspace = next((p for p in self._call('projects')['items'] if p['workspaceId'] == project_id), None)
        if workspace is None:
            raise CodexError('DSH workspace not found', code='not_found', http_status=404)
        session_id = 'session-' + str(uuid.uuid4())
        creation_intent(project_id, session_id, project_id)
        creation_phase(project_id, 'dispatching')
        result = self._call('create', {'workspaceId': project_id, 'sessionId': session_id})
        if result.get('sessionId') != session_id:
            raise CodexError('DSH creation identity missing', code='dsh_protocol', request_sent=True)
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
        media = list(first.get('media') or [])
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
            media.extend(older.get('media') or [])
        else:
            raise CodexError('DSH history exceeds validation window', code='dsh_history_limit')
        events = sorted([record['event'] for record in records], key=lambda e: e['seq'])
        if len({e['seq'] for e in events}) != len(events):
            raise CodexError('DSH history pages overlap', code='dsh_protocol')
        turns = project(events, session_id=sid, media=media)
        active = opening.get('assistantStream', {}).get('activeAttempt')
        if active:
            stream_text = projected_stream_text(active.get('stream'))
            reasoning = projected_stream_text(active.get('stream'), kind='reasoning')
            turn = next((t for t in turns if t['id'] == turn_id(active['turn'])), None)
            if turn and turn['status'] == 'inProgress':
                if reasoning:
                    turn['items'].append({'id': 'live-reasoning-' + active['attemptId'], 'type': 'reasoning',
                                          'summary': [], 'content': [reasoning]})
                turn['items'].append({'id': 'live-' + active['attemptId'], 'type': 'agentMessage', 'text': stream_text})
        with self._lock:
            changed = self._control_epoch != epoch
            if not changed:
                if active:
                    current = self._live.get(sid)
                    revision = opening['assistantStream'].get('revision', 0)
                    if current is None or current.get('revision', 0) <= revision:
                        self._live[sid] = {**active, 'text': stream_text, 'reasoning': reasoning, 'revision': revision}
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
        pending_input = state.get('pendingInput') or []
        status = 'waiting' if requests or pending_input else 'running' if state.get('running') else 'idle'
        from .models import settings
        result = {'thread': {'id': thread_id, 'turns': selected, 'cwd': header.get('cwd', ''),
                            'updatedAt': (events[-1].get('time', 0) if events else 0) / 1000},
                  'requests': requests, 'settings': settings(first.get('selected')), 'status': status,
                  'activeTurnId': current if state.get('running') or requests or pending_input else None,
                  'control': {'state': 'writable', 'canOperate': True, 'action': None}, 'readOnly': False,
                  'nativeSync': {'state': 'ready'}, 'nativeRuntime': {'runtime': 'dsh', 'displayName': 'DSH', 'handoffAvailable': False},
                  'nativeRevision': revision, 'nativeHeadRevision': digest([revision, selected, first['page'].get('assistantStream')]),
                  'nextCursor': str(begin) if begin else None, 'backwardsCursor': str(begin) if begin else None}
        plan = first.get('plan')
        if isinstance(plan, dict) and type(plan.get('active')) is bool:
            active = plan.get('pending') if type(plan.get('pending')) is bool else plan['active']
            result['settings']['collaborationMode'] = {'mode': 'plan' if active else 'default'}
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
            if pending_input:
                kinds = [value for value in pending_input if value in ('approval', 'question', 'other')]
                # Rivo-owned requests already have usable controls. Other native
                # inputs remain visible but cannot be answered through guessed APIs.
                owned = {'question' if request.get('kind') == 'questions' else 'approval'
                         for request in first.get('requests', [])}
                external = [value for value in kinds if value not in owned]
                if external:
                    interaction_revision = digest([first['generation'], thread_id, first['_controlEpoch'], external])
                    labels = {'approval': '操作授权', 'question': '问题回答', 'other': '其他输入'}
                    views.append(interaction(interaction_id='dsh-native-input-' + digest([thread_id, external]),
                        revision=interaction_revision, kind='unsupported', title='请在 DSH 中处理待办输入',
                        description='此请求由 DSH 原生界面处理，Rivo 尚不能代答。',
                        detail='等待：' + '、'.join(labels[value] for value in external), payload={}))
            self._interaction_views.update({view['id']: view for view in views})
            self._known.add(thread_id)
            self._session_states[thread_id] = state.get('running') is True
            if pending_input:
                self._native_waiting[thread_id] = [kind for kind in pending_input if kind in ('question', 'approval')]
            else:
                self._native_waiting.pop(thread_id, None)
            self._version += 1
            result['activity'] = self._activity_snapshot()
            result['sessionState'] = session_state(session_id=thread_id, generation=first['generation'],
                control_revision=first['_controlEpoch'], execution_status=status, execution_id=result['activeTurnId'],
                synchronization=result['nativeSync'], control=result['control'], interactions=views)
        return result

    def history(self, thread_id, **options):
        result = self.get_session(thread_id, **options)
        return {key: value for key, value in result.items() if key in ('nativeRevision', 'nativeHeadRevision', 'nextCursor', 'nativeSync')} | {
            'threadId': thread_id, 'turns': result['thread']['turns']}

    def send(self, thread_id, text, client_message_id=None, input_items=None, **options):
        from .inputs import content
        from .models import selection
        dispatch_phase(client_message_id, thread_id, 'preparing')
        if not client_message_id or options.get('resume_turn_id') or options.get('require_goal_support'):
            self._unsupported()
        if options.get('service_tier') is not None or options.get('mode') not in (None, 'default', 'plan'):
            self._unsupported()
        selected = selection(options.get('model'), options.get('effort'))
        if selected:
            self._require_feature('session.settings')
        items = content(text, input_items)
        if any(item['type'] == 'image' for item in items):
            self._require_feature('attachments.images')
        if any(item['type'] == 'file' for item in items):
            self._require_feature('files.upload')
            for item in items:
                if item['type'] == 'file':
                    item['receiptId'] = self._file_receipt(item.pop('_source'), thread_id, client_message_id)
        params = {'sessionId': thread_id, 'requestId': client_message_id}
        mode = options.get('mode')
        if mode is not None:
            if mode == 'plan' or 'session.plan' in self._features:
                self._require_feature('session.plan')
                params['collaborationMode'] = mode
        if all(item['type'] == 'text' for item in items):
            # Old bridges retain their proven text-only contract.
            params['text'] = '\n'.join(item['text'] for item in items)
        else:
            params['content'] = items
        if selected:
            params['selection'] = selected
        dispatch_phase(client_message_id, thread_id, 'dispatching')
        result = self._call('prompt', params)
        receipt = accepted_receipt(result, thread_id, client_message_id)
        if receipt is None:
            raise CodexError('DSH acceptance identity missing', code='dsh_protocol', request_sent=True)
        dispatch_phase(client_message_id, thread_id, 'received', receipt)
        return receipt

    def recover_submission(self, record):
        if record['operation'] == 'thread.create':
            intent = record.get('intent')
            if (not isinstance(intent, dict) or intent.get('kind') != 'session-create'
                    or intent.get('projectId') != record['target'] or not intent.get('sessionId')):
                return None
            result = self._call('createReceipt', {'workspaceId': intent['projectId'], 'sessionId': intent['sessionId']})
            if (isinstance(result, dict) and result.get('created') is True
                    and result.get('sessionId') == intent['sessionId'] and result.get('workspaceId') == intent['projectId']):
                return {'threadId': intent['sessionId']}
            return None
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
        result = self._call('cancel', {'sessionId': thread_id, 'expectedTurn': number})
        if not isinstance(result, dict) or result.get('accepted') is not True:
            raise CodexError('DSH cancellation receipt missing', code='dsh_protocol', request_sent=True)
        return {'threadId': thread_id, 'turnId': turn_id}

    @staticmethod
    def _request(request, events=()):
        if request.get('kind') == 'questions':
            return {'key': request['key'], 'method': 'runtime/questions', 'params': {
                'runtime': 'dsh', 'threadId': request['sessionId'], 'questions': request['questions']}}
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
        question_answer = request and request.get('kind') == 'questions' and isinstance(response.get('answers'), list)
        if not (decision or question_answer) or not request or not connected:
            raise CodexError('DSH approval expired or invalid', code='request_expired', http_status=409)
        self._call('respond', {'key': key, **({'answers': response['answers']} if question_answer else {'outcome': decision})})
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
        from .models import catalog
        if self._connected and 'session.models' in self._features:
            return catalog(self._call('models'))
        return {'models': [], 'catalog': {'stale': False, 'source': 'dsh', 'updatedAt': time.time()},
                'modelSelectionAvailable': False}

    def update_settings(self, thread_id, *, model=None, effort=None, service_tier=None, sandbox=None, approval_policy=None):
        from .models import selection
        if any(value is not None for value in (service_tier, sandbox, approval_policy)):
            self._unsupported()
        self._require_feature('session.settings')
        selected = selection(model, effort)
        if not selected:
            raise CodexError('请选择模型或推理强度', code='invalid_settings', http_status=400)
        with self.operation_gate(thread_id):
            result = self._call('selectModel', {'sessionId': thread_id, 'selection': selected})
            actual = result.get('selected') if isinstance(result, dict) else None
            if not isinstance(actual, dict) or not actual.get('provider') or not actual.get('model') or any(actual.get(k) != v for k, v in selected.items()):
                raise CodexError('DSH 模型设置回执不完整', code='dsh_protocol', request_sent=True)
            with self._lock:
                self._control_epoch += 1
                generation = self._generation
                self._known.discard(thread_id)
                self._session_states.pop(thread_id, None)
            self._invalidate_sessions([thread_id], generation, 'control')
        return {'threadId': thread_id}

    @staticmethod
    def is_media_source(source):
        from .media import is_media_source
        return is_media_source(source)

    def prepare_download(self, path, range_header=None, if_range=None):
        from .media import parse_source, download
        locator = parse_source(path)
        self._require_feature('media.read')
        with self._lock:
            generation = self._generation
        result = self._call('media', locator)
        with self._lock:
            if not self._connected or self._generation != generation:
                raise CodexError('DSH 图片读取期间运行时已变化', code='dsh_disconnected')
        return download(result, generation=generation, range_header=range_header, if_range=if_range)

    def _project_contains(self, project_id, session_id):
        return any(row['workspaceId'] == project_id and session_id in row.get('sessionIds', [])
                   for row in self._call('projects')['items'])

    def _file_receipt(self, source, session_id, request_id):
        if self._uploads is None:
            self._unsupported()
        return self._uploads.receipt(source, session_id, request_id, project_contains=self._project_contains,
            upload=lambda raw, name: self._call('upload', {'sessionId': session_id, 'name': name,
                'data': base64.b64encode(raw).decode('ascii')}))

    def upload_attachment_stream(self, thread_id, name, stream, size):
        self._require_feature('files.upload')
        if self._uploads is None:
            self._unsupported()
        # Validate the native session before retaining phone data. Upload receipts
        # are acquired at send time because DSH retires them after one prompt.
        if not any(row['sessionId'] == thread_id for row in self._call('sessions')['items']):
            raise CodexError('DSH 会话不存在', code='not_found', http_status=404)
        return self._uploads.stage({'sessionId': thread_id}, name, stream, size)

    def upload_project_attachment_stream(self, project_id, name, stream, size):
        self._require_feature('files.upload')
        if self._uploads is None:
            self._unsupported()
        if not any(row['workspaceId'] == project_id for row in self._call('projects')['items']):
            raise CodexError('DSH 项目不存在', code='not_found', http_status=404)
        return self._uploads.stage({'projectId': project_id}, name, stream, size)

    def usage(self):
        return {}

    def goal_capabilities(self):
        return {'goalSupported': False}

    def active_tasks(self):
        # Native catalog running=true is positive activity evidence, including
        # sessions never opened in this APP. A false row is not proof of idle:
        # native input may still be waiting and needs a session snapshot.
        with self._lock:
            generation, epoch, connected = self._generation, self._control_epoch, self._connected
        if connected:
            try:
                result = self._call('sessions')
                rows = result.get('items')
                if result.get('generation') == generation and isinstance(rows, list) and all(
                        isinstance(row, dict) and isinstance(row.get('sessionId'), str)
                        and type(row.get('running')) is bool for row in rows):
                    running = {row['sessionId'] for row in rows if row['running']}
                    ids = {row['sessionId'] for row in rows}
                    projects = {row['sessionId']: row['projectId'] for row in rows
                                if isinstance(row.get('projectId'), str) and row['projectId']}
                    with self._lock:
                        if self._connected and (self._generation, self._control_epoch) == (generation, epoch):
                            changed = (running, ids, projects) != (self._catalog_running, self._catalog_ids, self._session_projects)
                            previous_known = set(self._known)
                            # New catalog evidence can disprove an old running
                            # snapshot, but cannot certify that pending input is
                            # gone. Drop its confirmation instead of inventing idle.
                            for sid in self._known - running:
                                if self._session_states.get(sid):
                                    self._session_states.pop(sid, None)
                                    self._native_waiting.pop(sid, None)
                            self._known = {sid for sid in self._known if sid in self._session_states and sid in ids}
                            previous_waiting = dict(self._native_waiting)
                            for row in rows:
                                if isinstance(row.get('pendingInput'), list):
                                    if row['pendingInput']:
                                        self._native_waiting[row['sessionId']] = [k for k in row['pendingInput'] if k in ('question', 'approval')]
                                    else:
                                        self._native_waiting.pop(row['sessionId'], None)
                            self._native_waiting = {sid: kinds for sid, kinds in self._native_waiting.items() if sid in ids}
                            if changed or self._known != previous_known or previous_waiting != self._native_waiting:
                                self._version += 1
                            self._catalog_running, self._catalog_ids = running, ids
                            self._session_projects = projects
            except CodexError:
                pass
        return self._activity_snapshot()

    def _response_kinds(self, session_id):
        with self._lock:
            kinds = set(self._native_waiting.get(session_id, []))
            for request in self._requests.values():
                if request.get('sessionId') == session_id:
                    questions = request.get('questions') or []
                    kinds.add('question' if request.get('kind') == 'questions' and questions and not questions[0].get('intent') else 'approval')
            return sorted(kinds)

    def _activity_snapshot(self):
        with self._lock:
            waiting = {r.get('sessionId') for r in self._requests.values()} | set(self._native_waiting)
            observed = self._known | self._catalog_running | waiting
            return {'generation': self._generation or 'dsh-unavailable', 'version': self._version,
                    'complete': self._connected and bool(observed),
                    'tasks': [{'threadId': s, 'status': 'waiting' if s in waiting else 'running'}
                              | ({'responseKinds': self._response_kinds(s)} if self._response_kinds(s) else {})
                              | ({'projectId': self._session_projects[s]} if s in self._session_projects else {})
                              for s in sorted(observed) if self._connected and (s in waiting or s in self._catalog_running or self._session_states.get(s))],
                    'knownThreadIds': sorted(observed) if self._connected else [],
                    'unknownThreadIds': sorted(self._catalog_ids - observed) if self._connected else []}

    @staticmethod
    def _unsupported(*args, **kwargs):
        raise CodexError('此 DSH 验证节点暂不支持该操作', code='runtime_unsupported', http_status=409)

    project_git = project_directory = add_project = pin_session = archive_session = _unsupported
    upload_todo_attachment_stream = _unsupported
    fork_session = delete_session = preview_handoff = handoff = predict_message = update_goal = _unsupported
    answer_questions = steer = validate_queue_target = send_queued = review_context = diff = _unsupported

    def external_workspace(self, thread_id):
        return None
