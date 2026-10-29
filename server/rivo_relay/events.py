"""Bounded ephemeral notifications; a gap always requires a native snapshot."""
from __future__ import annotations

import threading
import time
from collections import deque
from uuid import uuid4


class EventBus:
    def __init__(self, capacity: int = 512):
        if capacity < 1:
            raise ValueError("Event buffer capacity must be positive")
        self._events: deque[tuple[int, dict]] = deque(maxlen=capacity)
        self._condition = threading.Condition()
        self._sequence = 0
        self._epoch = uuid4().hex
        self.closed = False

    def publish(self, event: dict) -> None:
        with self._condition:
            self._sequence += 1
            self._events.append((self._sequence, event))
            self._condition.notify_all()

    def cursor(self, sequence: int) -> str:
        return f"{self._epoch}:{sequence}"

    def read(self, cursor: str | None, timeout: float = 15) -> tuple[str, list[dict], bool]:
        """Return (cursor, events, reset). Reset has no partial history payload."""
        with self._condition:
            try:
                epoch, number = (cursor or "").rsplit(":", 1)
                sequence = int(number)
                if epoch != self._epoch or sequence < 0 or sequence > self._sequence:
                    raise ValueError
            except ValueError:
                return self.cursor(self._sequence), [], True
            deadline = time.monotonic() + timeout
            while not self.closed and sequence == self._sequence:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self._condition.wait(remaining)
            if self._events and sequence < self._events[0][0] - 1:
                return self.cursor(self._sequence), [], True
            events = [event for number, event in self._events if number > sequence]
            return self.cursor(self._sequence), events, False

    def close(self) -> None:
        with self._condition:
            self.closed = True
            self._condition.notify_all()
