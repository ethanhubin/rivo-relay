"""Process-local authenticated activity, including direct and relayed requests."""
import threading
import time


class Presence:
    window = 90

    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.lock = threading.Lock()
        self.clients = {}

    def begin(self, identity):
        with self.lock:
            self._prune()
            row = self.clients.setdefault(identity, [self.clock(), 0])
            row[0], row[1] = self.clock(), row[1] + 1

    def end(self, identity):
        with self.lock:
            row = self.clients.get(identity)
            if row:
                row[0], row[1] = self.clock(), max(0, row[1] - 1)

    def _prune(self):
        now = self.clock()
        self.clients = {k: v for k, v in self.clients.items() if v[1] or now - v[0] < self.window}

    def snapshot(self, authorized):
        with self.lock:
            self._prune()
            rows = [v for k, v in self.clients.items() if k in authorized]
            return {'activePairings': len(rows), 'inFlightRequests': sum(v[1] for v in rows),
                    'activityWindowSeconds': self.window}
