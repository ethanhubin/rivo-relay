"""Antigravity CLI sessions carried by a Relay-owned ACP connection."""

from concurrent.futures import Future, TimeoutError
from dataclasses import dataclass, field
from copy import deepcopy
import os
from pathlib import Path
import threading
import time
from uuid import uuid4

from ..operation_gates import ThreadGates
from ..receipts import input_accepted
from ..runtime_capabilities import CapabilityError, declare, session_restrictions
from ..runtime_state import choice_id, interaction, invalidated, revision_of, session_state
from ..submission_dispatch import DeferredSubmission, creation_phase, defer_dispatch, dispatch_phase
from .projection import Transcript, activity_time, display_title, parse_session, project_id, tool_detail, valid_id
from .transport import AcpError, AcpTransport


OPERATIONS = {'session.create', 'session.send', 'interaction.respond', 'interaction.respond_while_input_pending'}
STOP_REASONS = {'end_turn', 'max_tokens', 'max_turn_requests', 'refusal', 'cancelled'}
PERMISSION_EFFECTS = {'allow_once': 'allow-once', 'reject_once': 'deny'}
PERMISSION_CONFIRMATION_SECONDS = 5


@dataclass
class Approval:
    request_id: str | int
    tool_id: str
    view: dict
    choices: dict
    confirmation: Future = field(default_factory=Future)
    selected: bool = False


@dataclass
class Session:
    info: dict
    transcript: Transcript = field(default_factory=Transcript)
    control_revision: int = 0
    loaded: bool = False
    unusable: bool = False
    external_wait: bool = False
    prompting: bool = False
    approvals: dict = field(default_factory=dict)
    external_requests: dict = field(default_factory=dict)
    request_ids: set = field(default_factory=set)

    @property
    def status(self):
        if self.external_wait:
            return 'waiting'
        if self.unusable:
            return 'unknown'
        if any(not approval.selected for approval in self.approvals.values()):
            return 'waiting'
        return 'running' if self.prompting else 'idle'


class AntigravityService:
    def __init__(self, executable: Path, workspace: Path, on_event=None, *,
                 project_dirs=(), transport_factory=AcpTransport):
        self.executable = executable
        self.workspace = workspace
        self.on_event = on_event or (lambda event: None)
        self._project_dirs = tuple(str(path) for path in project_dirs)
        self._transport_factory = transport_factory
        self._lock = threading.RLock()
        self._startup_lock = threading.Lock()
        self._gates = ThreadGates()
        self._transport = None
        self._initialized = False
        self._ready = False
        self._auth_required = False
        self._closed = False
        self._retry_at = 0
        self._sessions = {}
        self._version = 0
        self._generation = 'antigravity-unavailable'
        self._agent_version = None

    def start(self):
        with self._startup_lock:
            with self._lock:
                if self._closed or self._ready or time.monotonic() < self._retry_at:
                    return
                transport = self._transport if self._initialized else None
            try:
                if transport is None:
                    transport = self._transport_factory(
                        self.executable, Path.home(), self._update, self._permission, self._disconnect)
                    with self._lock:
                        closed = self._closed
                        if not closed:
                            self._transport = transport
                    if closed:
                        transport.close()
                        return
                    transport.start()
                    result = transport.request('initialize', {
                        'protocolVersion': 1, 'clientCapabilities': {},
                        'clientInfo': {'name': 'rivo-relay', 'title': 'Rivo Relay', 'version': '1'},
                    }, timeout=8)
                    if type(result.get('protocolVersion')) is not int or result['protocolVersion'] != 1:
                        raise AcpError('Antigravity ACP 协议版本不兼容', code='antigravity_protocol')
                    self._initialize(transport, result)
                params = {'cwd': self._project_dirs[0]} if self._project_dirs else {}
                catalog = transport.request('session/list', params, timeout=4)
                if not isinstance(catalog.get('sessions'), list):
                    raise AcpError('Antigravity 会话目录格式无效', code='antigravity_protocol')
                with self._lock:
                    if self._transport is transport:
                        self._ready = True
                        self._auth_required = False
            except (AcpError, AttributeError, OSError, ValueError) as error:
                with self._lock:
                    initialized = self._initialized
                if transport is not None and not initialized:
                    transport.close()
                with self._lock:
                    self._auth_required = getattr(error, 'code', '') == 'antigravity_auth_required'
                    self._retry_at = time.monotonic() + 3

    def _initialize(self, transport, result):
        caps = result.get('agentCapabilities') or {}
        session_caps = caps.get('sessionCapabilities') or {}
        if not isinstance(session_caps.get('list'), dict) or caps.get('loadSession') is not True:
            raise AcpError('Antigravity ACP 未声明会话目录和历史能力', code='antigravity_protocol')
        agent_version = (result.get('agentInfo') or {}).get('version')
        with self._lock:
            if self._transport is not transport:
                raise AcpError('Antigravity ACP 连接已断开')
            self._agent_version = agent_version[:128] if isinstance(agent_version, str) else None
            self._generation = transport.generation
            self._initialized = True
            self._version += 1

    def close(self):
        with self._lock:
            self._closed = True
            transport = self._transport
        if transport is not None:
            transport.close()

    def _disconnect(self, transport):
        with self._lock:
            if self._transport is not transport:
                return
            sessions, self._sessions = self._sessions, {}
            self._initialized = False
            self._ready = False
            self._transport = None
            self._version += 1
            self._retry_at = time.monotonic() + 3
            for sid, session in sessions.items():
                self._expire_permissions(session)
                self.on_event(invalidated(sid, transport.generation, 'history'))

    def _connection(self):
        self.start()
        with self._lock:
            if self._auth_required:
                raise AcpError('请先在 Antigravity CLI 登录', code='antigravity_auth_required', http_status=401)
            if not self._ready:
                raise AcpError('Antigravity CLI 当前离线')
            return self._transport

    def _require_generation(self, transport, *, sent=False):
        if not self._ready or self._transport is not transport:
            raise AcpError('Antigravity ACP 会话连接已变化', sent=sent)

    def _rpc(self, transport, method, params, *, timeout=10):
        try:
            return transport.request(method, params, timeout=timeout)
        except AcpError as error:
            self._note_auth_error(transport, error)
            raise

    def _note_auth_error(self, transport, error):
        if getattr(error, 'code', '') != 'antigravity_auth_required':
            return
        with self._lock:
            if self._transport is transport:
                self._ready = False
                self._auth_required = True
                self._retry_at = time.monotonic() + 3
                for session in self._sessions.values():
                    if session.approvals:
                        session.external_wait = True
                        self._expire_permissions(session)
                    self._changed(session, transport, 'control')

    def status(self):
        self.start()
        with self._lock:
            return {'runtime': 'antigravity', 'displayName': 'Antigravity',
                    'connected': self._ready, 'generation': self._generation,
                    'version': self._agent_version, 'authRequired': self._auth_required,
                    'capabilities': {'handoffAvailable': False}}

    def runtime_capabilities(self, *, snapshot=None, connected=None):
        with self._lock:
            unavailable = session_restrictions(snapshot)
            if snapshot and snapshot.get('status') != 'idle':
                unavailable['session.send'] = 'session_busy'
            if unavailable.get('interaction.respond'):
                unavailable['interaction.respond_while_input_pending'] = unavailable['interaction.respond']
            return declare(OPERATIONS, connected=self._ready if connected is None else connected,
                           unavailable=unavailable)

    def capabilities(self, refresh=False):
        return {'models': []}

    def goal_capabilities(self):
        return {'goalSupported': False}

    def usage(self):
        return {'available': False}

    def update_blocker(self):
        # This process only owns ACP work on this connection. A lost connection
        # leaves its last prompt outcome unverified.
        with self._lock:
            if not self._ready or any(session.unusable or session.external_wait for session in self._sessions.values()):
                return 'native_state_unknown'
            if any(session.approvals for session in self._sessions.values()):
                return 'native_waiting_user'
            if any(session.prompting for session in self._sessions.values()):
                return 'native_running'
        return None

    def _catalog(self, transport, cwd=None):
        with self._lock:
            self._require_generation(transport)
        rows = {}
        deadline = time.monotonic() + 8
        params = {} if cwd is None else {'cwd': cwd}
        seen = set()
        for _ in range(32):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise AcpError('Antigravity 会话目录读取超时', code='antigravity_timeout', http_status=504)
            result = self._rpc(transport, 'session/list', params, timeout=remaining)
            sessions = result.get('sessions')
            if not isinstance(sessions, list):
                raise AcpError('Antigravity 会话目录格式无效', code='antigravity_protocol')
            for row in sessions:
                parsed = parse_session(row)
                if parsed is None or (cwd is not None and parsed['cwd'] != cwd):
                    continue
                previous = rows.get(parsed['id'])
                if previous and previous['cwd'] != parsed['cwd']:
                    raise AcpError('Antigravity 会话身份冲突', code='antigravity_protocol')
                rows[parsed['id']] = parsed
            next_cursor = result.get('nextCursor')
            if next_cursor in (None, ''):
                break
            if not isinstance(next_cursor, str) or len(next_cursor) > 8192 or next_cursor in seen:
                raise AcpError('Antigravity 会话目录游标无效', code='antigravity_protocol')
            seen.add(next_cursor)
            params['cursor'] = next_cursor
        else:
            raise AcpError('Antigravity 会话目录超出分页上限', code='antigravity_protocol')
        with self._lock:
            self._require_generation(transport)
            for sid, session in self._sessions.items():
                if cwd is None or session.info['cwd'] == cwd:
                    if sid in rows:
                        if rows[sid]['cwd'] != session.info['cwd']:
                            raise AcpError('Antigravity 会话目录已变化', code='history_changed', http_status=409)
                        session.info.update(rows[sid])
                    rows[sid] = dict(session.info)
        return rows

    def _projects(self, transport):
        paths = self._project_dirs or dict.fromkeys(row['cwd'] for row in self._catalog(transport).values())
        return {project_id(cwd): {'id': project_id(cwd), 'path': cwd,
                                  'name': Path(cwd).name or cwd, 'cwd': cwd,
                                  'runtime': 'antigravity'} for cwd in paths}

    def list_projects(self):
        return list(self._projects(self._connection()).values())

    def _cwd(self, transport, project):
        placement = self._projects(transport).get(project)
        if not placement:
            raise AcpError('Antigravity 项目不存在', code='not_found', http_status=404)
        return placement['cwd']

    def list_sessions(self, project_id, archived=False, summary=False):
        transport = self._connection()
        cwd = self._cwd(transport, project_id)
        if archived:
            return []
        rows = self._catalog(transport, cwd)
        with self._lock:
            self._require_generation(transport)
            for sid, row in rows.items():
                session = self._sessions.get(sid)
                row['status'] = session.status if session and not session.unusable else 'unknown'
                row['responseKinds'] = ['approval'] if row['status'] == 'waiting' else []
        return sorted(rows.values(), key=lambda row: (-row['updatedAt'], row['id']))

    def create_session(self, project_id, **options):
        creation_phase(project_id, 'preparing')
        if any(value is not None for value in options.values()):
            raise CapabilityError('session.settings')
        transport = self._connection()
        cwd = self._cwd(transport, project_id)
        if not os.path.isdir(cwd):
            raise AcpError('Antigravity 项目目录不可用', code='not_found', http_status=404)
        creation_phase(project_id, 'dispatching')
        result = self._rpc(transport, 'session/new', {'cwd': cwd, 'mcpServers': []})
        row = parse_session({'sessionId': result.get('sessionId'), 'cwd': cwd})
        if row is None:
            raise AcpError('Antigravity 创建回执缺少会话标识', code='antigravity_protocol', sent=True)
        sid = row['id']
        receipt = {'threadId': sid}
        creation_phase(project_id, 'received', receipt)
        with self._lock:
            self._require_generation(transport, sent=True)
            self._sessions.setdefault(sid, Session(row, loaded=True))
            self._version += 1
        return receipt

    def _open(self, transport, sid):
        with self._gates.hold(sid):
            with self._lock:
                self._require_generation(transport)
                session = self._sessions.get(sid)
                if session is not None and session.loaded:
                    return session
                if session is not None and session.unusable:
                    raise AcpError('Antigravity 会话历史当前不可用', code='antigravity_history_unavailable')
            row = None
            for cwd in self._project_dirs or (None,):
                row = self._catalog(transport, cwd).get(sid)
                if row is not None:
                    break
            if row is None:
                raise AcpError('Antigravity 会话不存在', code='not_found', http_status=404)
            with self._lock:
                self._require_generation(transport)
                session = self._sessions[sid] = Session(row)
            try:
                self._rpc(transport, 'session/load', {'sessionId': sid, 'cwd': row['cwd'], 'mcpServers': []}, timeout=12)
            except Exception:
                # A late replay on this connection cannot become the next prompt.
                with self._lock:
                    session.unusable = True
                raise
            with self._lock:
                self._require_generation(transport)
                if session.unusable:
                    raise AcpError('Antigravity 会话历史当前不可用', code='antigravity_history_unavailable')
                if any(context.get('status') in ('pending', 'in_progress') for _, _, context in session.transcript.tools.values()):
                    session.external_wait = True
                session.loaded = True
                self._version += 1
                return session

    def get_session(self, thread_id, cursor=None, limit=8, native_revision=None):
        if not 1 <= limit <= 100:
            raise ValueError('Invalid history limit')
        transport = self._connection()
        session = self._open(transport, thread_id)
        with self._lock:
            self._require_generation(transport)
            transcript = session.transcript
            revision = revision_of(transport.generation, thread_id, transcript.revision)
            if native_revision and native_revision != revision:
                raise AcpError('Antigravity 历史已变化', code='history_changed', http_status=409)
            end = len(transcript.turns)
            if cursor is not None:
                cursor_revision, sep, offset = cursor.partition(':')
                if not sep or cursor_revision != revision or not offset.isascii() or not offset.isdecimal() or len(offset) > 12:
                    raise AcpError('Antigravity 历史页已变化', code='history_changed', http_status=409)
                end = int(offset)
                if not 0 < end <= len(transcript.turns):
                    raise ValueError('Invalid history cursor')
            begin = max(0, end - limit)
            turns = deepcopy(transcript.turns[begin:end])
            if session.prompting and turns and end == len(transcript.turns):
                turns[-1]['items'] = [item for item in turns[-1]['items'] if item['type'] != 'userMessage']
            status = session.status
            writable = not (session.external_wait or session.unusable)
            controls = {'state': 'writable' if writable else 'readOnly', 'canOperate': writable}
            sync = {'state': 'ready'} if not session.unusable else {'state': 'unavailable'}
            views = [approval.view for approval in session.approvals.values() if not approval.selected]
            views.extend(session.external_requests.values())
            state = session_state(session_id=thread_id, generation=transport.generation,
                                  control_revision=session.control_revision, execution_status=status,
                                  execution_id=None, synchronization=sync, control=controls,
                                  interactions=views)
            next_cursor = f'{revision}:{begin}' if begin else None
            return {'thread': {**session.info, 'turns': turns}, 'settings': {}, 'status': status,
                    'requests': [], 'control': controls, 'readOnly': not controls['canOperate'],
                    'nativeSync': sync, 'nativeRevision': revision,
                    'nativeHeadRevision': revision_of(revision, turns),
                    'nextCursor': next_cursor, 'backwardsCursor': next_cursor,
                    'nativeRuntime': {'runtime': 'antigravity', 'displayName': 'Antigravity'},
                    'sessionState': state}

    def history(self, thread_id, **options):
        result = self.get_session(thread_id, **options)
        return {key: result[key] for key in ('nextCursor', 'nativeRevision', 'nativeHeadRevision', 'nativeSync')} | {
            'threadId': thread_id, 'turns': result['thread']['turns'],
        }

    def send(self, thread_id, text, client_message_id=None, input_items=None, **options):
        accepted, failed = defer_dispatch(client_message_id, thread_id)
        dispatch_phase(client_message_id, thread_id, 'preparing')
        if any(options.get(key) for key in ('model', 'effort', 'service_tier', 'resume_turn_id', 'require_goal_support')) or options.get('mode') not in (None, 'default'):
            raise CapabilityError('session.settings')
        if input_items is not None:
            if not all(isinstance(item, dict) and item.get('type') == 'text' and isinstance(item.get('text'), str)
                       for item in input_items):
                raise CapabilityError('files.upload')
            text = '\n'.join(item['text'] for item in input_items)
        if not isinstance(text, str) or not text.strip():
            raise ValueError('Text required')
        transport = self._connection()
        session = self._open(transport, thread_id)
        with self._lock:
            self._require_generation(transport)
            if session.prompting or session.unusable or session.external_wait:
                raise AcpError('Antigravity 会话当前无法接收输入', code='antigravity_busy', http_status=409)
            dispatch_phase(client_message_id, thread_id, 'dispatching')
            _, future = transport.send_request('session/prompt', {
                'sessionId': thread_id, 'prompt': [{'type': 'text', 'text': text}],
            })
            session.prompting = True
            turn = session.transcript.begin(live=True)
            self._changed(session, transport, 'control')
            self._event('turn/started', thread_id, turn=deepcopy(turn))
        future.add_done_callback(lambda outcome: self._complete_prompt(
            transport, session, thread_id, client_message_id, text, outcome, accepted, failed))
        return DeferredSubmission()

    def _complete_prompt(self, transport, session, sid, submission_id, text, outcome, accepted, failed):
        error = None
        reason = None
        try:
            reason = outcome.result().get('stopReason')
            if not isinstance(reason, str) or reason not in STOP_REASONS:
                raise AcpError('Antigravity ACP 缺少可识别的轮次结果', code='antigravity_protocol', sent=True)
        except Exception as failure:
            error = failure
            self._note_auth_error(transport, error)

        with self._lock:
            if self._transport is transport and self._sessions.get(sid) is session:
                if error is None:
                    status = 'completed' if reason == 'end_turn' else 'failed' if reason == 'refusal' else 'interrupted'
                    turn = session.transcript.finish(status, prompt=text, submission_id=submission_id)
                else:
                    turn = session.transcript.finish('failed', error='Antigravity 未确认该输入的执行结果')
                    session.unusable = session.unusable or not getattr(error, 'definite', False)
                session.prompting = False
                self._expire_permissions(session)
                self._changed(session, transport, 'control')
                self._event('turn/completed', sid, turn=deepcopy(turn))

        if error is None:
            # The response is scoped to the exact JSON-RPC prompt, whereas
            # session/update may also represent a history replay.
            accepted(input_accepted(submission_id, sid))
        else:
            failed(error)

    def recover_submission(self, record):
        # ACP v1 has no durable client request-ID lookup.
        return None

    def active_tasks(self):
        with self._lock:
            return {'generation': self._generation, 'version': self._version,
                    'complete': self._ready and bool(self._sessions),
                    'tasks': [{'threadId': sid, 'projectId': session.info['projectId'],
                               'status': session.status,
                               'responseKinds': ['approval'] if session.status == 'waiting' else []}
                              for sid, session in self._sessions.items() if session.prompting or session.external_wait],
                    'knownThreadIds': sorted(self._sessions), 'unknownThreadIds': []}

    def _event(self, method, session_id, **params):
        self.on_event({'method': method, 'params': {'threadId': session_id, **params}})

    def _changed(self, session, transport, scope):
        session.control_revision += 1
        self._version += 1
        self.on_event(invalidated(session.info['id'], transport.generation, scope))

    def _update(self, transport, params):
        if not isinstance(params, dict) or not isinstance(params.get('update'), dict) or not valid_id(params.get('sessionId')):
            return
        sid, update = params['sessionId'], params['update']
        with self._lock:
            session = self._sessions.get(sid)
            if self._transport is not transport or session is None or session.unusable:
                return
            kind = update.get('sessionUpdate')
            if kind == 'session_info_update':
                if 'updatedAt' in update:
                    session.info['updatedAt'] = activity_time(update['updatedAt'])
                if 'title' in update:
                    session.info['name'] = display_title(update['title'])
                    if session.loaded:
                        self._event('thread/name/updated', sid, threadName=session.info['name'])
                return
            if not isinstance(kind, str):
                return
            if kind == 'tool_call_update':
                self._confirm_permission(session, update)
            try:
                changed = session.transcript.apply(update)
            except ValueError:
                session.unusable = True
                self._expire_permissions(session)
                self._changed(session, transport, 'control')
                return
            if changed is None or not session.loaded or not session.prompting:
                return
            turn, item, delta = changed
            if item['type'] == 'userMessage':
                return  # Keep the optimistic user input until correlated receipt.
            if delta is not None:
                self._event('item/agentMessage/delta', sid, turnId=turn['id'], itemId=item['id'],
                            delta=delta, relayOffset=len(item['text']) - len(delta))
            else:
                self._event('item/completed', sid, turnId=turn['id'], item=deepcopy(item))

    def _permission(self, transport, request_id, params):
        if not isinstance(params, dict) or not valid_id(params.get('sessionId')):
            return
        sid = params['sessionId']
        with self._lock:
            session = self._sessions.get(sid)
            if self._transport is not transport or session is None:
                return
            if any(request_id in tracked.request_ids for tracked in self._sessions.values()):
                return
            if not session.prompting:
                # Loading history does not authorize acting on another
                # controller's in-flight permission request.
                session.external_wait = True
                session.request_ids.add(request_id)
                identifier = 'antigravity-external-' + uuid4().hex
                session.external_requests[request_id] = interaction(
                    interaction_id=identifier, revision=revision_of(transport.generation, sid, request_id),
                    kind='unsupported', title='请在 Antigravity 中处理待授权操作')
                self._changed(session, transport, 'interactions')
                return
            if not session.unusable:
                approval = self._approval(session, transport.generation, request_id, params)
                if approval is not None:
                    session.approvals[approval.view['id']] = approval
                    session.request_ids.add(request_id)
                    self._changed(session, transport, 'interactions')
                    return
        transport.respond(request_id, {'outcome': 'cancelled'})

    @staticmethod
    def _approval(session, generation, request_id, params):
        tool = params.get('toolCall')
        options = params.get('options')
        if not isinstance(tool, dict) or not valid_id(tool.get('toolCallId')) or not isinstance(options, list):
            return None
        tool_id = tool['toolCallId']
        observed = session.transcript.tools.get(tool_id)
        context = observed[2] if observed else {}
        title = tool.get('title') or context.get('title')
        detail = tool_detail(tool.get('rawInput')) or context.get('detail', '')
        if not isinstance(title, str) or not title.strip():
            return None
        interaction_id = 'antigravity-permission-' + uuid4().hex
        revision = revision_of(generation, session.info['id'], request_id, tool_id, title[:512], detail)
        choices, mapping, seen = [], {}, set()
        for option in options:
            if not isinstance(option, dict) or not valid_id(option.get('optionId')):
                continue
            native, name, kind = option['optionId'], option.get('name'), option.get('kind')
            if native in seen:
                return None
            seen.add(native)
            if not isinstance(kind, str) or kind not in PERMISSION_EFFECTS or not isinstance(name, str) or not name.strip():
                continue
            identifier = choice_id(interaction_id, revision, native)
            choices.append({'id': identifier, 'label': name[:512], 'effect': PERMISSION_EFFECTS[kind]})
            mapping[identifier] = native
        if not choices:
            return None
        view = interaction(interaction_id=interaction_id, revision=revision, kind='approval',
                           title=title[:512], detail=detail, choices=choices)
        return Approval(request_id, tool_id, view, mapping)

    @staticmethod
    def _expire_permissions(session):
        for approval in session.approvals.values():
            if not approval.confirmation.done():
                approval.confirmation.set_result(False)
        session.approvals.clear()
        session.request_ids.clear()

    def _confirm_permission(self, session, update):
        if update.get('status') not in ('in_progress', 'completed', 'failed'):
            return
        for key, approval in list(session.approvals.items()):
            if approval.tool_id != update.get('toolCallId'):
                continue
            session.approvals.pop(key)
            if not approval.confirmation.done():
                approval.confirmation.set_result(approval.selected)
            self._changed(session, self._transport, 'interactions')

    def respond_interaction(self, session_id, generation, interaction_id, revision, response):
        transport = self._connection()
        with self._lock:
            self._require_generation(transport)
            session = self._sessions.get(session_id)
            approval = session.approvals.get(interaction_id) if session else None
            if (generation != transport.generation or approval is None or approval.selected or not session.prompting
                    or session.unusable or approval.view['revision'] != revision):
                raise AcpError('此请求已失效，请刷新会话', code='interaction_expired', http_status=409)
            choice = response.get('choiceId') if isinstance(response, dict) and set(response) == {'choiceId'} else None
            option = approval.choices.get(choice) if isinstance(choice, str) else None
            if option is None:
                raise AcpError('请选择当前请求的选项', code='invalid_response', http_status=400)
            # Hold this exact pending scope across the bounded frame write, not
            # across the tool's native reply or the remainder of the ACP prompt.
            try:
                transport.respond(approval.request_id, {'outcome': 'selected', 'optionId': option})
            except AcpError as error:
                if not error.definite:
                    approval.selected = True
                    self._changed(session, transport, 'interactions')
                raise
            approval.selected = True
            self._changed(session, transport, 'interactions')
        try:
            confirmed = approval.confirmation.result(timeout=PERMISSION_CONFIRMATION_SECONDS)
        except TimeoutError:
            confirmed = False
        if not confirmed:
            raise AcpError('Antigravity 未确认审批已应用', code='interaction_unconfirmed', sent=True)
        return {'threadId': session_id, 'interactionId': interaction_id,
                'runtimeGeneration': generation, 'revision': revision}
