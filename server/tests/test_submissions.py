from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import tempfile
import threading
import unittest

from rivo_relay.database import Database
from rivo_relay.pairing import PairingStore
from rivo_relay.submissions import SubmissionConflict, SubmissionNotFound, SubmissionStore


class SubmissionTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "rivo.sqlite3"
        self.db = Database(self.path)
        self.addCleanup(self.db.close)
        self.pairing = PairingStore(self.db)
        self.owner = self.pairing.exchange(self.pairing.create()["code"], "Phone")["connection_id"]
        self.store = SubmissionStore(self.db)

    def claim(self, **overrides):
        args = {"submission_id": "request-1", "connection_id": self.owner,
                "operation": "turn/start", "target": {"project": "project-1", "thread": "thread-1"},
                "payload": {"input": "Do not persist this message", "model": "selected"}}
        args.update(overrides)
        return self.store.claim(**args)

    def test_claim_is_durable_before_dispatch_and_duplicate_never_dispatches(self):
        self.assertTrue(self.claim()["should_dispatch"])
        reopened = Database(self.path)
        self.addCleanup(reopened.close)
        observed = SubmissionStore(reopened).get("request-1")
        self.assertEqual(observed["status"], "pending")
        self.assertFalse(self.claim()["should_dispatch"])
        with self.db.read() as connection:
            self.assertNotIn("Do not persist this message", "\n".join(connection.iterdump()))

    def test_fixed_target_payload_operation_and_owner_are_enforced(self):
        self.claim()
        changes = ({"target": {"thread": "other"}}, {"payload": {"input": "different"}},
                   {"operation": "turn/interrupt"}, {"connection_id": "different-connection"})
        for change in changes:
            with self.subTest(change=change), self.assertRaises(SubmissionConflict):
                self.claim(**change)
        reordered = self.claim(target={"thread": "thread-1", "project": "project-1"},
                               payload={"model": "selected", "input": "Do not persist this message"})
        self.assertFalse(reordered["should_dispatch"])

    def test_concurrent_claims_only_dispatch_once(self):
        other_db = Database(self.path)
        self.addCleanup(other_db.close)
        other = SubmissionStore(other_db)
        barrier = threading.Barrier(2)

        def claim(store):
            barrier.wait()
            return store.claim("concurrent", self.owner, "turn/start", "thread", {"input": "test"})

        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(claim, (self.store, other)))
        self.assertEqual(sum(result["should_dispatch"] for result in results), 1)

    def test_startup_recovery_never_replays_unknown_submission(self):
        self.claim()
        self.claim(submission_id="accepted")
        receipt = {"thread_id": "thread-1", "turn_id": "turn-1"}
        self.store.mark_accepted("accepted", receipt)
        reopened = Database(self.path)
        self.addCleanup(reopened.close)
        recovered = SubmissionStore(reopened)
        self.assertEqual(recovered.recover_pending(), 1)
        self.assertEqual(recovered.get("request-1")["status"], "unknown")
        self.assertEqual(recovered.get("accepted")["receipt"], receipt)
        self.assertFalse(self.claim()["should_dispatch"])
        self.assertEqual(recovered.recover_pending(), 0)
        self.store.mark_accepted("request-1", receipt)
        self.assertFalse(self.claim()["should_dispatch"])

    def test_confirmed_result_cannot_be_overwritten_and_failed_cannot_retry(self):
        self.claim()
        self.store.mark_failed("request-1", "native_rejected")
        self.assertFalse(self.claim()["should_dispatch"])
        with self.assertRaises(SubmissionConflict):
            self.store.mark_unknown("request-1")
        with self.assertRaises(SubmissionConflict):
            self.store.mark_accepted("request-1", {"turn_id": "different"})
        self.assertEqual(self.store.mark_failed("request-1", "native_rejected")["status"], "failed")

    def test_record_lookup_is_scoped_and_revoked_owner_cannot_claim_new_work(self):
        self.claim()
        with self.assertRaises(SubmissionNotFound):
            self.store.get("request-1", connection_id="different")
        self.pairing.revoke(self.owner)
        with self.assertRaises(ValueError):
            self.claim(submission_id="after-revoke")

    def test_nonfinite_payload_is_rejected_before_claim(self):
        with self.assertRaises(ValueError):
            self.claim(payload={"number": float("nan")})
        with self.assertRaises(SubmissionNotFound):
            self.store.get("request-1")

    def test_image_sized_payload_only_persists_hash_and_has_eight_mib_limit(self):
        image = "data:image/png;base64," + "a" * (2 * 1024 * 1024)
        result = self.claim(payload={"input": [{"type": "image", "url": image}]})
        self.assertTrue(result["should_dispatch"])
        self.assertEqual(len(result["submission"]["payload_hash"]), 64)
        with self.db.read() as connection:
            dump = "\n".join(connection.iterdump())
            self.assertNotIn("data:image/png", dump)
            self.assertLess(len(dump), 20000)
        with self.assertRaises(ValueError):
            self.claim(submission_id="oversize", payload={"image": "a" * (8 * 1024 * 1024)})
        with self.assertRaises(SubmissionNotFound):
            self.store.get("oversize")

    def test_thread_checkpoint_is_durable_before_link_or_send(self):
        self.claim(target={"projectId": "project-1", "newThread": True})
        checkpoint = self.store.checkpoint("request-1", {"threadId": "created-thread"})
        self.assertEqual(checkpoint["status"], "pending")
        reopened = Database(self.path)
        self.addCleanup(reopened.close)
        observed = SubmissionStore(reopened)
        self.assertEqual(observed.get("request-1")["receipt"], {"threadId": "created-thread"})
        self.assertEqual(observed.recover_pending(), 1)
        recovered = observed.get("request-1")
        self.assertEqual(recovered["status"], "unknown")
        self.assertEqual(recovered["receipt"], {"threadId": "created-thread"})
        self.assertFalse(self.claim(target={"projectId": "project-1", "newThread": True})["should_dispatch"])

    def test_unknown_and_failed_transitions_preserve_thread_checkpoint(self):
        self.claim()
        self.store.checkpoint("request-1", {"threadId": "thread-1"})
        unknown = self.store.mark_unknown("request-1")
        self.assertEqual(unknown["receipt"], {"threadId": "thread-1"})
        self.assertEqual(self.store.mark_unknown("request-1"), unknown)
        failed = self.store.mark_failed("request-1", "native_rejected")
        self.assertEqual(failed["receipt"], {"threadId": "thread-1"})

    def test_acceptance_merges_with_checkpoint_and_preserves_identity(self):
        self.claim()
        self.store.checkpoint("request-1", {"threadId": "thread-1"})
        accepted = self.store.mark_accepted("request-1", {"turnId": "turn-1"})
        self.assertEqual(accepted["receipt"], {"threadId": "thread-1", "turnId": "turn-1"})
        self.assertEqual(self.store.mark_accepted("request-1", {"turnId": "turn-1"}), accepted)
        with self.assertRaises(SubmissionConflict):
            self.store.mark_accepted("request-1", {"thread_id": "different"})

    def test_checkpoint_rejects_identity_replacement_and_response_bodies(self):
        self.claim()
        self.store.checkpoint("request-1", {"threadId": "thread-1"})
        with self.assertRaises(SubmissionConflict):
            self.store.checkpoint("request-1", {"thread_id": "different"})
        for receipt in ({"text": "do not store this"}, {"threadId": {"id": "nested"}}, {}, None):
            with self.subTest(receipt=receipt), self.assertRaises(ValueError):
                self.store.checkpoint("request-1", receipt)
        self.assertEqual(self.store.get("request-1")["receipt"], {"threadId": "thread-1"})

    def test_late_checkpoint_does_not_turn_unknown_back_into_dispatchable(self):
        self.claim()
        self.store.mark_unknown("request-1")
        checkpoint = self.store.checkpoint("request-1", {"threadId": "thread-1"})
        self.assertEqual(checkpoint["status"], "unknown")
        self.assertFalse(self.claim()["should_dispatch"])

    def test_dispatch_receipt_keeps_todo_identity_without_allowing_empty_acceptance(self):
        self.claim()
        with self.assertRaises(ValueError):
            self.store.mark_accepted("request-1", None)
        self.assertEqual(self.store.get("request-1")["status"], "pending")
        self.store.checkpoint("request-1", {"threadId": "thread-1"})
        receipt = {"threadId": "thread-1", "turnId": "turn-1", "todoId": "todo-1"}
        self.assertEqual(self.store.mark_accepted("request-1", receipt)["receipt"], receipt)
        with self.assertRaises(SubmissionConflict):
            self.store.mark_accepted("request-1", {"todo_id": "different-todo"})

    def test_target_lookup_retains_prior_claims_for_restart_coordination(self):
        self.claim(operation="todo.dispatch", target="todo-1")
        self.store.checkpoint("request-1", {"threadId": "created-thread"})
        self.store.mark_unknown("request-1")
        self.claim(submission_id="different-operation", operation="thread.start", target="todo-1")
        self.claim(submission_id="different-target", operation="todo.dispatch", target="todo-2")
        reopened = Database(self.path)
        self.addCleanup(reopened.close)
        records = SubmissionStore(reopened).find_for_target("todo.dispatch", "todo-1")
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0]["id"], "request-1")
        self.assertEqual(records[0]["status"], "unknown")
        self.assertEqual(records[0]["receipt"], {"threadId": "created-thread"})


if __name__ == "__main__":
    unittest.main()
