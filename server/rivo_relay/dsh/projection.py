"""Display projection of DSH's authoritative events; never a second history store."""
import hashlib
import json
from ..codex.connection import CodexError


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()


def text(blocks):
    if isinstance(blocks, str):
        return blocks
    parts = []
    for block in blocks or []:
        if not isinstance(block, dict):
            continue
        kind = block.get('type')
        if kind == 'text':
            parts.append(block.get('text', ''))
        elif kind not in ('reasoning', 'tool-call'):
            label = '图片' if kind in ('image', 'image-url', 'image-ref') else '附件'
            parts.append(f'\n[DSH {label}暂不支持预览]\n')
    return ''.join(parts)


def stream_text(stream):
    return ''.join(''.join(record.get('texts', [])) if record.get('type') == 'text-chunks'
                   else record.get('chunk', {}).get('text', '')
                   if record.get('type') == 'chunk' and record.get('chunk', {}).get('type') == 'text-delta'
                   else '' for record in stream or [])


def turn_id(number):
    # A reversible representation of the real DSH turn number, not an invented receipt.
    return 'dsh-turn-' + str(number)


def project(events, live=None):
    turns, by_turn, tools = [], {}, {}
    current = None
    for event in sorted(events, key=lambda e: e['seq']):
        kind, data, seq = event['type'], event.get('data', {}), event['seq']
        if kind == 'turn/start':
            number = data['turn']
            current = by_turn.get(number)
            if current is None:
                current = {'id': turn_id(number), 'status': 'inProgress', 'items': []}
                by_turn[number] = current
                turns.append(current)
        elif kind == 'turn/end':
            target = by_turn.get(data.get('turn'))
            if target:
                reason = data.get('reason', {}).get('kind')
                target['status'] = {'completed': 'completed', 'aborted': 'interrupted',
                                    'error': 'failed', 'interrupted': 'interrupted'}.get(reason, 'failed')
        elif kind in ('user/message', 'assistant/message', 'assistant/attempt', 'tool/call', 'tool/result'):
            target = by_turn.get(data.get('turn'), current)
            if target is None:
                raise CodexError('DSH history lacks its turn boundary', code='dsh_protocol')
            message = data if kind == 'user/message' else data.get('message', {})
            if kind == 'user/message':
                source = message.get('source', {})
                # Context injection is not a human chat bubble.
                if source.get('kind') not in ('user', 'user-rpc', 'sdk'):
                    continue
                item = {'id': message.get('id', f'event-{seq}'), 'type': 'userMessage',
                        'content': [{'type': 'text', 'text': text(message.get('content'))}]}
                if source.get('rpcId'):
                    item['clientId'] = source['rpcId']
                target['items'].append(item)
            elif kind == 'assistant/message':
                content = text(message.get('content'))
                if content:
                    target['items'].append({'id': message.get('id', f'event-{seq}'), 'type': 'agentMessage', 'text': content})
                reasoning = ''.join(b.get('text', '') for b in message.get('content', []) if b.get('type') == 'reasoning')
                if reasoning:
                    target['items'].append({'id': f'reasoning-{seq}', 'type': 'reasoning', 'summary': [], 'content': [reasoning]})
            elif kind == 'assistant/attempt':
                content = stream_text(data.get('stream'))
                if content:
                    target['items'].append({'id': f'attempt-{seq}', 'type': 'agentMessage',
                                            'text': content + '\n\n（此段输出来自一次未完成的模型请求。）'})
            elif kind == 'tool/call':
                item = {'id': data['callId'], 'type': 'dynamicToolCall', 'tool': data.get('name', ''),
                        'arguments': data.get('arguments', ''), 'status': 'inProgress'}
                tools[data['callId']] = item
                target['items'].append(item)
            else:
                item = tools.get(message.get('toolCallId'))
                if item:
                    item.update(status='failed' if message.get('isError') else 'completed',
                                success=not message.get('isError', False), result=text(message.get('content')))
    # Live frames are transient and disappear as soon as the durable settlement exists.
    active = None
    for frame in live or []:
        if frame.get('type') == 'start':
            active = {**frame, 'text': '', 'nextIndex': 0}
        elif frame.get('type') == 'chunk' and active and frame.get('attemptId') == active.get('attemptId'):
            if frame.get('index') != active['nextIndex']:
                active = None
                continue
            active['nextIndex'] += 1
            chunk = frame.get('chunk', {})
            if chunk.get('type') == 'text-delta':
                active['text'] += chunk.get('text', '')
        elif frame.get('type') == 'end':
            active = None
    if active and active['text']:
        target = by_turn.get(active.get('turn'))
        if target and target['status'] == 'inProgress':
            target['items'].append({'id': 'live-' + active['attemptId'], 'type': 'agentMessage', 'text': active['text']})
    return turns
