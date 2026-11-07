"""Pure execution/delivery projection adapted from QiGuang Codex.

History freshness never changes execution or queue ownership. These values are
shared by the catalog and detail views; no second execution history is stored.
"""


def execution_projection(status, queue=(), *, read_only=False, requests=(), ownership_unverified=False):
    running = status in ('running', 'waiting')
    recovery = next((item for item in queue if item.get('recoveryRequired')), None)
    waiting = [item for item in queue if item.get('status') == 'waiting']
    dispatching = any(item.get('status') == 'dispatching' for item in queue)
    failed = any(item.get('status') == 'failed' for item in queue)
    delivery = ('recovery' if recovery else 'dispatching' if dispatching else
                'queued' if waiting else 'failed' if failed else 'idle')
    display = ('waiting' if requests else 'executing_with_queue' if running and waiting else
               'executing' if running else 'recovery' if recovery else
               'dispatching' if dispatching else 'queued' if waiting else
               'failed' if failed else 'unknown' if status == 'unknown' else 'idle')
    return {
        'nativeExecution': 'running' if running else 'unknown' if status == 'unknown' else 'idle',
        'deliveryState': delivery, 'displayState': display,
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
