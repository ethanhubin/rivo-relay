"""Short local update lease; fail closed when execution state is uncertain."""
import os
import threading
import time


class UpdateGate:
    def __init__(self):
        self.lock = threading.RLock()
        self.requests = 0
        self.until = float('inf') if os.environ.get('RIVO_UPDATE_TRIAL') == '1' else 0.0

    def enter(self):
        with self.lock:
            if self.until > time.monotonic():
                return False
            self.requests += 1
            return True

    def leave(self):
        with self.lock:
            self.requests -= 1

    def acquire(self, app):
        with self.lock:
            if self.requests:
                return False
            # Keep admission closed while checking both the queue and native state.
            with app.queue._tick_lock:
                with app.db.read() as db:
                    if db.execute("SELECT 1 FROM queued_tasks WHERE status IN ('waiting','dispatching','running','unknown') LIMIT 1").fetchone():
                        return False
                    if db.execute("SELECT 1 FROM submissions WHERE status IN ('pending','unknown') LIMIT 1").fetchone():
                        return False
                if not app.codex.idle_for_update():
                    return False
                self.until = time.monotonic() + 30
                return True
