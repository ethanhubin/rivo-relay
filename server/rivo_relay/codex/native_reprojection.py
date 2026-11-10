"""Offline reprojection of one Codex paginated-history thread.

The rollout remains the conversation authority.  Reprojection runs the same
Codex executable against an isolated copy of ``CODEX_HOME`` and publishes the
officially migrated rollout and its SQLite projection together while holding
the native per-thread writer lock.
"""

from __future__ import annotations

import copy
import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import tempfile
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Dict, Iterable, List, Optional, Sequence

from . import native_history_support as fcntl
from .native_history_support import AppServerConnection
from .native_history_support import sqlite_connection
from .native_history_support import background_process_options


THREAD_ID = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    re.IGNORECASE,
)
PROJECTION_TABLES = (
    # Deleting projection state runs Codex's realtime cleanup trigger, so it
    # must happen before the official realtime rows are inserted.
    "thread_history_projection_state",
    "thread_items",
    "thread_realtime_items",
    "thread_turns",
)
MAX_ROLLOUT_BYTES = 256 * 1024 * 1024
MAX_RECORD_BYTES = 32 * 1024 * 1024
MAX_RECORDS = 1_000_000
MAX_BACKUP_BYTES = 512 * 1024 * 1024
BACKUP_MAX_AGE_SECONDS = 7 * 24 * 60 * 60


class NativeHistoryReprojectionError(RuntimeError):
    """The isolated official migration could not be safely published."""


@dataclass(frozen=True)
class NativeHistoryReprojectionResult:
    reasons: tuple[str, ...]
    rollout_backup: Path
    projection_backup: Path
    record_count: int
    turn_count: int

    @property
    def reason(self) -> str:
        return ",".join(self.reasons)


Migrator = Callable[[Path, str, Path], None]


class NativeHistoryReprojector:
    """Rebuild and atomically publish one idle native Codex history."""

    def __init__(
        self,
        *,
        codex_home: Path,
        repair_dir: Path,
        executable: Path,
        environment: Dict[str, str],
        migrator: Optional[Migrator] = None,
        paginated_rebuilder: Optional[Migrator] = None,
        clock: Callable[[], float] = time.time,
        before_publish: Optional[Callable[[], None]] = None,
    ) -> None:
        self.codex_home = codex_home.resolve()
        self.repair_dir = repair_dir.resolve()
        self.executable = executable.resolve()
        self.environment = dict(environment)
        self._migrator = migrator or self._run_official_migrator
        self._paginated_rebuilder = paginated_rebuilder or self._run_paginated_rebuilder
        self._clock = clock
        self._before_publish = before_publish or (lambda: None)

    def reproject(
        self,
        thread_id: str,
        rollout_path: Path,
        workspace_root: Path,
    ) -> NativeHistoryReprojectionResult:
        if not THREAD_ID.fullmatch(thread_id):
            raise NativeHistoryReprojectionError("Codex thread ID 无效")
        rollout_path = rollout_path.resolve()
        if not any(self._path_is_within(rollout_path, self.codex_home / directory)
                   for directory in ('sessions', 'archived_sessions')):
            raise NativeHistoryReprojectionError('Codex 历史路径越过原生会话目录')
        history_database = self.codex_home / "thread_history_1.sqlite"
        state_database = self.codex_home / "state_5.sqlite"
        if not history_database.is_file() or not state_database.is_file():
            raise NativeHistoryReprojectionError("Codex 原生历史数据库不存在")

        lock_path = self.codex_home / "thread-writer-locks" / f"{thread_id}.lock"
        lock_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0), 0o600)
        with os.fdopen(descriptor, 'a+b') as writer_lock:
            try:
                fcntl.flock(writer_lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise NativeHistoryReprojectionError(
                    "Codex Desktop 已重新打开该会话，请重试"
                ) from error
            try:
                original_digest = self._file_digest(rollout_path)
                records = self._read_rollout_records(thread_id, rollout_path)
                def verify_source():
                    self._before_publish()
                    locked = os.fstat(writer_lock.fileno())
                    current = lock_path.stat()
                    if (locked.st_dev, locked.st_ino) != (current.st_dev, current.st_ino):
                        raise NativeHistoryReprojectionError('Codex writer lock 已更换，未发布修复')
                    if self._file_digest(rollout_path) != original_digest:
                        raise NativeHistoryReprojectionError('Codex 历史在重建期间发生变化，未发布修复')
                verify_source()
                normalized, raw_reasons = self._normalized_rollout_records(records)
                reasons = tuple(raw_reasons or ("projection_lag",))
                with tempfile.TemporaryDirectory(
                    prefix="rivo-codex-reprojection."
                ) as temporary_directory:
                    temporary_home = Path(temporary_directory).resolve()
                    preserve_records = self._preserve_paginated_records(records, normalized)
                    prepare = (
                        self._prepare_isolated_paginated_home
                        if preserve_records else self._prepare_isolated_home
                    )
                    prepare(
                        temporary_home,
                        state_database,
                        thread_id,
                        normalized,
                    )
                    preserved_prefix = (
                        self._isolated_rollout_path(temporary_home, thread_id).read_bytes()
                        if preserve_records else None
                    )
                    try:
                        rebuild = self._paginated_rebuilder if preserve_records else self._migrator
                        rebuild(temporary_home, thread_id, workspace_root.resolve())
                    except NativeHistoryReprojectionError:
                        raise
                    except Exception as error:
                        raise NativeHistoryReprojectionError(
                            f"Codex 官方迁移器执行失败：{error}"
                        ) from error
                    migrated_rollout = self._isolated_rollout_path(
                        temporary_home, thread_id
                    )
                    if self._projection_id(thread_id, rollout_path) != self._projection_id(thread_id, migrated_rollout):
                        raise NativeHistoryReprojectionError("官方重建改变了历史段身份")
                    migrated_database = temporary_home / "thread_history_1.sqlite"
                    turn_count = self._validate_migration(
                        thread_id,
                        normalized,
                        migrated_rollout,
                        migrated_database,
                        history_database,
                        preserved_prefix=preserved_prefix,
                    )
                    record_count = len(self._read_rollout_records(thread_id, migrated_rollout))
                    verify_source()
                    rollout_backup, projection_backup = self._publish(
                        thread_id,
                        rollout_path,
                        migrated_rollout,
                        history_database,
                        migrated_database,
                        verify_source=verify_source,
                    )
                return NativeHistoryReprojectionResult(
                    reasons=reasons,
                    rollout_backup=rollout_backup,
                    projection_backup=projection_backup,
                    record_count=record_count,
                    turn_count=turn_count,
                )
            finally:
                fcntl.flock(writer_lock.fileno(), fcntl.LOCK_UN)

    def check_takeover(self, thread_id: str, rollout_path: Path, workspace_root: Path) -> None:
        """Prove byte-preserving repair works before killing an external writer."""
        records = self._read_rollout_records(thread_id, rollout_path)
        normalized, _ = self._normalized_rollout_records(records)
        if not self._preserve_paginated_records(records, normalized):
            return
        with tempfile.TemporaryDirectory(prefix="rivo-codex-preflight.") as directory:
            home = Path(directory).resolve()
            self._prepare_isolated_paginated_home(
                home, self.codex_home / "state_5.sqlite", thread_id, normalized
            )
            preserved_prefix = self._isolated_rollout_path(home, thread_id).read_bytes()
            self._paginated_rebuilder(home, thread_id, workspace_root)
            self._validate_migration(
                thread_id, normalized, self._isolated_rollout_path(home, thread_id),
                home / "thread_history_1.sqlite", self.codex_home / "thread_history_1.sqlite",
                preserved_prefix=preserved_prefix,
            )

    def _preserve_paginated_records(self, records: Sequence[dict], normalized: Sequence[dict]) -> bool:
        # Legacy migration reserializes records and can drop fields understood by
        # Desktop but not by the bundled CLI. Already paginated histories only
        # need a fresh projection; preserve their original bytes whenever possible.
        return self._history_base(records) is not None or (
            records[0].get("payload", {}).get("history_mode") == "paginated"
            and list(records) == list(normalized[:len(records)])
        )

    @staticmethod
    def _projection_id(thread_id: str, rollout_path: Path) -> str:
        # After a fork, the original thread may continue in a named segment.
        # Its projection belongs to the filename's snapshot ID, while its
        # metadata and writer lock still belong to the original thread ID.
        suffix = rollout_path.stem.rsplit("_", 1)
        return suffix[1] if len(suffix) == 2 and THREAD_ID.fullmatch(suffix[1]) else thread_id

    @staticmethod
    def _history_base(records: Sequence[dict]) -> Optional[dict]:
        base = records[0].get("payload", {}).get("history_base")
        if base is None:
            return None
        if (
            not isinstance(base, dict)
            or not THREAD_ID.fullmatch(str(base.get("thread_id", "")))
            or any(type(base.get(key)) is not int or base[key] <= 0
                   for key in ("end_ordinal_exclusive", "end_byte_offset"))
        ):
            raise NativeHistoryReprojectionError("Codex 分叉历史引用无效")
        return base

    def _prepare_isolated_paginated_home(
        self, temporary_home: Path, state_database: Path,
        thread_id: str, normalized: Sequence[dict],
    ) -> None:
        # Never turn a fork into legacy: migrate-rollouts discards history_base.
        # A fresh official projection is built by resuming only the isolated copy.
        temporary_state = temporary_home / "state_5.sqlite"
        self._backup_database(state_database, temporary_state)
        target: Optional[Path] = None
        original: Optional[Path] = None
        with sqlite_connection(temporary_state, timeout=30) as connection:
            # No metadata row may lead the isolated app-server back to live files.
            rows = connection.execute("SELECT id, rollout_path FROM threads").fetchall()
            for native_id, raw_path in rows:
                if not isinstance(native_id, str) or not THREAD_ID.fullmatch(native_id):
                    raise NativeHistoryReprojectionError('Codex 原生 thread 元数据身份无效')
                source = Path(str(raw_path or '')).resolve()
                relative = (
                    source.relative_to(self.codex_home)
                    if self._path_is_within(source, self.codex_home)
                    else Path("missing") / str(native_id)
                )
                connection.execute("UPDATE threads SET rollout_path = ? WHERE id = ?",
                                   (str(temporary_home / relative), native_id))
                if native_id == thread_id:
                    target = temporary_home / relative
                    original = source
            if target is None or original is None:
                raise NativeHistoryReprojectionError("Codex 原生 thread 元数据不存在")
        target.parent.mkdir(parents=True, exist_ok=True)
        # Other forks may reference a byte-bounded prefix of this very segment.
        # Preserve that prefix verbatim; only append terminal repair records.
        raw = original.read_bytes()
        existing = [json.loads(line) for line in raw.splitlines() if line.strip()]
        if existing != list(normalized[:len(existing)]) or not raw.endswith(b"\n"):
            raise NativeHistoryReprojectionError("分叉历史已有记录需要重写，拒绝破坏父历史引用边界")
        with target.open("wb") as writer:
            writer.write(raw)
            for record in normalized[len(existing):]:
                writer.write((json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))

        base = self._history_base(normalized)
        seen = {target.relative_to(temporary_home)}
        while base is not None:
            reference_id = base["thread_id"]
            if len(seen) >= 64:
                raise NativeHistoryReprojectionError("Codex 分叉历史引用循环或过深")
            # Desktop may store a frozen segment as originalId_snapshotId.jsonl;
            # snapshot IDs need not have a row in state_5.sqlite.
            matches = [path for root in ("sessions", "archived_sessions")
                       for path in (self.codex_home / root).rglob(f"*{reference_id}.jsonl")]
            if len(matches) != 1:
                raise NativeHistoryReprojectionError("Codex 分叉父历史缺失或不唯一")
            source = matches[0].resolve()
            if not self._path_is_within(source, self.codex_home):
                raise NativeHistoryReprojectionError("Codex 分叉父历史路径越界")
            relative = source.relative_to(self.codex_home)
            if relative in seen:
                raise NativeHistoryReprojectionError("Codex 分叉历史引用循环或过深")
            seen.add(relative)
            destination = temporary_home / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            # Preserve bytes: nested history_base offsets refer to the original
            # JSONL encoding, not to a reserialized equivalent.
            remaining = base["end_byte_offset"]
            if remaining > MAX_ROLLOUT_BYTES:
                raise NativeHistoryReprojectionError('Codex 分叉父历史超过安全重建大小限制')
            with source.open("rb") as reader, destination.open("wb") as writer:
                while remaining:
                    chunk = reader.read(min(remaining, 1024 * 1024))
                    if not chunk:
                        raise NativeHistoryReprojectionError("Codex 分叉父历史短于引用边界")
                    writer.write(chunk)
                    remaining -= len(chunk)
            try:
                raw_records = destination.read_bytes().splitlines()
                if len(raw_records) > MAX_RECORDS or any(len(line) > MAX_RECORD_BYTES for line in raw_records):
                    raise ValueError('fork history exceeds bounded repair size')
                records = [json.loads(line) for line in raw_records]
                if (not records or records[0].get("type") != "session_meta"
                        or records[-1].get("ordinal") != base["end_ordinal_exclusive"] - 1):
                    raise ValueError("invalid boundary")
                base = self._history_base(records)
            except (ValueError, TypeError, AttributeError) as error:
                raise NativeHistoryReprojectionError("Codex 分叉父历史引用边界无效") from error

    def _run_paginated_rebuilder(
        self, temporary_home: Path, thread_id: str, workspace_root: Path
    ) -> None:
        records = self._read_rollout_records(
            thread_id, self._isolated_rollout_path(temporary_home, thread_id)
        )
        settings = next((record.get("payload", {}).get("thread_settings")
                         for record in reversed(records)
                         if record.get("type") == "event_msg"
                         and record.get("payload", {}).get("type") == "thread_settings_applied"), {})
        resume = {"threadId": thread_id, "excludeTurns": True}
        # Older CLIs do not restore these fields automatically on resume.
        # Pass persisted values explicitly and validate the resulting snapshot.
        for native, rpc in (("model", "model"), ("model_provider_id", "modelProvider"),
                            ("service_tier", "serviceTier"), ("personality", "personality"),
                            ("approval_policy", "approvalPolicy"),
                            ("approvals_reviewer", "approvalsReviewer"),
                            ("runtime_workspace_roots", "runtimeWorkspaceRoots")):
            if settings.get(native) is not None:
                resume[rpc] = settings[native]
        config = {key: settings[value] for key, value in (
            ("model_reasoning_effort", "reasoning_effort"),
            ("model_reasoning_summary", "reasoning_summary"),
        ) if settings.get(value) is not None}
        profile = settings.get("permission_profile") or {}
        filesystem = profile.get("file_system") or {}
        if (profile.get("type") == "managed"
                and filesystem.get("type") == "restricted"
                and any(entry.get("access") == "write" for entry in filesystem.get("entries", []))):
            # An isolated home defaults to read-only. Reconstruct the original
            # workspace sandbox, including extra roots and temporary-directory
            # exclusions. Codex adds cwd itself before the configured roots;
            # remove only that first occurrence, preserving order and duplicates
            # so its protected subpaths are reproduced identically as well.
            entries = filesystem.get("entries", [])
            writable_roots = [entry["path"]["path"] for entry in entries
                              if entry.get("access") == "write"
                              and entry.get("path", {}).get("type") == "path"]
            if writable_roots and writable_roots[0] == settings.get("cwd"):
                writable_roots = writable_roots[1:]
            writable_specials = {entry["path"].get("value", {}).get("kind")
                                 for entry in entries if entry.get("access") == "write"
                                 and entry.get("path", {}).get("type") == "special"}
            resume["sandbox"] = "workspace-write"
            config["sandbox_workspace_write.network_access"] = profile.get("network") == "enabled"
            config["sandbox_workspace_write.writable_roots"] = writable_roots
            config["sandbox_workspace_write.exclude_slash_tmp"] = "slash_tmp" not in writable_specials
            config["sandbox_workspace_write.exclude_tmpdir_env_var"] = "tmpdir" not in writable_specials
            # The validator below still requires the entire resulting profile
            # (including read-only exceptions) to match, never just its roots.
        if config:
            resume["config"] = config
        process = subprocess.Popen(
            [str(self.executable), "app-server", "--stdio"],
            cwd=temporary_home,
            env={**self.environment, "CODEX_HOME": str(temporary_home)},
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding="utf-8", **background_process_options(),
        )
        connection = AppServerConnection(process, lambda _message: None)
        try:
            connection.request("initialize", {
                "clientInfo": {"name": "rivo_history_repair", "version": "1"},
                "capabilities": {"experimentalApi": True},
            }, timeout=15)
            connection.notify("initialized")
            # No turn/start, no credentials, no live writer lock. Resume preserves
            # persisted settings and only appends an equivalent settings event.
            connection.request("thread/resume", resume, timeout=60)
            connection.request("thread/turns/list", {
                "threadId": thread_id, "limit": 1,
                "sortDirection": "desc", "itemsView": "full",
            }, timeout=60)
        except Exception as error:
            raise NativeHistoryReprojectionError(
                f"Codex 官方分页历史重建失败：{error}"
            ) from error
        finally:
            connection.close_input()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            connection.join()

    def _prepare_isolated_home(
        self,
        temporary_home: Path,
        state_database: Path,
        thread_id: str,
        normalized: Sequence[dict],
    ) -> None:
        temporary_home.mkdir(mode=0o700, parents=True, exist_ok=True)
        temporary_state = temporary_home / "state_5.sqlite"
        self._backup_database(state_database, temporary_state)
        temporary_rollout = temporary_home / "sessions" / f"rollout-{thread_id}.jsonl"
        temporary_rollout.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        migration_input_records = copy.deepcopy(list(normalized))
        for record in migration_input_records:
            record.pop("ordinal", None)
        session_payload = migration_input_records[0].get("payload")
        if not isinstance(session_payload, dict):
            raise NativeHistoryReprojectionError("Codex session_meta payload 无效")
        session_payload["history_mode"] = "legacy"
        self._write_records(temporary_rollout, migration_input_records)
        try:
            with sqlite_connection(temporary_state, timeout=30) as connection:
                for native_id, raw_path in connection.execute('SELECT id, rollout_path FROM threads').fetchall():
                    if not isinstance(native_id, str) or not THREAD_ID.fullmatch(native_id):
                        raise NativeHistoryReprojectionError('Codex 原生 thread 元数据身份无效')
                    source = Path(str(raw_path)).resolve()
                    relative = source.relative_to(self.codex_home) if self._path_is_within(source, self.codex_home) else Path('missing') / str(native_id)
                    connection.execute('UPDATE threads SET rollout_path=? WHERE id=?',
                                       (str(temporary_home / relative), native_id))
                cursor = connection.execute(
                    "UPDATE threads SET rollout_path = ?, history_mode = 'legacy' WHERE id = ?",
                    (str(temporary_rollout), thread_id),
                )
                if cursor.rowcount != 1:
                    raise NativeHistoryReprojectionError("Codex 原生 thread 元数据不存在")
        except sqlite3.Error as error:
            raise NativeHistoryReprojectionError(
                f"无法准备隔离的 Codex thread 元数据：{error}"
            ) from error

    def _run_official_migrator(
        self, temporary_home: Path, thread_id: str, workspace_root: Path
    ) -> None:
        environment = {**self.environment, "CODEX_HOME": str(temporary_home)}
        try:
            result = subprocess.run(
                [
                    str(self.executable),
                    "migrate-rollouts",
                    "--apply",
                    "--thread",
                    thread_id,
                    "--json",
                ],
                cwd=workspace_root,
                env=environment,
                text=True,
                capture_output=True,
                timeout=120,
                check=False,
                **background_process_options(),
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise NativeHistoryReprojectionError(
                f"无法执行 Codex 官方迁移器：{error}"
            ) from error
        if result.returncode != 0:
            detail = (result.stderr or result.stdout).strip()
            raise NativeHistoryReprojectionError(
                f"Codex 官方迁移器退出码 {result.returncode}：{detail[:1000]}"
            )
        outcomes = self._migration_outcomes(result.stdout)
        if not any(
            str(outcome.get("thread_id") or outcome.get("threadId") or "") == thread_id
            and str(outcome.get("status") or "") == "migrated"
            for outcome in outcomes
        ):
            raise NativeHistoryReprojectionError(
                "Codex 官方迁移器未确认该 thread 已迁移"
            )

    @staticmethod
    def _migration_outcomes(output: str) -> List[dict]:
        values = []
        try:
            parsed = json.loads(output)
        except json.JSONDecodeError:
            parsed = None
        if isinstance(parsed, dict) and isinstance(parsed.get("outcomes"), list):
            values.extend(parsed["outcomes"])
        elif isinstance(parsed, dict):
            values.append(parsed)
        else:
            for line in output.splitlines():
                try:
                    value = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(value, dict):
                    values.append(value)
        return [value for value in values if isinstance(value, dict)]

    def _isolated_rollout_path(self, temporary_home: Path, thread_id: str) -> Path:
        state_database = temporary_home / "state_5.sqlite"
        try:
            with sqlite_connection(state_database, timeout=30) as connection:
                row = connection.execute(
                    "SELECT rollout_path, history_mode FROM threads WHERE id = ?",
                    (thread_id,),
                ).fetchone()
        except sqlite3.Error as error:
            raise NativeHistoryReprojectionError(
                f"无法读取官方迁移结果：{error}"
            ) from error
        if not row or str(row[1] or "") != "paginated":
            raise NativeHistoryReprojectionError("官方迁移结果未切换为 paginated")
        migrated_rollout = Path(str(row[0])).resolve()
        if not self._path_is_within(migrated_rollout, temporary_home):
            raise NativeHistoryReprojectionError("官方迁移结果越过隔离目录")
        if not migrated_rollout.is_file():
            raise NativeHistoryReprojectionError("官方迁移结果缺少 rollout")
        return migrated_rollout

    def _validate_migration(
        self,
        thread_id: str,
        expected_records: Sequence[dict],
        migrated_rollout: Path,
        migrated_database: Path,
        live_database: Path,
        *,
        preserved_prefix: Optional[bytes] = None,
    ) -> int:
        if not migrated_database.is_file():
            raise NativeHistoryReprojectionError("官方迁移结果缺少 history projection")
        migrated_records = self._read_rollout_records(thread_id, migrated_rollout)
        base = self._history_base(expected_records)
        extra = migrated_records[len(expected_records):]
        if preserved_prefix is not None and extra:
            settings = next((record.get("payload", {}).get("thread_settings")
                             for record in reversed(expected_records)
                             if record.get("type") == "event_msg"
                             and record.get("payload", {}).get("type") == "thread_settings_applied"), None)
            # Resume may append exactly one unchanged settings snapshot. It must
            # never introduce a new turn, change settings, or rewrite any prefix.
            valid_extra = (
                len(extra) == 1 and settings is not None
                and extra[0].get("type") == "event_msg"
                and extra[0].get("payload", {}).get("type") == "thread_settings_applied"
                and extra[0].get("payload", {}).get("thread_settings") == settings
                and extra[0].get("ordinal") == (base["end_ordinal_exclusive"] if base else 0) + len(expected_records)
            )
            if not valid_extra:
                raise NativeHistoryReprojectionError("官方重建改变了会话设置或追加了非预期记录")
        elif len(migrated_records) != len(expected_records):
            raise NativeHistoryReprojectionError("官方迁移改变了 rollout 记录数量")
        expected = copy.deepcopy(list(expected_records))
        expected_payload = expected[0].get("payload")
        if isinstance(expected_payload, dict):
            expected_payload["history_mode"] = "paginated"
        if self._without_null_fields(expected) != self._without_null_fields(
            migrated_records[:len(expected_records)]
        ):
            raise NativeHistoryReprojectionError("官方迁移改变了 rollout 语义")
        if preserved_prefix is not None and not migrated_rollout.read_bytes().startswith(preserved_prefix):
            raise NativeHistoryReprojectionError("官方重建改变了父历史引用的字节边界")

        self._validate_projection_schemas(live_database, migrated_database)
        projection_id = self._projection_id(thread_id, migrated_rollout)
        try:
            with sqlite_connection(migrated_database, timeout=30) as connection:
                state = connection.execute(
                    """SELECT next_rollout_byte_offset, next_rollout_ordinal
                       FROM thread_history_projection_state WHERE thread_id = ?""",
                    (projection_id,),
                ).fetchone()
                ordinal_base = base["end_ordinal_exclusive"] if base else 0
                if state != (migrated_rollout.stat().st_size, ordinal_base + len(migrated_records)):
                    raise NativeHistoryReprojectionError(
                        "官方 projection cursor 未覆盖完整 rollout"
                    )
                turn_rows = connection.execute(
                    """SELECT turn_id, status FROM thread_turns
                       WHERE thread_id = ? ORDER BY rollout_ordinal""",
                    (projection_id,),
                ).fetchall()
        except sqlite3.Error as error:
            raise NativeHistoryReprojectionError(
                f"无法校验官方 projection：{error}"
            ) from error
        expected_lifecycle = self._latest_turn_lifecycle(migrated_records)
        if expected_lifecycle is None:
            if turn_rows:
                raise NativeHistoryReprojectionError("官方 projection 产生了意外 turn")
        else:
            if not turn_rows or tuple(turn_rows[-1]) != expected_lifecycle:
                raise NativeHistoryReprojectionError(
                    "官方 projection 的最新 turn 与 rollout 不一致"
                )
        return len(turn_rows)

    def _publish(self, *args, **kwargs) -> tuple[Path, Path]:
        # Serialize retention with publication across threads and processes.
        self.repair_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        with (self.repair_dir / '.retention.lock').open('a+b') as lock:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
            try:
                self._prune_backups()
                return self._publish_locked(*args, **kwargs)
            finally:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)

    def _publish_locked(
        self,
        thread_id: str,
        live_rollout: Path,
        migrated_rollout: Path,
        live_database: Path,
        migrated_database: Path,
        *, verify_source: Optional[Callable[[], None]] = None,
    ) -> tuple[Path, Path]:
        self.repair_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        stamp = datetime.fromtimestamp(self._clock(), timezone.utc).strftime(
            "%Y%m%dT%H%M%S%fZ"
        )
        prefix = f"{stamp}-{thread_id}-before-official-reprojection"
        rollout_backup = self.repair_dir / f"{prefix}.jsonl"
        projection_backup = self.repair_dir / f"{prefix}.sqlite"
        projection_id = self._projection_id(thread_id, live_rollout)
        try:
            budget = MAX_BACKUP_BYTES - live_rollout.stat().st_size
            if budget < 4096:
                raise NativeHistoryReprojectionError("会话修复备份超过全局容量限制")
            self._backup_projection(live_database, projection_backup, projection_id, budget)
            shutil.copy2(live_rollout, rollout_backup)
            # Retention age is creation time, not the source rollout's mtime.
            os.utime(rollout_backup, (self._clock(), self._clock()))
            self._prune_backups(protected=rollout_backup)
        except Exception:
            rollout_backup.unlink(missing_ok=True)
            projection_backup.unlink(missing_ok=True)
            raise

        staged_rollout = live_rollout.with_name(
            f".{live_rollout.name}.{uuid.uuid4().hex}.reproject"
        )
        rollout_replaced = False
        projection_id = self._projection_id(thread_id, live_rollout)
        try:
            shutil.copy2(migrated_rollout, staged_rollout)
            with staged_rollout.open("rb+") as stream:
                os.fsync(stream.fileno())
            os.chmod(staged_rollout, live_rollout.stat().st_mode)
            with sqlite_connection(live_database, timeout=30) as connection:
                connection.execute("PRAGMA busy_timeout=30000")
                connection.execute("ATTACH DATABASE ? AS official", (str(migrated_database),))
                connection.execute("BEGIN IMMEDIATE")
                if verify_source:
                    verify_source()
                for table in PROJECTION_TABLES:
                    columns = self._table_columns(connection, "main", table)
                    quoted = ", ".join(f'"{column}"' for column in columns)
                    connection.execute(
                        f'DELETE FROM main."{table}" WHERE thread_id = ?',
                        (projection_id,),
                    )
                    connection.execute(
                        f'INSERT INTO main."{table}" ({quoted}) '
                        f'SELECT {quoted} FROM official."{table}" WHERE thread_id = ?',
                        (projection_id,),
                    )
                if verify_source:
                    verify_source()
                os.replace(staged_rollout, live_rollout)
                rollout_replaced = True
                connection.commit()
        except Exception as error:
            if rollout_replaced:
                self._restore_file(rollout_backup, live_rollout)
            if isinstance(error, NativeHistoryReprojectionError):
                raise
            raise NativeHistoryReprojectionError(
                f"无法发布官方 Codex projection：{error}"
            ) from error
        finally:
            staged_rollout.unlink(missing_ok=True)
            self._prune_backups(protected=rollout_backup)
        return rollout_backup, projection_backup

    @classmethod
    def _validate_projection_schemas(cls, live: Path, official: Path) -> None:
        try:
            with sqlite_connection(live, timeout=30) as live_connection, sqlite_connection(
                official, timeout=30
            ) as official_connection:
                for table in PROJECTION_TABLES:
                    live_schema = live_connection.execute(
                        f'PRAGMA table_info("{table}")'
                    ).fetchall()
                    official_schema = official_connection.execute(
                        f'PRAGMA table_info("{table}")'
                    ).fetchall()
                    if not live_schema or live_schema != official_schema:
                        raise NativeHistoryReprojectionError(
                            f"官方 projection 表结构不兼容：{table}"
                        )
        except sqlite3.Error as error:
            raise NativeHistoryReprojectionError(
                f"无法校验 projection 表结构：{error}"
            ) from error

    @staticmethod
    def _table_columns(
        connection: sqlite3.Connection, schema: str, table: str
    ) -> List[str]:
        rows = connection.execute(f'PRAGMA {schema}.table_info("{table}")').fetchall()
        if not rows:
            raise NativeHistoryReprojectionError(f"projection 缺少表：{table}")
        return [str(row[1]) for row in rows]

    @staticmethod
    def _backup_projection(source: Path, destination: Path, projection_id: str,
                           budget: int) -> None:
        """Export only affected rows, without copying unrelated data or triggers."""
        try:
            with sqlite_connection(destination) as target:
                target.execute("PRAGMA page_size=4096")
                target.execute("PRAGMA journal_mode=OFF")
                target.execute(f"PRAGMA max_page_count={budget // 4096}")
                target.execute("ATTACH DATABASE ? AS source", (source.as_uri() + '?mode=ro',))
                target.execute("BEGIN")
                for table in PROJECTION_TABLES:
                    target.execute(
                        f'CREATE TABLE "{table}" AS SELECT * FROM source."{table}" '
                        'WHERE thread_id = ?', (projection_id,))
        except sqlite3.Error as error:
            raise NativeHistoryReprojectionError(f"无法备份会话 projection（容量受限）：{error}") from error

    @staticmethod
    def _backup_database(source: Path, destination: Path) -> None:
        destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        try:
            with sqlite_connection(source, timeout=30) as source_connection:
                target = sqlite3.connect(destination)
                try:
                    source_connection.backup(target)
                finally:
                    target.close()
        except sqlite3.Error as error:
            raise NativeHistoryReprojectionError(
                f"无法备份 Codex SQLite：{error}"
            ) from error

    @classmethod
    def _read_rollout_records(cls, thread_id: str, path: Path) -> List[dict]:
        records: List[dict] = []
        try:
            if path.stat().st_size > MAX_ROLLOUT_BYTES:
                raise ValueError('rollout exceeds bounded repair size')
            with path.open("rb") as stream:
                line_number = 0
                total_bytes = 0
                while True:
                    line = stream.readline(MAX_RECORD_BYTES + 1)
                    if not line:
                        break
                    line_number += 1
                    total_bytes += len(line)
                    if len(line) > MAX_RECORD_BYTES or line_number > MAX_RECORDS or total_bytes > MAX_ROLLOUT_BYTES:
                        raise ValueError('rollout record exceeds bounded repair size')
                    if not line.endswith(b'\n'):
                        raise ValueError('rollout has an incomplete append')
                    if not line.strip():
                        continue
                    record = json.loads(line)
                    if not isinstance(record, dict):
                        raise ValueError(f"第 {line_number} 行不是 JSON 对象")
                    records.append(record)
        except (OSError, json.JSONDecodeError, ValueError) as error:
            raise NativeHistoryReprojectionError(
                f"Codex 原生会话历史损坏：{error}"
            ) from error
        if not records or records[0].get("type") != "session_meta":
            raise NativeHistoryReprojectionError("Codex 原生会话历史缺少 session_meta")
        payload = records[0].get("payload")
        native_thread_id = ""
        if isinstance(payload, dict):
            native_thread_id = str(payload.get("id") or payload.get("session_id") or "")
        if native_thread_id != thread_id:
            raise NativeHistoryReprojectionError("rollout 与 Codex thread ID 不匹配")
        return records

    @staticmethod
    def _file_digest(path: Path) -> str:
        if path.stat().st_size > MAX_ROLLOUT_BYTES:
            raise NativeHistoryReprojectionError('Codex 历史超过本次安全修复大小限制')
        digest = hashlib.sha256()
        total = 0
        with path.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                total += len(chunk)
                if total > MAX_ROLLOUT_BYTES:
                    raise NativeHistoryReprojectionError('Codex 历史超过本次安全修复大小限制')
                digest.update(chunk)
        return digest.hexdigest()

    def _normalized_rollout_records(
        self, records: Sequence[dict]
    ) -> tuple[List[dict], List[str]]:
        normalized: List[dict] = []
        active_turns: Dict[str, int] = {}
        reasons: set[str] = set()
        base = self._history_base(records)
        ordinal_base = base["end_ordinal_exclusive"] if base else 0

        def append_aborted(turn_id: str, completed_at: int, timestamp: str) -> None:
            started_at = active_turns.pop(turn_id, completed_at)
            normalized.append(
                {
                    "timestamp": timestamp,
                    "type": "event_msg",
                    "payload": {
                        "type": "turn_aborted",
                        "turn_id": turn_id,
                        "reason": "interrupted",
                        "started_at": started_at,
                        "completed_at": completed_at,
                        "duration_ms": max(0, completed_at - started_at) * 1000,
                    },
                }
            )
            reasons.add("orphan_turn")

        for expected_ordinal, original in enumerate(records):
            record = copy.deepcopy(original)
            payload = record.get("payload") if isinstance(record.get("payload"), dict) else {}
            event_type = (
                str(payload.get("type") or "")
                if record.get("type") == "event_msg"
                else ""
            )
            turn_id = str(payload.get("turn_id") or "")
            if event_type == "task_started" and active_turns:
                completed_at = int(payload.get("started_at") or self._clock())
                timestamp = str(
                    record.get("timestamp")
                    or datetime.fromtimestamp(self._clock(), timezone.utc).isoformat()
                )
                for orphan_id in list(active_turns):
                    append_aborted(orphan_id, completed_at, timestamp)
            if event_type == "task_started" and THREAD_ID.fullmatch(turn_id):
                active_turns[turn_id] = int(payload.get("started_at") or self._clock())
            elif event_type in {"task_complete", "turn_aborted"}:
                active_turns.pop(turn_id, None)
            if record.get("ordinal") != ordinal_base + expected_ordinal:
                reasons.add("ordinal")
            normalized.append(record)

        if active_turns:
            completed_at = int(self._clock())
            timestamp = datetime.fromtimestamp(
                self._clock(), timezone.utc
            ).isoformat(timespec="milliseconds").replace("+00:00", "Z")
            for orphan_id in list(active_turns):
                append_aborted(orphan_id, completed_at, timestamp)

        for ordinal, record in enumerate(normalized):
            record["ordinal"] = ordinal_base + ordinal
        return normalized, sorted(reasons)

    @staticmethod
    def _latest_turn_lifecycle(records: Iterable[dict]) -> Optional[tuple[str, str]]:
        latest_turn_id = ""
        latest_status = ""
        for record in records:
            payload = record.get("payload") if isinstance(record.get("payload"), dict) else {}
            if record.get("type") != "event_msg":
                continue
            event_type = str(payload.get("type") or "")
            turn_id = str(payload.get("turn_id") or "")
            if event_type == "task_started" and THREAD_ID.fullmatch(turn_id):
                latest_turn_id, latest_status = turn_id, "inProgress"
            elif event_type == "task_complete" and turn_id == latest_turn_id:
                latest_status = "failed" if payload.get("error") is not None else "completed"
            elif event_type == "turn_aborted" and turn_id == latest_turn_id:
                latest_status = "interrupted"
        return (latest_turn_id, latest_status) if latest_turn_id else None

    @classmethod
    def _without_null_fields(cls, value):
        if isinstance(value, dict):
            return {
                key: cls._without_null_fields(item)
                for key, item in value.items()
                if item is not None
            }
        if isinstance(value, list):
            return [cls._without_null_fields(item) for item in value]
        return value

    @staticmethod
    def _write_records(path: Path, records: Sequence[dict]) -> None:
        with path.open("w", encoding="utf-8") as stream:
            for record in records:
                stream.write(
                    json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n"
                )
            stream.flush()
            os.fsync(stream.fileno())

    @staticmethod
    def _restore_file(backup: Path, destination: Path) -> None:
        temporary = destination.with_name(
            f".{destination.name}.{uuid.uuid4().hex}.rollback"
        )
        try:
            shutil.copy2(backup, temporary)
            with temporary.open("rb+") as stream:
                os.fsync(stream.fileno())
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)

    def _prune_backups(self, *, keep: int = 2,
                       protected: Optional[Path] = None) -> None:
        """Global retention; caller holds the directory lock during publication.

        Also recognizes old full-database backups and incomplete pairs.
        """
        protected = protected.resolve() if protected is not None else None
        groups = {}
        for suffix in ('jsonl', 'sqlite'):
            for path in self.repair_dir.glob(f'*-before-official-reprojection.{suffix}'):
                groups.setdefault(path.with_suffix(''), []).append(path)
        entries = []
        for base, paths in groups.items():
            rollout = base.with_suffix('.jsonl')
            stamp = rollout.stat().st_mtime if rollout in paths else max(p.stat().st_mtime for p in paths)
            owner = base.name[-len('-before-official-reprojection') - 36:-len('-before-official-reprojection')]
            entries.append((stamp, owner, paths, sum(p.stat().st_size for p in paths)))
        entries.sort(key=lambda entry: entry[0], reverse=True)
        if protected is not None:
            entries.sort(key=lambda entry: protected not in entry[2])
        total = 0
        counts = {}
        for stamp, owner, paths, size in entries:
            pinned = protected in paths
            discard = (len(paths) != 2 or self._clock() - stamp > BACKUP_MAX_AGE_SECONDS
                       or counts.get(owner, 0) >= keep or total + size > MAX_BACKUP_BYTES)
            if pinned and size > MAX_BACKUP_BYTES:
                raise NativeHistoryReprojectionError("会话修复备份超过全局容量限制")
            if discard and not pinned:
                for path in paths:
                    path.unlink(missing_ok=True)
            else:
                total += size
                counts[owner] = counts.get(owner, 0) + 1

    @staticmethod
    def _path_is_within(path: Path, root: Path) -> bool:
        try:
            path.relative_to(root)
            return True
        except ValueError:
            return False
