"""Authenticated JSON routes for files resources."""

import secrets
import time

from ..codex.files import native_path
from .common import ApiError, required


def route(handler, path, query, body, connection):
    method = handler.command
    codex = handler.app.codex
    if path == ["v1", "download-links"] and method == "POST":
        target = native_path(required(body, "path", 4096))
        key = secrets.token_urlsafe(32)
        expiry = time.time() + 3600
        with handler.app.download_lock:
            handler.app.download_links = {k: v for k, v in handler.app.download_links.items() if v[1] > time.time()}
            if len(handler.app.download_links) >= 128:
                raise ApiError(429, "too_many_downloads", "下载链接过多")
            handler.app.download_links[key] = (target, expiry)
        return {"url": handler.app.config.public_url.rstrip("/") + "/v1/download-links/" + key, "expiresAt": expiry}
    if path == ["v1", "files"] and method == "GET":
        file_path = required({"path": query.get("path", [None])[0]}, "path", 4096)
        return codex.read_file(file_path)
    if path == ["v1", "directories"] and method == "GET":
        return codex.read_directory(required({"path": query.get("path", [None])[0]}, "path", 4096))
    raise ApiError(404, "not_found", "Unknown endpoint")
