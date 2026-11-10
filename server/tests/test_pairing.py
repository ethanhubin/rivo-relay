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

    def test_cli_emits_v2_direct_pairing_link(self):
        import contextlib
        import io
        import json
        from urllib.parse import urlsplit, parse_qs
        from rivo_relay.__main__ import main
        from rivo_relay.config import Config
        from rivo_relay.gateway.config import pairing_route_binding
        import hashlib
        workspace = Path(self.directory.name) / 'cli'
        output = workspace / 'pair.json'
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, main(['pair', '--workspace', str(workspace), '--url',
                'https://node.example', '--json-output', str(output)]))
        fields = parse_qs(urlsplit(json.loads(output.read_text())['url']).fragment, keep_blank_values=True)
        self.assertEqual(['2'], fields['v'])
        self.assertEqual([Config(workspace=workspace).node_id], fields['node'])
        self.assertEqual(['https://node.example'], fields['direct'])
        self.assertEqual([''], fields['relay'])
        self.assertEqual([''], fields['pin'])
        self.assertTrue(fields['code'][0])
        self.assertEqual(hashlib.sha256(b'https://node.example\n\n').hexdigest(),
            pairing_route_binding(workspace, 'https://node.example'))

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

class PairingRouteUpgradeTests(unittest.TestCase):
    def test_route_upgrade_consumes_code_and_preserves_identity(self):
        import tempfile
        from pathlib import Path
        from rivo_relay.database import Database
        from rivo_relay.pairing import PairingStore, PairingError, route_proof
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            db = Database(Path(directory) / 'db.sqlite')
            try:
                store = PairingStore(db)
                original = store.exchange(store.create()['code'], 'phone')
                code = store.create()['code']
                def resume(code):
                    digest = hashlib.sha256(original['token'].encode()).hexdigest()
                    return dict(resume={'deviceId': original['connection_id'], 'binding': 'routes',
                        'proof': route_proof(digest, 'request', code, 'node', 'routes')}, node_id='node', route_binding='routes')
                upgraded = store.exchange(code, 'phone', **resume(code))
                self.assertEqual('', upgraded['token'])
                self.assertEqual(original['connection_id'], upgraded['connection_id'])
                self.assertEqual(len(store.list_connections()), 1)
                with self.assertRaises(PairingError):
                    store.exchange(code, 'phone', **resume(code))
                store.revoke(original['connection_id'])
                fresh_code = store.create()['code']
                fresh = store.exchange(fresh_code, 'phone', **resume(fresh_code))
                self.assertNotEqual(fresh['connection_id'], original['connection_id'])
            finally:
                db.close()

    def test_upgrade_proof_is_bound_to_routes_and_direction(self):
        import tempfile, hashlib
        from pathlib import Path
        from rivo_relay.database import Database
        from rivo_relay.pairing import PairingStore, route_proof
        with tempfile.TemporaryDirectory() as directory:
            db = Database(Path(directory) / 'db.sqlite')
            try:
                store = PairingStore(db)
                original = store.exchange(store.create()['code'], 'phone')
                code = store.create()['code']
                digest = hashlib.sha256(original['token'].encode()).hexdigest()
                resume = {'deviceId': original['connection_id'], 'binding': 'trusted-route',
                          'proof': route_proof(digest, 'request', code, 'node', 'trusted-route')}
                result = store.exchange(code, 'phone', resume=resume, node_id='node', route_binding='trusted-route')
                self.assertEqual(result['connection_id'], original['connection_id'])
                self.assertEqual(result['token'], '')
                self.assertEqual(result['reuseProof'], route_proof(digest, 'response', code, 'node', 'trusted-route'))
                self.assertNotEqual(result['reuseProof'], resume['proof'])
                code = store.create()['code']
                resume.update(binding='attacker-route', proof=route_proof(digest, 'request', code, 'node', 'attacker-route'))
                result = store.exchange(code, 'phone', resume=resume, node_id='node', route_binding='trusted-route')
                self.assertNotEqual(result['connection_id'], original['connection_id'])
                self.assertEqual(result['reuseProof'], '')
            finally:
                db.close()
