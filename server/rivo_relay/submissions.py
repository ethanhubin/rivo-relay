"""Durable submission claims; duplicate or uncertain requests never dispatch."""

from __future__ import annotations

import hashlib
import json
import time
import threading
from typing import Any, Callable

from .database import Database
from .receipts import ReceiptConflict, merge_receipts, validate_receipt, dispatch_receipt_matches


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
    if len(result.encode("utf-8")) > 64 * 1024 * 1024:
        raise ValueError("Submission field is too large")
    return result


def _identifier(value: str, name: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 200 or value.strip() != value:
        raise ValueError(f"{name} must be a nonempty identifier of at most 200 characters")
    return value


def _merge_receipt(existing: dict | None, incoming: dict | None) -> dict | None:
    """Compatibility import used by the offline evidence review tool."""
    try:
        return merge_receipts(existing, incoming)
    except ReceiptConflict as error:
        raise SubmissionConflict(str(error)) from error


def _validate_receipt(receipt, row, *, accepted=False, dispatched=False):
    try:
        validate = dispatch_receipt_matches if dispatched else validate_receipt
        args = (receipt, row['id'], row['operation'], json.loads(row['target_json']))
        return validate(*args) if dispatched else validate(*args, accepted=accepted)
    except ReceiptConflict as error:
        raise SubmissionConflict(str(error)) from error


def _public(row) -> dict:
    return {
        "id": row["id"], "connection_id": row["connection_id"],
        "operation": row["operation"], "target": json.loads(row["target_json"]),
        "payload_hash": row["payload_hash"], "status": row["status"],
        "receipt": json.loads(row["receipt_json"]) if row["receipt_json"] else None,
        "error_code": row["error_code"],
        "created_at": row["created_at"], "updated_at": row["updated_at"],
        "dispatch_phase": row["dispatch_phase"],
    }


class SubmissionStore:
    def __init__(self, db: Database, *, clock: Callable[[], float] = time.time):
        self.db = db
        self.clock = clock
        self._recovery_lock = threading.Lock()
        self._recovering = set()

    def claim(self, submission_id: str, connection_id: str, operation: str,
              target: Any, payload: Any, *, preparing=False) -> dict:
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
                """INSERT INTO submissions(id,connection_id,operation,target_json,payload_hash,status,created_at,updated_at,dispatch_phase)
                   VALUES (?,?,?,?,?,'pending',?,?,?)""",
                (submission_id, connection_id, operation, target_json, payload_hash, now, now, 'preparing' if preparing else None),
            )
            row = connection.execute("SELECT * FROM submissions WHERE id=?", (submission_id,)).fetchone()
            return {"should_dispatch": True, "submission": _public(row)}

    def get(self, submission_id: str, connection_id: str | None = None) -> dict:
        with self.db.read() as connection:
            row = connection.execute("SELECT * FROM submissions WHERE id=?", (submission_id,)).fetchone()
            if row is None or (connection_id is not None and row["connection_id"] != connection_id):
                raise SubmissionNotFound(submission_id)
            return _public(row)

    def reconcile(self, submission_id: str, connection_id: str) -> dict:
        """Read an existing claim or fence an absent request before declaring failure.

        The same transaction lock as claim() ensures a delayed POST cannot dispatch
        after the phone is told that its missing submission did not execute.
        """
        _identifier(submission_id, "submission_id")
        _identifier(connection_id, "connection_id")
        with self.db.transaction() as connection:
            row = connection.execute("SELECT * FROM submissions WHERE id=?", (submission_id,)).fetchone()
            if row is not None:
                if row["connection_id"] != connection_id:
                    raise SubmissionNotFound(submission_id)
                return _public(row)
            owner = connection.execute(
                "SELECT id FROM connections WHERE id=? AND revoked_at IS NULL", (connection_id,)
            ).fetchone()
            if owner is None:
                raise ValueError("Submission connection is missing or revoked")
            now = self.clock()
            connection.execute(
                """INSERT INTO submissions(id,connection_id,operation,target_json,payload_hash,status,error_code,created_at,updated_at)
                   VALUES (?,?,'submission.reconcile','null','','failed','submission_not_received',?,?)""",
                (submission_id, connection_id, now, now),
            )
            return _public(connection.execute("SELECT * FROM submissions WHERE id=?", (submission_id,)).fetchone())

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

    def find_usage_resets(self, account_scope: str) -> list[dict]:
        """Coordinate selected-credit and legacy claims within the same account."""
        with self.db.read() as connection:
            return [_public(row) for row in connection.execute(
                "SELECT * FROM submissions WHERE operation='usage.reset' AND "
                "(target_json=? OR json_extract(target_json, '$.accountScope')=?) "
                "ORDER BY created_at DESC, id", (_json(account_scope), account_scope),
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
            _validate_receipt(merged, row)
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
            _validate_receipt(merged, row, accepted=status == 'accepted')
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

    def record_dispatch(self, submission_id, phase, receipt=None):
        """Called synchronously at the actual turn boundary; failure prevents sending."""
        with self.db.transaction() as db:
            row = db.execute('SELECT * FROM submissions WHERE id=?', (submission_id,)).fetchone()
            if row is None or row['status'] != 'pending' or row['operation'] not in {'thread.turns', 'thread.continue', 'thread.create', 'thread.fork', 'thread.goal', 'thread.ownership'}:
                raise SubmissionConflict('Submission cannot advance dispatch')
            expected = {'preparing': None, 'dispatching': 'preparing', 'received': 'dispatching'}
            if phase not in expected or row['dispatch_phase'] != expected[phase]:
                raise SubmissionConflict('Invalid dispatch phase transition')
            merged = None
            if phase == 'received':
                merged = _merge_receipt(json.loads(row['receipt_json']) if row['receipt_json'] else None, receipt)
                if not _validate_receipt(merged, row, dispatched=True):
                    raise SubmissionConflict('Native receipt does not match the submission')
            elif receipt is not None:
                raise SubmissionConflict('Unexpected dispatch receipt')
            db.execute('UPDATE submissions SET dispatch_phase=?,receipt_json=COALESCE(?,receipt_json),updated_at=? WHERE id=?',
                       (phase, _json(merged) if merged else None, self.clock(), submission_id))

    def begin_local(self, submission_id):
        with self.db.transaction() as db:
            db.execute("UPDATE submissions SET dispatch_phase='local_transaction' WHERE id=? AND status='pending'", (submission_id,))

    def resolve_dispatch(self, submission_id, *, preparation_error=None):
        """Only after action exit, or with the service stopped. Never resolve a live preparation."""
        with self.db.transaction() as db:
            row = db.execute('SELECT * FROM submissions WHERE id=?', (submission_id,)).fetchone()
            if row is None or row['status'] not in {'pending', 'unknown'}:
                return None
            if row['dispatch_phase'] == 'local_transaction' and row['receipt_json'] is None:
                return self.mark_failed(submission_id, 'operation_not_dispatched')
            if row['operation'] not in {'thread.turns', 'thread.continue', 'thread.create', 'thread.fork', 'thread.goal', 'thread.ownership'}:
                return None
            if row['dispatch_phase'] == 'preparing' and row['receipt_json'] is None:
                return self.mark_failed(submission_id, 'turn_not_dispatched' if row['operation'] in {'thread.turns', 'thread.continue'} else (preparation_error or 'operation_not_dispatched'))
            if row['dispatch_phase'] == 'received' and row['receipt_json']:
                receipt = json.loads(row['receipt_json'])
                if _validate_receipt(receipt, row, dispatched=True):
                    return self.mark_accepted(submission_id, receipt)
            return None

    def recover_native(self, submission_id, connection_id, backend):
        """Recover positive evidence only. Never infer absence from age/history gaps.

        Called by authenticated receipt polling. Native evidence and bounded
        read progress belong to the backend; storage never interprets history.
        """
        record = self.get(submission_id, connection_id)
        if record['status'] != 'unknown':
            return record
        with self._recovery_lock:
            if submission_id in self._recovering:
                return record
            self._recovering.add(submission_id)
        try:
            record = self.get(submission_id, connection_id)
            if record['status'] != 'unknown':
                return record
            resolved = self.resolve_dispatch(submission_id)
            if resolved:
                return resolved
            if record['operation'] == 'thread.queue.enqueue':
                with self.db.read() as db:
                    row = db.execute('SELECT thread_id,connection_id FROM queued_tasks WHERE id=?', (submission_id,)).fetchone()
                if row and row['connection_id'] == connection_id and row['thread_id'] == record['target']:
                    return self.mark_accepted(submission_id, {'threadId': row['thread_id'], 'itemId': submission_id})
                return record
            recover = getattr(backend, 'recover_submission', None)
            receipt = recover(record) if callable(recover) else None
            if receipt is not None:
                return self.mark_accepted(submission_id, receipt)
            return record
        except Exception:
            # A read/storage failure must not turn uncertainty into a retry.
            return self.get(submission_id, connection_id)
        finally:
            with self._recovery_lock:
                self._recovering.discard(submission_id)

    def recover_pending(self) -> int:
        """Exclusive startup recovery; preserve legacy and sent-but-unconfirmed claims."""
        with self.db.transaction() as connection:
            rows = connection.execute("SELECT id,status FROM submissions WHERE status IN ('pending','unknown')").fetchall()
            count = 0
            for row in rows:
                if self.resolve_dispatch(row['id']) is not None:
                    count += 1
                elif row['status'] == 'pending':
                    self.mark_unknown(row['id'])
                    count += 1
            return count
