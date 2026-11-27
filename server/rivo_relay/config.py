from __future__ import annotations

import hashlib
import ipaddress
import json
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit
from uuid import NAMESPACE_URL, uuid5


def is_loopback(host: str) -> bool:
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


@dataclass(frozen=True)
class Config:
    workspace: Path
    host: str = "127.0.0.1"
    port: int = 8765
    public_url: str = "http://10.0.2.2:8765"
    cert: Path | None = None
    key: Path | None = None
    codex_home: Path = Path.home() / ".codex"
    codex_runtime: str = 'auto'
    desktop_bridge_dir: Path | None = None

    codex_executable: Path | None = None
    runtime: str = 'codex'
    dsh_bridge_socket: Path | None = None
    dsh_admin_socket: Path | None = None
    antigravity_executable: Path | None = None
    antigravity_projects: tuple[Path, ...] = ()
    antigravity_mode: str = 'cli'
    antigravity_profile_dir: Path | None = None

    def validate(self) -> None:
        if self.runtime not in {'codex', 'dsh', 'antigravity'}:
            raise ValueError('runtime must be codex, dsh or antigravity')
        if self.runtime == 'dsh':
            if not self.dsh_bridge_socket or not self.dsh_bridge_socket.is_absolute():
                raise ValueError('DSH requires an absolute --dsh-bridge-socket path')
            if self.codex_runtime != 'auto' or self.codex_executable or self.desktop_bridge_dir:
                raise ValueError('DSH cannot use Codex runtime options')
        elif self.dsh_bridge_socket:
            raise ValueError('--dsh-bridge-socket requires --runtime dsh')
        if self.dsh_admin_socket is not None:
            if self.runtime != 'dsh':
                raise ValueError('--dsh-admin-socket requires --runtime dsh')
            if not self.dsh_admin_socket.is_absolute() or len(os.fsencode(self.dsh_admin_socket)) > 100:
                raise ValueError('--dsh-admin-socket requires an absolute Unix socket path of at most 100 bytes')
            if self.dsh_admin_socket.resolve() == self.dsh_bridge_socket.resolve():
                raise ValueError('DSH bridge and admin sockets must have different paths')
        if self.runtime == 'antigravity':
            executable = self.antigravity_executable
            if self.antigravity_mode == 'cli':
                if sys.platform == 'win32':
                    raise ValueError('Antigravity CLI mode is unsupported on Windows')
                if not executable or not executable.is_absolute() or not executable.is_file() or not os.access(executable, os.X_OK):
                    raise ValueError('Antigravity CLI requires an absolute --antigravity-executable path to an executable file')
                if self.antigravity_profile_dir is not None:
                    raise ValueError('--antigravity-profile-dir requires Antigravity desktop mode')
            elif self.antigravity_mode == 'desktop':
                if executable is not None:
                    raise ValueError('--antigravity-executable requires Antigravity CLI mode')
                if self.antigravity_profile_dir is not None and not self.antigravity_profile_dir.is_absolute():
                    raise ValueError('--antigravity-profile-dir requires an absolute Antigravity App profile path')
            else:
                raise ValueError('Antigravity mode must be cli or desktop')
            if self.codex_runtime != 'auto' or self.codex_executable or self.desktop_bridge_dir:
                raise ValueError('Antigravity cannot use Codex runtime options')
            if any(not project.is_absolute() or not project.is_dir() for project in self.antigravity_projects):
                raise ValueError('--antigravity-project requires an absolute path to an existing directory')
        elif self.antigravity_executable or self.antigravity_projects or self.antigravity_profile_dir is not None or self.antigravity_mode != 'cli':
            raise ValueError('Antigravity options require --runtime antigravity')
        if self.codex_runtime not in {'auto', 'desktop', 'app_server', 'stdio'}:
            raise ValueError('codex_runtime must be auto, desktop, app_server or stdio')
        if self.codex_runtime == 'stdio' and (not self.codex_executable or not self.codex_executable.is_absolute() or not self.codex_executable.is_file()):
            raise ValueError('stdio mode requires an absolute --codex-executable path')
        if self.codex_executable and self.codex_runtime != 'stdio':
            raise ValueError('--codex-executable requires --codex-runtime stdio')
        if not 1 <= self.port <= 65535:
            raise ValueError("port must be between 1 and 65535")
        if bool(self.cert) != bool(self.key):
            raise ValueError("TLS requires both --cert and --key")
        if not self.cert and not is_loopback(self.host):
            raise ValueError("non-loopback listeners require TLS; use a local HTTPS proxy or --cert/--key")
        url = urlsplit(self.public_url)
        if (url.scheme not in ("http", "https") or not url.hostname or url.username is not None
                or url.password is not None or url.query or url.fragment):
            raise ValueError("invalid public URL")
        port = url.port
        if port is not None and not 1 <= port <= 65535:
            raise ValueError("invalid public URL port")
        if url.scheme == "http" and not (is_loopback(url.hostname) or url.hostname in ("10.0.2.2", "100.124.150.1")):
            raise ValueError("HTTP public URLs are limited to local development or the configured Tailscale node")

    def prepare(self) -> None:
        self.validate()
        self.workspace.mkdir(parents=True, exist_ok=True, mode=0o700)
        (self.workspace / "data").mkdir(exist_ok=True, mode=0o700)

    @property
    def database_path(self) -> Path:
        return self.workspace / "data" / "relay.sqlite3"

    @property
    def node_id(self) -> str:
        return str(uuid5(NAMESPACE_URL, str(self.workspace.resolve())))

    @property
    def antigravity_profile(self) -> Path:
        default = 'antigravity' if self.antigravity_mode == 'desktop' else 'antigravity-cli'
        return (self.antigravity_profile_dir or Path.home() / '.gemini' / default).resolve()

    @property
    def runtime_identity(self) -> str:
        """Identify the configured provider independently of the Relay release and node ID."""
        if self.runtime == 'antigravity':
            provider = [self.antigravity_mode, str(self.antigravity_profile)]
        elif self.runtime == 'dsh':
            provider = [str(self.dsh_bridge_socket.resolve())] if self.dsh_bridge_socket else []
        else:
            provider = [self.codex_runtime, str(self.codex_home.resolve()),
                        str(self.desktop_bridge_dir.resolve()) if self.desktop_bridge_dir else None]
        return hashlib.sha256(json.dumps([self.runtime, *provider], separators=(',', ':')).encode()).hexdigest()
