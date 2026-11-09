"""Node-wide activity snapshot, shared across readers and fenced by events."""
from contextlib import contextmanager
import copy
import threading
import time
from ..read_budget import read_lock


class ActivityRegistry:
    def __init__(self, load, generation=lambda: None):
        self.load = load
        self.generation = generation
        self.cached_generation = None
        self.lock = threading.Lock()
        self.read_lock = threading.Lock()
        self.revision = 0
        self.mutations = 0
        self.cached = None
        self.expires = 0

    def invalidate(self):
        with self.lock:
            self.revision += 1
            self.expires = 0

    @contextmanager
    def mutation(self):
        with self.lock:
            self.mutations += 1
            self.revision += 1
            self.expires = 0
        try:
            yield
        finally:
            with self.lock:
                self.mutations -= 1
                self.revision += 1
                self.expires = 0

    def current(self):
        """Reuse only a valid snapshot; callers may fall back during reconciliation."""
        with self.lock:
            if not self.mutations and self.cached is not None and self.cached_generation == self.generation() and time.monotonic() < self.expires:
                return copy.deepcopy(self.cached)
        return None

    def snapshot(self):
        with read_lock(self.read_lock):
            with self.lock:
                if not self.mutations and self.cached is not None and self.cached_generation == self.generation() and time.monotonic() < self.expires:
                    return copy.deepcopy(self.cached)
                revision = self.revision
                generation = self.generation()
            result = self.load()
            with self.lock:
                # Never label a read spanning an event as a complete snapshot.
                result.update(revision=revision, complete=not self.mutations and revision == self.revision and generation == self.generation())
                if result['complete']:
                    self.cached = copy.deepcopy(result)
                    self.cached_generation = generation
                    self.expires = time.monotonic() + 5
            return result
