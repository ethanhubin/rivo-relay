"""Observe existing Antigravity App sessions and their native terminal choices."""

from collections import OrderedDict
from copy import deepcopy
from pathlib import Path
import threading
import time

from ..operation_gates import ThreadGates
from ..receipts import input_accepted
from ..runtime_capabilities import CapabilityError, declare, session_restrictions
from ..runtime_state import invalidated, revision_of, session_state
from ..submission_dispatch import dispatch_phase
from .desktop_projection import ERROR, IDLE, RUNNING, session_row, snapshot, summary_rows, valid_client_message_id
from .desktop_transport import DesktopError, DesktopPeer
from .projection import project_id, valid_id


POLL_SECONDS = 1.2
MAX_WATCHES = 12
APPROVAL_CONFIRM_SECONDS = 4
INPUT_CONFIRM_SECONDS = 3
OPERATIONS = {'session.send', 'interaction.respond', 'interaction.respond_while_input_pending'}


class AntigravityDesktopService:
    def __init__(self, profile_dir=None, workspace=None, on_event=None, *, project_dirs=(),
                 app_path=Path('/Applications/Antigravity.app'), connect=DesktopPeer.connect):
        self._profile = Path(profile_dir or (Path.home() / '.gemini' / 'antigravity'))
        self._app_path = app_path
        paths = dict.fromkeys(str(Path(path).resolve()) for path in project_dirs)
        self._projects = {project_id(cwd): {'id': project_id(cwd), 'name': Path(cwd).name or cwd,
                                              'path': cwd, 'cwd': cwd, 'runtime': 'antigravity'} for cwd in paths}
        self._allowed = frozenset(paths)
        self._connect = connect
        self.on_event = on_event or (lambda event: None)
        self._lock = threading.RLock()
        self._connect_lock = threading.Lock()
        self._gates = ThreadGates()
        self._worker = None
        self._stop = threading.Event()
        self._peer = None
        self._retry_at = 0
        self._reason = None
        self._version = 0
        self._watched = OrderedDict()
        self._fences = set()
        self._input_fences = {}

    def start(self):
        with self._lock:
            if self._worker is None and not self._stop.is_set():
                self._worker = threading.Thread(target=self._poll, name='antigravity-desktop', daemon=True)
                self._worker.start()

    def close(self):
        self._stop.set()
        with self._lock:
            worker, self._peer = self._worker, None
        if worker is not None and worker is not threading.current_thread():
            worker.join(timeout=2)

    def _connection(self):
        self.start()
        with self._connect_lock:
            with self._lock:
                if self._stop.is_set():
                    raise DesktopError('Antigravity App 节点已关闭')
                if self._peer is not None:
                    if self._peer.alive():
                        return self._peer
                    self._lost(self._peer, 'antigravity_desktop_unavailable')
                if time.monotonic() < self._retry_at:
                    raise DesktopError('Antigravity App 本机服务不可用',
                                       code=self._reason or 'antigravity_desktop_unavailable')
            try:
                peer = self._connect(self._profile, self._app_path)
                summary_rows(peer.summaries())
            except DesktopError as error:
                with self._lock:
                    self._reason = error.code
                    self._retry_at = time.monotonic() + 3
                raise
            with self._lock:
                if self._stop.is_set():
                    raise DesktopError('Antigravity App 节点已关闭')
                self._peer, self._reason = peer, None
                self._fences = {fence for fence in self._fences if fence[0] == peer.generation}
                self._version += 1
                return peer

    def _lost(self, peer, code):
        with self._lock:
            if self._peer is not peer:
                return
            self._peer = None
            self._reason = code
            self._retry_at = time.monotonic() + 1
            self._version += 1
            for sid in self._watched:
                self.on_event(invalidated(sid, peer.generation, 'history'))

    def _ensure_current(self, peer, *, sent=False):
        if self._peer is not peer:
            raise DesktopError('Antigravity App 会话连接已变化', sent=sent)

    def status(self):
        try:
            peer = self._connection()
        except DesktopError:
            peer = None
        with self._lock:
            return {'runtime': 'antigravity', 'displayName': 'Antigravity', 'mode': 'desktop',
                    'connected': peer is not None, 'version': peer.version if peer else None,
                    'generation': peer.generation if peer else 'antigravity-desktop-unavailable',
                    'reasonCode': self._reason, 'capabilities': {'handoffAvailable': False}}

    def runtime_capabilities(self, *, snapshot=None, connected=None):
        if connected is None and snapshot is None:
            connected = self.status()['connected']
        elif connected is None:
            with self._lock:
                generation = snapshot.get('sessionState', {}).get('runtimeGeneration')
                connected = self._peer is not None and self._peer.generation == generation
        unavailable = session_restrictions(snapshot)
        if snapshot and snapshot.get('status') != 'idle':
            unavailable['session.send'] = 'session_busy'
        return declare(OPERATIONS, connected=connected, unavailable=unavailable)

    def capabilities(self, refresh=False):
        return {'models': []}

    def goal_capabilities(self):
        return {'goalSupported': False}

    def usage(self):
        return {'available': False}

    def _catalog(self, peer):
        try:
            result = summary_rows(peer.summaries())
        except DesktopError as error:
            self._lost(peer, error.code)
            raise
        with self._lock:
            self._ensure_current(peer)
        return result

    def _project_cwd(self, summary):
        if summary['parentId'] or not summary['rootsComplete']:
            return None
        # A multiple-root App project can reach every listed folder.
        roots = summary['roots']
        if roots and set(roots) <= self._allowed:
            return roots[0]
        return None

    def list_projects(self):
        self._connection()
        return list(self._projects.values())

    @staticmethod
    def _status(summary):
        return {RUNNING: 'running', IDLE: 'idle', ERROR: 'idle'}.get(summary['nativeStatus'], 'unknown')

    def list_sessions(self, project_id, archived=False, summary=False):
        project = self._projects.get(project_id)
        if project is None:
            raise DesktopError('Antigravity App 项目不存在', code='not_found', http_status=404)
        if archived:
            return []
        peer = self._connection()
        rows = self._catalog(peer)
        with self._lock:
            self._ensure_current(peer)
            return sorted((session_row(row, project['cwd'], self._observed_status(sid, row, peer))
                           for sid, row in rows.items() if self._project_cwd(row) == project['cwd']),
                          key=lambda row: (-row['updatedAt'], row['id']))

    def _observed_status(self, sid, row, peer):
        state = self._status(row)
        observed = self._watched.get(sid)
        if (state == 'running' and observed is not None and observed['generation'] == peer.generation
                and observed['status'] == 'waiting'):
            return 'waiting'
        return state

    def _snapshot(self, peer, row, cwd):
        sid = row['id']
        try:
            detail = snapshot(peer.trajectory(sid), sid, peer.generation)
        except DesktopError as error:
            if error.code == 'antigravity_desktop_unavailable':
                self._lost(peer, error.code)
            raise
        if not detail.roots_complete or cwd not in detail.roots or not set(detail.roots) <= self._allowed:
            raise DesktopError('Antigravity App 会话已离开配置范围', code='not_found', http_status=404)
        with self._lock:
            self._ensure_current(peer)
        return detail

    def _scoped(self, peer, sid):
        row = self._catalog(peer).get(sid)
        cwd = self._project_cwd(row) if row is not None else None
        if cwd is None:
            raise DesktopError('Antigravity App 会话不在配置范围内', code='not_found', http_status=404)
        return row, cwd

    def _observe(self, peer, row, detail, *, watch=False):
        sid = row['id']
        control = revision_of(peer.generation, detail.status, bool(detail.cascade_config), detail.interactions)
        timeline = revision_of(detail.trajectory_id, len(detail.steps), detail.turns[-2:])
        with self._lock:
            self._ensure_current(peer)
            pending_input = self._input_fences.get(sid)
            if pending_input and detail.input_count(pending_input) == 1:
                self._input_fences.pop(sid)
            previous = self._watched.get(sid)
            if previous is None and not watch:
                return
            self._watched[sid] = {'generation': peer.generation, 'control': control, 'timeline': timeline,
                                  'native': self._native_summary_signature(row), 'status': detail.status}
            if watch:
                self._watched.move_to_end(sid)
                if len(self._watched) > MAX_WATCHES:
                    self._watched.popitem(last=False)
            if previous is None:
                return
            if control != previous['control']:
                self._version += 1
                self.on_event(invalidated(sid, peer.generation, 'control'))
            elif timeline != previous['timeline']:
                self.on_event({'method': 'antigravity/historyUpdated', 'params': {'threadId': sid}})

    @staticmethod
    def _native_summary_signature(row):
        return (row['nativeStatus'], row['stepCount'], row['nativeModifiedAt'])

    def _poll(self):
        while not self._stop.wait(POLL_SECONDS):
            with self._lock:
                watched = tuple(self._watched)
            if not watched:
                continue
            try:
                peer = self._connection()
                rows = self._catalog(peer)
            except DesktopError:
                continue
            for sid in watched:
                if self._stop.is_set():
                    break
                row = rows.get(sid)
                cwd = self._project_cwd(row) if row is not None else None
                with self._lock:
                    previous = self._watched.get(sid)
                    if previous is None:
                        continue
                    if cwd is None:
                        self._watched.pop(sid)
                        self._version += 1
                        self.on_event(invalidated(sid, peer.generation, 'control'))
                        continue
                    if (row['nativeStatus'] != RUNNING and previous['generation'] == peer.generation
                            and self._native_summary_signature(row) == previous['native']):
                        continue
                try:
                    with self._gates.hold(sid):
                        self._observe(peer, row, self._snapshot(peer, row, cwd))
                except DesktopError:
                    continue

    def get_session(self, thread_id, cursor=None, limit=8, native_revision=None):
        if not 1 <= limit <= 100:
            raise ValueError('Invalid history limit')
        peer = self._connection()
        with self._gates.hold(thread_id):
            row, cwd = self._scoped(peer, thread_id)
            detail = self._snapshot(peer, row, cwd)
            self._observe(peer, row, detail, watch=True)
            return self._display(peer, row, cwd, detail, cursor, limit, native_revision)

    def _display(self, peer, row, cwd, detail, cursor, limit, native_revision):
        stable = detail.turns[:-1] if detail.status in ('running', 'waiting') else detail.turns
        latest = detail.turns[-1]['id'] if detail.turns else None
        revision = revision_of(peer.generation, detail.session_id, detail.trajectory_id, stable, latest)
        if native_revision and native_revision != revision:
            raise DesktopError('Antigravity App 历史已变化', code='history_changed', http_status=409)
        end = len(detail.turns)
        if cursor is not None:
            cursor_revision, sep, offset = cursor.partition(':')
            if not sep or revision != cursor_revision or not offset.isascii() or not offset.isdecimal() or len(offset) > 12:
                raise DesktopError('Antigravity App 历史页已变化', code='history_changed', http_status=409)
            end = int(offset)
            if not 0 < end <= len(detail.turns):
                raise ValueError('Invalid history cursor')
        begin = max(0, end - limit)
        turns = detail.turns[begin:end]
        next_cursor = f'{revision}:{begin}' if begin else None
        sync = {'state': 'ready'}
        views = deepcopy(detail.interactions)
        with self._lock:
            self._ensure_current(peer)
            if detail.approval and (peer.generation, detail.session_id, detail.approval.native_id) in self._fences:
                views = [value for value in views if value['id'] != detail.approval.view['id']]
            can_send = detail.status == 'idle' and bool(detail.cascade_config) and detail.session_id not in self._input_fences
        writable = can_send or (bool(views) and views[0]['kind'] == 'approval')
        controls = {'state': 'writable' if writable else 'readOnly', 'canOperate': writable}
        state = session_state(session_id=detail.session_id, generation=peer.generation,
                              control_revision=revision_of(detail.trajectory_id, detail.status, views),
                              execution_status=detail.status, execution_id=None, synchronization=sync,
                              control=controls, interactions=views)
        return {'thread': {**session_row(row, cwd, detail.status), 'turns': turns}, 'status': detail.status,
                'settings': {}, 'requests': [], 'control': controls, 'readOnly': not writable,
                'nativeSync': sync, 'nativeRevision': revision, 'nativeHeadRevision': revision_of(revision, turns),
                'nextCursor': next_cursor, 'backwardsCursor': next_cursor,
                'nativeRuntime': {'runtime': 'antigravity', 'displayName': 'Antigravity', 'mode': 'desktop'},
                'sessionState': state}

    def history(self, thread_id, **options):
        result = self.get_session(thread_id, **options)
        return {key: result[key] for key in ('nextCursor', 'nativeRevision', 'nativeHeadRevision', 'nativeSync')} | {
            'threadId': thread_id, 'turns': result['thread']['turns'],
        }

    def create_session(self, project_id, **options):
        raise CapabilityError('session.create')

    def send(self, session_id, text, client_message_id=None, input_items=None, **options):
        dispatch_phase(client_message_id, session_id, 'preparing')
        if (any(options.get(key) for key in ('model', 'effort', 'service_tier', 'resume_turn_id', 'require_goal_support'))
                or options.get('mode') not in (None, 'default')):
            raise CapabilityError('session.settings')
        if input_items is not None:
            if not all(isinstance(item, dict) and item.get('type') == 'text' and isinstance(item.get('text'), str)
                       for item in input_items):
                raise CapabilityError('files.upload')
            text = '\n'.join(item['text'] for item in input_items)
        if not valid_client_message_id(client_message_id) or not isinstance(text, str) or not text.strip():
            raise DesktopError('Antigravity App 需要文本及有效消息标识', code='invalid_request', http_status=400)
        peer = self._connection()
        with self._gates.hold(session_id):
            row, cwd = self._scoped(peer, session_id)
            detail = self._snapshot(peer, row, cwd)
            self._observe(peer, row, detail, watch=True)
            if detail.status != 'idle':
                raise DesktopError('Antigravity App 会话仍在执行', code='antigravity_busy', http_status=409)
            if not detail.cascade_config:
                raise DesktopError('Antigravity App 会话缺少原生运行配置', code='antigravity_desktop_config_missing', http_status=409)
            if detail.input_count(client_message_id):
                raise DesktopError('该 Antigravity 消息标识已存在', code='submission_conflict', http_status=409)
            with self._lock:
                self._ensure_current(peer)
                if session_id in self._input_fences:
                    raise DesktopError('先前的 Antigravity App 输入仍在核对', code='antigravity_desktop_input_pending', http_status=409)
                dispatch_phase(client_message_id, session_id, 'dispatching')
                self._input_fences[session_id] = client_message_id
            try:
                peer.send_text(session_id, text, client_message_id, detail.cascade_config)
            except DesktopError as error:
                if error.definite:
                    with self._lock:
                        self._input_fences.pop(session_id, None)
                    raise
                # The response can fail after the native input is persisted.
                # Read back the exact client ID without writing again.
            receipt = self._confirm_input(peer, row, cwd, client_message_id)
            dispatch_phase(client_message_id, session_id, 'received', receipt)
            return receipt

    def _confirm_input(self, peer, row, cwd, client_message_id):
        deadline = time.monotonic() + INPUT_CONFIRM_SECONDS
        while time.monotonic() < deadline and not self._stop.is_set():
            try:
                detail = self._snapshot(peer, row, cwd)
                self._observe(peer, row, detail)
                if detail.input_count(client_message_id) == 1:
                    return input_accepted(client_message_id, row['id'])
            except DesktopError:
                break
            self._stop.wait(.15)
        raise DesktopError('Antigravity App 未确认此输入已接收', code='antigravity_desktop_input_unconfirmed', sent=True)

    def active_tasks(self):
        try:
            peer = self._connection()
            rows = self._catalog(peer)
        except DesktopError:
            with self._lock:
                return {'generation': 'antigravity-desktop-unavailable', 'version': self._version,
                        'complete': False, 'tasks': [], 'knownThreadIds': [], 'unknownThreadIds': []}
        tasks, known, unknown = [], [], []
        with self._lock:
            self._ensure_current(peer)
            for sid, row in rows.items():
                cwd = self._project_cwd(row)
                if not cwd:
                    continue
                status = self._observed_status(sid, row, peer)
                (unknown if status == 'unknown' else known).append(sid)
                if status in ('running', 'waiting'):
                    tasks.append({'threadId': sid, 'projectId': project_id(cwd), 'status': status,
                                  'responseKinds': ['approval'] if status == 'waiting' else []})
            return {'generation': peer.generation, 'version': self._version,
                    'complete': not unknown, 'tasks': tasks, 'knownThreadIds': known, 'unknownThreadIds': unknown}

    def update_blocker(self):
        try:
            rows = self._catalog(self._connection())
        except DesktopError:
            return 'native_state_unknown'
        children = {}
        in_scope = set()
        for sid, row in rows.items():
            if self._allowed.intersection(row['roots']):
                in_scope.add(sid)
            if row['parentId']:
                children.setdefault(row['parentId'], []).append(sid)
        todo = list(in_scope)
        while todo:
            for sid in children.get(todo.pop(), ()):
                if sid not in in_scope:
                    in_scope.add(sid)
                    todo.append(sid)
        statuses = {rows[sid]['nativeStatus'] for sid in in_scope}
        if RUNNING in statuses:
            return 'native_running'
        return None if statuses <= {IDLE, ERROR} else 'native_state_unknown'

    def respond_interaction(self, session_id, generation, interaction_id, revision, response):
        peer = self._connection()
        with self._gates.hold(session_id):
            row, cwd = self._scoped(peer, session_id)
            detail = self._snapshot(peer, row, cwd)
            approval = detail.approval
            if (peer.generation != generation or approval is None or approval.view['id'] != interaction_id
                    or approval.view['revision'] != revision):
                raise DesktopError('此 Antigravity App 请求已失效', code='interaction_expired', http_status=409)
            choice = response.get('choiceId') if isinstance(response, dict) and set(response) == {'choiceId'} else None
            allow = approval.decision(choice)
            fence = (generation, session_id, approval.native_id)
            with self._lock:
                self._ensure_current(peer)
                if fence in self._fences:
                    raise DesktopError('此 Antigravity App 请求已提交，正在核对', code='interaction_expired', http_status=409)
                self._fences.add(fence)
            try:
                if approval.permission_based:
                    peer.answer_command_permission(session_id, approval.trajectory_id, approval.index, allow)
                else:
                    peer.answer_command(session_id, approval.trajectory_id, approval.index, approval.command, allow)
            except DesktopError as error:
                if error.definite:
                    with self._lock:
                        self._fences.discard(fence)
                raise
            finally:
                self.on_event(invalidated(session_id, generation, 'interactions'))
        return self._confirm_response(peer, row, cwd, approval, allow)

    def _confirm_response(self, peer, row, cwd, approval, allow):
        deadline = time.monotonic() + APPROVAL_CONFIRM_SECONDS
        while time.monotonic() < deadline and not self._stop.is_set():
            try:
                with self._gates.hold(row['id']):
                    detail = self._snapshot(peer, row, cwd)
                    self._observe(peer, row, detail)
                if detail.sees_answer(approval, allow):
                    with self._lock:
                        self._fences.discard((peer.generation, row['id'], approval.native_id))
                    return {'threadId': row['id'], 'interactionId': approval.view['id'],
                            'runtimeGeneration': peer.generation, 'revision': approval.view['revision']}
            except DesktopError:
                break
            self._stop.wait(.15)
        raise DesktopError('Antigravity App 未确认此审批已应用', code='interaction_unconfirmed', sent=True)

    def recover_submission(self, record):
        sid, client_id = record.get('target'), record.get('id')
        if (record.get('operation') != 'thread.turns' or record.get('dispatch_phase') != 'dispatching'
                or not valid_id(sid) or not valid_client_message_id(client_id)):
            return None
        peer = self._connection()
        with self._gates.hold(sid):
            row, cwd = self._scoped(peer, sid)
            detail = self._snapshot(peer, row, cwd)
            self._observe(peer, row, detail)
            return input_accepted(client_id, sid) if detail.input_count(client_id) == 1 else None
