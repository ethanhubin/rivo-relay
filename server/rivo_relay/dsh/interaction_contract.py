"""Project DSH approvals and questions; the Host key and lease remain authoritative."""
from ..runtime_state import revision_of, choice_id, interaction
from ..codex.connection import CodexError

DECISIONS = (('accept', '允许本次', 'allow-once'), ('decline', '拒绝', 'deny'), ('cancel', '取消当前请求', 'cancel'))


def project_interaction(request, generation):
    key, params = request['key'], request['params']
    revision = revision_of(generation, key, params)
    if request['method'] == 'runtime/questions':
        questions = params['questions']
        question = questions[0]
        intent = question.get('intent') or {}
        if len(questions) == 1 and intent.get('kind') in ('approval', 'plan-review'):
            return interaction(interaction_id=key, revision=revision, kind='approval',
                title='DSH 计划确认' if intent['kind'] == 'plan-review' else question.get('header') or 'DSH 请求确认',
                description=question['question'], detail=question.get('detail', ''),
                choices=[{'id': choice_id(key, revision, option['label']), 'label': option['label'],
                          'effect': 'allow-once' if option['label'] == intent.get('approve') else
                                    'allow-always' if option['label'] == intent.get('always') else 'deny'}
                         for option in question.get('options', [])], payload={'dshQuestions': questions})
        return interaction(interaction_id=key, revision=revision, kind='questions', title='DSH 需要你的回答',
            payload={'questions': [{**q, 'isOther': True,
                       'question': q['question'] + ('\n\n' + q['detail'] if q.get('detail') else '')}
                      for q in questions], 'dshQuestions': questions})
    return interaction(interaction_id=key, revision=revision, kind='approval',
        title=params['title'], description=params.get('reason', ''), detail=params.get('detail', ''),
        choices=[{'id': choice_id(key, revision, value), 'label': label, 'effect': effect}
                 for value, label, effect in DECISIONS])


def native_decision(view, response):
    questions = (view.get('payload') or {}).get('dshQuestions')
    if questions:
        if view['kind'] == 'approval':
            if isinstance(response, dict) and set(response) == {'choiceId'}:
                for option in questions[0].get('options', []):
                    if choice_id(view['id'], view['revision'], option['label']) == response['choiceId']:
                        return {'answers': [{'id': questions[0]['id'], 'selected': [option['label']]}]}
        elif isinstance(response, dict) and set(response) == {'answers'} and isinstance(response['answers'], dict):
            given = response['answers']
            if set(given) == {q['id'] for q in questions}:
                result = []
                for question in questions:
                    row = given[question['id']]
                    values = row.get('answers') if isinstance(row, dict) and set(row) == {'answers'} else None
                    if (not isinstance(values, list) or len(values) != 1 or not isinstance(values[0], str)
                            or not values[0].strip() or len(values[0]) > 8192):
                        break
                    answer = values[0]
                    labels = {option['label'] for option in question.get('options', [])}
                    result.append({'id': question['id'], 'selected': [answer]} if answer in labels
                                  else {'id': question['id'], 'selected': [], 'custom': answer})
                else:
                    return {'answers': result}
        raise CodexError('请完整回答当前问题', code='invalid_response', http_status=400)
    if isinstance(response, dict) and set(response) == {'choiceId'} and isinstance(response['choiceId'], str):
        for value, _, _ in DECISIONS:
            if choice_id(view['id'], view['revision'], value) == response['choiceId']:
                return {'decision': value}
    raise CodexError('请选择当前请求的选项', code='invalid_response', http_status=400)
