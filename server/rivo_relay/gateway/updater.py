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
import zipfile

import aiohttp
from .config import decode_key, save_private
from .update_store import validate, MAX_PACKAGE
from ..codex import native_history_support as locks

log = logging.getLogger(__name__)


def unpack(package, target):
    with zipfile.ZipFile(package) as archive:
        entries = archive.infolist()
        if len(entries) > 10000 or sum(i.file_size for i in entries) > MAX_PACKAGE:
            raise ValueError('Expanded package too large')
        if shutil.disk_usage(Path(package).parent).free < sum(i.file_size for i in entries) + 1024 ** 3:
            raise ValueError('Insufficient disk space for expanded package')
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


class Supervisor:
    def __init__(self, args):
        self.args = args
        self.workspace = args.workspace.resolve()
        self.root = self.workspace / 'auto-update'
        self.root.mkdir(parents=True, exist_ok=True)
        self.state_file = self.root / 'state.json'
        self.state = json.loads(self.state_file.read_text()) if self.state_file.exists() else {
            'active': {'python': sys.executable, 'versionCode': 0}, 'failed': []}
        stored = json.loads((self.workspace / 'gateway-client.json').read_text())
        self.key = decode_key(stored['key'])
        if stored.get('mode') == 'direct' or args.attach or args.pair_only or args.configure:
            raise ValueError('Managed node requires an initialized VPS node and exclusive ownership')
        self.token = secrets.token_urlsafe(32)
        self.child = None
        self.output = None
        self.stop = asyncio.Event()

    def save(self):
        from .publish import atomic
        atomic(self.state_file, (json.dumps(self.state) + '\n').encode())

    async def local(self, operation='health'):
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=20)) as client:
            url = f'http://127.0.0.1:{self.args.local_port}/'
            if operation == 'health':
                request = client.get(url + 'health', allow_redirects=False)
            else:
                request = client.post(url + 'internal/update-' + operation, json={},
                                      headers={'Authorization': 'Bearer ' + self.token}, allow_redirects=False)
            async with request as response:
                if response.status != 200:
                    raise RuntimeError('Node not ready for update')
                return await response.json()

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

    async def healthy(self, release):
        from ..config import Config
        identity = Config(workspace=self.workspace).node_id
        for _ in range(60):
            if self.child.poll() is not None:
                raise RuntimeError('Candidate exited')
            try:
                result = await self.local()
                if (result.get('nodeId') == identity and result.get('releaseCode') == release['versionCode']
                        and result.get('nativeConnected')
                        and (self.root / 'connected.json').exists()
                        and json.loads((self.root / 'connected.json').read_text()).get('token') == self.token):
                    return
            except (OSError, aiohttp.ClientError, RuntimeError, TimeoutError):
                pass
            await asyncio.sleep(1)
        raise RuntimeError('Candidate health check timed out')

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
        venv = target / 'venv'
        def install():
            with (target / 'install.log').open('ab') as output:
                for command in ([getattr(sys, '_base_executable', sys.executable), '-m', 'venv', str(venv)],
                                [str(python_at(venv)), '-m', 'pip', 'install', '--no-cache-dir', str(target / 'server') + '[gateway]'],
                                [str(python_at(venv)), '-c', 'import rivo_relay.gateway, rivo_relay.update_gate, aiohttp']):
                    subprocess.run(command, check=True, timeout=600, stdin=subprocess.DEVNULL, stdout=output, stderr=output)
        await asyncio.to_thread(install)
        release = {'python': str(python_at(venv)), 'versionCode': item['versionCode'], 'sha256': asset['sha256']}
        save_private(ready, release)
        return release

    async def switch(self, candidate):
        # No changes to the running process until download and installation finish.
        await self.local('stop')  # Atomic admission close + idle check + graceful shutdown.
        await self.exited()
        await asyncio.to_thread(self.backup)
        self.state['pending'] = candidate
        self.save()  # Persist rollback intent before ever starting candidate code.
        try:
            self.start(candidate, trial=True)
            await self.healthy(candidate)
        except Exception:
            await self.abort_trial()
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
        self.cleanup()
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

    async def abort_trial(self):
        # A candidate is admission-frozen: it has not accepted new user work.
        if self.child and self.child.poll() is None:
            self.child.terminate()
        await self.exited()
        await asyncio.to_thread(self.restore)
        failed = self.state.pop('pending')
        self.state['failed'] = (self.state['failed'] + [failed['sha256']])[-100:]
        self.save()
        self.start(self.state['active'])
        await self.healthy(self.state['active'])
        log.error('Candidate failed; previous Relay restored')

    async def run(self):
        # Refuse to alter an existing service or restore over a live database.
        try:
            await self.local()
        except (OSError, aiohttp.ClientError, TimeoutError):
            pass
        else:
            raise RuntimeError('Stop the old node service before starting managed-node')
        if self.state.get('pending'):
            await self.abort_trial()
        else:
            self.start(self.state['active'])
            await self.healthy(self.state['active'])
        timeout = aiohttp.ClientTimeout(total=300)
        connector = aiohttp.TCPConnector(ssl=aiohttp.Fingerprint(bytes.fromhex(self.key['pin'])))
        async with aiohttp.ClientSession(connector=connector, timeout=timeout) as client:
            while not self.stop.is_set():
                self.cleanup()
                if self.child.poll() is not None:
                    raise RuntimeError('Managed node exited; service manager should restart the agent')
                try:
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
                    if (item['versionCode'] > self.state['active']['versionCode']
                            and item['node']['sha256'] not in self.state['failed']):
                        candidate = await self.prepare(client, item)
                        self.state['staged'] = candidate
                        self.save()
                        if not self.stop.is_set():
                            await self.switch(candidate)
                except Exception as error:
                    log.warning('Update deferred: %s', type(error).__name__)
                    if self.child is None:
                        self.start(self.state['active'])
                        await self.healthy(self.state['active'])
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
        try:
            await supervisor.run()
        finally:
            if supervisor.child and supervisor.child.poll() is None:
                supervisor.child.terminate()
            await supervisor.exited()
            for sig, handler in previous.items(): signal.signal(sig, handler)
