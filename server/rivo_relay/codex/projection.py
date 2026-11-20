"""Pure execution/delivery projection adapted from QiGuang Codex.

History freshness never changes execution or queue ownership. These values are
shared by the catalog and detail views; no second execution history is stored.
"""


def request_kind(request):
    method = request.get('method', '')
    if method.endswith('/requestApproval') or method in ('execCommandApproval', 'applyPatchApproval'):
        return 'approval'
    if method == 'mcpServer/elicitation/request':
        params = request.get('params') or {}
        meta = params.get('_meta') or {}
        if isinstance(meta, dict) and meta.get('codex_approval_kind') == 'mcp_tool_call':
            return 'approval'
        if params.get('mode') in ('url', 'openai/userVerification'):
            return 'approval'
        return 'question'
    if method.endswith('/requestUserInput'):
        return 'question'
    return None


def request_blocks(request):
    return request_kind(request) is not None and not (
        request.get('method', '').endswith('/requestUserInput') and
        (request.get('params') or {}).get('isBlocking') is False)


def response_kinds(requests=(), native_status=None):
    """Classify concrete requests; native flags only fill missing categories.

    MCP forms use the native approval counter even when they ask an ordinary
    question. Do not turn that one form into two differently coloured prompts.
    """
    kinds = {kind for request in requests if (kind := request_kind(request))}
    if isinstance(native_status, dict) and native_status.get('type') == 'active':
        flags = native_status.get('activeFlags') or []
        has_input = any(r.get('method', '').endswith('/requestUserInput') for r in requests)
        has_permission = any(request_kind(r) == 'approval' or
                             r.get('method') == 'mcpServer/elicitation/request' for r in requests)
        if 'waitingOnUserInput' in flags and not has_input:
            kinds.add('question')
        if 'waitingOnApproval' in flags and not has_permission:
            kinds.add('approval')
    return [kind for kind in ('question', 'approval') if kind in kinds]


def response_waiting(requests=(), native_status=None):
    if any(request_blocks(request) for request in requests):
        return True
    if not isinstance(native_status, dict) or native_status.get('type') != 'active':
        return False
    flags = set(native_status.get('activeFlags') or [])
    if any(r.get('method', '').endswith('/requestUserInput') for r in requests):
        flags.discard('waitingOnUserInput')
    return bool(flags & {'waitingOnApproval', 'waitingOnUserInput'})


def execution_projection(status, queue=(), *, read_only=False, requests=(), ownership_unverified=False, native_status=None):
    running = status in ('running', 'waiting')
    recovery = next((item for item in queue if item.get('recoveryRequired')), None)
    waiting = [item for item in queue if item.get('status') == 'waiting']
    dispatching = any(item.get('status') == 'dispatching' for item in queue)
    failed = any(item.get('status') == 'failed' for item in queue)
    delivery = ('recovery' if recovery else 'dispatching' if dispatching else
                'queued' if waiting else 'failed' if failed else 'idle')
    display = ('waiting' if any(request_blocks(r) for r in requests) or status == 'waiting' else 'executing_with_queue' if running and waiting else
               'executing' if running else 'recovery' if recovery else
               'dispatching' if dispatching else 'queued' if waiting else
               'failed' if failed else 'unknown' if status == 'unknown' else 'idle')
    return {
        'nativeExecution': 'running' if running else 'unknown' if status == 'unknown' else 'idle',
        'deliveryState': delivery, 'displayState': display,
        'responseKinds': response_kinds(requests, native_status),
        'queueDepth': len(waiting), 'recoveryRequired': recovery is not None,
        'hasOutstandingWork': running or bool(queue), 'recoveryTask': recovery,
        'blockReason': ('ownership_unverified' if ownership_unverified else
                        'external_turn_active' if read_only and running else
                        'recovery_required' if recovery else
                        'external_writer_idle' if read_only and waiting else None),
    }


def control_projection(*, read_only, released, workspace_unavailable, runtime, ownership_unverified=False):
    state = ('workspace_unavailable' if workspace_unavailable else 'released' if released
             else 'external' if read_only else 'writable')
    if runtime.get('connected') is False:
        return {'state': 'unknown', 'canOperate': False, 'action': None,
                'unavailableReason': '原生运行时未连接，等待重新确认操作权限'}
    if ownership_unverified and not workspace_unavailable and not released:
        return {'state': 'ownership_unverified', 'canOperate': False, 'action': None,
                'unavailableReason': '暂时无法核验会话写权限；历史仍可查看，请重新核验后再操作'}
    action = ('resume' if state == 'released' else 'release' if state == 'writable'
              else 'takeover' if runtime.get('handoffAvailable') or runtime.get('handoffCheckAvailable') else None)
    return {'state': state, 'canOperate': state == 'writable', 'action': action,
            'unavailableReason': runtime.get('handoffUnavailableReason') if action is None else None}
