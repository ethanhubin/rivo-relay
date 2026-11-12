"""Discover the local machine's name without changing its stable Relay identity."""
from __future__ import annotations

import platform
import socket
import subprocess
import threading
import time


def _output(command: list[str]) -> str:
    try:
        return subprocess.run(command, capture_output=True, text=True, timeout=2,
                              check=True).stdout.strip()
    except (OSError, subprocess.SubprocessError, UnicodeError):
        return ''


def discover_name() -> str:
    if platform.system() == 'Darwin':
        name = _output(['/usr/sbin/scutil', '--get', 'ComputerName'])
        if name:
            return name
    return socket.gethostname().strip() or 'Rivo Relay'


class NodeName:
    """Bound discovery cost and re-read names after OS renames."""
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
