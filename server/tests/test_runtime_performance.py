"""Concurrency and bounded work, using fixtures rather than production services."""
import io
import json
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from rivo_relay.codex.files import upload_attachment_stream
from rivo_relay.codex.owned_writer import SessionWriters
from rivo_relay.codex.runtime import RuntimeConnection
from rivo_relay.database import Database
from rivo_relay.events import EventBus
from rivo_relay.media import MediaService, atomic_json, MediaError
from rivo_relay.operation_gates import ThreadGates
from rivo_relay.queue import ExecutionQueue
from relay_test_support.native import Backend
from relay_test_support.media import png, NativeMedia
from relay_test_support.native import QueueNative


class RuntimePerformanceTests(unittest.TestCase):
    def test_shutdown_during_queue_read_never_admits_a_new_dispatch(self):
        db = Database(':memory:'); self.addCleanup(db.close)
        with db.transaction() as c:
            c.execute("INSERT INTO connections VALUES ('phone','token','phone',0,NULL)")
        native = QueueNative(); native.operation_gate = ThreadGates().hold
        queue = ExecutionQueue(db, native, EventBus()); self.addCleanup(queue.close)
        queue.enqueue('thread', 'item', 'phone', {'input': [{'type': 'text', 'text': 'queued'}]})
        entered, release = threading.Event(), threading.Event()
        original = native.get_session
        def blocked_read(tid):
            entered.set(); self.assertTrue(release.wait(3)); return original(tid)
        native.get_session = blocked_read
        with ThreadPoolExecutor(max_workers=2) as workers:
            tick = workers.submit(queue.tick)
            self.assertTrue(entered.wait(1))
            closing = workers.submit(queue.close)
            try:
                self.assertTrue(queue.stopping.wait(1))
                self.assertFalse(closing.done())
            finally:
                release.set()
            tick.result(3); closing.result(3)
        self.assertEqual('waiting', queue.list('thread')[0]['status'])
        self.assertFalse(native.sent)

    def test_slow_queue_thread_does_not_block_another_and_claim_is_not_duplicated(self):
        db = Database(':memory:'); self.addCleanup(db.close)
        with db.transaction() as c:
            c.execute("INSERT INTO connections VALUES ('phone','token','phone',0,NULL)")
        native = QueueNative(); native.operation_gate = ThreadGates().hold
        queue = ExecutionQueue(db, native, EventBus()); self.addCleanup(queue.close)
        for tid in ('slow', 'fast'):
            queue.enqueue(tid, tid, 'phone', {'input': [{'type': 'text', 'text': tid}]})
        entered, release, fast = threading.Event(), threading.Event(), threading.Event()
        sent = []
        def send(tid, **kwargs):
            sent.append(tid)
            if tid == 'slow': entered.set(); self.assertTrue(release.wait(3))
            else: fast.set()
            return {'threadId': tid, 'turnId': tid + '-turn'}
        native.send_queued = send
        with ThreadPoolExecutor(max_workers=2) as workers:
            pending = workers.submit(queue.tick)
            try:
                self.assertTrue(entered.wait(1)); self.assertTrue(fast.wait(1))
                self.assertEqual('dispatching', queue.list('slow')[0]['status'])
            finally: release.set()
            pending.result(3)
        self.assertCountEqual(['slow', 'fast'], sent)

    def test_live_writer_does_not_wait_for_single_metadata_reconnect(self):
        metadata = Backend(); pool = SessionWriters(metadata); runtime = RuntimeConnection(native=pool, desktop=Backend(), mode='app_server')
        self.addCleanup(runtime.close)
        writer = Backend(); pool._writers['live'] = writer
        runtime._pins['live'] = 'app_server'
        entered, release = threading.Event(), threading.Event(); calls = []
        metadata.closed = True
        def start():
            calls.append(1); entered.set(); self.assertTrue(release.wait(3)); metadata.closed = False
        metadata.start = start
        try:
            for _ in range(10): self.assertTrue(runtime.start()['connected'])
            self.assertTrue(entered.wait(1))
            runtime.request('thread/read', {'threadId': 'live', 'includeTurns': False})
            self.assertEqual(1, len(calls))
            self.assertTrue(metadata.closed)
        finally: release.set()

    def test_upload_reuses_one_helper_and_closes_it_even_on_failure(self):
        metadata = Backend(); created = []
        def factory(descriptor, on_event):
            helper = Backend(); helper.on_event = on_event
            helper.request = lambda method, params=None, **kw: {'exitCode': 0} if method == 'command/exec' else {}
            created.append(helper); return helper
        pool = SessionWriters(metadata, factory=factory); self.addCleanup(pool.close)
        descriptor = {'codexHome': '/fixture', 'executable': '/fixture/codex'}
        with patch('rivo_relay.codex.host_probe.local_native_host_info', return_value=descriptor):
            result = upload_attachment_stream(pool, '/fixture', 'large.bin', io.BytesIO(b'x' * (20 * 1024 * 1024)), 20 * 1024 * 1024)
            self.assertEqual(20 * 1024 * 1024, result['size'])
            self.assertEqual(1, len(created)); self.assertTrue(created[0].closed)
            with self.assertRaises(Exception):
                upload_attachment_stream(pool, '/fixture', 'short.bin', io.BytesIO(b'x'), 10)
            self.assertEqual(2, len(created)); self.assertTrue(created[1].closed)
            self.assertFalse(pool._writers); self.assertFalse(metadata.closed)

    def test_queue_indexes_migrate_existing_schema_without_losing_claims(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'relay.sqlite3'
            db = Database(path)
            with db.transaction() as c:
                c.execute('DROP INDEX queue_active_thread_order')
                c.execute("INSERT INTO connections VALUES ('phone','token','phone',0,NULL)")
                c.execute("INSERT INTO queued_tasks VALUES ('q','phone','t','{}','done',1,1,NULL,NULL,0)")
            db.close(); db = Database(path)
            try:
                with db.read() as c:
                    self.assertEqual(1, c.execute('SELECT count(*) FROM queued_tasks').fetchone()[0])
                    plan = c.execute("EXPLAIN QUERY PLAN SELECT * FROM queued_tasks WHERE thread_id='t' AND status NOT IN ('done','cancelled') ORDER BY position,created_at,id").fetchall()
                    self.assertTrue(any('queue_active_thread_order' in row[3] for row in plan))
            finally: db.close()


class MediaPerformanceTests(unittest.TestCase):
    def test_cached_image_finishes_before_unrelated_origin_and_same_origin_is_coalesced(self):
        with tempfile.TemporaryDirectory() as directory:
            entered, release = threading.Event(), threading.Event(); calls = []
            def fetch(source, target):
                calls.append(source)
                if source.endswith('slow'): entered.set(); self.assertTrue(release.wait(3))
                target.write_bytes(png()); return 'image/png'
            media = MediaService(directory, NativeMedia(), fetch_public=fetch); media.close()
            media.resolve('https://fixture/cached')
            with ThreadPoolExecutor(max_workers=3) as workers:
                slow = workers.submit(media.resolve, 'https://fixture/slow')
                self.assertTrue(entered.wait(1))
                duplicate = workers.submit(media.resolve, 'https://fixture/slow')
                try:
                    cached = workers.submit(media.open_source, 'https://fixture/cached').result(1)
                    with cached[0] as stream: self.assertEqual(png(), stream.read())
                    self.assertFalse(slow.done())
                finally: release.set()
                slow.result(3); duplicate.result(3)
            self.assertEqual(1, calls.count('https://fixture/slow')); self.assertFalse(media._pins)

    def test_library_index_reuses_metadata_and_invalidates_favorite_delete_and_content(self):
        with tempfile.TemporaryDirectory() as directory:
            media = MediaService(directory, NativeMedia()); media.close()
            record = media.import_source('/fixture/image.png', 'thread')
            identity = record['id']; media.list('thread')
            with patch('rivo_relay.media.read_record', wraps=__import__('rivo_relay.media', fromlist=['read_record']).read_record) as read:
                self.assertEqual(identity, media.list('thread')[0]['id'])
                self.assertEqual(0, read.call_count)
            media.favorite(identity, True)
            self.assertTrue(media.list('thread', favorite=True)[0]['favorite'])
            path, _, _ = media.stored(identity)
            with patch('rivo_relay.media.hashlib.file_digest', side_effect=AssertionError('unchanged file should not be rehashed')):
                stream, _, _ = media.open_stored(identity); stream.close()
            path.write_bytes(b'changed')
            with self.assertRaises(MediaError): media.open_stored(identity)
            media.delete(identity); self.assertEqual([], media.list('thread'))

    def test_pending_binary_payload_is_not_retained_in_listing_index(self):
        with tempfile.TemporaryDirectory() as directory:
            media = MediaService(directory, NativeMedia()); media.close()
            identity = 'a' * 64
            atomic_json(media.library / (identity + '.json'), {'id': identity, 'threadId': 't', 'status': 'pending', 'result': 'x' * 100000})
            media.list('t')
            self.assertNotIn('result', next(iter(media._record_cache.values()))[1])
