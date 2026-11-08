"""Noninteractive Task Scheduler supervisor and worker; no console window."""
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import time


def main():
    if os.name == 'nt':
        import ctypes
        # Suppress OS fault dialogs in this process and its children.
        ctypes.windll.kernel32.SetErrorMode(0x0001 | 0x0002 | 0x8000)
    config_path = Path(sys.argv[1]).resolve()
    config = json.loads(config_path.read_text(encoding='utf-8-sig'))
    root = Path(config['repository'])
    os.chdir(root)
    sys.path.insert(0, str(root / 'server'))
    os.environ['PYTHONUTF8'] = '1'
    if '--worker' not in sys.argv[2:]:
        # Task Scheduler does not reliably restart externally terminated actions.
        # Keep the actual server in an owned Job; stopping this supervisor closes
        # the Job handle and kills its worker and runtime descendants.
        from rivo_relay.codex.windows_process import spawn_owned
        failures = []
        while True:
            child = spawn_owned([sys.executable, str(Path(__file__).resolve()), str(config_path), '--worker'],
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                result = child.wait()
            finally:
                child._rivo_job.terminate()
                child._rivo_job.close()
            if result == 0:
                return
            now = time.monotonic()
            failures = [value for value in failures if now - value < 60]
            failures.append(now)
            if len(failures) >= 3:
                raise RuntimeError('Node failed three times in one minute; inspect node.log')
            time.sleep(2)
    log_path = config_path.parent / 'node.log'
    if log_path.exists() and log_path.stat().st_size > 5 * 1024 * 1024:
        log_path.replace(log_path.with_suffix('.previous.log'))
    with log_path.open('a', encoding='utf-8', buffering=1) as log:
        sys.stdout = sys.stderr = log
        sys.argv = [config['module'], *config['arguments']]
        try:
            runpy.run_module(config['module'], run_name='__main__')
        except Exception:
            import traceback
            traceback.print_exc()
            raise


if __name__ == '__main__':
    main()
