"""Minimal todo CRUD adapted from QiGuang; no history or execution owner."""

from __future__ import annotations

import time
from typing import Any, Callable
import uuid

from .database import Database


class TodoNotFoundError(KeyError):
    code = "todo_not_found"
    status = 404


class TodoConflictError(ValueError):
    code = "todo_conflict"
    status = 409


_UNSET = object()


def _text(value: Any, name: str, maximum: int, *, empty: bool = False) -> str:
    if not isinstance(value, str):
        raise ValueError(f"{name} must be text")
    result = value.strip()
    if (not empty and not result) or len(result) > maximum:
        raise ValueError(f"{name} must contain {'0' if empty else '1'} to {maximum} characters")
    return result


def _public(row) -> dict:
    return {**dict(row), "completed": bool(row["completed"])}


class TodoStore:
    def __init__(self, db: Database, *, clock: Callable[[], float] = time.time):
        self.db = db
        self.clock = clock

    def create(self, title: str, notes: str = "", todo_id: str | None = None) -> dict:
        title = _text(title, "title", 200)
        notes = _text(notes, "notes", 4000, empty=True)
        todo_id = uuid.uuid4().hex if todo_id is None else _text(todo_id, "todo_id", 128)
        now = self.clock()
        with self.db.transaction() as connection:
            existing = connection.execute("SELECT * FROM todos WHERE id=?", (todo_id,)).fetchone()
            if existing:
                if existing["title"] != title or existing["notes"] != notes:
                    raise TodoConflictError("Todo ID already exists with different content")
                return _public(existing)
            connection.execute(
                "INSERT INTO todos(id,title,notes,created_at,updated_at) VALUES (?,?,?,?,?)",
                (todo_id, title, notes, now, now),
            )
            return _public(connection.execute("SELECT * FROM todos WHERE id=?", (todo_id,)).fetchone())

    def get(self, todo_id: str) -> dict:
        with self.db.read() as connection:
            row = connection.execute("SELECT * FROM todos WHERE id=?", (todo_id,)).fetchone()
            if row is None:
                raise TodoNotFoundError(todo_id)
            return _public(row)

    def list(self, completed: bool | None = None) -> list[dict]:
        if completed is not None and not isinstance(completed, bool):
            raise ValueError("completed must be a boolean")
        where = "WHERE completed=?" if completed is not None else ""
        parameters = (int(completed),) if completed is not None else ()
        with self.db.read() as connection:
            return [_public(row) for row in connection.execute(
                f"SELECT * FROM todos {where} ORDER BY completed ASC, created_at DESC, id", parameters
            )]

    def update(self, todo_id: str, *, title: Any = _UNSET, notes: Any = _UNSET,
               completed: Any = _UNSET) -> dict:
        updates = {}
        if title is not _UNSET:
            updates["title"] = _text(title, "title", 200)
        if notes is not _UNSET:
            updates["notes"] = _text(notes, "notes", 4000, empty=True)
        if completed is not _UNSET:
            if not isinstance(completed, bool):
                raise ValueError("completed must be a boolean")
            updates["completed"] = int(completed)
        if not updates:
            raise ValueError("At least one update field is required")
        now = self.clock()
        with self.db.transaction() as connection:
            row = connection.execute("SELECT * FROM todos WHERE id=?", (todo_id,)).fetchone()
            if row is None:
                raise TodoNotFoundError(todo_id)
            if "completed" in updates and updates["completed"] != row["completed"]:
                updates["completed_at"] = now if completed else None
            updates["updated_at"] = now
            columns = ", ".join(f"{name}=?" for name in updates)  # Fixed field names above.
            connection.execute(f"UPDATE todos SET {columns} WHERE id=?", (*updates.values(), todo_id))
            return _public(connection.execute("SELECT * FROM todos WHERE id=?", (todo_id,)).fetchone())

    def link(self, todo_id: str, project_id: str, thread_id: str) -> dict:
        """Attach only after a native thread is known. Never complete the todo."""
        project_id = _text(project_id, "project_id", 2048)
        thread_id = _text(thread_id, "thread_id", 200)
        with self.db.transaction() as connection:
            row = connection.execute("SELECT * FROM todos WHERE id=?", (todo_id,)).fetchone()
            if row is None:
                raise TodoNotFoundError(todo_id)
            if row["thread_id"] is not None:
                if (row["project_id"], row["thread_id"]) != (project_id, thread_id):
                    raise TodoConflictError("Todo is already linked to a different native thread")
                return _public(row)
            connection.execute(
                "UPDATE todos SET project_id=?,thread_id=?,updated_at=? WHERE id=?",
                (project_id, thread_id, self.clock(), todo_id),
            )
            return _public(connection.execute("SELECT * FROM todos WHERE id=?", (todo_id,)).fetchone())

    def delete(self, todo_id: str) -> None:
        with self.db.transaction() as connection:
            if connection.execute("DELETE FROM todos WHERE id=?", (todo_id,)).rowcount != 1:
                raise TodoNotFoundError(todo_id)
