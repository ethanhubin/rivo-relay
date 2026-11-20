"""Keep DSH provider/model identities and native reasoning choices lossless."""
import base64
import binascii
import json
import time

from ..codex.connection import CodexError

PREFIX = 'dsh-model:'


def model_id(provider, model):
    if not all(isinstance(value, str) and value for value in (provider, model)):
        raise CodexError('DSH 模型身份无效', code='dsh_protocol')
    value = json.dumps([provider, model], ensure_ascii=False, separators=(',', ':')).encode()
    return PREFIX + base64.urlsafe_b64encode(value).decode().rstrip('=')


def selection(model=None, effort=None):
    result = {}
    if model is not None:
        try:
            if not isinstance(model, str) or not model.startswith(PREFIX):
                raise ValueError()
            encoded = model[len(PREFIX):]
            value = json.loads(base64.b64decode(encoded + '=' * (-len(encoded) % 4), altchars=b'-_', validate=True))
            if not isinstance(value, list) or len(value) != 2 or model_id(*value) != model:
                raise ValueError()
            result.update(provider=value[0], model=value[1])
        except (ValueError, TypeError, UnicodeError, binascii.Error, CodexError) as error:
            raise CodexError('请选择此 DSH 节点提供的模型', code='invalid_settings', http_status=400) from error
    if effort is not None:
        if not isinstance(effort, str) or not effort or len(effort) > 200:
            raise CodexError('推理强度无效', code='invalid_settings', http_status=400)
        result['reasoningEffort'] = effort
    return result


def settings(selected):
    if not isinstance(selected, dict) or not selected.get('provider') or not selected.get('model'):
        return {}
    result = {'model': model_id(selected['provider'], selected['model']),
              'modelProvider': selected['provider']}
    if selected.get('reasoningEffort') is not None:
        result['reasoningEffort'] = selected['reasoningEffort']
    return result


def catalog(native):
    if not isinstance(native, dict) or not isinstance(native.get('groups'), list):
        raise CodexError('DSH 模型目录无效', code='dsh_protocol')
    default = native.get('default') or {}
    models, seen = [], set()
    for group in native['groups']:
        if not isinstance(group, dict) or not isinstance(group.get('models'), list):
            raise CodexError('DSH 模型目录无效', code='dsh_protocol')
        for row in group['models']:
            if not isinstance(row, dict):
                raise CodexError('DSH 模型目录无效', code='dsh_protocol')
            identity = model_id(group.get('id'), row.get('id'))
            if identity in seen:
                raise CodexError('DSH 模型目录身份重复', code='dsh_protocol')
            seen.add(identity)
            reasoning = row.get('reasoning') or {}
            efforts = reasoning.get('efforts', [])
            if not isinstance(efforts, list) or any(not isinstance(e, dict) or not isinstance(e.get('id'), str) or not e['id'] for e in efforts):
                raise CodexError('DSH 推理强度目录无效', code='dsh_protocol')
            name = row.get('name') or row['id']
            provider = group.get('name') or group['id']
            selected = default.get('provider') == group['id'] and default.get('model') == row['id']
            model = {'id': identity, 'model': identity, 'displayName': f'{name} · {provider}',
                     'isDefault': selected,
                     'supportedReasoningEfforts': [{'reasoningEffort': e['id'],
                         'displayName': e.get('name') or e['id'], 'description': e.get('description') or ''} for e in efforts]}
            default_effort = default.get('reasoningEffort') if selected else reasoning.get('defaultEffort')
            if default_effort is None:
                default_effort = reasoning.get('defaultEffort')
            if default_effort is not None:
                model['defaultReasoningEffort'] = default_effort
            models.append(model)
    return {'models': models, 'modelSelectionAvailable': bool(models),
            'catalog': {'stale': bool(native.get('failures')), 'source': 'dsh', 'updatedAt': time.time()}}
