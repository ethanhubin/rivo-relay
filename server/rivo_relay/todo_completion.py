"""Persisted, narrowly scoped capabilities for agent-confirmed todo completion."""
import hashlib
import json
import secrets


def fingerprint(todo):
    return hashlib.sha256(json.dumps(
        [todo["title"], todo["notes"], todo.get("details", {})],
        ensure_ascii=False, sort_keys=True).encode()).hexdigest()


class TodoCompletion:
    def __init__(self, db, todos, events):
        self.db, self.todos, self.events = db, todos, events

    def issue(self, todo):
        token = secrets.token_urlsafe(32)
        with self.db.transaction() as connection:
            connection.execute("INSERT INTO todo_completion VALUES (?,?,?,?,0)",
                (hashlib.sha256(token.encode()).hexdigest(), todo["id"],
                 todo["thread_id"], fingerprint(todo)))
        return token

    def complete(self, token):
        from .submissions import SubmissionConflict
        with self.db.transaction() as connection:
            row = connection.execute("SELECT * FROM todo_completion WHERE token_hash=?",
                (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
            if row is None:
                raise PermissionError("Invalid todo completion credential")
            todo = self.todos.get(row["todo_id"])
            if row["used"] == -1:
                raise SubmissionConflict("Completion authority was revoked by a todo edit; complete it manually")
            if row["used"] == 1:
                return todo
            if todo["thread_id"] != row["thread_id"] or fingerprint(todo) != row["fingerprint"]:
                raise SubmissionConflict("Todo changed after dispatch; review and complete it manually")
            result = self.todos.update(todo["id"], completed=True)
            connection.execute("UPDATE todo_completion SET used=1 WHERE token_hash=?", (row["token_hash"],))
        self.events.publish({"method": "relay/todos", "params": {}})
        return result
