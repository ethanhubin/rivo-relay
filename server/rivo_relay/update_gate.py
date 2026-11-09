"""Short, explicit update leases. Busy/uncertain work is never discarded."""
import os
import secrets
import threading
import time


class UpdateGate:
    def __init__(self):
        self.lock = threading.RLock()
        self.requests = 0
        self.until = float('inf') if os.environ.get('RIVO_UPDATE_TRIAL') == '1' else 0.0
        self.lease = None
        self.reason = 'not_checked'

    def enter(self):
        with self.lock:
            if self.until > time.monotonic():
                return False
            self.requests += 1
            return True

    def leave(self):
        with self.lock:
            self.requests -= 1

    def _local_idle(self, app):
        self.reason = 'active_requests'
        if self.requests:
            return False
        self.reason = 'queue_check_busy'
        if not app.queue._tick_lock.acquire(blocking=False):
            return False
        database_lock = getattr(app.db, '_lock', None)
        database_locked = False
        try:
            self.reason = 'database_busy'
            if database_lock is not None:
                database_locked = database_lock.acquire(blocking=False)
                if not database_locked:
                    return False
            with app.db.read() as db:
                self.reason = 'queue_pending_or_unknown'
                if db.execute("SELECT 1 FROM queued_tasks WHERE status IN ('waiting','dispatching','running','unknown') LIMIT 1").fetchone():
                    return False
                self.reason = 'submission_pending_or_unknown'
                if db.execute("SELECT 1 FROM submissions WHERE status IN ('pending','unknown') LIMIT 1").fetchone():
                    return False
            return True
        finally:
            if database_locked:
                database_lock.release()
            app.queue._tick_lock.release()

    def acquire(self, app):
        if not self.lock.acquire(blocking=False):
            return False
        try:
            if self.until > time.monotonic():
                return self.reason == 'ready'
            if not self._local_idle(app):
                return False
            reservation = secrets.token_hex(16)
            self.lease = reservation
            deadline = self.until = time.monotonic() + 5
            self.reason = 'native_checking'
        finally:
            self.lock.release()
        # Never hold admission/queue locks during native RPC. An overrun opens
        # admission automatically and cannot later authorize a delayed shutdown.
        try:
            idle = app.codex.idle_for_update()
        except Exception:
            idle = False
        with self.lock:
            if self.lease != reservation or self.until != deadline:
                return False
            try:
                self.reason = 'native_check_timeout'
                if time.monotonic() >= deadline:
                    return False
                self.reason = 'native_busy_or_unverified'
                if not idle or not self._local_idle(app):
                    return False
                self.until = time.monotonic() + 10
                self.reason = 'ready'
                return True
            finally:
                if self.reason != 'ready':
                    self.until = 0
                    self.lease = None

    def consume(self, lease):
        with self.lock:
            if not lease or lease != self.lease or self.until <= time.monotonic() or self.requests:
                self.reason = 'lease_expired'
                return False
            self.lease = None
            self.until = float('inf')
            self.reason = 'stopping'
            return True
