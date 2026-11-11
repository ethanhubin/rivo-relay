"""DSH's one-shot approval choices; the Host key and lease remain authoritative."""
from ..runtime_state import revision_of, choice_id, interaction
from ..codex.connection import CodexError

DECISIONS = (('accept', '允许本次', 'allow-once'), ('decline', '拒绝', 'deny'), ('cancel', '取消当前请求', 'cancel'))


def project_interaction(request, generation):
    key, params = request['key'], request['params']
    revision = revision_of(generation, key, params)
    return interaction(interaction_id=key, revision=revision, kind='approval',
        title=params['title'], description=params.get('reason', ''), detail=params.get('detail', ''),
        choices=[{'id': choice_id(key, revision, value), 'label': label, 'effect': effect}
                 for value, label, effect in DECISIONS])


def native_decision(view, response):
    if isinstance(response, dict) and set(response) == {'choiceId'} and isinstance(response['choiceId'], str):
        for value, _, _ in DECISIONS:
            if choice_id(view['id'], view['revision'], value) == response['choiceId']:
                return {'decision': value}
    raise CodexError('请选择当前请求的选项', code='invalid_response', http_status=400)
