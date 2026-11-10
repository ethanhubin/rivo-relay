"""Notification batching, boundaries and disconnect delivery."""
import unittest


class NotificationQueueTests(unittest.TestCase):
    def test_notification_bursts_are_lossless_and_lifecycle_is_a_barrier(self):
        from rivo_relay.codex.notifications import NotificationQueue
        q = NotificationQueue(maxsize=4)
        event = {'method': 'item/agentMessage/delta', 'connectionId': 'one',
                 'params': {'threadId': 't', 'turnId': 'turn', 'itemId': 'i', 'delta': '😀'}}
        for _ in range(1000):
            q.put_nowait(event)
        self.assertEqual(1, q.qsize())
        barrier = {'method': 'item/completed', 'params': {}}
        q.put_nowait(barrier)
        q.put_nowait(event)
        self.assertEqual('😀' * 1000, q.get_nowait()['params']['delta'])
        self.assertEqual(barrier, q.get_nowait())
        self.assertEqual('😀', q.get_nowait()['params']['delta'])
        self.assertEqual('😀', event['params']['delta'])

    def test_notification_bounds_and_identity_never_truncate_or_mix(self):
        import queue
        from rivo_relay.codex.notifications import NotificationQueue
        q = NotificationQueue(maxsize=3, max_delta_chars=3)
        def event(thread='t', generation='one', delta='ab'):
            return {'method': 'item/agentMessage/delta', 'connectionId': generation,
                    'params': {'threadId': thread, 'turnId': 'turn', 'itemId': 'i', 'delta': delta}}
        q.put_nowait(event())
        q.put_nowait(event())
        q.put_nowait(event(thread='other'))
        with self.assertRaises(queue.Full):
            q.put_nowait(event(generation='two'))
        self.assertEqual(['ab', 'ab', 'ab'], [q.get_nowait()['params']['delta'] for _ in range(3)])
        q.put_nowait(event(delta='a'))
        q.put_nowait(event(generation='two', delta='b'))
        self.assertEqual(2, q.qsize())

    def test_disconnect_survives_saturated_notifications(self):
        from rivo_relay.codex.notifications import NotificationQueue
        q = NotificationQueue(maxsize=1)
        q.put_nowait({'method': 'turn/started'})
        q.put_disconnect({'method': 'relay/nativeDisconnected'})
        self.assertEqual('relay/nativeDisconnected', q.get_nowait()['method'])
        q.task_done()
        self.assertEqual(0, q.unfinished_tasks)
