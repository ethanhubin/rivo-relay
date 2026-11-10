"""One SQLite database for Relay-owned state, never Codex history.

Transaction/savepoint and PRAGMA behavior adapted from QiGuang's
qiguang/server/qiguang_sqlite.py; see docs/migration-evidence-storage.md.
"""

from __future__ import annotations

from contextlib import contextmanager
import os
from pathlib import Path
import sqlite3
import threading
from typing import Iterator
import uuid


SCHEMA_VERSION = 5
_TABLES = {"pairing_codes", "connections", "submissions", "todos", "queued_tasks",
           "native_observations", "workspaces", "todo_completion"}
_SCHEMA = (
    """CREATE TABLE pairing_codes (
        id TEXT PRIMARY KEY, code_hash TEXT NOT NULL UNIQUE,
        created_at REAL NOT NULL, expires_at REAL NOT NULL,
        consumed_at REAL, connection_id TEXT, attempts INTEGER NOT NULL DEFAULT 0
    )""",
    """CREATE TABLE connections (
        id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE,
        name TEXT NOT NULL, created_at REAL NOT NULL, revoked_at REAL
    )""",
    """CREATE TABLE submissions (
        id TEXT PRIMARY KEY,
        connection_id TEXT NOT NULL REFERENCES connections(id),
        operation TEXT NOT NULL, target_json TEXT NOT NULL,
        payload_hash TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('pending','accepted','unknown','failed')),
        receipt_json TEXT, error_code TEXT,
        created_at REAL NOT NULL, updated_at REAL NOT NULL
    )""",
    """CREATE TABLE todos (
        id TEXT PRIMARY KEY, title TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '',
        completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN (0,1)),
        project_id TEXT, thread_id TEXT,
        created_at REAL NOT NULL, updated_at REAL NOT NULL, completed_at REAL, extra_json TEXT NOT NULL DEFAULT '{}',
        CHECK((project_id IS NULL AND thread_id IS NULL)
           OR (project_id IS NOT NULL AND thread_id IS NOT NULL))
    )""",
    "CREATE TABLE queued_tasks (id TEXT PRIMARY KEY, connection_id TEXT NOT NULL REFERENCES connections(id), thread_id TEXT NOT NULL, payload_json TEXT NOT NULL, status TEXT NOT NULL, position INTEGER NOT NULL, revision INTEGER NOT NULL DEFAULT 0, turn_id TEXT, error TEXT, created_at REAL NOT NULL)",
    "CREATE TABLE native_observations(thread_id TEXT NOT NULL,kind TEXT NOT NULL,turn_id TEXT NOT NULL DEFAULT '',payload_json TEXT NOT NULL,updated_at REAL NOT NULL,PRIMARY KEY(thread_id,kind,turn_id))",
    "CREATE TABLE workspaces(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,thread_id TEXT UNIQUE,source_path TEXT NOT NULL,path TEXT NOT NULL,commit_id TEXT NOT NULL,status TEXT NOT NULL,archive_path TEXT,created_at REAL NOT NULL)",
    """CREATE TABLE todo_completion (
        token_hash TEXT PRIMARY KEY, todo_id TEXT NOT NULL,
        thread_id TEXT NOT NULL, fingerprint TEXT NOT NULL,
        used INTEGER NOT NULL DEFAULT 0)""",
    "CREATE INDEX todos_order ON todos(completed, created_at DESC, id)",
    "CREATE INDEX submissions_connection ON submissions(connection_id, created_at DESC)",
)


@contextmanager
def sqlite_transaction(connection: sqlite3.Connection) -> Iterator[sqlite3.Connection]:
    """Use a savepoint when called within an existing owner transaction."""
    nested = connection.in_transaction
    savepoint = "rivo_" + uuid.uuid4().hex
    connection.execute(f"SAVEPOINT {savepoint}" if nested else "BEGIN IMMEDIATE")
    try:
        yield connection
    except BaseException:
        if nested:
            connection.execute(f"ROLLBACK TO SAVEPOINT {savepoint}")
            connection.execute(f"RELEASE SAVEPOINT {savepoint}")
        else:
            connection.rollback()
        raise
    else:
        if nested:
            connection.execute(f"RELEASE SAVEPOINT {savepoint}")
        else:
            connection.commit()


class Database:
    """A thread-safe connection with durable commits and explicit transactions."""

    def __init__(self, path: str | Path, *, timeout: float = 10.0):
        self.path = str(path)
        self._lock = threading.RLock()
        if self.path != ":memory:":
            target = Path(path)
            if target.is_symlink():
                raise ValueError("Database path must not be a symbolic link")
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            try:
                descriptor = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            except FileExistsError:
                pass
            else:
                os.close(descriptor)
        self._connection = sqlite3.connect(
            self.path, timeout=timeout, isolation_level=None, check_same_thread=False
        )
        try:
            self._connection.row_factory = sqlite3.Row
            self._connection.execute("PRAGMA journal_mode=WAL")
            self._connection.execute("PRAGMA synchronous=FULL")
            self._connection.execute("PRAGMA foreign_keys=ON")
            self._connection.execute(f"PRAGMA busy_timeout={max(1, int(timeout * 1000))}")
            with self.transaction() as connection:
                current = int(connection.execute("PRAGMA user_version").fetchone()[0])
                tables = {
                    row[0] for row in connection.execute(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                    )
                }
                if current == 0 and not tables:
                    for statement in _SCHEMA:
                        connection.execute(statement)
                    connection.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
                elif current != SCHEMA_VERSION or tables != _TABLES:
                    raise RuntimeError("Unsupported Rivo Relay database schema; existing data was not migrated")
                # Ensure indexes also exist on current-version databases created
                # before these query optimizations were introduced.
                for statement in (
                    "CREATE INDEX IF NOT EXISTS queue_active_thread_order ON queued_tasks(thread_id,position,created_at,id) WHERE status NOT IN ('done','cancelled')",
                    "CREATE INDEX IF NOT EXISTS queue_poll_threads ON queued_tasks(thread_id) WHERE status IN ('waiting','running','unknown')",
                    "CREATE INDEX IF NOT EXISTS queue_thread_position ON queued_tasks(thread_id,position)",
                    "CREATE INDEX IF NOT EXISTS submissions_target ON submissions(operation,target_json,created_at DESC,id)",
                    "CREATE INDEX IF NOT EXISTS observations_recent ON native_observations(updated_at DESC) WHERE kind NOT IN ('ownership','externalWorkspace')",
                ):
                    connection.execute(statement)
        except BaseException:
            self._connection.close()
            raise

    @contextmanager
    def read(self) -> Iterator[sqlite3.Connection]:
        """Borrow the connection under the lock; use transaction() for writes."""
        with self._lock:
            yield self._connection

    @contextmanager
    def transaction(self) -> Iterator[sqlite3.Connection]:
        with self._lock, sqlite_transaction(self._connection) as connection:
            yield connection

    def close(self) -> None:
        with self._lock:
            self._connection.close()
