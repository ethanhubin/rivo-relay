#!/usr/bin/env python3
"""Transactional Relay LaunchAgent replacement; never restarts the native runtime."""
import argparse
import datetime
import fcntl
import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys
import uuid
import tempfile
import time
import urllib.request


def record(event, **fields):
    print(json.dumps(dict(time=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                          event=event, **fields)), flush=True)


def command(*args):
    result = subprocess.run(['launchctl', *args], capture_output=True, text=True, timeout=15)
    record('launchctl', operation=args[0], returncode=result.returncode,
           detail=result.stderr.strip()[:2000])
    return result.returncode


def install(path, data):
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as out:
        temp = Path(out.name)
        out.write(data)
        out.flush()
        os.fsync(out.fileno())
    os.chmod(temp, 0o600)
    temp.replace(path)


def stop(label):
    if command('print', label) != 0:
        return
    if command('bootout', label) != 0:
        raise RuntimeError('bootout_failed')
    # bootout returns before launchd has finished terminating/removing the job.
    # Do not let start() mistake that dying job for an already loaded service.
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if command('print', label) != 0:
            record('unload_complete')
            return
        time.sleep(0.2)
    raise RuntimeError('unload_timeout')


def start(domain, label, path, health):
    for attempt in range(6):
        if command('print', label) == 0 or command('bootstrap', domain, str(path)) == 0:
            break
        time.sleep(1 + attempt)
    else:
        raise RuntimeError('bootstrap_failed')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for attempt in range(20):
        try:
            with opener.open(health, timeout=2) as response:
                if json.load(response).get('status') == 'ok':
                    record('healthy')
                    return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError('health_failed')


def replace(path, candidate, health):
    new = candidate.read_bytes()
    config = plistlib.loads(new)
    if config['Label'] != 'local.rivo-relay':
        raise ValueError('Only local.rivo-relay may be replaced')
    for executable in (config['ProgramArguments'][0],):
        if not os.access(executable, os.X_OK):
            raise ValueError('Executable unavailable')
    source = config.get('EnvironmentVariables', {}).get('PYTHONPATH')
    if source and not Path(source, 'rivo_relay', '__main__.py').is_file():
        raise ValueError('Server snapshot unavailable')
    domain = f'gui/{os.getuid()}'
    label = domain + '/' + config['Label']
    with path.with_suffix('.restart.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        old = path.read_bytes()
        if plistlib.loads(old)['Label'] != config['Label']:
            raise ValueError('Service identity changed')
        backup = path.with_suffix('.rollback.plist')
        install(backup, old)
        record('preflight_complete')
        try:
            stop(label)
            install(path, new)
            start(domain, label, path, health)
            record('complete')
        except BaseException as original:
            record('deployment_failed', error=type(original).__name__)
            record('rollback_started')
            try:
                stop(label)
                install(path, old)
                start(domain, label, path, health)
                record('rollback_complete')
            except BaseException as rollback:
                record('rollback_failed', error=type(rollback).__name__)
                raise RuntimeError('Deployment and rollback failed; inspect deployment log and rollback.plist') from original
            raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', required=True, type=Path)
    parser.add_argument('--plist', type=Path, default=Path.home() / 'Library/LaunchAgents/local.rivo-relay.plist')
    parser.add_argument('--health', default='http://127.0.0.1:18765/health')
    parser.add_argument('--submit', action='store_true', help='Run under launchd so caller interruption cannot stop deployment')
    parser.add_argument('--log-dir', type=Path, help='Required with --submit; inspect deployment.log for complete/rollback events')
    args = parser.parse_args()
    if args.submit:
        if args.log_dir is None:
            parser.error('--submit requires --log-dir')
        args.log_dir.mkdir(parents=True, exist_ok=True)
        log = (args.log_dir / 'deployment.log').resolve()
        job = 'local.rivo-relay.deploy.' + uuid.uuid4().hex
        # launchd owns the worker; no service is stopped in the caller process.
        result = command('submit', '-l', job, '-o', str(log), '-e', str(log), '--',
                         sys.executable, str(Path(__file__).resolve()),
                         '--candidate', str(args.candidate.resolve()),
                         '--plist', str(args.plist.resolve()), '--health', args.health)
        if result:
            raise SystemExit(result)
        record('submitted', job=job, log=str(log))
    else:
        replace(args.plist, args.candidate, args.health)
