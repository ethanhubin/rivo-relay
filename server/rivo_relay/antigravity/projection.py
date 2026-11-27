"""Project ACP updates into Rivo's existing chat history format."""

from dataclasses import dataclass, field
from datetime import datetime
import os

from ..runtime_state import revision_of


_TEXT_TYPES = {'user_message_chunk', 'agent_message_chunk', 'agent_thought_chunk'}


def valid_id(value):
    return isinstance(value, str) and 0 < len(value) <= 512 and not any(ord(c) < 32 for c in value)


def project_id(cwd):
    return 'antigravity-' + revision_of(cwd)


def display_title(title):
    return title[:512] if isinstance(title, str) and title.strip() else 'Antigravity 会话'


def activity_time(value):
    try:
        date = datetime.fromisoformat(value)
        return int(date.timestamp()) if date.tzinfo is not None else 0
    except (TypeError, ValueError, OverflowError, OSError):
        return 0


def parse_session(row):
    if not isinstance(row, dict) or not valid_id(row.get('sessionId')):
        return None
    cwd = row.get('cwd')
    if not isinstance(cwd, str) or len(cwd) > 4096 or '\x00' in cwd or not os.path.isabs(cwd):
        return None
    return {'id': row['sessionId'], 'cwd': cwd, 'projectId': project_id(cwd),
            'name': display_title(row.get('title')), 'updatedAt': activity_time(row.get('updatedAt'))}


@dataclass
class Transcript:
    turns: list = field(default_factory=list)
    tools: dict = field(default_factory=dict)
    revision: int = 0
    tail_type: str = ''
    live: bool = False
    size: int = 0

    def begin(self, *, live=False):
        turn = {'id': 'antigravity-history-' + str(len(self.turns) + 1),
                'status': 'inProgress' if live else 'history', 'items': []}
        self.turns.append(turn)
        self.revision += 1
        self.tail_type = ''
        self.live = live
        return turn

    def finish(self, status, *, prompt=None, submission_id=None, error=None):
        turn = self.turns[-1]
        if prompt is not None:
            items = turn['items']
            user = next((item for item in items if item['type'] == 'userMessage'), None)
            if user is None:
                user = {'id': turn['id'] + '-input', 'type': 'userMessage',
                        'content': [{'type': 'text', 'text': prompt}]}
                items.insert(0, user)
            user['clientId'] = submission_id
        turn['status'] = status
        if error:
            turn['error'] = {'message': error}
        self.revision += 1
        self.live = False
        return turn

    def apply(self, update):
        """Return the changed turn and item, with a text delta when possible."""
        kind = update.get('sessionUpdate')
        if kind in _TEXT_TYPES:
            return self._chunk(kind, update.get('content'))
        if kind in ('tool_call', 'tool_call_update'):
            return self._tool(update)
        return None

    def _chunk(self, kind, content):
        if not isinstance(content, dict):
            return None
        text = content.get('text')
        if content.get('type') != 'text':
            text = '[Antigravity 内容 · ' + str(content.get('type') or '未知')[:80] + ']'
        if not isinstance(text, str) or not text:
            return None
        self.size += len(text.encode())
        if self.size > 32 * 1024 * 1024:
            raise ValueError('Antigravity history exceeds the projection limit')
        if not self.turns or (not self.live and kind == 'user_message_chunk' and self.tail_type != kind):
            self.begin()
        turn = self.turns[-1]
        items = turn['items']
        same_run = self.tail_type == kind and bool(items)
        if same_run:
            item = items[-1]
        else:
            item_type = {'user_message_chunk': 'userMessage', 'agent_message_chunk': 'agentMessage',
                         'agent_thought_chunk': 'reasoning'}[kind]
            item = {'id': turn['id'] + '-item-' + str(len(items) + 1), 'type': item_type}
            if item_type == 'userMessage':
                item['content'] = [{'type': 'text', 'text': ''}]
            elif item_type == 'reasoning':
                item['content'] = ['']
            else:
                item['text'] = ''
            items.append(item)
        self.tail_type = kind
        if item['type'] == 'userMessage':
            item['content'][0]['text'] += text
        elif item['type'] == 'reasoning':
            item['content'][0] += text
        else:
            item['text'] += text
        delta = text if same_run and item['type'] == 'agentMessage' else None
        return turn, item, delta

    def _tool(self, update):
        tool_id = update.get('toolCallId')
        if not valid_id(tool_id):
            return None
        if tool_id in self.tools:
            turn, item, context = self.tools[tool_id]
        else:
            if not self.turns:
                self.begin()
            turn = self.turns[-1]
            item = {'id': 'antigravity-tool-' + revision_of(tool_id), 'type': 'dynamicToolCall'}
            turn['items'].append(item)
            context = {}
            self.tools[tool_id] = (turn, item, context)
        self.tail_type = ''
        for key in ('title', 'kind', 'status'):
            if isinstance(update.get(key), str):
                context[key] = update[key][:1024]
        if isinstance(update.get('rawInput'), dict):
            context['detail'] = tool_detail(update['rawInput']) or context.get('detail', '')
        item['tool'] = context.get('title') or 'Antigravity 操作'
        if 'status' in context:
            item['status'] = context['status']
        if context.get('detail') and context['detail'] != item['tool']:
            item['arguments'] = context['detail']
        return turn, item, None


def tool_detail(data):
    if not isinstance(data, dict):
        return ''
    for key in ('command', 'path', 'filePath', 'file_path'):
        value = data.get(key)
        if isinstance(value, str) and value:
            return value[:8192]
        if key == 'command' and isinstance(value, list) and all(isinstance(arg, str) for arg in value):
            import shlex
            return shlex.join(value)[:8192]
    return ''
