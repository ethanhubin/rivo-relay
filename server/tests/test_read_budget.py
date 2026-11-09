import queue
import socket
import threading
import time
import unittest

from rivo_relay.read_budget import ReadStopped, checkpoint, read_budget, read_reply
from rivo_relay.operation_gates import ThreadGates
from rivo_relay.codex.connection import NativeConnection


class ReadBudgetTests(unittest.TestCase):
    def test_deadline_and_peer_disconnect_have_distinct_safe_reasons(self):
        with self.assertRaises(ReadStopped) as stopped:
            with read_budget(seconds=0):
                pass
        self.assertEqual('deadline', stopped.exception.reason)
        server, client = socket.socketpair()
        try:
            client.close()
            with self.assertRaises(ReadStopped) as stopped:
                with read_budget(server):
                    pass
            self.assertEqual('client_disconnected', stopped.exception.reason)
        finally:
            server.close()
            client.close()
    def test_disconnected_read_stops_waiting(self):
        server, client = socket.socketpair()
        try:
            with self.assertRaises(ReadStopped), read_budget(server):
                client.close()
                read_reply(queue.Queue(), 30)
        finally:
            server.close()
            client.close()

    def test_deadline_applies_across_multiple_waits_and_context_resets(self):
        replies = queue.Queue()
        replies.put('ready')
        with self.assertRaises(ReadStopped), read_budget(seconds=.03):
            self.assertEqual(read_reply(replies, 30), 'ready')
            read_reply(replies, 30)
        checkpoint()
        replies.put('after')
        self.assertEqual(read_reply(replies, 1), 'after')

    def test_cancelled_gate_wait_releases_partial_scope(self):
        gates = ThreadGates()
        ready, release = threading.Event(), threading.Event()
        def hold():
            with gates.hold('b'):
                ready.set()
                release.wait(2)
        worker = threading.Thread(target=hold)
        worker.start()
        try:
            self.assertTrue(ready.wait(1))
            with self.assertRaises(ReadStopped), read_budget(seconds=.02), gates.hold('a', 'b'):
                self.fail('must not enter blocked gate')
            with gates.hold('a'):
                pass
        finally:
            release.set()
            worker.join(2)
        self.assertEqual(gates._entries, {})

    def test_read_rpc_cancellation_cleans_pending_without_closing_runtime(self):
        native = NativeConnection()
        class Socket:
            def send(self, _):
                pass
        native._socket = Socket()
        with self.assertRaises(ReadStopped), read_budget(seconds=.02):
            native.request('thread/read', {'threadId': 'test'})
        self.assertEqual(native._pending, {})
        self.assertIsNotNone(native._socket)

    def test_submitted_mutation_waits_for_result_past_read_deadline(self):
        native = NativeConnection()
        workers = []
        class Socket:
            def send(self, _):
                reply = next(iter(native._pending.values()))
                timer = threading.Timer(.04, lambda: reply.put({'result': {'ok': True}}))
                workers.append(timer)
                timer.start()
        native._socket = Socket()
        with self.assertRaises(ReadStopped), read_budget(seconds=.01):
            self.assertEqual(native.request('thread/resume', {}, timeout=1), {'ok': True})
        for worker in workers:
            worker.join()
        self.assertEqual(native._pending, {})
