"""Reentrant, bounded-lifetime operation gates shared by each thread's callers."""
from contextlib import contextmanager, ExitStack
from .read_budget import read_lock
import threading


class ThreadGates:
    def __init__(self):
        self._lock = threading.Lock()
        self._entries = {}

    @contextmanager
    def hold(self, *identities):
        # Multi-thread handoffs acquire the complete previewed scope in one order.
        keys = sorted(set(identities))
        with self._lock:
            entries = [(key, self._entries.setdefault(key, [threading.RLock(), 0])) for key in keys]
            for _, entry in entries:
                entry[1] += 1
        acquired = ExitStack()
        try:
            for _, entry in entries:
                acquired.enter_context(read_lock(entry[0]))
            yield
        finally:
            acquired.close()
            with self._lock:
                for key, entry in entries:
                    entry[1] -= 1
                    if not entry[1]:
                        del self._entries[key]
