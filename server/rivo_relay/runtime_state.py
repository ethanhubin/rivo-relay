"""Versioned display/control snapshots; adapters retain native authority.

Runtime generations, control revisions and history revisions are separate
domains. This module never infers a permission from a history state.
"""
from __future__ import annotations

import copy
import hashlib
import json


def revision_of(*values):
    return hashlib.sha256(json.dumps(values, sort_keys=True, ensure_ascii=False,
                                    separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def session_state(*, session_id, generation, control_revision, execution_status,
                  execution_id, synchronization, control, interactions):
    if generation is None or not isinstance(generation, (str, int)) or str(generation) == '' or not isinstance(session_id, str) or not session_id:
        raise ValueError('Session state requires scoped native identity')
    generation = str(generation)
    sync_state = (synchronization or {}).get('state', 'unknown')
    if sync_state not in {'ready', 'converging', 'unknown', 'unavailable'}:
        sync_state = 'unknown'
    sync = {'state': sync_state, 'historyReady': sync_state == 'ready' or (
        sync_state == 'converging' and synchronization.get('historyReady') is True)}
    execution = {'status': execution_status if execution_status in {'idle', 'running', 'waiting', 'unknown'} else 'unknown'}
    if isinstance(execution_id, str) and execution_id:
        execution['id'] = execution_id
    authority = copy.deepcopy(control or {})
    authority['state'] = authority.get('state') if isinstance(authority.get('state'), str) else 'unknown'
    authority['canOperate'] = authority.get('canOperate') is True
    state = {'schemaVersion': 1, 'sessionId': session_id, 'runtimeGeneration': generation,
             'execution': execution, 'synchronization': sync, 'control': authority,
             'interactions': copy.deepcopy(interactions)}
    state['revision'] = revision_of(control_revision, state)
    return state


def interaction(*, interaction_id, revision, kind, title, description='', detail='', choices=(), payload=None):
    if not interaction_id or not revision:
        raise ValueError('Interaction identity and revision are required')
    return {'id': str(interaction_id), 'revision': str(revision), 'kind': kind, 'status': 'pending',
            'title': title, 'description': description, 'detail': detail,
            'choices': copy.deepcopy(list(choices)), 'payload': copy.deepcopy(payload or {})}


def choice_id(interaction_id, revision, value):
    return 'choice-' + revision_of(interaction_id, revision, value)


def invalidated(session_id, generation, scope):
    if scope not in {'interactions', 'control', 'history'}:
        raise ValueError('Invalid session invalidation scope')
    return {'method': 'session/invalidated', 'params': {
        'sessionId': session_id, 'runtimeGeneration': str(generation), 'scope': scope}}
