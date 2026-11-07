"""Non-authoritative streaming item projection; native snapshots remain canonical."""
import copy

TERMINAL = {'completed', 'failed', 'interrupted', 'canceled', 'cancelled', 'declined'}
TEXT_DELTAS = {
    'item/agentMessage/delta': ('agentMessage', 'text'),
    'item/commandExecution/outputDelta': ('commandExecution', 'aggregatedOutput'),
    'item/fileChange/outputDelta': ('fileChange', 'output'),
    'item/plan/delta': ('plan', 'text'),
}
REASONING_DELTAS = {
    'item/reasoning/summaryTextDelta': ('summary', 'summaryIndex'),
    'item/reasoning/textDelta': ('content', 'contentIndex'),
    'item/reasoning/summaryPartAdded': ('summary', 'summaryIndex'),
}
ITEM_EVENTS = {'item/started', 'item/completed', *TEXT_DELTAS, *REASONING_DELTAS}


def project_item(items, method, params, completed):
    """Update in place, keeping native order. Return SSE text offset if applicable."""
    if method in {'item/started', 'item/completed'}:
        incoming = params.get('item')
        if (not isinstance(incoming, dict) or not isinstance(incoming.get('id'), str) or not incoming['id']
                or not isinstance(incoming.get('type'), str) or not incoming['type']):
            return None
        item_id = incoming['id']
        if method == 'item/started' and item_id in completed:
            return None
        if method == 'item/completed':
            completed.add(item_id)
        index = next((i for i, item in enumerate(items) if item.get('id') == item_id), None)
        if index is None:
            items.append(copy.deepcopy(incoming))
        elif method == 'item/completed':
            items[index] = copy.deepcopy(incoming)
        else:
            # A delayed start must not erase already buffered delta text.
            items[index] = merge_item(incoming, items[index])
        return None
    item_id = params.get('itemId')
    delta = params.get('delta', '')
    if not isinstance(item_id, str) or not item_id or item_id in completed or not isinstance(delta, str):
        return None
    index = None
    if method in REASONING_DELTAS:
        field, index_field = REASONING_DELTAS[method]
        index = params.get(index_field)
        # Malformed or sparse huge indices must not allocate unbounded arrays.
        if type(index) is not int or not 0 <= index < 1024:
            return None
        item_type = 'reasoning'
    else:
        item_type, field = TEXT_DELTAS[method]
    item = next((item for item in items if item.get('id') == item_id), None)
    if item is None and index is not None and index != 0:
        return None
    if item is not None and (item.get('type') != item_type or item.get('status') in TERMINAL):
        return None
    if item is None:
        item = {'id': item_id, 'type': item_type, 'status': 'inProgress'}
        items.append(item)
    if index is not None:
        values = item.setdefault(field, [])
        if not isinstance(values, list) or index > len(values):
            return None
        if index == len(values):
            values.append('')
        if isinstance(values[index], str):
            offset = len(values[index])
            values[index] += delta
            if method != 'item/reasoning/summaryPartAdded':
                return offset
        return None
    previous = item.get(field) or ''
    if not isinstance(previous, str):
        return None
    item[field] = previous + delta
    return len(previous)


def merge_item(persisted, live, *, completed=False):
    """Never regress terminal snapshots or overwrite a longer canonical stream."""
    result = copy.deepcopy(persisted)
    if persisted.get('status') in TERMINAL:
        return result
    if completed:
        result.update(copy.deepcopy(live))
        return result
    for field, value in live.items():
        old = result.get(field)
        if field in {'text', 'aggregatedOutput', 'output'} and isinstance(old, str):
            if not isinstance(value, str) or not value.startswith(old):
                continue
        if field in {'summary', 'content'} and isinstance(old, list):
            if not isinstance(value, list) or len(value) < len(old) or any(
                    not isinstance(part, str) or not isinstance(value[i], str) or not value[i].startswith(part)
                    for i, part in enumerate(old)):
                continue
        if value is not None:
            result[field] = copy.deepcopy(value)
    return result
