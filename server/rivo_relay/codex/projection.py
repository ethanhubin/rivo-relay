"""Pure execution/delivery projection adapted from QiGuang Codex.

History freshness never changes execution or queue ownership. These values are
shared by the catalog and detail views; no second execution history is stored.
"""


def execution_projection(status, queue=(), *, read_only=False, requests=()):
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
               'failed' if failed else 'idle')
    return {
        'nativeExecution': 'running' if running else 'idle',
        'deliveryState': delivery, 'displayState': display,
        'queueDepth': len(waiting), 'recoveryRequired': recovery is not None,
        'hasOutstandingWork': running or bool(queue), 'recoveryTask': recovery,
        'blockReason': ('external_turn_active' if read_only and running else
                        'recovery_required' if recovery else
                        'external_writer_idle' if read_only and waiting else None),
    }
