"""Local pairing issuance, atomic exchange, and revocable connection tokens."""

from __future__ import annotations

from collections import deque
import hashlib
import math
import secrets
import threading
import time
from typing import Callable
import uuid

from .database import Database


class PairingError(ValueError):
    code = "invalid_pairing_code"
    status = 401


class PairingRateLimited(PairingError):
    code = "pairing_rate_limited"
    status = 429

    def __init__(self, retry_after: int):
        super().__init__("Too many pairing attempts; try again later")
        self.retry_after = retry_after


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _code(value: str) -> str:
    if not isinstance(value, str) or len(value) > 128:
        return ""
    # Grouping is display-only, so QR and manual entry use the same credential.
    return value.replace("-", "").replace(" ", "").strip().upper()


def _public(row) -> dict:
    return {
        "connection_id": row["id"], "name": row["name"],
        "created_at": row["created_at"], "revoked_at": row["revoked_at"],
    }


class PairingStore:
    """Issue only from the trusted local CLI, never from an anonymous route.

    The service should keep one store instance. Attempt limits count all
    exchanges per remote address plus a global cap, with bounded in-memory
    windows. SQLite independently enforces one successful exchange per code.
    """

    def __init__(self, db: Database, *, clock: Callable[[], float] = time.time,
                 attempt_limit: int = 10, global_attempt_limit: int = 60,
                 window_seconds: float = 60):
        if attempt_limit < 1 or global_attempt_limit < 1 or window_seconds <= 0:
            raise ValueError("Pairing rate limits must be positive")
        self.db = db
        self.clock = clock
        self.attempt_limit = attempt_limit
        self.global_attempt_limit = global_attempt_limit
        self.window_seconds = window_seconds
        self._attempts: dict[str, deque] = {}
        self._global: deque = deque()
        self._lock = threading.Lock()

    def create(self, ttl_seconds: float = 300) -> dict:
        if isinstance(ttl_seconds, bool) or not 0 < ttl_seconds <= 300:
            raise ValueError("Pairing lifetime must be between 0 and 300 seconds")
        now = self.clock()
        # More than 79 bits, readable with grouping; no persistent plaintext credential.
        alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        raw = "".join(secrets.choice(alphabet) for _ in range(16))
        pairing_id = uuid.uuid4().hex
        with self.db.transaction() as connection:
            connection.execute("DELETE FROM pairing_codes WHERE expires_at <= ?", (now,))
            connection.execute(
                "INSERT INTO pairing_codes(id,code_hash,created_at,expires_at) VALUES (?,?,?,?)",
                (pairing_id, _digest(raw), now, now + ttl_seconds),
            )
        return {"pairing_id": pairing_id, "code": "-".join(raw[i:i+4] for i in range(0, 16, 4)),
                "expires_at": now + ttl_seconds}

    def _limit(self, client_key: str, now: float) -> None:
        key = _digest(str(client_key)[:256])
        threshold = now - self.window_seconds
        with self._lock:
            while self._global and self._global[0] <= threshold:
                self._global.popleft()
            for old_key, attempts in list(self._attempts.items()):
                while attempts and attempts[0] <= threshold:
                    attempts.popleft()
                if not attempts:
                    del self._attempts[old_key]
            attempts = self._attempts.get(key, deque())
            if len(self._global) >= self.global_attempt_limit:
                raise PairingRateLimited(max(1, math.ceil(self._global[0] + self.window_seconds - now)))
            if len(attempts) >= self.attempt_limit:
                raise PairingRateLimited(max(1, math.ceil(attempts[0] + self.window_seconds - now)))
            self._global.append(now)
            attempts.append(now)
            self._attempts[key] = attempts

    def exchange(self, code: str, name: str, *, client_key: str = "unknown") -> dict:
        now = self.clock()
        self._limit(client_key, now)
        if not isinstance(name, str) or not name.strip() or len(name.strip()) > 100:
            raise ValueError("Connection name must contain 1 to 100 characters")
        normalized = _code(code)
        valid = False
        token = secrets.token_urlsafe(32)
        connection_id = uuid.uuid4().hex
        with self.db.transaction() as connection:
            row = connection.execute(
                "SELECT * FROM pairing_codes WHERE code_hash = ?", (_digest(normalized),)
            ).fetchone()
            if row is not None:
                connection.execute("UPDATE pairing_codes SET attempts=attempts+1 WHERE id=?", (row["id"],))
                valid = row["consumed_at"] is None and now < row["expires_at"]
                if valid:
                    connection.execute(
                        "INSERT INTO connections(id,token_hash,name,created_at) VALUES (?,?,?,?)",
                        (connection_id, _digest(token), name.strip(), now),
                    )
                    connection.execute(
                        "UPDATE pairing_codes SET consumed_at=?, connection_id=? WHERE id=?",
                        (now, connection_id, row["id"]),
                    )
        if not valid:
            raise PairingError("Pairing code is invalid, expired, or already used")
        return {"connection_id": connection_id, "token": token,
                "name": name.strip(), "created_at": now}

    def authenticate(self, token: str) -> dict | None:
        if not isinstance(token, str) or not token or len(token) > 512:
            return None
        with self.db.read() as connection:
            row = connection.execute(
                "SELECT * FROM connections WHERE token_hash=? AND revoked_at IS NULL", (_digest(token),)
            ).fetchone()
            return _public(row) if row else None

    def list_connections(self, include_revoked: bool = False) -> list[dict]:
        where = "" if include_revoked else "WHERE revoked_at IS NULL"
        with self.db.read() as connection:
            return [_public(row) for row in connection.execute(
                f"SELECT * FROM connections {where} ORDER BY created_at DESC, id"
            )]

    def revoke(self, connection_id: str) -> bool:
        with self.db.transaction() as connection:
            return connection.execute(
                "UPDATE connections SET revoked_at=COALESCE(revoked_at,?) WHERE id=?",
                (self.clock(), connection_id),
            ).rowcount == 1

