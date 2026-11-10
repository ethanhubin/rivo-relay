import json
import os
import sqlite3
import tempfile
import unittest
from unittest.mock import Mock, patch
from pathlib import Path

from rivo_relay.codex import native_history_support as fcntl
from rivo_relay.codex.native_reprojection import (
    NativeHistoryReprojectionError,
    NativeHistoryReprojector,
)
from rivo_relay.codex.native_history_support import sqlite_connection


THREAD_ID = "01a04609-7917-7c73-ad6d-cae86ce70da8"
OTHER_THREAD_ID = "01a04609-7917-7c73-ad6d-cae86ce70da9"
FIRST_TURN_ID = "019fc374-aaaa-7ac2-a9c9-edeee37051ea"
SECOND_TURN_ID = "019fc374-bbbb-7ac2-a9c9-edeee37051ea"


def write_records(path: Path, records) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(
            json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n"
            for record in records
        ),
        encoding="utf-8",
    )


def projection_schema(connection: sqlite3.Connection) -> None:
    connection.execute(
        "CREATE TABLE thread_items(thread_id TEXT NOT NULL, value TEXT NOT NULL)"
    )
    connection.execute(
        "CREATE TABLE thread_realtime_items(thread_id TEXT NOT NULL, value TEXT NOT NULL)"
    )
    connection.execute(
        """CREATE TABLE thread_turns(
               thread_id TEXT NOT NULL,
               turn_id TEXT NOT NULL,
               rollout_ordinal INTEGER NOT NULL,
               status TEXT NOT NULL
           )"""
    )
    connection.execute(
        """CREATE TABLE thread_history_projection_state(
               thread_id TEXT PRIMARY KEY,
               next_rollout_byte_offset INTEGER NOT NULL,
               next_rollout_ordinal INTEGER NOT NULL
           )"""
    )
    connection.execute(
        """CREATE TRIGGER thread_realtime_items_projection_cleanup
           AFTER DELETE ON thread_history_projection_state
           BEGIN
               DELETE FROM thread_realtime_items WHERE thread_id = OLD.thread_id;
           END"""
    )


class NativeHistoryReprojectorTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.codex_home = self.root / "codex-home"
        self.codex_home.mkdir()
        self.repair_dir = self.root / "repairs"
        self.rollout = (
            self.codex_home
            / "sessions"
            / "2026"
            / "08"
            / "31"
            / f"rollout-{THREAD_ID}.jsonl"
        )
        self.records = [
            {
                "timestamp": "2026-08-31T00:00:00Z",
                "ordinal": 0,
                "type": "session_meta",
                "payload": {"id": THREAD_ID, "history_mode": "paginated"},
            },
            {
                "timestamp": "2026-08-31T00:00:01Z",
                "ordinal": 1,
                "type": "event_msg",
                "payload": {
                    "type": "task_started",
                    "turn_id": FIRST_TURN_ID,
                    "started_at": 100,
                },
            },
            {
                "timestamp": "2026-08-31T00:00:02Z",
                "ordinal": 1,
                "type": "event_msg",
                "payload": {"type": "token_count"},
            },
            {
                "timestamp": "2026-08-31T00:00:03Z",
                "ordinal": 3,
                "type": "event_msg",
                "payload": {
                    "type": "task_started",
                    "turn_id": SECOND_TURN_ID,
                    "started_at": 200,
                },
            },
            {
                "timestamp": "2026-08-31T00:00:04Z",
                "ordinal": 4,
                "type": "event_msg",
                "payload": {"type": "task_complete", "turn_id": SECOND_TURN_ID},
            },
        ]
        write_records(self.rollout, self.records)
        with sqlite_connection(self.codex_home / "state_5.sqlite") as connection:
            connection.execute(
                "CREATE TABLE threads(id TEXT PRIMARY KEY, rollout_path TEXT, history_mode TEXT)"
            )
            connection.execute(
                "INSERT INTO threads VALUES (?, ?, 'paginated')",
                (THREAD_ID, str(self.rollout)),
            )
        with sqlite_connection(
            self.codex_home / "thread_history_1.sqlite"
        ) as connection:
            projection_schema(connection)
            connection.execute("INSERT INTO thread_items VALUES (?, 'stale')", (THREAD_ID,))
            connection.execute(
                "INSERT INTO thread_realtime_items VALUES (?, 'stale')", (THREAD_ID,)
            )
            connection.execute(
                "INSERT INTO thread_turns VALUES (?, ?, 1, 'interrupted')",
                (THREAD_ID, FIRST_TURN_ID),
            )
            connection.execute(
                "INSERT INTO thread_history_projection_state VALUES (?, 10, 2)",
                (THREAD_ID,),
            )
            for table in ("thread_items", "thread_realtime_items"):
                connection.execute(
                    f"INSERT INTO {table} VALUES (?, 'other')", (OTHER_THREAD_ID,)
                )
            connection.execute(
                "INSERT INTO thread_turns VALUES (?, ?, 1, 'completed')",
                (OTHER_THREAD_ID, OTHER_THREAD_ID),
            )
            connection.execute(
                "INSERT INTO thread_history_projection_state VALUES (?, 1, 1)",
                (OTHER_THREAD_ID,),
            )

    def reprojector(self, migrator=None, paginated_rebuilder=None) -> NativeHistoryReprojector:
        return NativeHistoryReprojector(
            codex_home=self.codex_home,
            repair_dir=self.repair_dir,
            executable=Path("/bin/true"),
            environment={"PATH": "/usr/bin:/bin"},
            migrator=migrator or self.fake_official_migrator,
            paginated_rebuilder=paginated_rebuilder or self.fake_paginated_rebuilder,
            clock=lambda: 2000.0,
        )

    def fake_paginated_rebuilder(self, home, thread_id, cwd):
        source = self.reprojector()._isolated_rollout_path(home, thread_id)
        raw = source.read_bytes()
        self.fake_official_migrator(home, thread_id, cwd)
        if not json.loads(raw.splitlines()[0])["payload"].get("history_base"):
            migrated = self.reprojector()._isolated_rollout_path(home, thread_id)
            migrated.write_bytes(raw)
            with sqlite_connection(home / "thread_history_1.sqlite") as connection:
                connection.execute(
                    "UPDATE thread_history_projection_state SET next_rollout_byte_offset=? WHERE thread_id=?",
                    (len(raw), thread_id),
                )

    def test_paginated_rebuild_preserves_desktop_fields_without_legacy_migration(self):
        self.records = self.records[:2]
        self.records.append({"ordinal": 2, "type": "turn_context", "payload": {
            "personality": "pragmatic", "workspace_roots": ["/project"],
            "current_date": "2026-10-29", "timezone": "Asia/Shanghai",
        }})
        self.records.append({"ordinal": 3, "type": "event_msg", "payload": {
            "type": "item_completed", "item": {"stdout": "output", "stderr": "", "formatted_output": "output"},
        }})
        write_records(self.rollout, self.records)
        raw = self.rollout.read_bytes()
        legacy = Mock(side_effect=AssertionError("Must preserve paginated bytes"))
        reprojector = self.reprojector(migrator=legacy)
        reprojector.check_takeover(THREAD_ID, self.rollout, self.root)
        self.assertEqual(raw, self.rollout.read_bytes())
        self.assertFalse(self.repair_dir.exists())
        result = reprojector.reproject(THREAD_ID, self.rollout, self.root)
        self.assertTrue(self.rollout.read_bytes().startswith(raw))
        self.assertEqual(("orphan_turn",), result.reasons)
        legacy.assert_not_called()

    def test_paginated_resume_restores_extra_roots_and_temp_directory_permissions(self):
        cwd = str(self.root / "worktree")
        extra_roots = [str(self.root / "project"), cwd, str(self.root / "visualizations")]
        for specials in (("slash_tmp", "tmpdir"), ("slash_tmp",), ("tmpdir",), ()):
            with self.subTest(specials=specials):
                settings = {
                    "cwd": cwd,
                    "permission_profile": {
                        "type": "managed", "network": "enabled",
                        "file_system": {"type": "restricted", "entries": [
                            {"path": {"type": "special", "value": {"kind": "root"}}, "access": "read"},
                            {"path": {"type": "path", "path": cwd}, "access": "write"},
                            *[{"path": {"type": "special", "value": {"kind": kind}}, "access": "write"}
                              for kind in specials],
                            *[{"path": {"type": "path", "path": path}, "access": "write"}
                              for path in extra_roots],
                            {"path": {"type": "path", "path": cwd + "/.git"},
                             "access": "read", "missing_path_behavior": "skip"},
                        ]},
                    },
                }
                write_records(self.rollout, [self.records[0], {
                    "ordinal": 1, "type": "event_msg", "payload": {
                        "type": "thread_settings_applied", "thread_settings": settings,
                    },
                }])
                with patch("rivo_relay.codex.native_reprojection.subprocess.Popen") as process, \
                        patch("rivo_relay.codex.native_reprojection.AppServerConnection") as connection:
                    self.reprojector()._run_paginated_rebuilder(self.codex_home.resolve(), THREAD_ID, self.root)
                calls = connection.return_value.request.call_args_list
                self.assertEqual(["initialize", "thread/resume", "thread/turns/list"],
                                 [call.args[0] for call in calls])
                resume = calls[1].args[1]
                self.assertEqual("workspace-write", resume["sandbox"])
                self.assertEqual({
                    "sandbox_workspace_write.writable_roots": extra_roots,
                    "sandbox_workspace_write.network_access": True,
                    "sandbox_workspace_write.exclude_slash_tmp": "slash_tmp" not in specials,
                    "sandbox_workspace_write.exclude_tmpdir_env_var": "tmpdir" not in specials,
                }, resume["config"])
                self.assertEqual(str(self.codex_home.resolve()), process.call_args.kwargs["env"]["CODEX_HOME"])

    def test_takeover_preflight_rejects_permission_drift_without_touching_live_history(self):
        self.records = self.records[:1]
        settings = {"permission_profile": {"type": "managed", "network": "disabled"}}
        self.records.append({"ordinal": 1, "type": "event_msg", "payload": {
            "type": "thread_settings_applied", "thread_settings": settings,
        }})
        write_records(self.rollout, self.records)
        before = self.rollout.read_bytes()

        def rebuild(home, thread_id, cwd):
            self.fake_paginated_rebuilder(home, thread_id, cwd)
            rollout = self.reprojector()._isolated_rollout_path(home, thread_id)
            records = [json.loads(line) for line in rollout.read_text().splitlines()]
            records.append({"ordinal": len(records), "type": "event_msg", "payload": {
                "type": "thread_settings_applied",
                "thread_settings": {"permission_profile": {"type": "managed", "network": "enabled"}},
            }})
            write_records(rollout, records)

        with self.assertRaisesRegex(NativeHistoryReprojectionError, "改变了会话设置"):
            self.reprojector(paginated_rebuilder=rebuild).check_takeover(THREAD_ID, self.rollout, self.root)
        self.assertEqual(before, self.rollout.read_bytes())
        self.assertFalse(self.repair_dir.exists())

    @staticmethod
    def fake_official_migrator(temporary_home: Path, thread_id: str, _cwd: Path):
        state_database = temporary_home / "state_5.sqlite"
        with sqlite_connection(state_database) as connection:
            source = Path(
                connection.execute(
                    "SELECT rollout_path FROM threads WHERE id = ?", (thread_id,)
                ).fetchone()[0]
            )
        records = [json.loads(line) for line in source.read_text().splitlines()]
        ordinal_base = records[0]["payload"].get("history_base", {}).get("end_ordinal_exclusive", 0)
        for ordinal, record in enumerate(records):
            record["ordinal"] = ordinal_base + ordinal
        records[0]["payload"]["history_mode"] = "paginated"
        if not ordinal_base:
            records[0]["payload"]["official_optional_field"] = None
        migrated = source if ordinal_base else temporary_home / "sessions" / f"migrated-{thread_id}.jsonl"
        write_records(migrated, records)
        projection_id = NativeHistoryReprojector._projection_id(thread_id, migrated)

        database = temporary_home / "thread_history_1.sqlite"
        with sqlite_connection(database) as connection:
            projection_schema(connection)
            connection.execute(
                "INSERT INTO thread_items VALUES (?, 'official')", (projection_id,)
            )
            connection.execute(
                "INSERT INTO thread_realtime_items VALUES (?, 'official-realtime')",
                (projection_id,),
            )
            starts = {}
            statuses = {}
            for record in records:
                payload = record.get("payload", {})
                event_type = payload.get("type")
                turn_id = payload.get("turn_id")
                if event_type == "task_started":
                    starts[turn_id] = record["ordinal"]
                    statuses[turn_id] = "inProgress"
                elif event_type == "task_complete":
                    statuses[turn_id] = "completed"
                elif event_type == "turn_aborted":
                    statuses[turn_id] = "interrupted"
            for turn_id, ordinal in starts.items():
                connection.execute(
                    "INSERT INTO thread_turns VALUES (?, ?, ?, ?)",
                    (projection_id, turn_id, ordinal, statuses[turn_id]),
                )
            connection.execute(
                "INSERT INTO thread_history_projection_state VALUES (?, ?, ?)",
                (projection_id, migrated.stat().st_size, ordinal_base + len(records)),
            )
        with sqlite_connection(state_database) as connection:
            connection.execute(
                "UPDATE threads SET rollout_path = ?, history_mode = 'paginated' WHERE id = ?",
                (str(migrated), thread_id),
            )

    def test_failed_completion_validates_official_failed_projection(self):
        for record in self.records:
            if record.get("payload", {}).get("type") == "task_complete":
                record["payload"]["error"] = {"message": "Bad Request"}
        write_records(self.rollout, self.records)

        def migrate(home, thread_id, cwd):
            self.fake_official_migrator(home, thread_id, cwd)
            with sqlite_connection(home / "thread_history_1.sqlite") as connection:
                connection.execute(
                    "UPDATE thread_turns SET status = 'failed' WHERE turn_id = ?",
                    (SECOND_TURN_ID,),
                )

        result = self.reprojector(migrator=migrate).reproject(THREAD_ID, self.rollout, self.root)
        self.assertEqual(2, result.turn_count)
        self.assertEqual(
            (SECOND_TURN_ID, "failed"),
            self.reprojector()._latest_turn_lifecycle(self.records),
        )

    def test_reprojects_with_official_output_and_preserves_other_threads(self):
        result = self.reprojector().reproject(THREAD_ID, self.rollout, self.root)

        self.assertEqual(("ordinal", "orphan_turn"), result.reasons)
        repaired = [json.loads(line) for line in self.rollout.read_text().splitlines()]
        self.assertEqual(list(range(len(repaired))), [row["ordinal"] for row in repaired])
        self.assertEqual(
            [FIRST_TURN_ID],
            [
                row["payload"]["turn_id"]
                for row in repaired
                if row.get("payload", {}).get("type") == "turn_aborted"
            ],
        )
        self.assertTrue(result.rollout_backup.is_file())
        self.assertTrue(result.projection_backup.is_file())
        with sqlite_connection(
            self.codex_home / "thread_history_1.sqlite"
        ) as connection:
            self.assertEqual(
                ("official",),
                connection.execute(
                    "SELECT value FROM thread_items WHERE thread_id = ?", (THREAD_ID,)
                ).fetchone(),
            )
            self.assertEqual(
                [(FIRST_TURN_ID, "interrupted"), (SECOND_TURN_ID, "completed")],
                connection.execute(
                    "SELECT turn_id, status FROM thread_turns WHERE thread_id = ? "
                    "ORDER BY rollout_ordinal",
                    (THREAD_ID,),
                ).fetchall(),
            )
            self.assertEqual(
                ("official-realtime",),
                connection.execute(
                    "SELECT value FROM thread_realtime_items WHERE thread_id = ?",
                    (THREAD_ID,),
                ).fetchone(),
            )
            self.assertEqual(
                ("other",),
                connection.execute(
                    "SELECT value FROM thread_items WHERE thread_id = ?",
                    (OTHER_THREAD_ID,),
                ).fetchone(),
            )

    def test_reprojects_a_structurally_valid_lagging_rollout(self):
        records = self.records[:2] + [
            {
                "timestamp": "2026-08-31T00:00:02Z",
                "ordinal": 2,
                "type": "event_msg",
                "payload": {"type": "task_complete", "turn_id": FIRST_TURN_ID},
            }
        ]
        write_records(self.rollout, records)

        result = self.reprojector().reproject(THREAD_ID, self.rollout, self.root)

        self.assertEqual(("projection_lag",), result.reasons)

    def test_migrator_failure_does_not_mutate_live_history(self):
        before_rollout = self.rollout.read_bytes()
        before_database = (self.codex_home / "thread_history_1.sqlite").read_bytes()

        def fail(*_args):
            raise RuntimeError("failed")

        with self.assertRaisesRegex(
            NativeHistoryReprojectionError, "官方迁移器执行失败"
        ):
            self.reprojector(fail).reproject(THREAD_ID, self.rollout, self.root)

        self.assertEqual(before_rollout, self.rollout.read_bytes())
        self.assertEqual(
            before_database,
            (self.codex_home / "thread_history_1.sqlite").read_bytes(),
        )

    def test_semantic_mismatch_does_not_mutate_live_history(self):
        before_rollout = self.rollout.read_bytes()

        def mismatch(temporary_home, thread_id, cwd):
            self.fake_official_migrator(temporary_home, thread_id, cwd)
            with sqlite_connection(temporary_home / "state_5.sqlite") as connection:
                migrated = Path(
                    connection.execute(
                        "SELECT rollout_path FROM threads WHERE id = ?", (thread_id,)
                    ).fetchone()[0]
                )
            records = [json.loads(line) for line in migrated.read_text().splitlines()]
            records[1]["payload"]["started_at"] = 999
            write_records(migrated, records)

        with self.assertRaisesRegex(NativeHistoryReprojectionError, "rollout 语义"):
            self.reprojector(mismatch).reproject(THREAD_ID, self.rollout, self.root)

        self.assertEqual(before_rollout, self.rollout.read_bytes())
        self.assertFalse(self.repair_dir.exists())

    def test_concurrent_rollout_append_is_preserved_and_repair_not_published(self):
        before_database = (self.codex_home / 'thread_history_1.sqlite').read_bytes()
        append = b'{"type":"event_msg","payload":{"type":"newer_native_event"}}\n'
        expected = self.rollout.read_bytes() + append
        def migrate(home, thread, cwd):
            self.fake_official_migrator(home, thread, cwd)
            with self.rollout.open('ab') as writer:
                writer.write(append)
        with self.assertRaisesRegex(NativeHistoryReprojectionError, '重建期间发生变化'):
            self.reprojector(migrate).reproject(THREAD_ID, self.rollout, self.root)
        self.assertEqual(expected, self.rollout.read_bytes())
        self.assertEqual(before_database, (self.codex_home / 'thread_history_1.sqlite').read_bytes())
        self.assertFalse(self.repair_dir.exists())

    def test_replaced_writer_lock_aborts_publish(self):
        before = self.rollout.read_bytes()
        def migrate(home, thread, cwd):
            self.fake_official_migrator(home, thread, cwd)
            lock = self.codex_home / 'thread-writer-locks' / f'{THREAD_ID}.lock'
            lock.unlink()
            lock.write_bytes(b'new writer lock')
        with self.assertRaisesRegex(NativeHistoryReprojectionError, 'writer lock 已更换'):
            self.reprojector(migrate).reproject(THREAD_ID, self.rollout, self.root)
        self.assertEqual(before, self.rollout.read_bytes())
        self.assertFalse(self.repair_dir.exists())

    def test_isolated_migration_contains_no_auth_config_or_live_rollout_paths(self):
        (self.codex_home / 'auth.json').write_text('private credential')
        (self.codex_home / 'config.toml').write_text('private configuration')
        with sqlite_connection(self.codex_home / 'state_5.sqlite') as database:
            database.execute('INSERT INTO threads VALUES(?,?,?)',
                             (OTHER_THREAD_ID, str(self.codex_home / 'sessions' / 'other.jsonl'), 'paginated'))
        def migrate(home, thread, cwd):
            self.assertFalse((home / 'auth.json').exists())
            self.assertFalse((home / 'config.toml').exists())
            with sqlite_connection(home / 'state_5.sqlite') as database:
                for (rollout,) in database.execute('SELECT rollout_path FROM threads'):
                    self.assertTrue(Path(rollout).is_relative_to(home))
            self.fake_official_migrator(home, thread, cwd)
        self.reprojector(migrate).reproject(THREAD_ID, self.rollout, self.root)

    def test_invalid_metadata_id_cannot_escape_the_isolated_home(self):
        with sqlite_connection(self.codex_home / 'state_5.sqlite') as database:
            database.execute('INSERT INTO threads VALUES(?,?,?)', ('/outside/path', '/outside/rollout', 'paginated'))
        migrate = Mock()
        with self.assertRaisesRegex(NativeHistoryReprojectionError, '元数据身份无效'):
            self.reprojector(migrate).reproject(THREAD_ID, self.rollout, self.root)
        migrate.assert_not_called()
        self.assertFalse(self.repair_dir.exists())

    def test_sqlite_commit_failure_restores_rollout_and_rolls_back_projection(self):
        before = self.rollout.read_bytes()
        database_path = self.codex_home / 'thread_history_1.sqlite'
        with sqlite_connection(database_path) as connection:
            rows = connection.execute('SELECT * FROM thread_turns ORDER BY thread_id').fetchall()
        original_connect = sqlite3.connect
        class FailingCommit(sqlite3.Connection):
            def commit(self):
                if self.in_transaction:
                    raise sqlite3.OperationalError('fixture commit failure')
                return super().commit()
        def connect(path, *args, **kwargs):
            if Path(path).resolve() == database_path.resolve():
                kwargs['factory'] = FailingCommit
            return original_connect(path, *args, **kwargs)
        with patch('rivo_relay.codex.native_reprojection.sqlite3.connect', side_effect=connect):
            with self.assertRaisesRegex(NativeHistoryReprojectionError, 'fixture commit failure'):
                self.reprojector().reproject(THREAD_ID, self.rollout, self.root)
        self.assertEqual(before, self.rollout.read_bytes())
        with sqlite_connection(database_path) as connection:
            self.assertEqual(rows, connection.execute('SELECT * FROM thread_turns ORDER BY thread_id').fetchall())
        self.assertEqual(1, len(list(self.repair_dir.glob('*.jsonl'))))
        self.assertEqual(1, len(list(self.repair_dir.glob('*.sqlite'))))

    def test_live_writer_lock_blocks_reprojection(self):
        lock_path = self.codex_home / "thread-writer-locks" / f"{THREAD_ID}.lock"
        lock_path.parent.mkdir(parents=True)
        with lock_path.open("a+b") as writer_lock:
            fcntl.flock(writer_lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            try:
                with self.assertRaisesRegex(
                    NativeHistoryReprojectionError, "重新打开"
                ):
                    self.reprojector().reproject(THREAD_ID, self.rollout, self.root)
            finally:
                fcntl.flock(writer_lock.fileno(), fcntl.LOCK_UN)

    def test_projection_backup_contains_only_affected_rows(self):
        database = self.codex_home / 'thread_history_1.sqlite'
        with sqlite_connection(database) as connection:
            connection.execute("UPDATE thread_items SET value=? WHERE thread_id=?",
                               ('x' * 2_000_000, OTHER_THREAD_ID))
        result = self.reprojector().reproject(THREAD_ID, self.rollout, self.root)
        with sqlite_connection(result.projection_backup) as connection:
            for table in ('thread_items', 'thread_turns', 'thread_realtime_items',
                          'thread_history_projection_state'):
                self.assertEqual([(THREAD_ID,)], connection.execute(
                    f'SELECT DISTINCT thread_id FROM {table}').fetchall())
            self.assertEqual([('stale',)], connection.execute(
                'SELECT value FROM thread_items').fetchall())
        self.assertLess(result.projection_backup.stat().st_size, 100_000)

    def test_oversized_backup_leaves_live_history_unchanged(self):
        original = self.rollout.read_bytes()
        with patch('rivo_relay.codex.native_reprojection.MAX_BACKUP_BYTES', 8192):
            with self.assertRaises(NativeHistoryReprojectionError):
                self.reprojector().reproject(THREAD_ID, self.rollout, self.root)
        self.assertEqual(original, self.rollout.read_bytes())
        with sqlite_connection(self.codex_home / 'thread_history_1.sqlite') as connection:
            self.assertEqual(('stale',), connection.execute(
                'SELECT value FROM thread_items WHERE thread_id=?', (THREAD_ID,)).fetchone())
        self.assertEqual([], list(self.repair_dir.glob('*.sqlite')))
        self.assertEqual([], list(self.repair_dir.glob('*.jsonl')))

    def test_global_retention_expiry_capacity_and_incomplete_pairs(self):
        self.repair_dir.mkdir()
        def pair(stamp, owner, age, size=10):
            base = self.repair_dir / f'{stamp}-{owner}-before-official-reprojection'
            paths = [base.with_suffix(suffix) for suffix in ('.jsonl', '.sqlite')]
            for path in paths:
                path.write_bytes(b'x' * size)
                os.utime(path, (2000 - age, 2000 - age))
            return paths
        expired = pair('old', THREAD_ID, 101)
        older = pair('older', THREAD_ID, 20)
        newest = pair('new', OTHER_THREAD_ID, 10)
        orphan = pair('orphan', OTHER_THREAD_ID, 0)
        orphan[0].unlink()
        unrelated = self.repair_dir / 'unrelated.sqlite'
        unrelated.write_bytes(b'keep')
        with patch('rivo_relay.codex.native_reprojection.BACKUP_MAX_AGE_SECONDS', 100), \
             patch('rivo_relay.codex.native_reprojection.MAX_BACKUP_BYTES', 30):
            self.reprojector()._prune_backups()
        self.assertTrue(all(path.exists() for path in newest))
        self.assertTrue(all(not path.exists() for path in expired + older + orphan))
        self.assertTrue(unrelated.exists())

    def test_retention_protects_active_rollback_pair(self):
        self.repair_dir.mkdir()
        pairs = []
        for index, owner in enumerate((THREAD_ID, OTHER_THREAD_ID)):
            base = self.repair_dir / f'{index}-{owner}-before-official-reprojection'
            paths = [base.with_suffix(suffix) for suffix in ('.jsonl', '.sqlite')]
            for path in paths:
                path.write_bytes(b'x' * 10)
                os.utime(path, (index, index))
            pairs.append(paths)
        with patch('rivo_relay.codex.native_reprojection.MAX_BACKUP_BYTES', 20):
            self.reprojector()._prune_backups(protected=pairs[0][0])
        self.assertTrue(all(path.exists() for path in pairs[0]))
        self.assertTrue(all(not path.exists() for path in pairs[1]))

    def test_backup_pairs_are_bounded_per_thread(self):
        self.repair_dir.mkdir()
        for index in range(4):
            prefix = (
                f"20260831T00000{index}000000Z-{THREAD_ID}"
                "-before-official-reprojection"
            )
            rollout = self.repair_dir / f"{prefix}.jsonl"
            projection = self.repair_dir / f"{prefix}.sqlite"
            rollout.write_text(str(index), encoding="utf-8")
            projection.write_text(str(index), encoding="utf-8")
            rollout.touch()
            projection.touch()
            timestamp = index + 1
            os.utime(rollout, ns=(timestamp, timestamp))

        self.reprojector()._prune_backups()

        self.assertEqual(
            2,
            len(
                list(
                    self.repair_dir.glob(
                        f"*-{THREAD_ID}-before-official-reprojection.jsonl"
                    )
                )
            ),
        )
        self.assertEqual(
            2,
            len(
                list(
                    self.repair_dir.glob(
                        f"*-{THREAD_ID}-before-official-reprojection.sqlite"
                    )
                )
            ),
        )

    def fork_fixture(self):
        # Frozen segment IDs are not necessarily metadata IDs or state DB rows.
        parent = self.codex_home / "sessions" / f"rollout-{OTHER_THREAD_ID}_{FIRST_TURN_ID}.jsonl"
        parent_bytes = (json.dumps({
            "ordinal": 0, "type": "session_meta", "payload": {"id": OTHER_THREAD_ID},
        }, ensure_ascii=False) + "\n" + json.dumps({
            "ordinal": 1, "type": "event_msg", "payload": {"type": "token_count"},
        }) + "\n").encode()
        parent.parent.mkdir(parents=True, exist_ok=True)
        parent.write_bytes(parent_bytes)
        self.records[0]["payload"]["history_base"] = {
            "thread_id": FIRST_TURN_ID, "end_ordinal_exclusive": 2,
            "end_byte_offset": len(parent_bytes),
        }
        self.records[2]["payload"] = {"type": "task_complete", "turn_id": FIRST_TURN_ID}
        self.records.pop()  # Only the final turn is orphaned; earlier bytes are immutable.
        for index, record in enumerate(self.records):
            record["ordinal"] = index + 2
        write_records(self.rollout, self.records)
        return parent, parent_bytes

    def test_fork_rebuild_preserves_parent_bytes_reference_and_offset(self):
        parent, parent_bytes = self.fork_fixture()
        legacy = Mock(side_effect=AssertionError("A fork must not use legacy migration"))

        def rebuild(home, thread_id, cwd):
            self.assertEqual(parent_bytes, (home / parent.relative_to(self.codex_home)).read_bytes())
            with sqlite_connection(home / "state_5.sqlite") as connection:
                paths = connection.execute("SELECT rollout_path FROM threads").fetchall()
            self.assertTrue(all(Path(row[0]).is_relative_to(home) for row in paths))
            self.fake_official_migrator(home, thread_id, cwd)

        result = self.reprojector(legacy, rebuild).reproject(THREAD_ID, self.rollout, self.root)
        actual = [json.loads(line) for line in self.rollout.read_text().splitlines()]
        self.assertEqual(self.records[0]["payload"]["history_base"], actual[0]["payload"]["history_base"])
        self.assertEqual(list(range(2, 2 + len(actual))), [r["ordinal"] for r in actual])
        self.assertEqual(("orphan_turn",), result.reasons)
        self.assertEqual(parent_bytes, parent.read_bytes())
        legacy.assert_not_called()

    def test_fork_takeover_preflight_does_not_publish(self):
        self.fork_fixture()
        before = self.rollout.read_bytes()
        database = (self.codex_home / "thread_history_1.sqlite").read_bytes()
        self.reprojector().check_takeover(THREAD_ID, self.rollout, self.root)
        self.assertEqual(before, self.rollout.read_bytes())
        self.assertEqual(database, (self.codex_home / "thread_history_1.sqlite").read_bytes())
        self.assertFalse(self.repair_dir.exists())

    def test_same_thread_id_can_reference_an_older_segment(self):
        parent, _ = self.fork_fixture()
        original_path = self.rollout
        segment = original_path.with_name(f"rollout-{THREAD_ID}_{SECOND_TURN_ID}.jsonl")
        self.rollout.rename(segment)
        self.rollout = segment
        parent.rename(original_path)
        self.records[0]["payload"]["history_base"]["thread_id"] = THREAD_ID
        write_records(self.rollout, self.records)
        with sqlite_connection(self.codex_home / "state_5.sqlite") as connection:
            connection.execute("UPDATE threads SET rollout_path=? WHERE id=?", (str(segment), THREAD_ID))
        self.reprojector().reproject(THREAD_ID, self.rollout, self.root)
        self.assertTrue(original_path.is_file())
        with sqlite_connection(self.codex_home / "thread_history_1.sqlite") as connection:
            self.assertEqual(("stale",), connection.execute("SELECT value FROM thread_items WHERE thread_id=?", (THREAD_ID,)).fetchone())
            self.assertEqual(("official",), connection.execute("SELECT value FROM thread_items WHERE thread_id=?", (SECOND_TURN_ID,)).fetchone())

    def test_fork_repair_preserves_referenced_prefix_byte_for_byte(self):
        self.fork_fixture()
        raw = "".join(json.dumps(record, ensure_ascii=True) + "\n" for record in self.records).encode()
        self.rollout.write_bytes(raw)

        def preserve(home, thread_id, cwd):
            prepared = self.reprojector()._isolated_rollout_path(home, thread_id).read_bytes()
            self.assertTrue(prepared.startswith(raw))
            self.fake_official_migrator(home, thread_id, cwd)
            migrated = self.reprojector()._isolated_rollout_path(home, thread_id)
            migrated.write_bytes(prepared)
            with sqlite_connection(home / "thread_history_1.sqlite") as connection:
                connection.execute("UPDATE thread_history_projection_state SET next_rollout_byte_offset=? WHERE thread_id=?", (len(prepared), thread_id))

        self.reprojector(paginated_rebuilder=preserve).reproject(THREAD_ID, self.rollout, self.root)
        self.assertTrue(self.rollout.read_bytes().startswith(raw))

    def test_fork_rejects_equivalent_reserialization_that_breaks_byte_offsets(self):
        self.fork_fixture()
        raw = "".join(json.dumps(record) + "\n" for record in self.records).encode()
        self.rollout.write_bytes(raw)
        with self.assertRaisesRegex(NativeHistoryReprojectionError, "字节边界"):
            self.reprojector().reproject(THREAD_ID, self.rollout, self.root)
        self.assertEqual(raw, self.rollout.read_bytes())

    def test_fork_refuses_rewriting_existing_ordinals(self):
        self.fork_fixture()
        self.records[1]["ordinal"] = 99
        write_records(self.rollout, self.records)
        before = self.rollout.read_bytes()
        with self.assertRaisesRegex(NativeHistoryReprojectionError, "拒绝破坏"):
            self.reprojector().reproject(THREAD_ID, self.rollout, self.root)
        self.assertEqual(before, self.rollout.read_bytes())

    def test_fork_parent_boundary_failure_does_not_run_rebuilder(self):
        parent, _ = self.fork_fixture()
        parent.write_bytes(parent.read_bytes()[:-10])
        rebuild = Mock()
        with self.assertRaisesRegex(NativeHistoryReprojectionError, "短于引用边界"):
            self.reprojector(paginated_rebuilder=rebuild).reproject(THREAD_ID, self.rollout, self.root)
        rebuild.assert_not_called()
        self.assertFalse(self.repair_dir.exists())

    def test_fork_rebuilder_cannot_drop_parent_reference(self):
        self.fork_fixture()
        before = self.rollout.read_bytes()

        def drop_reference(home, thread_id, cwd):
            self.fake_official_migrator(home, thread_id, cwd)
            rollout = self.reprojector()._isolated_rollout_path(home, thread_id)
            records = [json.loads(line) for line in rollout.read_text().splitlines()]
            del records[0]["payload"]["history_base"]
            write_records(rollout, records)

        with self.assertRaisesRegex(NativeHistoryReprojectionError, "rollout 语义"):
            self.reprojector(paginated_rebuilder=drop_reference).reproject(THREAD_ID, self.rollout, self.root)
        self.assertEqual(before, self.rollout.read_bytes())

    def test_fork_allows_only_unchanged_resume_settings(self):
        self.fork_fixture()
        settings = {"model": "model-a", "reasoning_effort": "high", "service_tier": "default"}
        self.records.append({"ordinal": 2 + len(self.records), "type": "event_msg", "payload": {
            "type": "thread_settings_applied", "thread_settings": settings,
        }})
        write_records(self.rollout, self.records)
        before = self.rollout.read_bytes()

        def rebuild(home, thread_id, cwd, *, changed):
            self.fake_official_migrator(home, thread_id, cwd)
            rollout = self.reprojector()._isolated_rollout_path(home, thread_id)
            records = [json.loads(line) for line in rollout.read_text().splitlines()]
            records.append({"ordinal": 2 + len(records), "type": "event_msg", "payload": {
                "type": "thread_settings_applied",
                "thread_settings": {**settings, "model": "different" if changed else "model-a"},
            }})
            write_records(rollout, records)
            with sqlite_connection(home / "thread_history_1.sqlite") as connection:
                connection.execute("UPDATE thread_history_projection_state SET next_rollout_byte_offset=?, next_rollout_ordinal=? WHERE thread_id=?", (rollout.stat().st_size, 2 + len(records), thread_id))

        with self.assertRaisesRegex(NativeHistoryReprojectionError, "改变了会话设置"):
            self.reprojector(paginated_rebuilder=lambda *args: rebuild(*args, changed=True)).reproject(THREAD_ID, self.rollout, self.root)
        self.assertEqual(before, self.rollout.read_bytes())
        self.reprojector(paginated_rebuilder=lambda *args: rebuild(*args, changed=False)).reproject(THREAD_ID, self.rollout, self.root)

    def test_nonfork_paginated_allows_only_unchanged_resume_settings(self):
        self.records = self.records[:2]
        settings = {"model": "model-a", "reasoning_effort": "high", "service_tier": "default"}
        self.records.append({"ordinal": len(self.records), "type": "event_msg", "payload": {
            "type": "thread_settings_applied", "thread_settings": settings,
        }})
        write_records(self.rollout, self.records)
        before = self.rollout.read_bytes()

        def rebuild(home, thread_id, cwd, *, changed):
            self.fake_paginated_rebuilder(home, thread_id, cwd)
            rollout = self.reprojector()._isolated_rollout_path(home, thread_id)
            records = [json.loads(line) for line in rollout.read_text().splitlines()]
            records.append({"ordinal": len(records), "type": "event_msg", "payload": {
                "type": "thread_settings_applied",
                "thread_settings": {**settings, "model": "different" if changed else "model-a"},
            }})
            write_records(rollout, records)
            with sqlite_connection(home / "thread_history_1.sqlite") as connection:
                connection.execute("UPDATE thread_history_projection_state SET next_rollout_byte_offset=?, next_rollout_ordinal=? WHERE thread_id=?", (rollout.stat().st_size, len(records), thread_id))

        with self.assertRaisesRegex(NativeHistoryReprojectionError, "改变了会话设置"):
            self.reprojector(paginated_rebuilder=lambda *args: rebuild(*args, changed=True)).reproject(THREAD_ID, self.rollout, self.root)
        self.assertEqual(before, self.rollout.read_bytes())
        self.reprojector(paginated_rebuilder=lambda *args: rebuild(*args, changed=False)).reproject(THREAD_ID, self.rollout, self.root)

    def test_pretty_json_migration_outcome_is_strictly_parsed(self):
        output = json.dumps(
            {
                "outcomes": [
                    {"thread_id": THREAD_ID, "status": "migrated", "message": None}
                ]
            },
            indent=2,
        )

        self.assertEqual(
            [{"thread_id": THREAD_ID, "status": "migrated", "message": None}],
            NativeHistoryReprojector._migration_outcomes(output),
        )


if __name__ == "__main__":
    unittest.main()
