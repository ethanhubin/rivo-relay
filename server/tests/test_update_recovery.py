import asyncio
import json
import os
from pathlib import Path
import socket
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
import zipfile
from unittest.mock import AsyncMock, Mock, patch

from rivo_relay.gateway.config import encode_key
from rivo_relay.gateway.updater import Supervisor, UpdateFailure, UpdateDeferred, unpack
from rivo_relay.gateway.update_status import write_ready, is_ready, public_status
from rivo_relay.gateway.agent_maintenance import retry_candidate, upgrade_bootstrap, restore_bootstrap, python_at
from rivo_relay.update_gate import UpdateGate


class RecoveryTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.workspace = Path(temp.name)
        key = encode_key({'v': 2, 'slot': 'a'*32, 'secret': 'b'*40, 'pin': 'c'*64, 'gateway': 'https://127.0.0.1:80'})
        (self.workspace/'gateway-client.json').write_text(json.dumps({'key': key}))
        self.args = SimpleNamespace(workspace=self.workspace, local_port=18765, attach=False, pair_only=False, configure=False)
        self.agent = Supervisor(self.args)
        self.candidate = {'python': '/candidate/python', 'versionCode': 96, 'sha256': 'a'*64, 'readyProtocol': 2}
        self.item = {'versionCode': 96, 'node': {'sha256': 'a'*64}}

    def test_unpack_disk_pressure_is_retryable(self):
        package = self.workspace / 'package.zip'
        with zipfile.ZipFile(package, 'w') as archive:
            archive.writestr('server/pyproject.toml', '')
        with patch('rivo_relay.gateway.updater.shutil.disk_usage', return_value=SimpleNamespace(free=0)):
            with self.assertRaises(UpdateFailure) as caught:
                unpack(package, self.workspace / 'expanded')
        self.assertFalse(caught.exception.permanent)
        self.agent.failed_candidate(self.candidate, caught.exception)
        self.assertNotIn('a'*64, self.agent.state['quarantine'])
        self.assertEqual('retry_wait', self.agent.phase)

    def test_transient_failure_retries_then_stops_without_quarantine(self):
        with patch('rivo_relay.gateway.updater.time.time', return_value=100):
            self.agent.failed_candidate(self.candidate, UpdateFailure('candidate_readiness_timeout'))
            self.assertFalse(self.agent.eligible(self.item))
        self.assertNotIn('a'*64, self.agent.state['failed'])
        with patch('rivo_relay.gateway.updater.time.time', return_value=161):
            self.assertTrue(self.agent.eligible(self.item))
        self.agent.failed_candidate(self.candidate, UpdateFailure('candidate_readiness_timeout'))
        self.agent.failed_candidate(self.candidate, UpdateFailure('candidate_readiness_timeout'))
        with patch('rivo_relay.gateway.updater.time.time', return_value=10**12):
            self.assertFalse(self.agent.eligible(self.item))
        self.assertEqual(public_status(self.workspace)['phase'], 'retry_exhausted')

    def test_permanent_failure_never_automatically_retries(self):
        self.agent.failed_candidate(self.candidate, UpdateFailure('candidate_exited', permanent=True))
        self.assertFalse(self.agent.eligible(self.item))
        self.assertIn('a'*64, self.agent.state['failed'])

    def test_old_undifferentiated_failure_gets_bounded_reassessment(self):
        self.agent.state['failed'] = ['a'*64]
        self.agent.state.pop('recoverySchema')
        self.agent.save()
        restored = Supervisor(self.args)
        self.assertTrue(restored.eligible(self.item))
        self.assertEqual(restored.state['retries']['a'*64]['reason'], 'legacy_failure')

    async def test_network_timeout_rolls_back_and_retains_retry(self):
        self.agent.local = AsyncMock(return_value={'ready': True, 'lease': 'permit'})
        self.agent.exited = AsyncMock(); self.agent.backup = Mock(); self.agent.restore = Mock()
        self.agent.start = Mock(); self.agent.cleanup = Mock()
        self.agent.healthy = AsyncMock(side_effect=[UpdateFailure('candidate_readiness_timeout'), None])
        with self.assertRaises(UpdateFailure):
            await self.agent.switch(self.candidate)
        self.assertEqual(self.agent.state['active']['versionCode'], 0)
        self.agent.restore.assert_called_once()
        self.assertNotIn('pending', self.agent.state)
        self.assertNotIn('a'*64, self.agent.state['failed'])
        self.assertEqual(self.agent.state['retries']['a'*64]['attempts'], 1)

    async def test_failed_rollback_persistence_keeps_pending_and_does_not_start_old(self):
        self.agent.state['pending'] = self.candidate
        self.agent.exited = AsyncMock(); self.agent.restore = Mock(); self.agent.start = Mock()
        self.agent.failed_candidate = Mock(side_effect=OSError('disk unavailable'))
        with self.assertRaises(OSError): await self.agent.abort_trial()
        self.assertEqual(self.agent.state['pending'], self.candidate)
        self.agent.start.assert_not_called()

    async def test_lost_stop_response_reconciles_child_exit(self):
        async def local(operation='health', **kwargs):
            if operation == 'stop':
                self.assertEqual(kwargs['lease'], 'permit')
                raise TimeoutError()
            return {'ready': True, 'lease': 'permit'}
        self.agent.local = local
        self.agent.child = Mock(poll=Mock(return_value=0))
        self.agent.exited = AsyncMock(); self.agent.backup = Mock(); self.agent.start = Mock()
        self.agent.healthy = AsyncMock(); self.agent.cleanup = Mock()
        await self.agent.switch(self.candidate)
        self.assertEqual(self.agent.state['active']['versionCode'], 96)
        self.agent.start.assert_called_once_with(self.candidate, trial=True)

    async def test_lost_stop_response_does_not_start_duplicate(self):
        async def local(operation='health', **kwargs):
            if operation == 'stop': raise TimeoutError()
            return {'ready': True, 'lease': 'permit'}
        self.agent.local = local
        self.agent.child = Mock(poll=Mock(return_value=None)); self.agent.start = Mock()
        with patch('rivo_relay.gateway.updater.asyncio.sleep', new=AsyncMock()):
            with self.assertRaises(UpdateDeferred): await self.agent.switch(self.candidate)
        self.agent.start.assert_not_called()
        self.assertEqual(self.agent.state['active']['versionCode'], 0)

    async def test_late_connection_satisfies_candidate_health(self):
        from rivo_relay.config import Config
        self.agent.child = Mock(poll=Mock(return_value=None))
        self.agent.local = AsyncMock(return_value={'nodeId': Config(workspace=self.workspace).node_id,
            'releaseCode': 96, 'nativeConnected': True})
        path = self.agent.root/'connected.json'
        write_ready(path, self.agent.token, False)
        async def reconnect(_):
            write_ready(path, self.agent.token, True)
        with patch('rivo_relay.gateway.updater.asyncio.sleep', new=reconnect):
            await self.agent.healthy(self.candidate)
        self.assertGreaterEqual(self.agent.local.await_count, 2)

    def test_ready_marker_follows_disconnect_and_rejects_old_generation(self):
        path = self.agent.root/'connected.json'
        write_ready(path, 'old', True)
        self.assertFalse(is_ready(path, 'new'))
        write_ready(path, 'new', True)
        self.assertTrue(is_ready(path, 'new'))
        with patch('rivo_relay.gateway.update_status.time.time', return_value=time.time()+16):
            self.assertFalse(is_ready(path, 'new'))
        write_ready(path, 'new', False)
        self.assertFalse(is_ready(path, 'new'))

    def test_public_status_never_exposes_configuration_or_token(self):
        (self.agent.root/'status.json').write_text(json.dumps({'phase':'installing','updatedAt':time.time(),
            'token':'secret','python':'/private/path','key':'private'}))
        result = public_status(self.workspace)
        self.assertEqual(set(result), {'phase','updatedAt','stale'})

    async def test_existing_node_can_stay_online_while_gateway_is_unavailable(self):
        from rivo_relay.config import Config
        self.agent.child = Mock(poll=Mock(return_value=None))
        self.agent.local = AsyncMock(return_value={'nodeId': Config(workspace=self.workspace).node_id,
            'releaseCode': 96, 'nativeConnected': True})
        await self.agent.healthy(self.candidate, require_gateway=False)
        self.agent.local.assert_awaited_once()

    def test_retry_refuses_running_agent_lock(self):
        from rivo_relay.codex import native_history_support as locks
        self.agent.save()
        before = self.agent.state_file.read_bytes()
        with (self.agent.root / 'agent.lock').open('a') as lock:
            locks.flock(lock, locks.LOCK_EX | locks.LOCK_NB)
            try:
                with self.assertRaises(BlockingIOError): retry_candidate(self.workspace, 'a'*64)
            finally:
                locks.flock(lock, locks.LOCK_UN)
        self.assertEqual(self.agent.state_file.read_bytes(), before)

    def test_explicit_retry_keeps_other_candidates_and_user_work(self):
        self.agent.state.update(failed=['a'*64,'b'*64], quarantine={'a'*64:'bad','b'*64:'bad'},
                                retries={'a'*64:{'attempts':3}}, unrelated={'preserve':True})
        self.agent.save()
        retry_candidate(self.workspace, 'a'*64)
        state = json.loads(self.agent.state_file.read_text())
        self.assertEqual(state['failed'], ['b'*64])
        self.assertEqual(state['unrelated'], {'preserve':True})


class LeaseTests(unittest.TestCase):
    def app(self):
        from contextlib import nullcontext
        db = Mock(); db.execute.return_value.fetchone.return_value = None
        return SimpleNamespace(queue=SimpleNamespace(_tick_lock=threading.Lock()),
            db=SimpleNamespace(read=lambda: nullcontext(db)),codex=SimpleNamespace(idle_for_update=lambda:True))

    def test_expired_lease_cannot_stop_node(self):
        app=self.app(); gate=UpdateGate()
        self.assertTrue(gate.acquire(app)); lease=gate.lease
        gate.until=time.monotonic()-1
        self.assertFalse(gate.consume(lease)); self.assertTrue(gate.enter());gate.leave()

    def test_lease_is_single_use_and_freezes_admission(self):
        gate=UpdateGate(); self.assertTrue(gate.acquire(self.app())); lease=gate.lease
        self.assertFalse(gate.enter());self.assertTrue(gate.consume(lease))
        self.assertFalse(gate.consume(lease)); self.assertFalse(gate.enter())

    def test_queue_lock_contention_returns_immediately(self):
        app=self.app();app.queue._tick_lock.acquire();gate=UpdateGate()
        started=time.monotonic()
        try:self.assertFalse(gate.acquire(app))
        finally:app.queue._tick_lock.release()
        self.assertLess(time.monotonic()-started,.5)
        self.assertEqual(gate.reason,'queue_check_busy');self.assertTrue(gate.enter());gate.leave()

    def test_slow_native_check_does_not_hold_admission_lock_or_issue_late_lease(self):
        app = self.app(); gate = UpdateGate()
        entered, release = threading.Event(), threading.Event()
        def slow():
            entered.set(); release.wait(2); return True
        app.codex.idle_for_update = slow
        result = []
        worker = threading.Thread(target=lambda: result.append(gate.acquire(app)))
        worker.start()
        try:
            self.assertTrue(entered.wait(1))
            self.assertTrue(gate.lock.acquire(timeout=.1))
            gate.lock.release()
            self.assertFalse(gate.enter())
            # Simulate the reservation expiring while metadata is unavailable.
            gate.until = time.monotonic() - 1
            self.assertTrue(gate.enter()); gate.leave()
        finally:
            release.set(); worker.join(2)
        self.assertEqual(result, [False])

    def test_unknown_submission_remains_blocking_with_reason(self):
        app=self.app(); db=app.db.read().__enter__()
        db.execute.return_value.fetchone.side_effect=[None,(1,)]
        gate=UpdateGate(); self.assertFalse(gate.acquire(app))
        self.assertEqual(gate.reason,'submission_pending_or_unknown')


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup)
        self.workspace=Path(temp.name)
        self.bootstrap=self.workspace/'auto-update/bootstrap'
        self.venv=self.bootstrap/'venv'; python_at(self.venv).parent.mkdir(parents=True)
        python_at(self.venv).write_text('original')
        (self.bootstrap/'prepared.json').write_text('[]')

    def test_failed_upgrade_restores_original_environment(self):
        def execute(args, **kwargs):
            if args[0]==str(python_at(self.venv)) and '--force-reinstall' in args:
                python_at(self.venv).write_text('damaged')
                raise RuntimeError('install failed')
        with patch('rivo_relay.gateway.agent_maintenance.subprocess.run', side_effect=execute):
            with self.assertRaises(RuntimeError):upgrade_bootstrap(self.workspace, self.workspace/'server')
        self.assertEqual(python_at(self.venv).read_text(),'original')
        self.assertEqual(json.loads((self.bootstrap/'upgrade-state.json').read_text())['phase'],'rolled_back')

    def test_success_retains_recoverable_backup_and_can_restore(self):
        def execute(args, **kwargs):
            if args[0]==str(python_at(self.venv)) and '--force-reinstall' in args:
                python_at(self.venv).write_text('upgraded')
        with patch('rivo_relay.gateway.agent_maintenance.subprocess.run', side_effect=execute):
            upgrade_bootstrap(self.workspace, self.workspace/'server')
        self.assertEqual(python_at(self.venv).read_text(),'upgraded')
        restore_bootstrap(self.workspace)
        self.assertEqual(python_at(self.venv).read_text(),'original')

    def test_partial_backup_is_not_used_for_recovery(self):
        backup = self.bootstrap / 'venv.before-upgrade'
        python_at(backup).parent.mkdir(parents=True)
        python_at(backup).write_text('partial')
        (self.bootstrap / 'upgrade-state.json').write_text(json.dumps({'phase':'preparing_backup'}))
        with self.assertRaises(ValueError): restore_bootstrap(self.workspace)
        self.assertEqual(python_at(self.venv).read_text(), 'original')

    def test_preflight_failure_leaves_old_environment_untouched(self):
        with patch('rivo_relay.gateway.agent_maintenance.subprocess.run', side_effect=RuntimeError('network')):
            with self.assertRaises(RuntimeError):upgrade_bootstrap(self.workspace,self.workspace/'server')
        self.assertEqual(python_at(self.venv).read_text(),'original')
        self.assertFalse((self.bootstrap/'venv.before-upgrade').exists())
