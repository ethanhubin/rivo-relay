from hashlib import sha256
import json
from pathlib import Path
import stat
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest

from rivo_relay.config import Config
from rivo_relay.database import Database
from rivo_relay.dsh.admin import serve_local_admin
from rivo_relay.dsh.transport import UnixHTTPConnection
from rivo_relay.pairing import PairingStore, route_proof
from rivo_relay.update_gate import UpdateGate


GENERATION = 'bf696ee3-287d-4f04-aa2e-cb6b08a1cacc'


class DshAdminTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(self.enterContext(TemporaryDirectory(dir='/tmp')))
        self.socket = self.root / 'admin.sock'
        config = Config(workspace=self.root, runtime='dsh', dsh_bridge_socket=self.root / 'bridge.sock',
                        dsh_admin_socket=self.socket)
        config.prepare()
        self.db = Database(config.database_path)
        self.addCleanup(self.db.close)
        self.now = 1200
        self.app = SimpleNamespace(config=config, pairing=PairingStore(self.db, clock=lambda: self.now),
                                   update_gate=UpdateGate(), backend=SimpleNamespace(pairing_status=self.bridge_state))
        self.addCleanup(serve_local_admin(self.app, self.socket))

    def bridge_state(self, generation):
        return 'ready' if generation == GENERATION else 'wrong_bridge'

    def post(self, path, **fields):
        connection = UnixHTTPConnection(str(self.socket), timeout=2)
        try:
            connection.request('POST', path, body=json.dumps({'generation': GENERATION, **fields}),
                               headers={'Content-Type': 'application/json'})
            response = connection.getresponse()
            self.assertEqual('no-store', response.headers['Cache-Control'])
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_admin_pairing_status_and_route_identity(self):
        self.assertEqual(0o600, stat.S_IMODE(self.socket.lstat().st_mode))
        with self.assertRaisesRegex(ValueError, 'already exists'):
            serve_local_admin(self.app, self.socket)
        wrong_bridge = '00000000-0000-0000-0000-000000000000'
        self.assertEqual((409, {'state': 'wrong_bridge', 'directUrl': ''}), self.post('/pair', generation=wrong_bridge))
        status, pairing = self.post('/pair')
        self.assertEqual(200, status)
        self.assertEqual('ready', pairing['state'])
        self.assertEqual(self.app.config.public_url, pairing['directUrl'])
        self.assertTrue(pairing['png'].startswith('iVBORw0KGgo'))
        self.assertNotIn('code', pairing)
        pairing_id = pairing['pairingId']
        self.assertEqual('pending', self.post('/status', pairingId=pairing_id)[1]['pairingState'])
        self.now = pairing['expiresAt']
        self.assertEqual('expired', self.post('/status', pairingId=pairing_id)[1]['pairingState'])

    def test_direct_pairing_preserves_phone_identity_on_a_node_with_other_routes(self):
        (self.root / 'gateway-client.json').write_text('{"mode":"relay"}')
        store = self.app.pairing
        existing = store.exchange(store.create()['code'], 'Phone')
        created = []
        issue = store.create

        def capture_code(**kwargs):
            result = issue(**kwargs)
            created.append(result['code'])
            return result

        store.create = capture_code
        status, pairing = self.post('/pair')
        self.assertEqual(200, status)
        binding = sha256((self.app.config.public_url + '\n\n').encode()).hexdigest()
        digest = sha256(existing['token'].encode()).hexdigest()
        code, = created
        resume = {'deviceId': existing['connection_id'], 'binding': binding,
                  'proof': route_proof(digest, 'request', code, self.app.config.node_id, binding)}
        paired = store.exchange(code, 'Phone', resume=resume, node_id=self.app.config.node_id,
                                route_binding='other-route')
        self.assertEqual(existing['connection_id'], paired['connection_id'])
        self.assertEqual('', paired['token'])
        self.assertEqual('consumed', self.post('/status', pairingId=pairing['pairingId'])[1]['pairingState'])
