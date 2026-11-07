"""Submit one-shot launchd jobs, explicitly disabling automatic restart."""
import os
from pathlib import Path
import plistlib
import subprocess


def submit(label, command, log):
    log = Path(log).resolve()
    path = log.parent / (label + '.plist')
    config = {'Label': label, 'ProgramArguments': [str(v) for v in command],
              'RunAtLoad': True, 'KeepAlive': False,
              'StandardOutPath': str(log), 'StandardErrorPath': str(log)}
    with path.open('wb') as stream:
        os.chmod(path, 0o600)
        plistlib.dump(config, stream)
    return subprocess.run(['launchctl', 'bootstrap', f'gui/{os.getuid()}', str(path)],
                          capture_output=True, text=True, timeout=15)
