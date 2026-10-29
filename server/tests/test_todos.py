from pathlib import Path
import tempfile
import unittest

from rivo_relay.database import Database
from rivo_relay.todos import TodoConflictError, TodoNotFoundError, TodoStore


class TodoTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "rivo.sqlite3"
        self.db = Database(self.path)
        self.addCleanup(self.db.close)
        self.now = 1000.0
        self.store = TodoStore(self.db, clock=lambda: self.now)

    def test_crud_completed_state_and_restart(self):
        created = self.store.create("  Check reconnect  ", "  acceptance steps  ", todo_id="todo-1")
        self.assertEqual(created["title"], "Check reconnect")
        self.now += 10
        edited = self.store.update(created["id"], title="Check result", completed=True)
        self.assertTrue(edited["completed"])
        self.assertEqual(edited["completed_at"], 1010)
        self.now += 10
        repeated = self.store.update(created["id"], completed=True)
        self.assertEqual(repeated["completed_at"], 1010)
        self.assertEqual(self.store.list(completed=False), [])
        reopened = Database(self.path)
        self.addCleanup(reopened.close)
        restored = TodoStore(reopened)
        self.assertEqual(restored.get("todo-1")["notes"], "acceptance steps")
        self.assertIsNone(restored.update("todo-1", completed=False)["completed_at"])
        restored.delete("todo-1")
        with self.assertRaises(TodoNotFoundError):
            self.store.get("todo-1")

    def test_client_id_deduplicates_exact_create_and_conflicts_on_change(self):
        first = self.store.create("Task", todo_id="fixed-id")
        self.assertEqual(self.store.create("Task", todo_id="fixed-id"), first)
        with self.assertRaises(TodoConflictError):
            self.store.create("Different task", todo_id="fixed-id")
        self.assertEqual(len(self.store.list()), 1)

    def test_link_is_stable_through_edit_and_does_not_complete(self):
        task = self.store.create("Task")
        linked = self.store.link(task["id"], "project-id", "native-thread-id")
        self.assertFalse(linked["completed"])
        self.assertEqual(self.store.link(task["id"], "project-id", "native-thread-id"), linked)
        with self.assertRaises(TodoConflictError):
            self.store.link(task["id"], "other-project", "other-thread")
        edited = self.store.update(task["id"], title="New task title")
        self.assertEqual(edited["thread_id"], "native-thread-id")
        self.assertFalse(edited["completed"])

    def test_atomic_validation_and_missing_ids(self):
        task = self.store.create("Original")
        with self.assertRaises(ValueError):
            self.store.update(task["id"], title="Changed", completed="yes")
        self.assertEqual(self.store.get(task["id"])["title"], "Original")
        with self.assertRaises(ValueError):
            self.store.create(" ")
        with self.assertRaises(ValueError):
            self.store.list(completed=1)
        for operation in (lambda: self.store.update("missing", title="Changed"),
                          lambda: self.store.link("missing", "project", "thread"),
                          lambda: self.store.delete("missing")):
            with self.assertRaises(TodoNotFoundError):
                operation()


if __name__ == "__main__":
    unittest.main()
