"""Bounded, request-cancellable sharing of complete metadata catalog scans."""
from collections import OrderedDict
from contextlib import contextmanager
import copy
import json
import threading
import time

from .connection import CodexError
from ..observability import event
from ..read_budget import checkpoint, read_lock


class ThreadCatalog:
    def __init__(self, connection, loader, *, ttl=10, capacity=8):
        self.connection, self.loader = connection, loader
        self.ttl, self.capacity = ttl, capacity
        self._lock = threading.Lock()
        self._revision = 0
        self._cache = OrderedDict()
        self._flights = {}
        self._metadata = OrderedDict()

    def metadata_changed(self, params, rows):
        """A fresh DB index must invalidate an older complete-catalog snapshot.

        DB rows are only a subset of canonical rows, so the first observation
        detects additions/status changes, never interprets rollout-only rows as deletions.
        Later observations compare the same DB query with itself.
        """
        key = (str(self.connection.generation), json.dumps(params, sort_keys=True, separators=(',', ':')))
        observed = {row['id']: json.dumps(row.get('status'), sort_keys=True) for row in rows}
        with self._lock:
            prior = self._metadata.get(key)
            cached = self._cache.get(key)
            cached_rows = {row['id']: row for row in cached[1]} if cached is not None else {}
            changed = observed != prior if prior is not None else (
                any(tid not in cached_rows or status != json.dumps(cached_rows[tid].get('status'), sort_keys=True)
                    for tid, status in observed.items()) if cached is not None else key in self._flights)
            self._metadata[key] = observed
            self._metadata.move_to_end(key)
            while len(self._metadata) > self.capacity:
                self._metadata.popitem(last=False)
            return changed

    def invalidate(self):
        with self._lock:
            self._revision += 1
            self._cache.clear()

    def _generation(self, expected):
        if str(self.connection.generation) != expected:
            raise CodexError('目录读取时运行时已变化，请刷新', code='native_unavailable')

    @contextmanager
    def _flight(self, key):
        # Never evict an in-use lock with the cached data: otherwise a third
        # reader could start a duplicate scan while two readers still own it.
        with self._lock:
            entry = self._flights.setdefault(key, [threading.Lock(), 0])
            entry[1] += 1
        try:
            with read_lock(entry[0]):
                yield
        finally:
            with self._lock:
                entry[1] -= 1
                if not entry[1]:
                    self._flights.pop(key)

    def _cached(self, key):
        with self._lock:
            cached = self._cache.get(key)
            if cached is not None and time.monotonic() < cached[0]:
                self._cache.move_to_end(key)
                return copy.deepcopy(cached[1])
        return None

    def list(self, params):
        checkpoint()
        params = copy.deepcopy(params)
        generation = str(self.connection.generation)
        key = (generation, json.dumps(params, sort_keys=True, separators=(',', ':')))
        started, outcome, count = time.monotonic(), 'interrupted', None
        try:
            rows = self._cached(key)
            if rows is not None:
                self._generation(generation)
                checkpoint()
                outcome, count = 'hit', len(rows)
                return rows
            with self._flight(key):
                self._generation(generation)
                rows = self._cached(key)
                if rows is not None:
                    checkpoint()
                    outcome, count = 'shared', len(rows)
                    return rows
                with self._lock:
                    revision = self._revision
                rows = self.loader(params)
                self._generation(generation)
                checkpoint()
                outcome, count = 'loaded', len(rows)
                with self._lock:
                    if revision == self._revision:
                        self._cache[key] = (time.monotonic() + self.ttl, copy.deepcopy(rows))
                        self._cache.move_to_end(key)
                        while len(self._cache) > self.capacity:
                            self._cache.popitem(last=False)
                    else:
                        # Keep the existing in-flight read semantics, but a
                        # lifecycle event must force the NEXT caller to rescan.
                        outcome = 'invalidated'
                return rows
        finally:
            event('thread_catalog_read', outcome=outcome, count=count,
                  duration_ms=round((time.monotonic() - started) * 1000, 2))
