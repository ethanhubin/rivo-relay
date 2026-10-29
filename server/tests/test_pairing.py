from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import tempfile
import threading
import unittest

from rivo_relay.database import Database
from rivo_relay.pairing import PairingError, PairingRateLimited, PairingStore


class PairingTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "rivo.sqlite3"
        self.db = Database(self.path)
        self.addCleanup(self.db.close)
        self.now = 1000.0
        self.store = PairingStore(self.db, clock=lambda: self.now)

    def test_one_time_exchange_normalization_and_digest_only_storage(self):
        pairing = self.store.create()
        self.assertEqual(pairing["expires_at"], 1300)
        paired = self.store.exchange(pairing["code"].lower().replace("-", " "), "Pixel")
        self.assertEqual(self.store.authenticate(paired["token"])["name"], "Pixel")
        with self.assertRaises(PairingError):
            self.store.exchange(pairing["code"], "Other phone")
        with self.db.read() as connection:
            contents = "\n".join(connection.iterdump())
        self.assertNotIn(pairing["code"], contents)
        self.assertNotIn(pairing["code"].replace("-", ""), contents)
        self.assertNotIn(paired["token"], contents)
        self.assertNotIn("token_hash", self.store.list_connections()[0])

    def test_expiry_rejects_exact_expiration_boundary(self):
        pairing = self.store.create()
        self.now = pairing["expires_at"]
        with self.assertRaises(PairingError):
            self.store.exchange(pairing["code"], "Pixel")
        self.assertEqual(self.store.list_connections(), [])

    def test_only_one_concurrent_exchange_across_database_connections(self):
        other_db = Database(self.path)
        self.addCleanup(other_db.close)
        other_store = PairingStore(other_db, clock=lambda: self.now)
        code = self.store.create()["code"]
        barrier = threading.Barrier(2)

        def exchange(store):
            barrier.wait()
            try:
                return store.exchange(code, "Phone")
            except PairingError:
                return None

        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(exchange, (self.store, other_store)))
        self.assertEqual(sum(result is not None for result in results), 1)
        self.assertEqual(len(self.store.list_connections()), 1)

    def test_revocation_is_durable_and_independent_of_other_connections(self):
        first = self.store.exchange(self.store.create()["code"], "First")
        second = self.store.exchange(self.store.create()["code"], "Second")
        self.assertTrue(self.store.revoke(first["connection_id"]))
        self.assertTrue(self.store.revoke(first["connection_id"]))
        self.assertFalse(self.store.revoke("missing"))
        reopened = Database(self.path)
        self.addCleanup(reopened.close)
        restored = PairingStore(reopened)
        self.assertIsNone(restored.authenticate(first["token"]))
        self.assertIsNotNone(restored.authenticate(second["token"]))
        self.assertEqual(len(restored.list_connections()), 1)
        self.assertEqual(len(restored.list_connections(include_revoked=True)), 2)

    def test_attempt_limits_cover_unknown_codes_and_release_after_window(self):
        limited = PairingStore(self.db, clock=lambda: self.now, attempt_limit=2, global_attempt_limit=3)
        for _ in range(2):
            with self.assertRaises(PairingError):
                limited.exchange("wrong", "Phone", client_key="address-a")
        with self.assertRaises(PairingRateLimited) as error:
            limited.exchange("wrong", "Phone", client_key="address-a")
        self.assertEqual(error.exception.retry_after, 60)
        with self.assertRaises(PairingError):
            limited.exchange("wrong", "Phone", client_key="address-b")
        with self.assertRaises(PairingRateLimited):
            limited.exchange("wrong", "Phone", client_key="address-c")
        self.now += 60
        paired = limited.exchange(limited.create()["code"], "Phone", client_key="address-a")
        self.assertIsNotNone(limited.authenticate(paired["token"]))

    def test_invalid_names_do_not_consume_code(self):
        code = self.store.create()["code"]
        with self.assertRaises(ValueError):
            self.store.exchange(code, " ")
        self.assertIsNotNone(self.store.exchange(code, "Valid"))


if __name__ == "__main__":
    unittest.main()
