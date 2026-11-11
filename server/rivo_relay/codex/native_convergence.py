"""Consistency coordinator for Codex rollout and official history projection.

The rollout lifecycle is used only as a watermark. Conversation bodies remain
owned by the official ``thread/turns/list(itemsView=full)`` projection.
"""

from __future__ import annotations

import threading
import time
from weakref import WeakValueDictionary
from dataclasses import dataclass
from typing import Callable, Optional
from ..read_budget import read_lock, ReadStopped


@dataclass(frozen=True)
class NativeJournalWatermark:
    turn_id: str
    status: str

    @property
    def unfinished(self) -> bool:
        return self.status == "inProgress"


@dataclass(frozen=True)
class NativeProjectionWatermark:
    turn_id: str
    status: str

    @property
    def unfinished(self) -> bool:
        normalized = self.status.casefold().replace("_", "")
        return normalized in {"notstarted", "inprogress", "running", "active"}


@dataclass(frozen=True)
class NativeConvergenceResult:
    state: str
    generation: int
    checked_at: int
    journal_turn_id: Optional[str] = None
    projection_turn_id: Optional[str] = None
    reprojected: bool = False
    error: str = ""
    journal_check: str = "verified"

    @property
    def ready(self) -> bool:
        return self.state == "ready"

    def public(self) -> dict:
        return {
            "state": self.state,
            "generation": self.generation,
            "checked_at": self.checked_at,
            "journalCheck": self.journal_check,
        }


class NativeHistoryConverger:
    """Make one thread's disposable projection cover its rollout watermark.

    Rebuilds are permitted only while the official writer lock is idle. Each
    thread has its own lock so concurrent history, SSE, and restart recovery
    requests converge through one reprojection instead of racing projection state.
    """

    def __init__(
        self,
        *,
        journal_watermark: Callable[[str], Optional[NativeJournalWatermark]],
        projection_watermark: Callable[[str], Optional[NativeProjectionWatermark]],
        projection_contains: Callable[[str, str], bool],
        has_live_writer: Callable[[str], bool],
        reproject_projection: Callable[[str], bool],
        interruption_confirmed: Callable[[str, str], bool] = lambda _thread, _turn: False,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._journal_watermark = journal_watermark
        self._projection_watermark = projection_watermark
        self._projection_contains = projection_contains
        self._has_live_writer = has_live_writer
        self._reproject_projection = reproject_projection
        self._interruption_confirmed = interruption_confirmed
        self._clock = clock
        self._state_lock = threading.RLock()
        # Waiters retain a strong reference. Idle thread locks do not accumulate
        # forever when a node lists thousands of different conversations.
        self._thread_locks = WeakValueDictionary()
        self._generations: dict[str, int] = {}

    def ensure_consistent(
        self,
        thread_id: str,
        *,
        expected_turn_id: Optional[str] = None,
    ) -> NativeConvergenceResult:
        lock = self._lock_for_thread(thread_id)
        with read_lock(lock):
            checked_at = int(self._clock())
            try:
                journal = self._journal_watermark(thread_id)
                projection = self._projection_watermark(thread_id)
                if self._covers(
                    thread_id,
                    journal,
                    projection,
                    expected_turn_id=expected_turn_id,
                ):
                    return self._result(
                        thread_id,
                        "ready",
                        checked_at,
                        journal=journal,
                        projection=projection,
                    )
                if self._has_live_writer(thread_id):
                    return self._result(
                        thread_id,
                        "converging",
                        checked_at,
                        journal=journal,
                        projection=projection,
                    )
                reprojected = self._reproject_projection(thread_id)
                journal = self._journal_watermark(thread_id)
                projection = self._projection_watermark(thread_id)
                if not self._covers(
                    thread_id,
                    journal,
                    projection,
                    expected_turn_id=expected_turn_id,
                ):
                    return self._result(
                        thread_id,
                        "unavailable",
                        checked_at,
                        journal=journal,
                        projection=projection,
                        reprojected=reprojected,
                        error="Codex 原生历史重投影后仍落后于 rollout",
                    )
                if reprojected:
                    with self._state_lock:
                        self._generations[thread_id] = (
                            self._generations.get(thread_id, 0) + 1
                        )
                return self._result(
                    thread_id,
                    "ready",
                    checked_at,
                    journal=journal,
                    projection=projection,
                    reprojected=reprojected,
                )
            except ReadStopped:
                raise
            except Exception as error:
                return self._result(
                    thread_id,
                    "unavailable",
                    checked_at,
                    error=str(error).strip() or type(error).__name__,
                )

    def generation(self, thread_id: str) -> int:
        with self._state_lock:
            return self._generations.get(thread_id, 0)

    def _lock_for_thread(self, thread_id: str) -> threading.Lock:
        with self._state_lock:
            lock = self._thread_locks.get(thread_id)
            if lock is None:
                lock = threading.Lock()
                self._thread_locks[thread_id] = lock
            return lock

    def _covers(
        self,
        thread_id: str,
        journal: Optional[NativeJournalWatermark],
        projection: Optional[NativeProjectionWatermark],
        *,
        expected_turn_id: Optional[str],
    ) -> bool:
        if expected_turn_id and not self._projection_contains(thread_id, expected_turn_id):
            return False
        if journal is None:
            # Non-paginated or externally managed histories may not expose a
            # local rollout watermark. In that case the strict official RPC is
            # still the only available authority.
            return True
        if projection is None or projection.turn_id != journal.turn_id:
            return False
        if projection.unfinished != journal.unfinished:
            # A killed writer need not append turn_aborted. Accept the official
            # interruption only with fresh ownership/execution evidence.
            if journal.unfinished and projection.status == 'interrupted':
                return self._interruption_confirmed(thread_id, journal.turn_id)
            return False
        if not journal.unfinished:
            projected_status = projection.status.casefold()
            if journal.status == "completed":
                return projected_status == "completed"
            return projected_status in {"interrupted", "failed", "canceled", "cancelled"}
        return True

    def _result(
        self,
        thread_id: str,
        state: str,
        checked_at: int,
        *,
        journal: Optional[NativeJournalWatermark] = None,
        projection: Optional[NativeProjectionWatermark] = None,
        reprojected: bool = False,
        error: str = "",
    ) -> NativeConvergenceResult:
        with self._state_lock:
            generation = self._generations.get(thread_id, 0)
        return NativeConvergenceResult(
            state=state,
            generation=generation,
            checked_at=checked_at,
            journal_turn_id=journal.turn_id if journal else None,
            projection_turn_id=projection.turn_id if projection else None,
            reprojected=reprojected,
            error=error,
        )
