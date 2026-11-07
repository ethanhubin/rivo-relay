"""Discover the local machine's name without changing its stable Relay identity."""
from __future__ import annotations

import json
import platform
import shutil
import socket
import subprocess
import threading
import time
from pathlib import Path


def _output(command: list[str]) -> str:
    try:
        return subprocess.run(command, capture_output=True, text=True, timeout=2,
                              check=True).stdout.strip()
    except (OSError, subprocess.SubprocessError, UnicodeError):
        return ''


def discover_name() -> str:
    # LaunchAgents often lack /usr/local/bin in PATH. Read only Self, never peers.
    executable = shutil.which('tailscale')
    if not executable:
        executable = next((p for p in ('/usr/local/bin/tailscale', '/opt/homebrew/bin/tailscale',
            '/Applications/Tailscale.app/Contents/MacOS/Tailscale') if Path(p).is_file()), None)
    if executable:
        try:
            status = json.loads(_output([executable, 'status', '--json']))
            own = status.get('Self') if isinstance(status, dict) else None
            if isinstance(own, dict):
                dns = own.get('DNSName')
                if isinstance(dns, str) and dns.strip().strip('.'):
                    return dns.strip().rstrip('.').split('.')[0]
        except (ValueError, TypeError):
            pass
    if platform.system() == 'Darwin':
        name = _output(['/usr/sbin/scutil', '--get', 'ComputerName'])
        if name:
            return name
    return socket.gethostname().strip() or 'Rivo Relay'


class NodeName:
    """Bound discovery cost and re-read names after OS/Tailscale renames."""
    def __init__(self):
        self._lock = threading.Lock()
        self._expires = 0.0
        self._name = ''

    def get(self) -> str:
        with self._lock:
            if time.monotonic() >= self._expires:
                self._name = discover_name()
                self._expires = time.monotonic() + 30
            return self._name
