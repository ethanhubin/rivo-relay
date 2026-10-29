"""Durable submission claims; duplicate or uncertain requests never dispatch."""

from __future__ import annotations

import hashlib
import json
import time
from typing import Any, Callable

from .database import Database


class SubmissionConflict(ValueError):
    code = "submission_conflict"
    status = 409


class SubmissionNotFound(KeyError):
    code = "submission_not_found"
    status = 404


def _json(value: Any) -> str:
    try:
        result = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
    except (ValueError, TypeError) as error:
        raise ValueError("Submission fields must be finite JSON values") from error
    if len(result.encode("utf-8")) > 8 * 1024 * 1024:
        raise ValueError("Submission field is too large")
    return result


def _identifier(value: str, name: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 200 or value.strip() != value:
        raise ValueError(f"{name} must be a nonempty identifier of at most 200 characters")
    return value


_IDENTITY_KEYS = {
    alias: name for name in ("thread", "turn", "item", "request", "project", "runtime", "connection", "todo")
    for alias in (name + "Id", name + "_id")
}


def _merge_receipt(existing: dict | None, incoming: dict | None) -> dict | None:
    """Keep known identities without storing native response bodies."""
    if incoming is not None and (not isinstance(incoming, dict) or not incoming):
        raise ValueError("A native receipt with identity fields is required")
    merged = dict(existing or {})
    identities = {}
    for key, value in [*merged.items(), *(incoming or {}).items()]:
        kind = _IDENTITY_KEYS.get(key)
        if kind is None or not isinstance(value, str) or not value or len(value) > 2048:
            raise ValueError("Native receipt must contain only nonempty identity fields")
        if kind in identities and identities[kind] != value:
            raise SubmissionConflict("A recorded native identity cannot be replaced")
        identities[kind] = value
    merged.update(incoming or {})
    return merged or None


def _public(row) -> dict:
    return {
        "id": row["id"], "connection_id": row["connection_id"],
        "operation": row["operation"], "target": json.loads(row["target_json"]),
        "payload_hash": row["payload_hash"], "status": row["status"],
        "receipt": json.loads(row["receipt_json"]) if row["receipt_json"] else None,
        "error_code": row["error_code"],
        "created_at": row["created_at"], "updated_at": row["updated_at"],
    }


class SubmissionStore:
    def __init__(self, db: Database, *, clock: Callable[[], float] = time.time):
        self.db = db
        self.clock = clock

    def claim(self, submission_id: str, connection_id: str, operation: str,
              target: Any, payload: Any) -> dict:
        """Commit ownership before returning should_dispatch=True.

        Callers must never dispatch when false, including pending and unknown.
        IDs are globally unique: changing connection, target, operation, or
        payload is a conflict, not a fresh execution. Payload text is not stored.
        """
        _identifier(submission_id, "submission_id")
        _identifier(connection_id, "connection_id")
        _identifier(operation, "operation")
        target_json = _json(target)
        payload_hash = hashlib.sha256(_json(payload).encode("utf-8")).hexdigest()
        now = self.clock()
        with self.db.transaction() as connection:
            row = connection.execute("SELECT * FROM submissions WHERE id=?", (submission_id,)).fetchone()
            if row:
                if (row["connection_id"], row["operation"], row["target_json"], row["payload_hash"]) != (
                    connection_id, operation, target_json, payload_hash
                ):
                    raise SubmissionConflict("Submission ID was already claimed for a different request")
                return {"should_dispatch": False, "submission": _public(row)}
            owner = connection.execute(
                "SELECT id FROM connections WHERE id=? AND revoked_at IS NULL", (connection_id,)
            ).fetchone()
            if owner is None:
                raise ValueError("Submission connection is missing or revoked")
            connection.execute(
                """INSERT INTO submissions(id,connection_id,operation,target_json,payload_hash,status,created_at,updated_at)
                   VALUES (?,?,?,?,?,'pending',?,?)""",
                (submission_id, connection_id, operation, target_json, payload_hash, now, now),
            )
            row = connection.execute("SELECT * FROM submissions WHERE id=?", (submission_id,)).fetchone()
            return {"should_dispatch": True, "submission": _public(row)}

    def get(self, submission_id: str, connection_id: str | None = None) -> dict:
        with self.db.read() as connection:
            row = connection.execute("SELECT * FROM submissions WHERE id=?", (submission_id,)).fetchone()
            if row is None or (connection_id is not None and row["connection_id"] != connection_id):
                raise SubmissionNotFound(submission_id)
            return _public(row)

    def find_for_target(self, operation: str, target: Any) -> list[dict]:
        """Return durable claims for one operation/target, newest first.

        This is an internal coordination query, not a retry queue. It includes
        all statuses and owners so callers can inspect unknown work after a
        restart before creating another native thread for the same todo.
        """
        _identifier(operation, "operation")
        target_json = _json(target)
        with self.db.read() as connection:
            return [_public(row) for row in connection.execute(
                "SELECT * FROM submissions WHERE operation=? AND target_json=? ORDER BY created_at DESC, id",
                (operation, target_json),
            )]

    def checkpoint(self, submission_id: str, receipt: dict) -> dict:
        """Durably retain a created thread before linking or dispatching a turn.

        This does not change the claim status or authorize dispatch/retry.
        An unknown request stays unknown after late identity reconciliation.
        """
        if receipt is None:
            raise ValueError("A receipt with identity fields is required")
        _merge_receipt(None, receipt)
        with self.db.transaction() as connection:
            row = connection.execute("SELECT * FROM submissions WHERE id=?", (submission_id,)).fetchone()
            if row is None:
                raise SubmissionNotFound(submission_id)
            if row["status"] not in {"pending", "unknown"}:
                raise SubmissionConflict("Only unresolved submissions can be checkpointed")
            merged = _merge_receipt(json.loads(row["receipt_json"]) if row["receipt_json"] else None, receipt)
            connection.execute(
                "UPDATE submissions SET receipt_json=?,updated_at=? WHERE id=?",
                (_json(merged), self.clock(), submission_id),
            )
            return _public(connection.execute("SELECT * FROM submissions WHERE id=?", (submission_id,)).fetchone())

    def _transition(self, submission_id: str, status: str, *, receipt=None, error_code=None) -> dict:
        with self.db.transaction() as connection:
            row = connection.execute("SELECT * FROM submissions WHERE id=?", (submission_id,)).fetchone()
            if row is None:
                raise SubmissionNotFound(submission_id)
            merged = _merge_receipt(json.loads(row["receipt_json"]) if row["receipt_json"] else None, receipt)
            receipt_json = _json(merged) if merged is not None else None
            if row["status"] == status:
                if row["receipt_json"] != receipt_json or row["error_code"] != error_code:
                    raise SubmissionConflict("Submission result is already recorded")
                return _public(row)
            if row["status"] not in {"pending", "unknown"}:
                raise SubmissionConflict("A confirmed submission result cannot be overwritten")
            connection.execute(
                "UPDATE submissions SET status=?,receipt_json=?,error_code=?,updated_at=? WHERE id=?",
                (status, receipt_json, error_code, self.clock(), submission_id),
            )
            return _public(connection.execute("SELECT * FROM submissions WHERE id=?", (submission_id,)).fetchone())

    def mark_accepted(self, submission_id: str, receipt: dict) -> dict:
        """Record native identity-only receipt after acceptance or reconciliation."""
        if receipt is None:
            raise ValueError("A receipt with identity fields is required")
        _merge_receipt(None, receipt)
        return self._transition(submission_id, "accepted", receipt=receipt)

    def mark_unknown(self, submission_id: str) -> dict:
        return self._transition(submission_id, "unknown")

    def mark_failed(self, submission_id: str, error_code: str) -> dict:
        """Use only for a confirmed failure; timeouts belong in unknown."""
        _identifier(error_code, "error_code")
        return self._transition(submission_id, "failed", error_code=error_code)

    def recover_pending(self) -> int:
        """Call once at exclusive service startup, before accepting requests.

        Do not invoke from ordinary CLI database opens while a server is active.
        Recovery marks uncertainty and never replays an outbound operation.
        """
        with self.db.transaction() as connection:
            return connection.execute(
                "UPDATE submissions SET status='unknown',updated_at=? WHERE status='pending'",
                (self.clock(),),
            ).rowcount

