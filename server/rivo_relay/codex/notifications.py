"""Lossless adjacent-delta batching, adapted from QiGuang NotificationQueue."""
from __future__ import annotations

import queue


class NotificationQueue(queue.Queue):
    """Coalesce queued text fragments without delaying delivery or crossing barriers.

    Unlike the source's preview truncation, Relay must preserve every character:
    its live text offsets are used to reconcile SSE with native snapshots.
    """
    def __init__(self, maxsize=1024, max_delta_chars=24_000):
        super().__init__(maxsize=maxsize)
        self.max_delta_chars = max_delta_chars

    @staticmethod
    def _key(event):
        if not isinstance(event, dict) or 'id' in event:
            return None
        if event.get('method') not in {'item/agentMessage/delta', 'item/commandExecution/outputDelta'}:
            return None
        params = event.get('params', {})
        if not isinstance(params, dict) or not isinstance(params.get('delta'), str):
            return None
        identity = tuple(params.get(key) for key in ('threadId', 'turnId', 'itemId'))
        if not all(isinstance(value, str) and value for value in identity):
            return None
        return (event.get('connectionId'), event['method'], *identity)

    def put_nowait(self, event):
        with self.not_full:
            key = self._key(event)
            previous = self.queue[-1] if self.queue else None
            if key is not None and key == self._key(previous):
                old_delta, delta = previous['params']['delta'], event['params']['delta']
                if len(old_delta) + len(delta) <= self.max_delta_chars:
                    self.queue[-1] = {**previous, 'params': {**previous['params'], 'delta': old_delta + delta}}
                    return
            if self.maxsize > 0 and self._qsize() >= self.maxsize:
                raise queue.Full
            self._put(event)
            self.unfinished_tasks += 1
            self.not_empty.notify()

    def put_disconnect(self, event):
        """A saturated stream must still invalidate its consumer's live state."""
        with self.not_full:
            if self.maxsize > 0 and self._qsize() >= self.maxsize:
                self._get()
                self.unfinished_tasks -= 1
            self._put(event)
            self.unfinished_tasks += 1
            self.not_empty.notify()
