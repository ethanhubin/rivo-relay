import asyncio
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
import zipfile
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from aiohttp import ClientSession, web
from rivo_relay.gateway.config import encode_key
from rivo_relay.gateway.server import Gateway
from rivo_relay.gateway.publish import install, collect, GRACE
from rivo_relay.gateway.update_store import manifest
from rivo_relay.gateway.updater import Supervisor, unpack
from rivo_relay.update_gate import UpdateGate


def release(directory, code):
    directory.mkdir(parents=True, exist_ok=True)
    item = {'schema': 1, 'version': f'1.0.{code}', 'versionCode': code, 'notes': 'test'}
    for kind, suffix in [('app', 'apk'), ('node', 'zip')]:
        raw = f'{kind}-{code}'.encode()
        digest = hashlib.sha256(raw).hexdigest()
        name = digest + '.' + suffix
        (directory / name).write_bytes(raw)
        item[kind] = {'sha256': digest, 'size': len(raw), 'path': '/v1/releases/files/' + name}
    item['node']['agentProtocol'] = 1
    (directory / 'latest.json').write_text(json.dumps(item))
    return item


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.workspace = self.root / 'gateway'

    def test_atomic_idempotent_and_no_downgrade_or_replacement(self):
        item = release(self.root / 'in', 2)
        install(self.root / 'in', self.workspace)
        install(self.root / 'in', self.workspace)
        for code in (1, 2):
            changed = release(self.root / 'in', code)
            changed['notes'] = 'changed'
            (self.root / 'in/latest.json').write_text(json.dumps(changed))
            with self.assertRaises(ValueError): install(self.root / 'in', self.workspace)
        self.assertEqual(manifest(self.workspace), item)

    def test_corrupt_or_low_space_never_changes_manifest(self):
        item = release(self.root / 'in', 1); install(self.root / 'in', self.workspace)
        next_item = release(self.root / 'in', 2)
        path = self.root / 'in' / next_item['node']['path'].split('/')[-1]
        path.write_bytes(b'corrupt')
        with self.assertRaises(ValueError): install(self.root / 'in', self.workspace)
        release(self.root / 'in', 2)
        with patch('rivo_relay.gateway.publish.shutil.disk_usage', return_value=SimpleNamespace(free=10)):
            with self.assertRaises(ValueError): install(self.root / 'in', self.workspace)
        with patch('rivo_relay.gateway.publish.MAX_STORE', 1):
            with self.assertRaises(ValueError): install(self.root / 'in', self.workspace)
        self.assertEqual(item, manifest(self.workspace))

    def test_retention_keeps_three_and_grace_then_collects(self):
        for code in range(1, 6):
            release(self.root / 'in', code); install(self.root / 'in', self.workspace)
        self.assertEqual(len(list((self.workspace / 'releases/files').iterdir())), 10)
        collect(self.workspace, now=time.time() + GRACE + 1)
        self.assertEqual(len(list((self.workspace / 'releases/files').iterdir())), 6)
        self.assertEqual(manifest(self.workspace)['versionCode'], 5)

    def test_grace_starts_when_current_is_replaced(self):
        for code in range(1, 5):
            release(self.root / 'in', code); install(self.root / 'in', self.workspace)
        current = self.workspace / 'releases/history/4.json'
        old_time = time.time() - GRACE * 3
        os.utime(current, (old_time, old_time))
        release(self.root / 'in', 5); install(self.root / 'in', self.workspace)
        self.assertGreater(current.stat().st_mtime, old_time + GRACE)

    def test_archive_rejects_traversal_symlinks_and_unexpected_root(self):
        for name, mode in [('../escape', 0), ('server/../../escape', 0), ('C:/escape', 0),
                           ('workspace/key', 0), ('server/link', 0o120777 << 16)]:
            with zipfile.ZipFile(self.root / 'bad.zip', 'w') as archive:
                entry = zipfile.ZipInfo(name); entry.external_attr = mode
                archive.writestr(entry, 'outside')
            with self.assertRaises(ValueError): unpack(self.root / 'bad.zip', self.root / 'out')
        self.assertFalse((self.root / 'escape').exists())


class GatewayStoreTests(unittest.IsolatedAsyncioTestCase):
    async def test_updates_available_with_all_nodes_offline_and_range(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            item = release(root / 'in', 1); install(root / 'in', root)
            (root / 'keys.json').write_text('{}')
            runner = web.AppRunner(Gateway(root / 'keys.json').application()); await runner.setup()
            site = web.TCPSite(runner, '127.0.0.1', 0); await site.start()
            base = 'http://127.0.0.1:' + str(site._server.sockets[0].getsockname()[1])
            try:
                async with ClientSession() as client:
                    async with client.get(base + '/v1/releases/latest') as response:
                        self.assertEqual(await response.json(), item)
                        self.assertEqual(response.headers['Cache-Control'], 'no-store')
                    async with client.get(base + '/v1/app-updates/latest') as response:
                        app = await response.json()
                    async with client.get(base + app['apkPath'], headers={'Range': 'bytes=0-2'}) as response:
                        self.assertEqual(response.status, 206)
                        self.assertEqual(await response.read(), b'app')
                    async with client.get(base + '/v1/releases/files/keys.json') as response:
                        self.assertEqual(response.status, 404)
            finally: await runner.cleanup()


class SupervisorTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        workspace = Path(self.temp.name)
        key = encode_key({'v': 2, 'slot': 'a'*32, 'secret': 'b'*40, 'pin': 'c'*64, 'gateway': 'https://127.0.0.1:80'})
        (workspace / 'gateway-client.json').write_text(json.dumps({'key': key}))
        self.agent = Supervisor(SimpleNamespace(workspace=workspace, attach=False, pair_only=False, configure=False, local_port=18765))
        self.candidate = {'versionCode': 2, 'python': '/candidate/python', 'sha256': 'a'*64}
        self.agent.local = AsyncMock(return_value={'ready': True})
        self.agent.exited = AsyncMock()
        self.agent.backup = Mock()
        self.agent.restore = Mock()
        self.agent.start = Mock()
        self.agent.healthy = AsyncMock()
        self.agent.cleanup = Mock()

    async def test_busy_does_not_stop_or_change_active(self):
        self.agent.local.side_effect = RuntimeError('busy')
        with self.assertRaises(RuntimeError): await self.agent.switch(self.candidate)
        self.agent.start.assert_not_called(); self.agent.backup.assert_not_called()
        self.assertEqual(self.agent.state['active']['versionCode'], 0)

    async def test_health_failure_restores_db_and_previous_then_records_bad_digest(self):
        self.agent.healthy.side_effect = [RuntimeError('bad candidate'), None]
        with self.assertRaises(RuntimeError): await self.agent.switch(self.candidate)
        self.agent.restore.assert_called_once()
        self.assertNotIn('pending', self.agent.state)
        self.assertEqual(self.agent.state['active']['versionCode'], 0)
        self.assertIn(self.candidate['sha256'], self.agent.state['failed'])
        self.assertEqual(self.agent.start.call_args_list[0].kwargs, {'trial': True})

    async def test_success_persists_before_admitting_work(self):
        async def activate(operation):
            if operation == 'activate':
                saved = json.loads(self.agent.state_file.read_text())
                self.assertEqual(saved['active'], self.candidate)
                self.assertNotIn('pending', saved)
        self.agent.local.side_effect = activate
        await self.agent.switch(self.candidate)
        self.agent.restore.assert_not_called()
        self.assertEqual(self.agent.state['previous']['versionCode'], 0)

    async def test_crash_pending_can_recover_without_candidate_process(self):
        self.agent.state['pending'] = self.candidate; self.agent.save()
        await self.agent.abort_trial()
        self.agent.restore.assert_called_once()
        self.agent.start.assert_called_once_with(self.agent.state['active'])


class GateTests(unittest.TestCase):
    def test_admission_lease_and_busy_native(self):
        import threading
        from contextlib import nullcontext
        db = Mock(); db.execute.return_value.fetchone.return_value = None
        app = SimpleNamespace(queue=SimpleNamespace(_tick_lock=threading.Lock()),
                              db=SimpleNamespace(read=lambda: nullcontext(db)),
                              codex=SimpleNamespace(idle_for_update=lambda: True))
        gate = UpdateGate()
        self.assertTrue(gate.enter()); self.assertFalse(gate.acquire(app)); gate.leave()
        app.codex.idle_for_update = lambda: False
        self.assertFalse(gate.acquire(app)); self.assertTrue(gate.enter()); gate.leave()
        app.codex.idle_for_update = lambda: True
        self.assertTrue(gate.acquire(app)); self.assertFalse(gate.enter())
        gate.until = 0
        self.assertTrue(gate.enter()); gate.leave()

class NativeIdleTests(unittest.TestCase):
    def test_live_turn_keys_are_thread_ids_and_owned_writers_survive_metadata_reset(self):
        import threading
        from rivo_relay.codex.sessions import CodexService
        service = CodexService.__new__(CodexService)
        service._lock = threading.RLock()
        service._joined = set(); service._active = {}; service._unmaterialized = set()
        service._live = {('live-thread', 'turn-1'): {'status': 'completed'}}
        service.connection = SimpleNamespace(status=lambda: {'connected': True}, update_threads=lambda: {'owned-thread'})
        service.get_session = Mock(return_value={'status': 'idle'})
        self.assertTrue(service.idle_for_update())
        self.assertEqual({call.args[0] for call in service.get_session.call_args_list}, {'live-thread', 'owned-thread'})
        service.get_session.return_value = {'status': 'idle', 'goal': {'status': 'active'}}
        self.assertFalse(service.idle_for_update())
        service.get_session.side_effect = RuntimeError('unknown native state')
        self.assertFalse(service.idle_for_update())

class ProcessLifecycleTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_child_switch_and_database_rollback(self):
        """Local fake native health only: real HTTP/process/SQLite, no user's Codex."""
        import socket
        import sqlite3
        import subprocess
        import sys
        from rivo_relay.config import Config
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            config = Config(workspace=root); config.prepare()
            with sqlite3.connect(config.database_path) as db:
                db.execute('CREATE TABLE marker(value TEXT)'); db.execute("INSERT INTO marker VALUES ('original')")
            db.close()
            key = encode_key({'v': 2, 'slot': 'a'*32, 'secret': 'b'*40, 'pin': 'c'*64, 'gateway': 'https://127.0.0.1:80'})
            (root / 'gateway-client.json').write_text(json.dumps({'key': key}))
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', 0)); port = probe.getsockname()[1]
            agent = Supervisor(SimpleNamespace(workspace=root, attach=False, pair_only=False, configure=False, local_port=port))
            script = root / 'fake.py'
            script.write_text('''
import json,sys,threading,sqlite3
from http.server import HTTPServer,BaseHTTPRequestHandler
from pathlib import Path
root,port,code,node,token=sys.argv[1:]
if code=='2':
    with sqlite3.connect(Path(root)/'data/relay.sqlite3') as db: db.execute("UPDATE marker SET value='candidate-migration'")
    sys.exit(1)
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        raw=json.dumps({'nodeId':node,'releaseCode':int(code),'nativeConnected':True}).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length','0')))
        self.send_response(200); self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.write(b'{"ready":true}')
        if self.path.endswith('update-stop'): threading.Thread(target=self.server.shutdown).start()
server=HTTPServer(('127.0.0.1',int(port)),Handler)
(Path(root)/'auto-update/connected.json').write_text(json.dumps({'token':token}))
server.serve_forever(poll_interval=.01)
''')
            def start(release, trial=False):
                (agent.root / 'connected.json').unlink(missing_ok=True)
                agent.child = subprocess.Popen([sys.executable, str(script), str(root), str(port), str(release['versionCode']), config.node_id, agent.token],
                                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            agent.start = start
            try:
                agent.start(agent.state['active']); await agent.healthy(agent.state['active'])
                candidate = {'python': sys.executable, 'versionCode': 1, 'sha256': '1'*64}
                await agent.switch(candidate)
                self.assertEqual((await agent.local())['releaseCode'], 1)
                broken = {'python': sys.executable, 'versionCode': 2, 'sha256': '2'*64}
                with self.assertRaises(RuntimeError): await agent.switch(broken)
                self.assertEqual((await agent.local())['releaseCode'], 1)
                with sqlite3.connect(config.database_path) as db:
                    self.assertEqual(db.execute('SELECT value FROM marker').fetchone()[0], 'original')
                db.close()
                self.assertEqual(json.loads(agent.state_file.read_text())['active']['versionCode'], 1)
            finally:
                if agent.child and agent.child.poll() is None: agent.child.terminate()
                await agent.exited()
