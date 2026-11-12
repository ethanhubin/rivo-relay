"""Node-owned GitHub PR watches and durable notification outbox."""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import sqlite3
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from uuid import uuid4


_REPOSITORY = re.compile(r"[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}\Z")
_BRANCH = re.compile(r"(?!/)(?!.*//)(?!.*\.\.)(?!.*[~^:?*\[\\])[A-Za-z0-9_./-]{1,200}\Z")
_REGULAR_INTERVAL = 300
_FOREGROUND_INTERVAL = 30
_FOREGROUND_LEASE = 300


class GithubWatchError(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status, self.code = status, code
        super().__init__(message)


def _github_json(url: str, *, token: str = "", form: dict | None = None) -> tuple[dict | list, dict]:
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "Rivo-Relay-PR-Watch"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = urlencode(form).encode() if form is not None else None
    if data is not None:
        headers["Accept"] = "application/json"
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    request = Request(url, data=data, headers=headers)
    try:
        with urlopen(request, timeout=15) as response:
            return json.load(response), dict(response.headers)
    except HTTPError as error:
        if error.code in (401, 403, 404, 429):
            code = {401: "github_unauthorized", 403: "github_forbidden", 404: "github_not_found", 429: "github_rate_limited"}[error.code]
            raise GithubWatchError(error.code, code, "GitHub 授权、仓库访问或请求额度不可用") from None
        raise GithubWatchError(502, "github_unavailable", "GitHub 暂时不可用") from None
    except (URLError, TimeoutError, ValueError, OSError):
        raise GithubWatchError(503, "github_unavailable", "无法连接 GitHub") from None


class GithubWatches:
    def __init__(self, path: Path, *, clock=time.time, transport=_github_json, on_alert=lambda _connection_id: None):
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if path.is_symlink():
            raise ValueError("GitHub watch database must not be a symbolic link")
        if not path.exists():
            descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.close(descriptor)
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        self.clock, self.transport, self.on_alert = clock, transport, on_alert
        self.flows: dict[str, dict] = {}
        self.stop = threading.Event()
        self.wake = threading.Event()
        self.urgent_connections: set[str] = set()
        self.last_fast_scan: dict[str, float] = {}
        self.foreground_until: dict[str, float] = {}
        self.foreground_session: dict[str, str] = {}
        self.next_foreground_scan: dict[str, float] = {}
        self.connected_streams: dict[str, int] = {}
        self.worker: threading.Thread | None = None
        with self.lock:
            self.db.execute("PRAGMA foreign_keys=ON")
            self.db.executescript("""
                CREATE TABLE IF NOT EXISTS github_auth(
                    connection_id TEXT PRIMARY KEY, login TEXT NOT NULL, access_token TEXT NOT NULL,
                    refresh_token TEXT NOT NULL, expires_at REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS github_watches(
                    id TEXT PRIMARY KEY, connection_id TEXT NOT NULL, repository TEXT NOT NULL,
                    branch TEXT NOT NULL, initialized INTEGER NOT NULL DEFAULT 0,
                    checked_at REAL, error TEXT, created_at REAL NOT NULL,
                    UNIQUE(connection_id, repository, branch));
                CREATE TABLE IF NOT EXISTS github_seen(
                    watch_id TEXT NOT NULL REFERENCES github_watches(id) ON DELETE CASCADE,
                    number INTEGER NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL,
                    open INTEGER NOT NULL, ready INTEGER NOT NULL,
                    PRIMARY KEY(watch_id, number));
                CREATE TABLE IF NOT EXISTS github_alerts(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    watch_id TEXT NOT NULL REFERENCES github_watches(id) ON DELETE CASCADE,
                    connection_id TEXT NOT NULL, repository TEXT NOT NULL, branch TEXT NOT NULL,
                    number INTEGER NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL,
                    created_at REAL NOT NULL);
                CREATE INDEX IF NOT EXISTS github_alerts_connection ON github_alerts(connection_id,id);
            """)

    def start(self):
        if self.worker is None:
            self.worker = threading.Thread(target=self._run, name="github-pr-watches", daemon=True)
            self.worker.start()

    def close(self):
        self.stop.set()
        self.wake.set()
        if self.worker is not None:
            self.worker.join(timeout=20)
        with self.lock:
            self.db.close()

    def _run(self):
        next_regular = 0.0
        while not self.stop.is_set():
            self.wake.clear()
            with self.lock:
                now = self.clock()
                regular = now >= next_regular
                for connection_id, until in list(self.foreground_until.items()):
                    if until <= now:
                        self.foreground_until.pop(connection_id, None)
                        self.foreground_session.pop(connection_id, None)
                        self.next_foreground_scan.pop(connection_id, None)
                authorized = {row[0] for row in self.db.execute("SELECT connection_id FROM github_auth")}
                configured = {row[0] for row in self.db.execute("SELECT DISTINCT connection_id FROM github_watches")}
                foreground_active = self.foreground_until.keys() & authorized & configured & self.connected_streams.keys()
                foreground_due = {connection_id for connection_id in self.foreground_until
                                  if connection_id in foreground_active and self.next_foreground_scan.get(connection_id, 0) <= now}
                for connection_id in foreground_due:
                    self.next_foreground_scan[connection_id] = now + _FOREGROUND_INTERVAL
                urgent = set(self.urgent_connections)
                self.urgent_connections.clear()
                watches = [dict(row) for row in self.db.execute("SELECT * FROM github_watches ORDER BY created_at")
                           if regular or row["connection_id"] in urgent or row["connection_id"] in foreground_due]
                if regular:
                    next_regular = now + _REGULAR_INTERVAL
                next_check = min((self.next_foreground_scan[connection_id] for connection_id in foreground_active),
                                 default=next_regular)
            for watch in watches:
                if self.stop.is_set():
                    break
                try:
                    self.scan(watch["id"])
                except Exception:
                    # A failing repository must not stop other watches. scan
                    # records expected GitHub failures for the settings screen.
                    pass
            self.wake.wait(max(0, min(next_regular, next_check) - self.clock()))

    def set_foreground(self, connection_id: str, active: bool, session_id: str) -> dict:
        """Let the node own fast scans while an authorized phone is foregrounded."""
        with self.lock:
            if active:
                now = self.clock()
                expired = (self.foreground_until.get(connection_id, 0) <= now
                           or self.foreground_session.get(connection_id) != session_id)
                self.foreground_until[connection_id] = now + _FOREGROUND_LEASE
                self.foreground_session[connection_id] = session_id
                if expired:
                    self.next_foreground_scan[connection_id] = now
            elif self.foreground_session.get(connection_id) == session_id:
                self.foreground_until.pop(connection_id, None)
                self.foreground_session.pop(connection_id, None)
                self.next_foreground_scan.pop(connection_id, None)
            current = self.foreground_until.get(connection_id, 0) > self.clock()
        self.wake.set()
        return {"active": current}

    def stream_connected(self, connection_id: str):
        with self.lock:
            count = self.connected_streams.get(connection_id, 0)
            self.connected_streams[connection_id] = count + 1
            if count == 0 and connection_id in self.foreground_until:
                self.next_foreground_scan[connection_id] = self.clock()
        self.wake.set()

    def stream_disconnected(self, connection_id: str):
        with self.lock:
            count = self.connected_streams.get(connection_id, 0)
            if count <= 1:
                self.connected_streams.pop(connection_id, None)
            else:
                self.connected_streams[connection_id] = count - 1
        self.wake.set()

    def request_fast_scan(self, connection_id: str) -> dict:
        """Wake only this paired phone's watches; cap foreground GitHub traffic."""
        with self.lock:
            authorized = self.db.execute("SELECT 1 FROM github_auth WHERE connection_id=?", (connection_id,)).fetchone() is not None
            if not authorized:
                return {"scheduled": False, "requiresGithubAuthorization": True}
            now = self.clock()
            if now < self.last_fast_scan.get(connection_id, 0) + 60:
                return {"scheduled": False, "retryAfter": max(1, int(self.last_fast_scan[connection_id] + 60 - now))}
            self.last_fast_scan[connection_id] = now
            self.urgent_connections.add(connection_id)
        self.wake.set()
        return {"scheduled": True}

    def auth_status(self, connection_id: str) -> dict:
        with self.lock:
            row = self.db.execute("SELECT login FROM github_auth WHERE connection_id=?", (connection_id,)).fetchone()
        return {"connected": row is not None, "login": row["login"] if row else None,
                "available": bool(os.environ.get("RIVO_GITHUB_CLIENT_ID"))}

    def auth_start(self, connection_id: str) -> dict:
        client_id = os.environ.get("RIVO_GITHUB_CLIENT_ID", "").strip()
        if not client_id:
            raise GithubWatchError(503, "github_not_configured", "节点尚未配置 GitHub App")
        result, _ = self.transport("https://github.com/login/device/code", form={"client_id": client_id, "scope": "offline_access"})
        if not isinstance(result, dict) or not all(result.get(key) for key in ("device_code", "user_code", "verification_uri")):
            raise GithubWatchError(502, "github_invalid_response", "GitHub 授权响应无效")
        flow_id = uuid4().hex
        with self.lock:
            self.flows = {key: value for key, value in self.flows.items()
                          if value["expires_at"] > self.clock() and value["connection_id"] != connection_id}
            self.flows[flow_id] = {"connection_id": connection_id, "device_code": result["device_code"],
                               "expires_at": self.clock() + min(int(result.get("expires_in", 900)), 900),
                               "next_at": 0, "interval": max(5, int(result.get("interval", 5))), "client_id": client_id}
        return {"flowId": flow_id, "userCode": result["user_code"],
                "verificationUri": result["verification_uri"], "interval": self.flows[flow_id]["interval"],
                "expiresIn": int(result.get("expires_in", 900))}

    def auth_poll(self, connection_id: str, flow_id: str) -> dict:
        with self.lock:
            flow = self.flows.get(flow_id)
        if flow is None or flow["connection_id"] != connection_id or self.clock() >= flow["expires_at"]:
            self.flows.pop(flow_id, None)
            raise GithubWatchError(404, "github_flow_expired", "GitHub 授权已过期，请重新开始")
        if self.clock() < flow["next_at"]:
            return {"pending": True, "interval": flow["interval"]}
        flow["next_at"] = self.clock() + flow["interval"]
        result, _ = self.transport("https://github.com/login/oauth/access_token",
            form={"client_id": flow["client_id"], "device_code": flow["device_code"],
                  "grant_type": "urn:ietf:params:oauth:grant-type:device_code"})
        error = result.get("error") if isinstance(result, dict) else None
        if error in ("authorization_pending", "slow_down"):
            if error == "slow_down":
                flow["interval"] += 5
                flow["next_at"] = self.clock() + flow["interval"]
            return {"pending": True, "interval": flow["interval"]}
        if error or not isinstance(result, dict) or not result.get("access_token"):
            self.flows.pop(flow_id, None)
            raise GithubWatchError(400, "github_authorization_failed", "GitHub 授权未完成")
        profile, _ = self.transport("https://api.github.com/user", token=result["access_token"])
        login = profile.get("login") if isinstance(profile, dict) else None
        if not isinstance(login, str) or not login:
            raise GithubWatchError(502, "github_invalid_response", "GitHub 用户信息无效")
        with self.lock:
            if self.flows.get(flow_id) is not flow:
                raise GithubWatchError(404, "github_flow_expired", "GitHub 授权已过期，请重新开始")
            self.db.execute("INSERT OR REPLACE INTO github_auth VALUES(?,?,?,?,?)",
                (connection_id, login, result["access_token"], result.get("refresh_token", ""),
                 self.clock() + int(result.get("expires_in", 0)) if result.get("expires_in") else 0))
            self.flows.pop(flow_id, None)
            self.urgent_connections.add(connection_id)
        self.wake.set()
        return {"pending": False, "login": login}

    def auth_delete(self, connection_id: str) -> dict:
        with self.lock:
            self.db.execute("DELETE FROM github_auth WHERE connection_id=?", (connection_id,))
            self.flows = {key: value for key, value in self.flows.items() if value["connection_id"] != connection_id}
            self.last_fast_scan.pop(connection_id, None)
        self.wake.set()
        return {"connected": False}

    def purge_connection(self, connection_id: str):
        """Forget credentials and watches when a paired device is revoked."""
        with self.lock:
            self.db.execute("DELETE FROM github_auth WHERE connection_id=?", (connection_id,))
            self.db.execute("DELETE FROM github_watches WHERE connection_id=?", (connection_id,))
            self.flows = {key: value for key, value in self.flows.items() if value["connection_id"] != connection_id}
            self.urgent_connections.discard(connection_id)
            self.last_fast_scan.pop(connection_id, None)
            self.foreground_until.pop(connection_id, None)
            self.foreground_session.pop(connection_id, None)
            self.next_foreground_scan.pop(connection_id, None)
            self.connected_streams.pop(connection_id, None)

    def _token(self, connection_id: str) -> str:
        with self.lock:
            row = self.db.execute("SELECT * FROM github_auth WHERE connection_id=?", (connection_id,)).fetchone()
        if row is None:
            return ""
        if not row["expires_at"] or row["expires_at"] > self.clock() + 60:
            return row["access_token"]
        if not row["refresh_token"]:
            raise GithubWatchError(401, "github_authorization_expired", "GitHub 授权已过期")
        result, _ = self.transport("https://github.com/login/oauth/access_token",
            form={"client_id": os.environ.get("RIVO_GITHUB_CLIENT_ID", ""),
                  "grant_type": "refresh_token", "refresh_token": row["refresh_token"]})
        if not isinstance(result, dict) or not result.get("access_token"):
            raise GithubWatchError(401, "github_authorization_expired", "GitHub 授权已过期")
        with self.lock:
            self.db.execute("UPDATE github_auth SET access_token=?,refresh_token=?,expires_at=? WHERE connection_id=?",
                (result["access_token"], result.get("refresh_token", ""),
                 self.clock() + int(result.get("expires_in", 0)) if result.get("expires_in") else 0,
                 connection_id))
        return result["access_token"]

    def create(self, connection_id: str, repository: str, branch: str) -> dict:
        repository, branch = repository.strip(), branch.strip()
        if not _REPOSITORY.fullmatch(repository) or not _BRANCH.fullmatch(branch) or branch.endswith(("/", ".")):
            raise GithubWatchError(400, "invalid_watch", "仓库或目标分支格式无效")
        with self.lock:
            count = self.db.execute("SELECT COUNT(*) FROM github_watches WHERE connection_id=?", (connection_id,)).fetchone()[0]
            if count >= 20:
                raise GithubWatchError(400, "watch_limit", "最多监听 20 个仓库分支")
            watch_id = uuid4().hex
            try:
                self.db.execute("INSERT INTO github_watches(id,connection_id,repository,branch,created_at) VALUES(?,?,?,?,?)",
                    (watch_id, connection_id, repository, branch, self.clock()))
            except sqlite3.IntegrityError:
                raise GithubWatchError(409, "watch_exists", "该仓库分支已在监听") from None
            self.urgent_connections.add(connection_id)
        self.wake.set()
        return {"id": watch_id, "repository": repository, "branch": branch}

    def delete(self, connection_id: str, watch_id: str) -> dict:
        with self.lock:
            deleted = self.db.execute("DELETE FROM github_watches WHERE id=? AND connection_id=?", (watch_id, connection_id)).rowcount
        if not deleted:
            raise GithubWatchError(404, "watch_not_found", "监听记录不存在")
        return {"deleted": True}

    def list(self, connection_id: str) -> dict:
        with self.lock:
            rows = [dict(row) for row in self.db.execute(
                "SELECT id,repository,branch,initialized,checked_at,error FROM github_watches WHERE connection_id=? ORDER BY created_at",
                (connection_id,))]
            for row in rows:
                row["pending"] = [dict(item) for item in self.db.execute(
                    "SELECT number,title,url FROM github_seen WHERE watch_id=? AND open=1 AND ready=1 ORDER BY number DESC LIMIT 100", (row["id"],))]
                row["initialized"] = bool(row["initialized"])
        return {"watches": rows, "auth": self.auth_status(connection_id)}

    def alerts(self, connection_id: str, after: int) -> dict:
        if not 0 <= after <= 2**63 - 1:
            raise GithubWatchError(400, "invalid_cursor", "提醒游标无效")
        with self.lock:
            rows = [dict(row) for row in self.db.execute(
                "SELECT id,repository,branch,number,title,url,created_at FROM github_alerts WHERE connection_id=? AND id>? ORDER BY id LIMIT 100",
                (connection_id, after))]
        return {"alerts": rows}

    def scan(self, watch_id: str):
        with self.lock:
            watch = self.db.execute("SELECT * FROM github_watches WHERE id=?", (watch_id,)).fetchone()
            if watch is None:
                return
            watch = dict(watch)
        try:
            token = self._token(watch["connection_id"])
            pulls = []
            for page in range(1, 11):
                if self.stop.is_set():
                    return
                url = "https://api.github.com/repos/" + watch["repository"] + "/pulls?" + urlencode(
                    {"state": "open", "base": watch["branch"], "per_page": 100, "page": page})
                batch, _ = self.transport(url, token=token)
                if not isinstance(batch, list):
                    raise GithubWatchError(502, "github_invalid_response", "GitHub PR 响应无效")
                pulls.extend(batch)
                if len(batch) < 100:
                    break
            else:
                raise GithubWatchError(502, "github_too_many_pulls", "开放 PR 过多，无法完整检查")
            for item in pulls:
                if not isinstance(item, dict) or not isinstance(item.get("number"), int) or not isinstance(item.get("draft"), bool):
                    raise GithubWatchError(502, "github_invalid_response", "GitHub PR 响应无效")
                if not isinstance(item.get("title"), str) or not isinstance(item.get("html_url"), str):
                    raise GithubWatchError(502, "github_invalid_response", "GitHub PR 响应无效")
            now = self.clock()
            created_alert = False
            with self.lock:
                self.db.execute("BEGIN IMMEDIATE")
                try:
                    current = self.db.execute("SELECT * FROM github_watches WHERE id=?", (watch_id,)).fetchone()
                    if current is None:
                        self.db.rollback()
                        return
                    seen = {row["number"]: row for row in self.db.execute("SELECT * FROM github_seen WHERE watch_id=?", (watch_id,))}
                    numbers = set()
                    for item in pulls:
                        number, ready = item["number"], not item["draft"]
                        numbers.add(number)
                        previous = seen.get(number)
                        if current["initialized"] and ready and (previous is None or not previous["open"] or not previous["ready"]):
                            self.db.execute("INSERT INTO github_alerts(watch_id,connection_id,repository,branch,number,title,url,created_at) VALUES(?,?,?,?,?,?,?,?)",
                                (watch_id, current["connection_id"], current["repository"], current["branch"],
                                 number, item["title"][:500], item["html_url"][:1000], now))
                            created_alert = True
                        self.db.execute("INSERT INTO github_seen VALUES(?,?,?,?,?,?) ON CONFLICT(watch_id,number) DO UPDATE SET title=excluded.title,url=excluded.url,open=1,ready=excluded.ready",
                            (watch_id, number, item["title"][:500], item["html_url"][:1000], 1, int(ready)))
                    for number in seen.keys() - numbers:
                        self.db.execute("UPDATE github_seen SET open=0 WHERE watch_id=? AND number=?", (watch_id, number))
                    self.db.execute("UPDATE github_watches SET initialized=1,checked_at=?,error=NULL WHERE id=?", (now, watch_id))
                    self.db.execute("DELETE FROM github_alerts WHERE created_at<?", (now - 30 * 86400,))
                    self.db.commit()
                except BaseException:
                    self.db.rollback()
                    raise
            if created_alert:
                try:
                    self.on_alert(watch["connection_id"])
                except Exception:
                    # Durable outbox remains authoritative if the live stream is unavailable.
                    pass
        except GithubWatchError as error:
            with self.lock:
                self.db.execute("UPDATE github_watches SET checked_at=?,error=? WHERE id=?",
                    (self.clock(), error.code, watch_id))
