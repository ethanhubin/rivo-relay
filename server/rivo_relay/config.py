from __future__ import annotations

import ipaddress
import os
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

    def validate(self) -> None:
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
