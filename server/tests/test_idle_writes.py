"""Unchanged liveness/observations must not cause one disk write per poll."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from rivo_relay.database import Database
from rivo_relay.codex.observations import Observations
from rivo_relay.gateway.update_status import ReadinessPublisher, is_ready


class ReadinessWriteTests(unittest.TestCase):
    def test_38_supervision_ticks_only_write_eight_heartbeats(self):
        publisher = ReadinessPublisher('/unused', 'generation')
        with patch('rivo_relay.gateway.update_status.write_ready') as write:
            for second in range(38):
                with patch('rivo_relay.gateway.update_status.time.monotonic', return_value=second):
                    publisher.publish(True)
            self.assertEqual(8, write.call_count)

    def test_disconnect_reconnect_and_restart_publish_immediately(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'connected.json'
            with patch('rivo_relay.gateway.update_status.time.monotonic', return_value=0):
                publisher = ReadinessPublisher(path, 'one')
                publisher.publish(True)
                self.assertTrue(is_ready(path, 'one'))
                publisher.publish(False)
                self.assertFalse(is_ready(path, 'one'))
                publisher.publish(True)
                self.assertTrue(is_ready(path, 'one'))
                ReadinessPublisher(path, 'two').publish(True)
                self.assertFalse(is_ready(path, 'one'))
                self.assertTrue(is_ready(path, 'two'))
            with patch('rivo_relay.gateway.update_status.time.time', return_value=json.loads(path.read_text())['updatedAt'] + 16):
                self.assertFalse(is_ready(path, 'two'))

    def test_write_failure_is_retried_without_waiting_five_seconds(self):
        publisher = ReadinessPublisher('/unused', 'generation')
        with patch('rivo_relay.gateway.update_status.time.monotonic', return_value=0), \
                patch('rivo_relay.gateway.update_status.write_ready', side_effect=[OSError(), None]) as write:
            with self.assertRaises(OSError):
                publisher.publish(True)
            publisher.publish(True)
            self.assertEqual(2, write.call_count)


class ObservationWriteTests(unittest.TestCase):
    def test_identical_observation_does_not_change_wal_or_timestamp(self):
        with tempfile.TemporaryDirectory() as directory:
            db = Database(Path(directory) / 'relay.sqlite3')
            self.addCleanup(db.close)
            observations = Observations(db)
            observations.put('thread', 'externalWorkspace', '', {'path': '/worktree', 'projectId': 'p'})
            wal = Path(str(db.path) + '-wal')
            before = wal.stat()
            with db.read() as c:
                changed = c.total_changes
                stamp = c.execute('SELECT updated_at FROM native_observations').fetchone()[0]
            for _ in range(38):
                observations.put('thread', 'externalWorkspace', '', {'projectId': 'p', 'path': '/worktree'})
            with db.read() as c:
                self.assertEqual(changed, c.total_changes)
                self.assertEqual(stamp, c.execute('SELECT updated_at FROM native_observations').fetchone()[0])
            self.assertEqual(before.st_mtime_ns, wal.stat().st_mtime_ns)
            self.assertEqual(before.st_size, wal.stat().st_size)
            observations.put('thread', 'externalWorkspace', '', {'projectId': 'p', 'path': '/new'})
            self.assertEqual('/new', observations.get('thread')[('externalWorkspace', '')]['path'])
            with db.read() as c:
                self.assertEqual(changed + 1, c.total_changes)

    def test_changed_rows_still_prune_bounded_records_and_preserve_ownership(self):
        db = Database(':memory:')
        self.addCleanup(db.close)
        observations = Observations(db)
        observations.put('thread', 'ownership', '', {'released': True})
        for index in range(1002):
            observations.put('thread', 'usage', str(index), {'value': index})
        rows = observations.get('thread')
        self.assertEqual(1001, len(rows))
        self.assertTrue(rows[('ownership', '')]['released'])
        self.assertNotIn(('usage', '0'), rows)
        self.assertIn(('usage', '1001'), rows)
