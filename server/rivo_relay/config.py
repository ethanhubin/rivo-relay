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
    name: str = "Rivo Relay"
    public_url: str = "http://10.0.2.2:8765"
    cert: Path | None = None
    key: Path | None = None
    codex_home: Path = Path.home() / ".codex"

    def validate(self) -> None:
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
        if url.scheme == "http" and not (is_loopback(url.hostname) or url.hostname == "10.0.2.2"):
            raise ValueError("HTTP public URLs are limited to local emulator development")

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
