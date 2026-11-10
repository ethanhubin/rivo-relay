"""Stable supervisor for Relay + connector, with isolated installs and crash recovery.

Protocol 1 deliberately leaves the bootstrap environment unchanged. New release
code runs in a separate venv; no source checkout or shared Codex process is killed.
"""
from __future__ import annotations
import asyncio
from contextlib import closing
import json
import hashlib
import logging
import os
from pathlib import Path, PurePosixPath
import secrets
import signal
import shutil
import sqlite3
import subprocess
import sys
import time
import zipfile

import aiohttp
from .config import decode_key, save_private
from .update_status import AGENT_REVISION, is_ready
from .update_store import validate, MAX_PACKAGE
from ..codex import native_history_support as locks

log = logging.getLogger(__name__)


def unpack(package, target):
    with zipfile.ZipFile(package) as archive:
        entries = archive.infolist()
        if len(entries) > 10000 or sum(i.file_size for i in entries) > MAX_PACKAGE:
            raise ValueError('Expanded package too large')
        if shutil.disk_usage(Path(package).parent).free < sum(i.file_size for i in entries) + 1024 ** 3:
            raise UpdateFailure('insufficient_disk_space')
        names = set()
        for item in entries:
            path = PurePosixPath(item.filename)
            if (item.filename in names or '\\' in item.filename or ':' in item.filename
                    or path.is_absolute() or '..' in path.parts
                    or not path.parts or path.parts[0] not in ('server', 'release.json')
                    or ((item.external_attr >> 16) & 0o170000) == 0o120000):
                raise ValueError('Unsafe package entry')
            names.add(item.filename)
        archive.extractall(target)
    if not (target / 'server/pyproject.toml').is_file():
        raise ValueError('Missing server package')


def python_at(root):
    return root / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')


class UpdateFailure(RuntimeError):
    def __init__(self, reason, *, permanent=False):
        super().__init__(reason)
        self.reason, self.permanent = reason, permanent


class UpdateDeferred(UpdateFailure):
    pass


class Supervisor:
    def __init__(self, args):
        self.args = args
        self.workspace = args.workspace.resolve()
        self.root = self.workspace / 'auto-update'
        self.root.mkdir(parents=True, exist_ok=True)
        self.state_file = self.root / 'state.json'
        self.state = json.loads(self.state_file.read_text()) if self.state_file.exists() else {
            'active': {'python': sys.executable, 'versionCode': 0}}
        stored = json.loads((self.workspace / 'gateway-client.json').read_text())
        self.key = decode_key(stored['key'])
        if stored.get('mode') == 'direct' or args.attach or args.pair_only or args.configure:
            raise ValueError('Managed node requires an initialized VPS node and exclusive ownership')
        self.token = secrets.token_urlsafe(32)
        self.child = None
        self.output = None
        self.stop = asyncio.Event()
        self.phase = 'starting'
        self.latest_code = None
        self.state.setdefault('retries', {})
        self.state.setdefault('quarantine', {})

    def report(self, phase, reason=None, **fields):
        self.phase = phase
        value = {'agentRevision': AGENT_REVISION, 'phase': phase,
                 'activeCode': self.state['active']['versionCode'],
                 'latestCode': self.latest_code, 'updatedAt': time.time(), **fields}
        if reason:
            value['reason'] = reason
        save_private(self.root / 'status.json', value)
        log.info('Update phase=%s reason=%s', phase, reason or 'none')

    def failed_candidate(self, candidate, error):
        digest = candidate['sha256']
        reason = getattr(error, 'reason', 'interrupted_trial')
        if getattr(error, 'permanent', False):
            self.state['quarantine'][digest] = reason
            self.report('quarantined', reason)
        else:
            attempts = self.state['retries'].get(digest, {}).get('attempts', 0) + 1
            retry = {'attempts': attempts, 'retryAt': time.time() + (60 if attempts == 1 else 300) if attempts < 3 else None, 'reason': reason}
            self.state['retries'][digest] = retry
            self.report('retry_exhausted' if attempts >= 3 else 'retry_wait', reason,
                        attempts=attempts, retryAt=retry['retryAt'])
        self.save()

    def eligible(self, item):
        digest = item['node']['sha256']
        if digest in self.state['quarantine']:
            self.report('quarantined', self.state['quarantine'][digest])
            return False
        retry = self.state['retries'].get(digest, {})
        if retry.get('attempts', 0) >= 3 or retry.get('retryAt', 0) > time.time():
            self.report('retry_exhausted' if retry.get('attempts', 0) >= 3 else 'retry_wait',
                        retry.get('reason'), attempts=retry.get('attempts', 0), retryAt=retry.get('retryAt'))
            return False
        return True

    def save(self):
        from .publish import atomic
        atomic(self.state_file, (json.dumps(self.state) + '\n').encode())

    async def local(self, operation='health', *, lease=None):
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=3 if operation == 'health' else 10)) as client:
            url = f'http://127.0.0.1:{self.args.local_port}/'
            if operation == 'health':
                request = client.get(url + 'health', allow_redirects=False)
            else:
                request = client.post(url + 'internal/update-' + operation, json={'lease': lease} if lease else {},
                                      headers={'Authorization': 'Bearer ' + self.token}, allow_redirects=False)
            async with request as response:
                result = await response.json()
                if response.status == 409:
                    reason = result.get('reason')
                    allowed = {'active_requests', 'queue_check_busy', 'database_busy', 'queue_pending_or_unknown',
                               'submission_pending_or_unknown', 'native_busy_or_unverified', 'native_check_timeout', 'native_checking', 'lease_expired'}
                    raise UpdateDeferred(reason if reason in allowed else 'node_busy')
                if response.status != 200:
                    raise UpdateFailure('local_update_rejected')
                return result

    def start(self, release, trial=False):
        command = [release['python'], '-m', 'rivo_relay.gateway', 'node', '--workspace', str(self.workspace),
                   '--local-port', str(self.args.local_port), '--no-pair']
        for option in ('codex_runtime', 'codex_executable', 'codex_home', 'direct_url', 'mode', 'native_socket'):
            value = getattr(self.args, option, None)
            if value is not None:
                command += ['--' + option.replace('_', '-'), str(value)]
        ready = self.root / 'connected.json'
        ready.unlink(missing_ok=True)
        env = dict(os.environ, RIVO_MANAGED_READY=str(ready), RIVO_UPDATE_TOKEN=self.token, RIVO_MANAGED_NODE='1',
                   RIVO_RELEASE_CODE=str(release['versionCode']), RIVO_UPDATE_TRIAL='1' if trial else '0')
        env.pop('PYTHONPATH', None)
        path = self.root / 'node.log'
        if path.exists() and path.stat().st_size > 5 * 1024 * 1024:
            path.replace(path.with_suffix('.previous.log'))
        self.output = path.open('ab')
        kwargs = dict(env=env, cwd=self.root, stdin=subprocess.DEVNULL, stdout=self.output, stderr=self.output)
        if os.name == 'nt':
            from ..codex.windows_process import spawn_owned
            self.child = spawn_owned(command, **kwargs)
        else:
            self.child = subprocess.Popen(command, **kwargs)

    async def healthy(self, release, *, require_gateway=True):
        from ..config import Config
        identity = Config(workspace=self.workspace).node_id
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if self.child.poll() is not None:
                raise UpdateFailure('candidate_exited', permanent=True)
            try:
                result = await self.local()
                ready_path = self.root / 'connected.json'
                ready = not require_gateway or is_ready(ready_path, self.token)
                if not ready and release.get('readyProtocol', 1) < 2:
                    # Compatibility for the previously installed child only.
                    marker = json.loads(ready_path.read_text())
                    ready = 'connected' not in marker and marker.get('token') == self.token
                if (result.get('nodeId') == identity and result.get('releaseCode') == release['versionCode']
                        and result.get('nativeConnected') and ready):
                    return
            except (OSError, aiohttp.ClientError, RuntimeError, TimeoutError, ValueError):
                pass
            await asyncio.sleep(1)
        raise UpdateFailure('candidate_readiness_timeout')

    async def exited(self):
        if self.child:
            await asyncio.to_thread(self.child.wait, 30)
            job = getattr(self.child, '_rivo_job', None)
            if job:
                job.terminate(); job.close()
            self.child = None
        if self.output:
            self.output.close(); self.output = None

    def backup(self):
        source = self.workspace / 'data/relay.sqlite3'
        destination = self.root / 'rollback.sqlite3'
        with (self.workspace / 'data/serve.lock').open('a') as lock:
            locks.flock(lock, locks.LOCK_EX | locks.LOCK_NB)
            with closing(sqlite3.connect(source)) as db, closing(sqlite3.connect(destination)) as copy:
                db.backup(copy)

    def restore(self):
        destination = self.workspace / 'data/relay.sqlite3'
        with (self.workspace / 'data/serve.lock').open('a') as lock:
            locks.flock(lock, locks.LOCK_EX | locks.LOCK_NB)
            with closing(sqlite3.connect(self.root / 'rollback.sqlite3')) as source, closing(sqlite3.connect(destination)) as db:
                source.backup(db)

    async def prepare(self, client, item):
        asset = item['node']
        self.report('downloading')
        target = self.root / 'versions' / asset['sha256']
        ready = target / 'ready.json'
        if ready.exists():
            return json.loads(ready.read_text())
        if shutil.disk_usage(self.root).free < asset['size'] * 3 + 1024 ** 3:
            raise RuntimeError('Insufficient node disk space; preserve 1 GiB free')
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True)
        package = target / 'package.zip'
        digest = hashlib.sha256()
        total = 0
        async with client.get(self.key['gateway'] + asset['path'], allow_redirects=False) as response:
            if response.status != 200:
                raise RuntimeError('Package download failed')
            with package.open('wb') as stream:
                async for chunk in response.content.iter_chunked(65536):
                    total += len(chunk)
                    if total > asset['size']:
                        raise ValueError('Package exceeds declared size')
                    stream.write(chunk); digest.update(chunk)
        if total != asset['size'] or digest.hexdigest() != asset['sha256']:
            raise ValueError('Package integrity check failed')
        await asyncio.to_thread(unpack, package, target)
        metadata = json.loads((target / 'release.json').read_text())
        if metadata != {k: item[k] for k in ('version', 'versionCode')}:
            raise ValueError('Package version mismatch')
        self.report('installing')
        venv = target / 'venv'
        def install():
            with (target / 'install.log').open('ab') as output:
                for command in ([getattr(sys, '_base_executable', sys.executable), '-m', 'venv', str(venv)],
                                [str(python_at(venv)), '-m', 'pip', 'install', '--no-cache-dir', str(target / 'server') + '[gateway]'],
                                [str(python_at(venv)), '-c', 'import rivo_relay.gateway, rivo_relay.update_gate, aiohttp']):
                    subprocess.run(command, check=True, timeout=600, stdin=subprocess.DEVNULL, stdout=output, stderr=output)
        await asyncio.to_thread(install)
        release = {'python': str(python_at(venv)), 'versionCode': item['versionCode'], 'sha256': asset['sha256'],
                   'readyProtocol': 2 if (target / 'server/rivo_relay/gateway/update_status.py').exists() else 1}
        save_private(ready, release)
        return release

    async def switch(self, candidate):
        # No changes to the running process until download and installation finish.
        self.report('waiting_idle')
        permit = await self.local('lease')
        if not permit.get('ready'):
            raise UpdateDeferred('node_busy')
        try:
            await self.local('stop', lease=permit.get('lease'))
        except (TimeoutError, aiohttp.ClientError):
            # A response may be lost after the stop was accepted. Observe the
            # owned child before deciding whether it is safe to start another.
            for _ in range(30):
                if self.child.poll() is not None:
                    break
                await asyncio.sleep(.1)
            else:
                raise UpdateDeferred('stop_result_pending')
        await self.exited()
        await asyncio.to_thread(self.backup)
        self.state['pending'] = candidate
        self.save()  # Persist rollback intent before ever starting candidate code.
        try:
            self.report('trial')
            self.start(candidate, trial=True)
            await self.healthy(candidate)
        except Exception as error:
            await self.abort_trial(error)
            raise
        old = self.state['active']
        self.state['previous'] = old
        self.state['active'] = candidate
        self.state.pop('pending')
        try:
            self.save()
        except Exception:
            self.state['active'] = old
            self.state['pending'] = candidate
            await self.abort_trial()
            raise
        try:
            await self.local('activate')
        except Exception:
            # Committed version is good but still frozen. Restart it normally.
            self.child.terminate()
            await self.exited()
            self.start(candidate)
            await self.healthy(candidate)
        self.state['retries'].pop(candidate['sha256'], None)
        self.save()
        self.cleanup()
        self.report('idle')
        log.info('Node updated to code %s', candidate['versionCode'])

    def cleanup(self):
        protected = {self.state.get(k, {}).get('sha256') for k in ('active', 'previous', 'pending', 'staged')}
        versions = self.root / 'versions'
        import re
        for path in versions.iterdir() if versions.exists() else []:
            if path.name not in protected and re.fullmatch('[a-f0-9]{64}', path.name) and not path.is_symlink():
                shutil.rmtree(path)
        # Running child retains its descriptor; truncate only our own oversized log.
        log_path = self.root / 'node.log'
        if log_path.exists() and log_path.stat().st_size > 5 * 1024 * 1024:
            with log_path.open('r+b') as stream: stream.truncate(0)

    async def abort_trial(self, error=None):
        self.report("rolling_back")
        # A candidate is admission-frozen: it has not accepted new user work.
        if self.child and self.child.poll() is None:
            self.child.terminate()
        await self.exited()
        await asyncio.to_thread(self.restore)
        failed = self.state.pop('pending')
        try:
            self.failed_candidate(failed, error or UpdateFailure('interrupted_trial'))
        except BaseException:
            # Keep recovery intent in memory too: the outer loop must never
            # resume business against a rollback whose state was not persisted.
            self.state['pending'] = failed
            raise
        self.start(self.state['active'])
        await self.healthy(self.state['active'], require_gateway=False)
        log.error('Candidate failed; previous Relay restored')

    async def heartbeat(self):
        while not self.stop.is_set():
            try:
                path = self.root / 'status.json'
                value = json.loads(path.read_text())
                value['updatedAt'] = time.time()
                save_private(path, value)
            except (OSError, ValueError):
                pass
            try:
                await asyncio.wait_for(self.stop.wait(), 30)
            except TimeoutError:
                pass

    async def run(self):
        # Refuse to alter an existing service or restore over a live database.
        try:
            await self.local()
        except (OSError, aiohttp.ClientError, TimeoutError):
            pass
        else:
            raise RuntimeError('Stop the old node service before starting managed-node')
        self.save()
        if self.state.get('pending'):
            await self.abort_trial()
        else:
            self.start(self.state['active'])
            await self.healthy(self.state['active'], require_gateway=False)
        timeout = aiohttp.ClientTimeout(total=300)
        connector = aiohttp.TCPConnector(ssl=aiohttp.Fingerprint(bytes.fromhex(self.key['pin'])))
        async with aiohttp.ClientSession(connector=connector, timeout=timeout) as client:
            while not self.stop.is_set():
                self.cleanup()
                if self.child is None or self.child.poll() is not None:
                    await self.exited()
                    self.start(self.state['active'])
                    await self.healthy(self.state['active'], require_gateway=False)
                item = None
                try:
                    self.report('checking')
                    async with client.get(self.key['gateway'] + '/v1/releases/latest', allow_redirects=False) as response:
                        if response.status != 200:
                            raise RuntimeError('No central release available')
                        raw = b''
                        async for chunk in response.content.iter_chunked(8192):
                            raw += chunk
                            if len(raw) > 65536: break
                        if len(raw) > 65536:
                            raise ValueError('Manifest too large')
                        item = validate(json.loads(raw))
                    self.latest_code = item['versionCode']
                    if item['versionCode'] > self.state['active']['versionCode'] and self.eligible(item):
                        candidate = await self.prepare(client, item)
                        self.state['staged'] = candidate
                        self.save()
                        if not self.stop.is_set():
                            await self.switch(candidate)
                    elif item['versionCode'] <= self.state['active']['versionCode']:
                        self.report('idle')
                except UpdateDeferred as error:
                    self.report('waiting_idle', error.reason)
                except Exception as error:
                    if self.state.get('pending'):
                        raise  # Failed rollback must be recovered before another cycle.
                    log.warning('Update deferred: %s', type(error).__name__)
                    if self.phase not in ('retry_wait', 'retry_exhausted', 'quarantined', 'rolling_back'):
                        failure = (UpdateFailure('package_invalid', permanent=True) if isinstance(error, (ValueError, zipfile.BadZipFile))
                                   else UpdateFailure('install_failed' if self.phase == 'installing' else 'update_transport_failed'))
                        if item is not None:
                            self.failed_candidate({'sha256': item['node']['sha256']}, failure)
                        else:
                            self.report('retry_wait', failure.reason)
                    if self.child is None:
                        self.start(self.state['active'])
                        await self.healthy(self.state['active'], require_gateway=False)
                try:
                    await asyncio.wait_for(self.stop.wait(), 60)
                except TimeoutError:
                    pass


async def run(args):
    supervisor = Supervisor(args)
    loop = asyncio.get_running_loop()
    previous = {}
    for sig in (signal.SIGTERM, signal.SIGINT):
        previous[sig] = signal.signal(sig, lambda *_: loop.call_soon_threadsafe(supervisor.stop.set))
    with (supervisor.root / 'agent.lock').open('a') as lock:
        locks.flock(lock, locks.LOCK_EX | locks.LOCK_NB)
        heartbeat = asyncio.create_task(supervisor.heartbeat())
        try:
            await supervisor.run()
        finally:
            heartbeat.cancel()
            await asyncio.gather(heartbeat, return_exceptions=True)
            if supervisor.child and supervisor.child.poll() is None:
                supervisor.child.terminate()
            await supervisor.exited()
            for sig, handler in previous.items(): signal.signal(sig, handler)
