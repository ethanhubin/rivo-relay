"""In-memory event transport tests; these do not validate a native runtime."""

from concurrent.futures import ThreadPoolExecutor
import threading
import unittest

from rivo_relay.events import EventBus


class EventTests(unittest.TestCase):
    def test_initial_and_foreign_epoch_cursors_require_snapshot(self):
        bus = EventBus(capacity=2)
        bus.publish({"method": "synthetic/item"})
        cursor, events, reset = bus.read(None, timeout=0)
        self.assertTrue(reset)
        self.assertEqual(events, [])
        self.assertEqual(cursor, bus.cursor(1))
        restarted = EventBus(capacity=2)
        self.assertTrue(restarted.read(cursor, timeout=0)[2])

    def test_retained_events_are_ordered_and_overflow_never_returns_partial_history(self):
        bus = EventBus(capacity=2)
        start = bus.cursor(0)
        bus.publish({"sequence": 1})
        bus.publish({"sequence": 2})
        cursor, events, reset = bus.read(start, timeout=0)
        self.assertFalse(reset)
        self.assertEqual(events, [{"sequence": 1}, {"sequence": 2}])
        self.assertEqual(cursor, bus.cursor(2))
        bus.publish({"sequence": 3})
        cursor, events, reset = bus.read(start, timeout=0)
        self.assertTrue(reset)
        self.assertEqual(events, [])
        self.assertEqual(cursor, bus.cursor(3))
        self.assertEqual(bus.read(bus.cursor(1), timeout=0)[1], [{"sequence": 2}, {"sequence": 3}])

    def test_malformed_negative_and_future_cursors_reset(self):
        bus = EventBus()
        for cursor in ("broken", bus.cursor(-1), bus.cursor(1), bus.cursor("not-a-number")):
            with self.subTest(cursor=cursor):
                self.assertEqual(bus.read(cursor, timeout=0)[1:], ([], True))

    def test_publish_and_close_wake_waiters(self):
        bus = EventBus()
        entered = threading.Event()

        def read():
            entered.set()
            return bus.read(bus.cursor(0), timeout=10)

        with ThreadPoolExecutor(max_workers=1) as pool:
            result = pool.submit(read)
            self.assertTrue(entered.wait(1))
            bus.publish({"method": "synthetic/notice"})
            self.assertEqual(result.result(timeout=1)[1], [{"method": "synthetic/notice"}])
        bus = EventBus()
        entered.clear()
        with ThreadPoolExecutor(max_workers=1) as pool:
            result = pool.submit(read)
            self.assertTrue(entered.wait(1))
            bus.close()
            self.assertEqual(result.result(timeout=1)[1:], ([], False))

    def test_zero_capacity_is_rejected_instead_of_silently_dropping_notifications(self):
        with self.assertRaises(ValueError):
            EventBus(capacity=0)


if __name__ == "__main__":
    unittest.main()
