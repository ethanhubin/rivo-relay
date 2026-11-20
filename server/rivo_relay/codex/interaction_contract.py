"""Codex interaction presentation and exact choice-to-native reply mapping."""
import copy
import json

from ..runtime_state import choice_id, interaction
from .connection import CodexError

APPROVAL_METHODS = {'item/commandExecution/requestApproval', 'item/fileChange/requestApproval',
                    'execCommandApproval', 'applyPatchApproval'}


def _text(value):
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, indent=2) if value is not None else ''


def _choice(value):
    if isinstance(value, str):
        return {'accept': ('允许本次', 'allow-once'), 'approved': ('允许本次', 'allow-once'),
                'acceptForSession': ('在此会话中允许', 'allow-session'),
                'approved_for_session': ('在此会话中允许', 'allow-session'),
                'decline': ('拒绝', 'deny'), 'cancel': ('取消当前请求', 'cancel'),
                'abort': ('取消当前请求', 'cancel')}.get(value)
    if not isinstance(value, dict) or len(value) != 1:
        return None
    if 'denied' in value:
        return ('拒绝', 'deny')
    for key, field in (('acceptWithExecpolicyAmendment', 'execpolicy_amendment'),
                       ('approved_execpolicy_amendment', 'proposed_execpolicy_amendment')):
        if key in value and isinstance(value[key], dict):
            rule = value[key].get(field)
            if isinstance(rule, list) and rule and all(isinstance(item, str) for item in rule):
                return ('允许并记住命令规则：' + ' '.join(rule), 'allow-policy')
    for key in ('applyNetworkPolicyAmendment', 'network_policy_amendment'):
        if key in value and isinstance(value[key], dict):
            rule = value[key].get('network_policy_amendment')
            if isinstance(rule, dict) and rule.get('action') in ('allow', 'deny') and isinstance(rule.get('host'), str) and rule['host']:
                return (('允许访问并记住：' if rule['action'] == 'allow' else '拒绝访问并记住：') + rule['host'],
                        'allow-policy' if rule['action'] == 'allow' else 'deny')
    return None


def _decisions(request):
    params = request['params']
    declared = params.get('availableDecisions')
    if isinstance(declared, list):
        return declared
    # Fixed defaults of the already supported Codex approval protocols. Unknown
    # methods never receive defaults, and an explicit empty list stays empty.
    if request['method'] in APPROVAL_METHODS:
        return ['decline', 'accept'] if '/' in request['method'] else [
            {'denied': {'rejection': '用户拒绝此请求'}}, 'approved']
    return []


def project_interaction(request, revision):
    method, params, key = request['method'], request.get('params') or {}, request['key']
    title, kind, choices = '需要你的回应', 'unsupported', []
    if method in APPROVAL_METHODS:
        kind = 'approval'
        title = '允许修改文件？' if method in {'item/fileChange/requestApproval', 'applyPatchApproval'} else '允许运行命令？'
        for value in _decisions(request):
            display = _choice(value)
            if display:
                choice = {'id': choice_id(key, revision, value), 'label': display[0], 'effect': display[1]}
                if choice not in choices:
                    choices.append(choice)
    elif method.endswith('requestUserInput'):
        kind = 'questions'
    elif method == 'item/permissions/requestApproval':
        kind, title = 'permissions', '请求权限'
    elif method == 'mcpServer/elicitation/request':
        kind, title = 'elicitation', params.get('serverName') or '需要你的回应'
    else:
        title = '请在原生客户端处理此请求'
    detail = params.get('detail') or params.get('command') or params.get('cwd') or params.get('grantRoot') or ''
    return interaction(interaction_id=key, revision=revision, kind=kind, title=_text(title),
        description=_text(params.get('reason') or params.get('message') or params.get('description') or ''),
        detail=_text(detail), choices=choices, payload=params)


def native_response(request, revision, response):
    if not isinstance(response, dict):
        raise CodexError('交互回答必须是对象', code='invalid_response', http_status=400)
    method, params = request['method'], request.get('params') or {}
    if method in APPROVAL_METHODS:
        if set(response) != {'choiceId'} or not isinstance(response['choiceId'], str):
            raise CodexError('请选择当前请求的选项', code='invalid_response', http_status=400)
        for value in _decisions(request):
            if _choice(value) and choice_id(request['key'], revision, value) == response['choiceId']:
                return {'decision': copy.deepcopy(value)}
    elif method.endswith('requestUserInput'):
        if set(response) == {'answers'}:
            return copy.deepcopy(response)  # Interactions validates question IDs and all answer values.
    elif method == 'item/permissions/requestApproval':
        if set(response) == {'permissions', 'scope'} and response.get('scope') in ('turn', 'session'):
            granted, requested = response.get('permissions'), params.get('permissions') or {}
            if isinstance(granted, dict) and isinstance(requested, dict) and all(
                    name in ('network', 'fileSystem') and name in requested and value == requested[name]
                    for name, value in granted.items()):
                return copy.deepcopy(response)
    elif method == 'mcpServer/elicitation/request':
        if (set(response) <= {'action', 'content', '_meta'} and response.get('action') in ('accept', 'decline', 'cancel')
                and response.get('_meta') is None):
            action, content, mode = response['action'], response.get('content'), params.get('mode')
            if action != 'accept' and content is None:
                return copy.deepcopy(response)
            if action == 'accept' and mode == 'url' and content is None:
                return copy.deepcopy(response)
            if action == 'accept' and mode in ('form', 'openai/form', 'openaiForm') and isinstance(content, dict):
                _validate_form(content, params.get('requestedSchema'))
                return copy.deepcopy(response)
    raise CodexError('此回答不属于当前交互请求', code='invalid_response', http_status=400)


def _validate_form(content, schema):
    """Check the mobile JSON form subset without changing native field values."""
    from decimal import Decimal, InvalidOperation
    import math
    import re
    from datetime import date, datetime
    from urllib.parse import urlparse

    def invalid():
        raise CodexError('表单答案无效或结构不支持', code='invalid_response', http_status=400)

    def check(value, field, depth=0):
        if depth > 6 or not isinstance(field, dict):
            invalid()
        options = field.get('enum')
        if options is None:
            variants = field.get('oneOf', field.get('anyOf'))
            if variants is not None:
                if not isinstance(variants, list) or not all(isinstance(v, dict) and isinstance(v.get('const'), str) for v in variants):
                    invalid()
                options = [variant['const'] for variant in variants]
        kind = field.get('type') or ('string' if options else None)
        if kind == 'object':
            properties, required = field.get('properties'), field.get('required', [])
            if (not isinstance(value, dict) or not isinstance(properties, dict) or not isinstance(required, list)
                    or not all(isinstance(name, str) for name in required)
                    or not set(value) <= set(properties) or not set(required) <= set(value)):
                invalid()
            for name, child in value.items():
                check(child, properties[name], depth + 1)
        elif kind == 'array':
            if (not isinstance(value, list) or len(value) > 100
                    or len(value) < field.get('minItems', 0) or len(value) > field.get('maxItems', 100)):
                invalid()
            if field.get('uniqueItems') and len({json.dumps(v, sort_keys=True) for v in value}) != len(value):
                invalid()
            for child in value:
                check(child, field.get('items'), depth + 1)
        elif kind == 'string':
            if (not isinstance(value, str) or len(value) < field.get('minLength', 0)
                    or len(value) > field.get('maxLength', 200000)
                    or options is not None and (not isinstance(options, list) or value not in options)):
                invalid()
            format = field.get('format')
            if format == 'email' and re.fullmatch(r'[^@\s]+@[^@\s]+\.[^@\s]+', value) is None:
                invalid()
            if format == 'uri' and not urlparse(value).scheme:
                invalid()
            if format == 'date':
                date.fromisoformat(value)
            if format == 'date-time' and datetime.fromisoformat(value.replace('Z', '+00:00')).tzinfo is None:
                invalid()
        elif kind in ('number', 'integer'):
            if type(value) not in (int, float) or (type(value) is float and not math.isfinite(value)):
                invalid()
            number = Decimal(str(value))
            if kind == 'integer' and number != number.to_integral_value():
                invalid()
            for name, compare in (('minimum', lambda a, b: a >= b), ('maximum', lambda a, b: a <= b),
                                  ('exclusiveMinimum', lambda a, b: a > b), ('exclusiveMaximum', lambda a, b: a < b)):
                if name in field and not compare(number, Decimal(str(field[name]))):
                    invalid()
            if 'multipleOf' in field:
                step = Decimal(str(field['multipleOf']))
                if step <= 0 or number % step:
                    invalid()
        elif kind == 'boolean':
            if type(value) is not bool:
                invalid()
        else:
            invalid()
    try:
        if not isinstance(schema, dict) or schema.get('type') != 'object':
            invalid()
        check(content, schema)
    except (TypeError, ValueError, InvalidOperation, OverflowError):
        invalid()
