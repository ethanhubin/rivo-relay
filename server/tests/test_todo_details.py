import tempfile
import unittest
from pathlib import Path

from rivo_relay.database import Database
from rivo_relay.todos import TodoStore, TodoConflictError


class TodoDetailsTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.db = Database(Path(temporary.name) / "todos.sqlite")
        self.addCleanup(self.db.close)
        self.store = TodoStore(self.db, clock=lambda: 1000)

    def test_details_survive_edit_and_client_create_replay(self):
        details = {"dueDate": "2026-11-01", "reminderAt": 1793494800000,
                   "subtasks": [{"id": "a", "title": "Inspect", "completed": False}],
                   "attachments": [{"name": "notes.md", "path": "/project/notes.md", "type": "file"}]}
        first = self.store.create("Review", todo_id="stable", details=details)
        self.assertEqual(first, self.store.create("Review", todo_id="stable", details=details))
        self.assertNotIn("extra_json", first)
        edited = self.store.update("stable", notes="Updated")
        self.assertEqual(first["details"], edited["details"])
        with self.assertRaises(TodoConflictError):
            self.store.create("Review", todo_id="stable", details={"dueDate": "2026-11-02"})

    def test_month_end_repeat_is_atomic_idempotent_and_does_not_inherit_thread(self):
        original = self.store.create("Monthly", todo_id="monthly", details={"dueDate": "2027-01-31", "repeatRule": "monthly",
            "reminderAt": 1801386000000, "subtasks": [{"id": "a", "title": "Check", "completed": True}]})
        self.store.link("monthly", "project", "native-thread")
        done = self.store.update("monthly", completed=True)
        self.assertTrue(done["completed"])
        self.store.update("monthly", completed=True)  # Lost HTTP response replay.
        remaining = self.store.list(completed=False)
        self.assertEqual(len(remaining), 1)
        following = remaining[0]
        self.assertEqual(following["details"]["dueDate"], "2027-02-28")
        self.assertEqual(following["details"]["reminderAt"], original["details"]["reminderAt"] + 28 * 86400000)
        self.assertFalse(following["details"]["subtasks"][0]["completed"])
        self.assertIsNone(following["thread_id"])
        self.store.update("monthly", completed=False)
        self.store.update("monthly", completed=True)
        self.assertEqual(len(self.store.list()), 2)

    def test_yearly_leap_day_and_manual_completion_only(self):
        item = self.store.create("Yearly", details={"dueDate": "2028-02-29", "repeatRule": "yearly"})
        self.store.link(item["id"], "p", "t")
        self.assertEqual(len(self.store.list()), 1)
        self.assertFalse(self.store.get(item["id"])["completed"])
        self.store.update(item["id"], completed=True)
        self.assertEqual(self.store.list(completed=False)[0]["details"]["dueDate"], "2029-02-28")

    def test_invalid_details_are_rejected_without_changing_existing_title(self):
        self.store.create("Original", todo_id="one")
        invalid = [None, [], {"dueDate": "2026-02-30"}, {"repeatRule": "weekly"}, {"repeatRule": []},
                   {"reminderAt": True}, {"reminderAt": -1}, {"unexpected": 1},
                   {"subtasks": [{"id": "x", "title": "A"}, {"id": "x", "title": "B"}]},
                   {"attachments": [{"name": "Missing path"}]}]
        for details in invalid:
            with self.subTest(details=details), self.assertRaises(ValueError):
                self.store.update("one", title="Changed", details=details)
            self.assertEqual(self.store.get("one")["title"], "Original")


if __name__ == "__main__":
    unittest.main()
