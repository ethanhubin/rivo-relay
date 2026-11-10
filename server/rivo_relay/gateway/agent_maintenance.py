"""Explicit, stopped-agent maintenance; never changes unknown submissions."""
from contextlib import contextmanager, ExitStack
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

from ..codex import native_history_support as locks
from .config import save_private
from .updater import python_at


@contextmanager
def stopped_agent(workspace):
    root = Path(workspace) / 'auto-update'
    with ExitStack() as stack:
        held = []
        try:
            paths = [root / 'agent.lock', Path(workspace) / 'data/serve.lock']
            for path in paths:
                if path.name == 'serve.lock' and not path.exists():
                    continue
                lock = stack.enter_context(path.open('a'))
                locks.flock(lock, locks.LOCK_EX | locks.LOCK_NB)
                held.append(lock)
            yield root
        finally:
            for lock in reversed(held):
                locks.flock(lock, locks.LOCK_UN)


def retry_candidate(workspace, digest):
    import re
    if not re.fullmatch('[a-f0-9]{64}', digest):
        raise ValueError('Specify the exact package SHA-256')
    with stopped_agent(workspace) as root:
        path = root / 'state.json'
        state = json.loads(path.read_text())
        if state.get('pending'):
            raise ValueError('Recover the pending trial before resetting retries')
        state.setdefault('quarantine', {}).pop(digest, None)
        state.setdefault('retries', {}).pop(digest, None)
        save_private(path, state)


def upgrade_bootstrap(workspace, source):
    """Preflight isolated wheels, then replace packages at the canonical venv path.

    The operator must stop the service first. Its registration and original venv
    path remain unchanged on every OS, so rollback restores the original paths.
    """
    with stopped_agent(workspace) as root:
        bootstrap = root / 'bootstrap'
        venv = bootstrap / 'venv'
        python = python_at(venv)
        if not python.is_file() or not (bootstrap / 'prepared.json').is_file():
            raise ValueError('Missing installed bootstrap')
        backup = bootstrap / 'venv.before-upgrade'
        if backup.exists():
            raise ValueError('Previous backup exists; inspect/recover it before another upgrade')
        size = sum(p.stat().st_size for p in venv.rglob('*') if p.is_file())
        if shutil.disk_usage(root).free < size * 3 + 1024 ** 3:
            raise ValueError('Insufficient space for bootstrap preflight and rollback')
        log = bootstrap / 'upgrade.log'
        with tempfile.TemporaryDirectory(dir=bootstrap, prefix='upgrade-') as temporary, log.open('ab') as output:
            temp = Path(temporary)
            wheels = temp / 'wheels'
            def run(args):
                subprocess.run([str(x) for x in args], check=True, timeout=600,
                               stdin=subprocess.DEVNULL, stdout=output, stderr=output)
            # All network/dependency resolution happens before the old venv changes.
            run([python, '-m', 'pip', 'wheel', '--no-cache-dir', '--wheel-dir', wheels, str(Path(source).resolve()) + '[gateway]'])
            probe = temp / 'probe'
            run([python, '-m', 'venv', probe])
            install = ['-m', 'pip', 'install', '--no-index', '--find-links', str(wheels), '--force-reinstall', 'rivo-relay[gateway]']
            verify = ['-c', 'from rivo_relay.gateway.update_status import AGENT_REVISION; from rivo_relay.gateway.updater import Supervisor; assert AGENT_REVISION >= 2']
            run([python_at(probe), *install]); run([python_at(probe), *verify])
            save_private(bootstrap / 'upgrade-state.json', {'phase': 'preparing_backup'})
            try:
                shutil.copytree(venv, backup, symlinks=True)
            except BaseException:
                if backup.exists(): shutil.rmtree(backup)
                raise
            save_private(bootstrap / 'upgrade-state.json', {'phase': 'installing', 'backup': backup.name})
            try:
                run([python, *install]); run([python, *verify])
            except BaseException:
                shutil.rmtree(venv)
                backup.rename(venv)
                save_private(bootstrap / 'upgrade-state.json', {'phase': 'rolled_back'})
                raise
            save_private(bootstrap / 'upgrade-state.json', {'phase': 'complete', 'backup': backup.name})


def restore_bootstrap(workspace):
    """Explicit recovery for interrupted installs, or rollback of a verified upgrade."""
    with stopped_agent(workspace) as root:
        bootstrap = root / 'bootstrap'
        backup = bootstrap / 'venv.before-upgrade'
        if not python_at(backup).is_file():
            raise ValueError('No complete bootstrap backup')
        state = json.loads((bootstrap / 'upgrade-state.json').read_text())
        if state.get('phase') not in ('installing', 'complete'):
            raise ValueError('No recoverable bootstrap transaction')
        venv = bootstrap / 'venv'
        if venv.exists():
            shutil.rmtree(venv)
        backup.rename(venv)
        save_private(bootstrap / 'upgrade-state.json', {'phase': 'rolled_back'})
