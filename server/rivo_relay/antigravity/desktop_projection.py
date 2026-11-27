"""Project the pinned Desktop App conversation and terminal-approval shapes."""

from dataclasses import dataclass
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

from ..runtime_state import choice_id, interaction, revision_of
from .desktop_transport import DesktopError
from .projection import activity_time, display_title, project_id, valid_id


RUNNING = 'CASCADE_RUN_STATUS_RUNNING'
IDLE = 'CASCADE_RUN_STATUS_IDLE'
ERROR = 'CASCADE_RUN_STATUS_ERROR'
WAITING = 'CORTEX_STEP_STATUS_WAITING'
FINISHED_STEP = 'CORTEX_STEP_STATUS_DONE'
USER_INPUT = 'CORTEX_STEP_TYPE_USER_INPUT'
RUN_COMMAND = 'CORTEX_STEP_TYPE_RUN_COMMAND'
GENERIC = 'CORTEX_STEP_TYPE_GENERIC'
ERROR_STEP = 'CORTEX_STEP_TYPE_ERROR_MESSAGE'
CLIENT_MESSAGE_ID = re.compile(r'[A-Za-z0-9_-]{1,64}')
INVALID_URI_ESCAPE = re.compile(r'%(?![A-Fa-f0-9]{2})')


def _object(value):
    return value if isinstance(value, dict) else {}


def _text(value):
    return value if isinstance(value, str) else ''


def valid_client_message_id(value):
    return isinstance(value, str) and CLIENT_MESSAGE_ID.fullmatch(value) is not None


def _workspace_path(uri):
    if (not isinstance(uri, str) or len(uri) > 8192 or INVALID_URI_ESCAPE.search(uri)
            or any(ord(char) < 32 for char in uri)):
        return None
    try:
        parsed = urlsplit(uri)
        path = Path(unquote(parsed.path, errors='strict'))
        if (parsed.scheme == 'file' and parsed.netloc.lower() in ('', 'localhost') and path.is_absolute()
                and not parsed.fragment and not parsed.query and '\x00' not in str(path)):
            return str(path.resolve())
    except (OSError, ValueError, RuntimeError):
        pass
    return None


def _workspace_paths(*records):
    paths = {}
    complete = True
    for record in records:
        if not isinstance(record, dict):
            complete = False
            continue
        for key in ('workspaceUris', 'workspaces'):
            values = record.get(key, [])
            if not isinstance(values, list):
                complete = False
                continue
            for value in values:
                uri = _object(value).get('workspaceFolderAbsoluteUri') if key == 'workspaces' else value
                path = _workspace_path(uri)
                if path is None:
                    complete = False
                else:
                    paths[path] = None
    return tuple(paths), complete


def summary_rows(data):
    summaries = data.get('trajectorySummaries')
    if not isinstance(summaries, dict):
        raise DesktopError('Antigravity App 会话目录格式不兼容', code='antigravity_desktop_protocol')
    rows = {}
    for sid, row in summaries.items():
        if not valid_id(sid) or not isinstance(row, dict):
            raise DesktopError('Antigravity App 会话目录格式不兼容', code='antigravity_desktop_protocol')
        metadata = row.get('trajectoryMetadata', {})
        roots, roots_complete = _workspace_paths(metadata, row)
        parent = _object(metadata).get('parentConversationId')
        rows[sid] = {'id': sid, 'name': display_title(row.get('summary')),
                     'updatedAt': activity_time(row.get('lastModifiedTime')),
                     'nativeModifiedAt': _text(row.get('lastModifiedTime')), 'roots': roots,
                     'rootsComplete': roots_complete,
                     'nativeStatus': _text(row.get('status')),
                     'parentId': parent if valid_id(parent) else None,
                     'stepCount': row.get('stepCount')}
    return rows


def session_row(summary, cwd, status):
    return {'id': summary['id'], 'name': summary['name'], 'path': cwd, 'cwd': cwd,
            'projectId': project_id(cwd), 'updatedAt': summary['updatedAt'],
            'status': status, 'responseKinds': ['approval'] if status == 'waiting' else []}


@dataclass(frozen=True)
class CommandApproval:
    native_id: str
    trajectory_id: str
    index: int
    command: str
    view: dict
    permission_based: bool

    def decision(self, candidate):
        for value in (True, False):
            if choice_id(self.view['id'], self.view['revision'], value) == candidate:
                return value
        raise DesktopError('请选择当前 Antigravity 操作的选项', code='invalid_response', http_status=400)


@dataclass(frozen=True)
class DesktopSnapshot:
    session_id: str
    trajectory_id: str
    steps: list
    status: str
    turns: list
    approval: CommandApproval | None
    interactions: list
    roots: tuple
    roots_complete: bool
    cascade_config: dict | None

    def input_count(self, client_message_id):
        return sum(
            step.get('type') == USER_INPUT
            and _object(step.get('userInput')).get('clientMessageId') == client_message_id
            for step in self.steps
        )

    def sees_answer(self, approval, allow):
        if approval.trajectory_id != self.trajectory_id or approval.index >= len(self.steps):
            return False
        step = self.steps[approval.index]
        if (_step_identity(step, self.trajectory_id, approval.index, permission_based=approval.permission_based)
                != approval.native_id or step.get('status') == WAITING):
            return False
        if approval.permission_based:
            return _completed_command_permission(step, approval, allow)
        command = _object(step.get('runCommand'))
        if step.get('type') != RUN_COMMAND or command.get('commandLine') != approval.command:
            return False
        if allow:
            return (command.get('userRejected') is not True
                    and step.get('status') in ('CORTEX_STEP_STATUS_RUNNING', FINISHED_STEP, 'CORTEX_STEP_STATUS_ERROR'))
        return command.get('userRejected') is True


def _step_identity(step, trajectory_id, index, *, permission_based=False):
    metadata = _object(step.get('metadata'))
    tool_call = _object(step.get('toolCall')) or _object(metadata.get('toolCall'))
    kind = 'permission' if permission_based else step.get('type')
    return revision_of(trajectory_id, index, kind, metadata.get('createdAt'), tool_call.get('id'))


def _generic_command(step):
    call = _object(_object(step.get('metadata')).get('toolCall'))
    if step.get('type') != GENERIC or call.get('name') != 'run_command':
        return ''
    return _text(_object(_object(step.get('generic')).get('args')).get('CommandLine'))


def _requested_command_permission(step, permission, session_id, trajectory_id, index):
    resource = _object(permission.get('resource'))
    if resource.get('action') != 'command':
        return ''
    metadata = _object(step.get('metadata'))
    source = _object(metadata.get('sourceTrajectoryStepInfo'))
    if (source.get('cascadeId') != session_id or source.get('trajectoryId') != trajectory_id
            or type(source.get('stepIndex')) is not int or source['stepIndex'] != index
            or not _text(metadata.get('createdAt')) or not _text(_object(metadata.get('toolCall')).get('id'))):
        return ''
    command = _generic_command(step)
    return command if resource.get('target') == command else ''


def _completed_command_permission(step, approval, allow):
    completed = step.get('completedInteractions')
    if not isinstance(completed, list):
        return False
    for value in completed:
        row = _object(value)
        request = _object(_object(row.get('request')).get('permission'))
        resource = _object(request.get('resource'))
        response = _object(row.get('response'))
        decision = _object(response.get('permission'))
        if (resource.get('action') == 'command' and resource.get('target') == approval.command
                and response.get('trajectoryId') == approval.trajectory_id
                and type(response.get('stepIndex')) is int and response['stepIndex'] == approval.index
                and decision.get('scope') == 'PERMISSION_SCOPE_ONCE'
                and decision.get('editedTarget', approval.command) == approval.command
                and decision.get('allow', False) is allow):
            return True
    return False


def _command_approval(step, session_id, trajectory_id, index, generation):
    request = _object(step.get('requestedInteraction'))
    permission_based = set(request) == {'permission'}
    if permission_based:
        proposed = _object(request.get('permission'))
        command = _requested_command_permission(step, proposed, session_id, trajectory_id, index)
    elif step.get('type') == RUN_COMMAND and set(request) == {'runCommand'}:
        proposed = _object(request.get('runCommand'))
        command = _text(proposed.get('commandLine'))
    else:
        return None
    if not command.strip() or len(command) > 8192 or '\x00' in command:
        return None
    native_id = _step_identity(step, trajectory_id, index, permission_based=permission_based)
    key = 'antigravity-desktop-command-' + revision_of(session_id, native_id)
    revision = revision_of(generation, session_id, native_id, proposed)
    cwd = _text(_object(step.get('runCommand')).get('cwd'))
    if cwd:
        description = '工作目录：' + cwd[:4096]
    else:
        description = ''
    view = interaction(
        interaction_id=key, revision=revision, kind='approval', title='Antigravity 请求运行终端命令',
        description=description, detail=command, choices=[
            {'id': choice_id(key, revision, True), 'label': '允许本次', 'effect': 'allow-once'},
            {'id': choice_id(key, revision, False), 'label': '拒绝', 'effect': 'deny'},
        ])
    return CommandApproval(native_id, trajectory_id, index, command, view, permission_based)


def _user_item(step, item_id):
    row = _object(step.get('userInput'))
    raw_items = row.get('items')
    text = _text(row.get('userResponse'))
    if not text and isinstance(raw_items, list):
        text = ''.join(_text(_object(value).get('text')) for value in raw_items)
    content = [{'type': 'text', 'text': text}] if text else []
    if row.get('images') or row.get('media'):
        content.append({'type': 'text', 'text': '[Antigravity 附件]'})
    if not content:
        return []
    item = {'id': item_id, 'type': 'userMessage', 'content': content}
    if valid_id(row.get('clientMessageId')):
        item['clientId'] = row['clientMessageId']
    return [item]


def _step_items(step, item_id):
    render = _object(step.get('renderInfo'))
    if render.get('hidden'):
        return []
    status = {WAITING: 'inProgress', 'CORTEX_STEP_STATUS_RUNNING': 'inProgress',
              FINISHED_STEP: 'completed', 'CORTEX_STEP_STATUS_ERROR': 'failed'}.get(_text(step.get('status')), 'unknown')
    if step.get('type') == USER_INPUT:
        return _user_item(step, item_id)
    planner = _object(step.get('plannerResponse'))
    if planner:
        result = []
        thinking = _text(planner.get('rawThinking')) or _text(planner.get('thinking'))
        if thinking:
            result.append({'id': item_id + '-thinking', 'type': 'reasoning', 'content': [thinking]})
        text = _text(planner.get('response'))
        if text:
            result.append({'id': item_id, 'type': 'agentMessage', 'text': text})
        return result
    command = _object(step.get('runCommand'))
    if command:
        value = _text(command.get('commandLine')) or _text(command.get('proposedCommandLine'))
        if value:
            item = {'id': item_id, 'type': 'commandExecution', 'command': value,
                    'cwd': _text(command.get('cwd')),
                    'status': 'failed' if command.get('userRejected') is True else status}
            output = _object(command.get('combinedOutput'))
            terminal_text = _text(output.get('full')) or _text(output.get('truncated'))
            if terminal_text:
                item['aggregatedOutput'] = terminal_text
            if type(command.get('exitCode')) is int:
                item['exitCode'] = command['exitCode']
            return [item]
    title = _text(render.get('title')) or _text(_object(step.get('metadata')).get('toolSummary'))
    return [{'id': item_id, 'type': 'dynamicToolCall', 'tool': title, 'status': status}] if title else []


def _cascade_config(trajectory):
    executors = trajectory.get('executorMetadatas')
    if isinstance(executors, list):
        for value in reversed(executors):
            if isinstance(value, dict) and 'cascadeConfig' in value:
                config = _object(value['cascadeConfig'])
                model = _text(_object(config.get('plannerConfig')).get('planModel'))
                return config if model.strip() else None
    return None


def _visible_failure(step):
    if step.get('type') != ERROR_STEP:
        return ''
    notice = _object(step.get('errorMessage'))
    error = _object(notice.get('error'))
    if notice.get('shouldShowUser') is not True or error.get('isBenign') is True:
        return ''
    message = _text(error.get('userErrorMessage')) or _text(error.get('shortError'))
    return message[:4000] or 'Antigravity App 执行失败'


def snapshot(data, session_id, generation):
    trajectory = _object(data.get('trajectory'))
    tid, steps = trajectory.get('trajectoryId'), trajectory.get('steps')
    if (trajectory.get('cascadeId') != session_id or not valid_id(tid) or not isinstance(steps, list)
            or not all(isinstance(step, dict) for step in steps)):
        raise DesktopError('无法核验 Antigravity App 会话记录', code='antigravity_desktop_protocol')
    native_status = _text(data.get('status'))
    status = {RUNNING: 'running', IDLE: 'idle', ERROR: 'idle'}.get(native_status, 'unknown')
    approval, interactions = None, []
    if status == 'running':
        pending = next(((idx, steps[idx]) for idx in range(len(steps) - 1, -1, -1)
                        if steps[idx].get('status') == WAITING and steps[idx].get('requestedInteraction')), None)
        if pending is not None:
            status = 'waiting'
            idx, step = pending
            approval = _command_approval(step, session_id, tid, idx, generation)
            if approval:
                interactions.append(approval.view)
            else:
                key = 'antigravity-desktop-native-' + revision_of(session_id, tid, idx)
                interactions.append(interaction(
                    interaction_id=key, revision=revision_of(generation, key, step.get('requestedInteraction')),
                    kind='unsupported', title='请在 Antigravity App 中处理此请求'))

    turns = []
    for index, step in enumerate(steps):
        item_id = 'antigravity-app-step-' + revision_of(tid, index)
        if step.get('type') == USER_INPUT or not turns:
            turns.append({'id': 'antigravity-app-history-' + revision_of(tid, index),
                          'status': 'history', 'items': []})
        turn = turns[-1]
        if failure := _visible_failure(step):
            turn['status'] = 'failed'
            turn['error'] = {'message': failure}
            turn['items'].append({'id': item_id, 'type': 'dynamicToolCall',
                                  'tool': 'Antigravity 执行错误', 'status': 'failed', 'error': failure})
        else:
            turn['items'].extend(_step_items(step, item_id))
    if turns:
        latest_turn = turns[-1]
        if status in ('running', 'waiting'):
            latest_turn['status'] = 'inProgress'
        elif native_status == ERROR:
            latest_turn['status'] = 'failed'
        elif (native_status == IDLE and latest_turn['status'] == 'history'
              and any(item['type'] == 'agentMessage' for item in latest_turn['items'])):
            latest_turn['status'] = 'completed'
    roots, roots_complete = _workspace_paths(trajectory.get('metadata', {}), trajectory)
    return DesktopSnapshot(session_id, tid, steps, status, turns, approval, interactions, roots,
                           roots_complete, _cascade_config(trajectory))
