"""Display projection of DSH's authoritative events; never a second history store."""
import hashlib
import json
from ..codex.connection import CodexError
from .media import source_url


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()


def _sources(session_id, seq, media):
    sources = {}
    if not session_id:
        return sources
    for entry in media or []:
        if not isinstance(entry, dict):
            raise CodexError('DSH 图片定位无效', code='dsh_protocol')
        if entry.get('eventSeq') != seq:
            continue
        block, image = entry.get('blockIndex'), entry.get('imageIndex')
        if type(block) is not int or block < 0 or type(image) is not int or image < 0:
            raise CodexError('DSH 图片定位无效', code='dsh_protocol')
        value = {**entry, 'url': source_url(session_id, seq, block, image,
                    media_type=entry.get('mediaType'), name=entry.get('name'))}
        if any(previous['imageIndex'] == image for previous in sources.get(block, [])):
            raise CodexError('DSH 图片定位重复', code='dsh_protocol')
        sources.setdefault(block, []).append(value)
    for values in sources.values():
        values.sort(key=lambda entry: entry['imageIndex'])
    return sources


def _markdown(value, sources):
    # Offsets originate from the same native block, in Unicode code points.
    # Do not independently parse Markdown and risk choosing a different image.
    end = len(value)
    edits = []
    for entry in sources:
        if entry.get('kind') != 'markdown':
            continue
        edits.append((entry.get('start'), entry.get('end'), entry['url']))
        for reference in entry.get('referenceUses') or []:
            if (not isinstance(reference, dict) or not isinstance(reference.get('label'), str)
                    or type(reference.get('image')) is not bool):
                raise CodexError('DSH 图片引用定位无效', code='dsh_protocol')
            replacement = ('!' if reference['image'] else '') + '[' + reference['label'] + '](' + entry['url'] + ')'
            edits.append((reference.get('start'), reference.get('end'), replacement))
    if any(type(start) is not int or type(stop) is not int for start, stop, _ in edits):
        raise CodexError('DSH 图片文本定位无效', code='dsh_protocol')
    for start, stop, replacement in sorted(edits, key=lambda edit: edit[0], reverse=True):
        if type(start) is not int or type(stop) is not int or not 0 <= start < stop <= end:
            raise CodexError('DSH 图片文本定位已变化', code='dsh_protocol')
        value = value[:start] + replacement + value[stop:]
        end = start
    return value


def text(blocks, sources=None):
    if isinstance(blocks, str):
        return blocks
    parts = []
    for index, block in enumerate(blocks or []):
        if not isinstance(block, dict):
            continue
        kind = block.get('type')
        images = (sources or {}).get(index, [])
        if kind == 'text':
            parts.append(_markdown(block.get('text', ''), images))
        elif kind == 'image' and images:
            parts.extend('\n\n![DSH 图片](' + entry['url'] + ')\n\n' for entry in images)
        elif kind not in ('reasoning', 'tool-call'):
            label = '图片' if kind in ('image', 'image-url', 'image-ref') else '附件'
            attachment = block.get('attachment')
            name = block.get('name') or (attachment.get('name') if isinstance(attachment, dict) else None)
            detail = f'：{name}' if isinstance(name, str) and name else ''
            parts.append(f'\n[DSH {label}{detail}暂不支持预览]\n')
    return ''.join(parts)


def user_content(blocks, sources):
    if isinstance(blocks, str):
        return [{'type': 'text', 'text': blocks}]
    result = []
    for index, block in enumerate(blocks or []):
        if not isinstance(block, dict):
            continue
        images = sources.get(index, [])
        if block.get('type') == 'image' and images:
            result.extend({'type': 'image', 'url': entry['url']} for entry in images)
        else:
            value = text([block], {0: images})
            if value:
                result.append({'type': 'text', 'text': value})
    return result


def stream_text(stream, kind='text'):
    return ''.join(''.join(record.get('texts', [])) if record.get('type') == kind + '-chunks'
                   else record.get('chunk', {}).get('text', '')
                   if record.get('type') == 'chunk' and record.get('chunk', {}).get('type') == kind + '-delta'
                   else '' for record in stream or [])


def failure_text(value):
    """Only native user-facing code/message, never request/config diagnostics."""
    if not isinstance(value, dict):
        return ''
    code = value.get('code')
    message = value.get('message')
    code = code[:128] if isinstance(code, str) else ''
    message = message[:8192] if isinstance(message, str) else ''
    return f'[{code}] {message}'.strip() if code else message


def _diagnostic(turn, message):
    if not message:
        return
    error = turn.setdefault('error', {})
    lines = error.get('additionalDetails', '').split('\n\n') if error.get('additionalDetails') else []
    if message not in lines and len(lines) < 20:
        error['additionalDetails'] = '\n\n'.join([*lines, message])


def turn_id(number):
    # A reversible representation of the real DSH turn number, not an invented receipt.
    return 'dsh-turn-' + str(number)


def project(events, live=None, *, session_id=None, media=None):
    turns, by_turn, tools = [], {}, {}
    current = None
    media_by_event = {}
    for entry in media or []:
        if not isinstance(entry, dict) or type(entry.get('eventSeq')) is not int:
            raise CodexError('DSH 图片定位无效', code='dsh_protocol')
        media_by_event.setdefault(entry['eventSeq'], []).append(entry)
    for event in sorted(events, key=lambda e: e['seq']):
        kind, data, seq = event['type'], event.get('data', {}), event['seq']
        sources = _sources(session_id, seq, media_by_event.get(seq))
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
                message = failure_text(data.get('reason', {}).get('error'))
                if message:
                    target.setdefault('error', {})['message'] = message
        elif kind == 'llm/retry':
            target = by_turn.get(data.get('turn'), current)
            if target:
                retry, maximum = data.get('retry'), data.get('maxRetries')
                count = f'（{retry}/{maximum}）' if type(retry) is int and type(maximum) is int else ''
                detail = failure_text(data.get('failure'))
                _diagnostic(target, '模型请求重试' + count + (f'：{detail}' if detail else ''))
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
                        'content': user_content(message.get('content'), sources)}
                if source.get('rpcId'):
                    item['clientId'] = source['rpcId']
                target['items'].append(item)
            elif kind == 'assistant/message':
                content = text(message.get('content'), sources)
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
                reasoning = stream_text(data.get('stream'), kind='reasoning')
                if reasoning:
                    target['items'].append({'id': f'attempt-reasoning-{seq}', 'type': 'reasoning',
                                            'summary': [], 'content': [reasoning]})
                for record in data.get('stream') or []:
                    chunk = record.get('chunk', {}) if record.get('type') == 'chunk' else record
                    if chunk.get('type') == 'finish':
                        detail = failure_text((chunk.get('reason') or {}).get('failure'))
                        _diagnostic(target, '未完成的模型请求：' + detail if detail else '')
            elif kind == 'tool/call':
                item = {'id': data['callId'], 'type': 'dynamicToolCall', 'tool': data.get('name', ''),
                        'arguments': data.get('arguments', ''), 'status': 'inProgress'}
                tools[data['callId']] = item
                target['items'].append(item)
            else:
                item = tools.get(message.get('toolCallId'))
                if item:
                    item.update(status='failed' if message.get('isError') else 'completed',
                                success=not message.get('isError', False), result=text(message.get('content'), sources))
                if sources:
                    # Existing APP media rendering lives in message Markdown.
                    # Label this compatibility view instead of claiming a new
                    # assistant response or image-generation event occurred.
                    images = [entry for entries in sources.values() for entry in entries]
                    target['items'].append({'id': f'tool-images-{seq}', 'type': 'agentMessage',
                        'text': '工具返回图片\n\n' + '\n\n'.join('![工具返回图片](' + e['url'] + ')' for e in images)})
    # Live frames are transient and disappear as soon as the durable settlement exists.
    active = None
    for frame in live or []:
        if frame.get('type') == 'start':
            active = {**frame, 'text': '', 'reasoning': '', 'nextIndex': 0}
        elif frame.get('type') == 'chunk' and active and frame.get('attemptId') == active.get('attemptId'):
            if frame.get('index') != active['nextIndex']:
                active = None
                continue
            active['nextIndex'] += 1
            chunk = frame.get('chunk', {})
            if chunk.get('type') == 'text-delta':
                active['text'] += chunk.get('text', '')
            elif chunk.get('type') == 'reasoning-delta':
                active['reasoning'] += chunk.get('text', '')
        elif frame.get('type') == 'end':
            active = None
    if active:
        target = by_turn.get(active.get('turn'))
        if target and target['status'] == 'inProgress':
            if active['reasoning']:
                target['items'].append({'id': 'live-reasoning-' + active['attemptId'], 'type': 'reasoning',
                                        'summary': [], 'content': [active['reasoning']]})
            if active['text']:
                target['items'].append({'id': 'live-' + active['attemptId'], 'type': 'agentMessage', 'text': active['text']})
    return turns
