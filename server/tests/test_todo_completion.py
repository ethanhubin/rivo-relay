import unittest
from rivo_relay.database import Database
from rivo_relay.todos import TodoStore
from rivo_relay.events import EventBus
from rivo_relay.todo_completion import TodoCompletion
from rivo_relay.submissions import SubmissionConflict


class TodoCompletionTest(unittest.TestCase):
    def setUp(self):
        self.db = Database(":memory:")
        self.todos = TodoStore(self.db)
        self.events = EventBus()
        self.service = TodoCompletion(self.db, self.todos, self.events)
        todo = self.todos.create("目标")
        self.todo = self.todos.link(todo["id"], "project", "thread")
        self.token = self.service.issue(self.todo)

    def tearDown(self):
        self.events.close()
        self.db.close()

    def test_complete_and_retry_does_not_complete_reopened_todo(self):
        self.assertTrue(self.service.complete(self.token)["completed"])
        self.todos.update(self.todo["id"], completed=False)
        with self.assertRaises(SubmissionConflict):
            self.service.complete(self.token)

    def test_changed_content_rejected(self):
        self.todos.update(self.todo["id"], notes="新要求")
        with self.assertRaises(SubmissionConflict):
            self.service.complete(self.token)
        self.assertFalse(self.todos.get(self.todo["id"])["completed"])

    def test_invalid_credential_and_persistence(self):
        with self.assertRaises(PermissionError):
            self.service.complete("invalid")
        service = TodoCompletion(self.db, self.todos, self.events)
        self.assertTrue(service.complete(self.token)["completed"])

    def test_manual_complete_reopen_revokes_unused_token(self):
        self.todos.update(self.todo["id"], completed=True)
        self.todos.update(self.todo["id"], completed=False)
        with self.assertRaises(SubmissionConflict):
            self.service.complete(self.token)

    def test_edit_revert_still_revoked(self):
        self.todos.update(self.todo["id"], title="changed")
        self.todos.update(self.todo["id"], title=self.todo["title"])
        with self.assertRaises(SubmissionConflict):
            self.service.complete(self.token)

    def test_delete_recreate_cannot_reuse_token(self):
        self.todos.delete(self.todo["id"])
        self.todos.create("目标", todo_id=self.todo["id"])
        self.todos.link(self.todo["id"], "project", "thread")
        with self.assertRaises(PermissionError):
            self.service.complete(self.token)

    def test_retry_is_idempotent(self):
        first = self.service.complete(self.token)
        second = self.service.complete(self.token)
        self.assertEqual(first, second)

    def test_issuance_from_stale_dispatch_snapshot_rejected(self):
        self.todos.update(self.todo["id"], notes="new requirement")
        token = self.service.issue(self.todo)
        with self.assertRaises(SubmissionConflict):
            self.service.complete(token)

    def test_concurrent_completion_and_recurrence_only_once(self):
        from concurrent.futures import ThreadPoolExecutor
        todo = self.todos.create("recurring", details={"dueDate": "2026-11-08", "repeatRule": "daily"})
        todo = self.todos.link(todo["id"], "project", "thread")
        token = self.service.issue(todo)
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda _: self.service.complete(token), range(4)))
        self.assertTrue(all(item["completed"] for item in results))
        self.assertEqual(len(self.todos.list()), 3)

    def test_version_four_migration_and_actual_restart(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.db"
            db = Database(path)
            with db.transaction() as connection:
                connection.execute("DROP TABLE todo_completion")
                connection.execute("PRAGMA user_version=4")
            db.close()
            db = Database(path)
            todos = TodoStore(db)
            todo = todos.create("persisted")
            todo = todos.link(todo["id"], "project", "thread")
            token = TodoCompletion(db, todos, self.events).issue(todo)
            db.close()
            db = Database(path)
            try:
                service = TodoCompletion(db, TodoStore(db), self.events)
                self.assertTrue(service.complete(token)["completed"])
            finally:
                db.close()
