"""Minimal todo CRUD adapted from QiGuang; no history or execution owner."""

from __future__ import annotations

import time
import calendar
import copy
from datetime import date, timedelta
import json
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
    result = dict(row)
    result["details"] = json.loads(result.pop("extra_json", "{}"))
    return {**result, "completed": bool(row["completed"])}


def _details(value: Any) -> dict:
    if not isinstance(value, dict):
        raise ValueError("details must be an object")
    if set(value) - {"dueDate", "reminderAt", "repeatRule", "subtasks", "attachments"}:
        raise ValueError("Unsupported todo details field")
    due = value.get("dueDate", "")
    if not isinstance(due, str):
        raise ValueError("dueDate must be YYYY-MM-DD")
    if due:
        try:
            parsed = date.fromisoformat(due)
        except ValueError as error:
            raise ValueError("dueDate must be YYYY-MM-DD") from error
        if parsed.isoformat() != due:
            raise ValueError("dueDate must be YYYY-MM-DD")
    reminder = value.get("reminderAt", 0)
    if isinstance(reminder, bool) or not isinstance(reminder, int) or reminder < 0 or reminder > 253402300799000:
        raise ValueError("reminderAt must be a non-negative epoch millisecond value")
    repeat = value.get("repeatRule", "none")
    if not isinstance(repeat, str) or repeat not in {"none", "daily", "weekly", "monthly", "yearly"} or (repeat != "none" and not due):
        raise ValueError("A repeating todo requires a due date and supported repeatRule")
    if repeat != "none" and date.fromisoformat(due).year >= 9999:
        raise ValueError("Repeating todo dueDate must leave room for the next occurrence")
    subtasks = value.get("subtasks", [])
    attachments = value.get("attachments", [])
    if not isinstance(subtasks, list) or len(subtasks) > 64:
        raise ValueError("subtasks must contain at most 64 items")
    if not isinstance(attachments, list) or len(attachments) > 16:
        raise ValueError("attachments must contain at most 16 items")
    checked_tasks, checked_attachments = [], []
    ids = set()
    for task in subtasks:
        if not isinstance(task, dict) or set(task) - {"id", "title", "completed"}:
            raise ValueError("Invalid subtask")
        task_id = _text(task.get("id"), "subtask id", 128)
        if task_id in ids or not isinstance(task.get("completed", False), bool):
            raise ValueError("Duplicate subtask ID or invalid completed state")
        ids.add(task_id)
        checked_tasks.append({"id": task_id, "title": _text(task.get("title"), "subtask title", 500), "completed": task.get("completed", False)})
    for attachment in attachments:
        if not isinstance(attachment, dict) or set(attachment) - {"name", "path", "type"}:
            raise ValueError("Invalid attachment reference")
        checked_attachments.append({"name": _text(attachment.get("name"), "attachment name", 300),
            "path": _text(attachment.get("path"), "attachment path", 4096),
            "type": _text(attachment.get("type", "file"), "attachment type", 50)})
    return {"dueDate": due, "reminderAt": reminder, "repeatRule": repeat,
            "subtasks": checked_tasks, "attachments": checked_attachments}


def _next_details(details: dict) -> dict | None:
    rule = details.get("repeatRule", "none")
    if rule == "none" or not details.get("dueDate"):
        return None
    previous = date.fromisoformat(details["dueDate"])
    if rule in {"daily", "weekly"}:
        following = previous + timedelta(days=1 if rule == "daily" else 7)
    else:
        month = previous.month + (1 if rule == "monthly" else 12)
        year = previous.year + (month - 1) // 12
        month = (month - 1) % 12 + 1
        following = date(year, month, min(previous.day, calendar.monthrange(year, month)[1]))
    result = copy.deepcopy(details)
    result["dueDate"] = following.isoformat()
    if result.get("reminderAt", 0):
        result["reminderAt"] += (following - previous).days * 86400000
    for subtask in result.get("subtasks", []):
        subtask["completed"] = False
    return result


class TodoStore:
    def __init__(self, db: Database, *, clock: Callable[[], float] = time.time):
        self.db = db
        self.clock = clock

    def create(self, title: str, notes: str = "", todo_id: str | None = None, details: Any = _UNSET) -> dict:
        title = _text(title, "title", 200)
        notes = _text(notes, "notes", 4000, empty=True)
        todo_id = uuid.uuid4().hex if todo_id is None else _text(todo_id, "todo_id", 128)
        details = _details({} if details is _UNSET else details)
        extra = json.dumps(details, ensure_ascii=False, sort_keys=True)
        now = self.clock()
        with self.db.transaction() as connection:
            existing = connection.execute("SELECT * FROM todos WHERE id=?", (todo_id,)).fetchone()
            if existing:
                if existing["title"] != title or existing["notes"] != notes or _details(json.loads(existing["extra_json"])) != details:
                    raise TodoConflictError("Todo ID already exists with different content")
                return _public(existing)
            connection.execute(
                "INSERT INTO todos(id,title,notes,extra_json,created_at,updated_at) VALUES (?,?,?,?,?,?)",
                (todo_id, title, notes, extra, now, now),
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
               completed: Any = _UNSET, details: Any = _UNSET) -> dict:
        updates = {}
        if title is not _UNSET:
            updates["title"] = _text(title, "title", 200)
        if notes is not _UNSET:
            updates["notes"] = _text(notes, "notes", 4000, empty=True)
        if completed is not _UNSET:
            if not isinstance(completed, bool):
                raise ValueError("completed must be a boolean")
            updates["completed"] = int(completed)
        if details is not _UNSET:
            updates["extra_json"] = json.dumps(_details(details), ensure_ascii=False, sort_keys=True)
        if not updates:
            raise ValueError("At least one update field is required")
        now = self.clock()
        with self.db.transaction() as connection:
            row = connection.execute("SELECT * FROM todos WHERE id=?", (todo_id,)).fetchone()
            if row is None:
                raise TodoNotFoundError(todo_id)
            if "completed" in updates and updates["completed"] != row["completed"]:
                updates["completed_at"] = now if completed else None
                if completed:
                    next_details = _next_details(json.loads(updates.get("extra_json", row["extra_json"])))
                    if next_details is not None:
                        next_id = uuid.uuid5(uuid.NAMESPACE_URL, f"rivo-todo:{todo_id}:{next_details['dueDate']}").hex
                        connection.execute(
                            "INSERT OR IGNORE INTO todos(id,title,notes,extra_json,created_at,updated_at) VALUES (?,?,?,?,?,?)",
                            (next_id, updates.get("title", row["title"]), updates.get("notes", row["notes"]),
                             json.dumps(next_details, ensure_ascii=False, sort_keys=True), now, now),
                        )
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
