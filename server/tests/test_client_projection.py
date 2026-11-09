"""Mobile payload reduction without altering canonical history or approvals."""
import copy
import json
import unittest

from rivo_relay.client_projection import client_history, client_event
from rivo_relay.events import EventBus


class ClientProjectionTests(unittest.TestCase):
    def setUp(self):
        self.command = {"id": "cmd", "type": "commandExecution", "status": "completed",
                        "exitCode": 1, "durationMs": 12, "command": "private command",
                        "aggregatedOutput": "x" * 100_000, "commandActions": [{"command": "private"}]}
        self.turn = {"id": "turn", "items": [self.command,
                     {"id": "file", "type": "fileChange", "changes": [{"diff": "keep"}]}]}

    def test_history_is_small_and_preserves_identity_other_items_and_approvals(self):
        request = {"method": "item/commandExecution/requestApproval", "params": {"command": "private command"}}
        source = {"thread": {"id": "thread", "turns": [self.turn]}, "requests": [request],
                  "nextCursor": "next", "nativeRevision": "revision"}
        original = copy.deepcopy(source)
        result = client_history(source)
        self.assertEqual(source, original)
        self.assertLess(len(json.dumps(result)), 1000)
        self.assertEqual(result["requests"], [request])
        self.assertEqual(result["nextCursor"], "next")
        self.assertEqual(result["nativeRevision"], "revision")
        item = result["thread"]["turns"][0]["items"][0]
        self.assertEqual(item, {key: self.command[key] for key in ("id", "type", "status", "exitCode", "durationMs")})
        self.assertEqual(result["thread"]["turns"][0]["items"][1], self.turn["items"][1])
        self.assertEqual(client_history({"turns": [self.turn]})["turns"], result["thread"]["turns"])

    def test_lifecycle_payloads_are_compact_without_mutating_native_events(self):
        for method, key, value in [
            ("item/started", "item", self.command), ("item/completed", "item", self.command),
            ("turn/started", "turn", self.turn), ("turn/completed", "turn", self.turn),
            ("thread/started", "thread", {"turns": [self.turn]})
        ]:
            event = {"method": method, "params": {"threadId": "thread", key: value}}
            original = copy.deepcopy(event)
            result = client_event(event)
            self.assertLess(len(json.dumps(result)), 1000)
            self.assertEqual(result["params"]["threadId"], "thread")
            self.assertEqual(event, original)

    def test_output_flood_does_not_consume_replay_capacity_or_cursor(self):
        bus = EventBus(capacity=2)
        start = bus.cursor(0)
        started = {"method": "item/started", "params": {"item": self.command}}
        bus.publish(started)
        for _ in range(10):
            bus.publish({"method": "item/commandExecution/outputDelta", "params": {"delta": "large output"}})
        approval = {"id": 7, "method": "item/commandExecution/requestApproval",
                    "params": {"command": "private command", "commandActions": ["keep"]}}
        bus.publish(approval)
        cursor, events, reset = bus.read(start, timeout=0)
        self.assertFalse(reset)
        self.assertEqual(cursor, bus.cursor(2))
        self.assertEqual(events, [client_event(started), approval])
        agent = {"method": "item/agentMessage/delta", "params": {"delta": "answer"}}
        self.assertEqual(client_event(agent), agent)

class GeneratedImageProjectionTests(unittest.TestCase):
    def test_inline_results_become_stable_media_urls_only_after_registration(self):
        from rivo_relay.media import MediaService
        item = {'id': 'image', 'type': 'imageGeneration', 'status': 'completed', 'result': 'A' * 100_000}
        source = {'thread': {'id': 'thread', 'turns': [{'id': 'turn', 'status': 'completed', 'items': [item]}]}}
        self.assertIs(client_history(source)['thread']['turns'][0]['items'][0], item)
        projected = client_history(source, compact_images=True)['thread']['turns'][0]['items'][0]
        self.assertEqual('/v1/media/library/' + MediaService._identity('thread', 'turn', 'image') + '/content', projected['result'])
        self.assertTrue(projected['mediaRef'])
        self.assertLess(len(json.dumps(projected)), 300)
        self.assertEqual('A' * 100_000, item['result'])
        for result in ('https://example.com/image.png', '/native/image.png', 'C:\\native\\image.png'):
            item['result'] = result
            self.assertEqual(result, client_history(source, compact_images=True)['thread']['turns'][0]['items'][0]['result'])

    def test_incomplete_or_failed_images_keep_fallback_and_completed_events_compact(self):
        item = {'id': 'image', 'type': 'imageGeneration', 'result': 'AAAA'}
        event = {'method': 'item/completed', 'params': {'threadId': 'thread', 'turnId': 'turn', 'item': item}}
        self.assertTrue(client_event(event, compact_images=True)['params']['item']['mediaRef'])
        self.assertEqual('AAAA', item['result'])
        for status in ('failed', 'inProgress', 'interrupted'):
            item['status'] = status
            self.assertEqual('AAAA', client_event(event, compact_images=True)['params']['item']['result'])
        item.pop('status')
        event['method'] = 'item/started'
        self.assertEqual('AAAA', client_event(event, compact_images=True)['params']['item']['result'])

    def test_app_registers_completed_event_before_bus_projection_and_falls_back_on_failure(self):
        import tempfile
        from pathlib import Path
        from types import SimpleNamespace
        from unittest.mock import patch
        from rivo_relay.app import RelayApp
        from rivo_relay.config import Config
        with tempfile.TemporaryDirectory() as directory:
            bus = EventBus()
            native = SimpleNamespace(on_event=bus.publish, close=lambda: None)
            app = RelayApp(Config(workspace=Path(directory)), native, bus)
            try:
                item = {'id': 'image', 'type': 'imageGeneration', 'result': 'A' * 10000}
                event = {'method': 'item/completed', 'params': {'threadId': 'thread', 'turnId': 'turn', 'item': item}}
                cursor = bus.cursor(0)
                with patch.object(app.media, 'observe_event') as observe:
                    native.on_event(event)
                observe.assert_called_once_with(event)
                _, events, _ = bus.read(cursor, timeout=0)
                self.assertTrue(events[0]['params']['item']['mediaRef'])
                self.assertEqual('A' * 10000, item['result'])
                cursor = bus.cursor(1)
                with patch.object(app.media, 'observe_event', side_effect=OSError('fixture')):
                    native.on_event(event)
                _, events, _ = bus.read(cursor, timeout=0)
                self.assertEqual(item, events[0]['params']['item'])
                turn = {'id': 'turn', 'status': 'completed', 'items': [item]}
                for method, params in (
                    ('turn/completed', {'threadId': 'thread', 'turn': turn}),
                    ('thread/started', {'thread': {'id': 'thread', 'turns': [turn]}}),
                ):
                    cursor = bus.cursor(bus._sequence)
                    with patch.object(app.media, 'observe_thread') as registered:
                        native.on_event({'method': method, 'params': params})
                    registered.assert_called_once_with({'id': 'thread', 'turns': [turn]})
                    _, events, _ = bus.read(cursor, timeout=0)
                    self.assertLess(len(json.dumps(events)), 1000)
            finally:
                app.close()

    def test_registered_inline_bytes_remain_available_at_projected_media_identity(self):
        import base64
        import io
        import tempfile
        from PIL import Image
        from rivo_relay.media import MediaService
        with tempfile.TemporaryDirectory() as directory:
            media = MediaService(directory, object())
            media.close()
            output = io.BytesIO()
            Image.new('RGB', (2, 2), 'red').save(output, format='PNG')
            raw = output.getvalue()
            item = {'id': 'image', 'type': 'imageGeneration', 'status': 'completed', 'result': base64.b64encode(raw).decode()}
            thread = {'id': 'thread', 'turns': [{'id': 'turn', 'status': 'completed', 'items': [item]}]}
            media.observe_thread(thread)
            projected = client_history(thread, compact_images=True)
            media.drain()
            identity = projected['turns'][0]['items'][0]['result'].split('/')[-2]
            self.assertEqual('ready', media.info(identity)['status'])
            path, _, _ = media.stored(identity)
            self.assertEqual(raw, path.read_bytes())

class UnchangedHeadStateTests(unittest.TestCase):
    def test_unchanged_messages_never_omit_current_task_state(self):
        from rivo_relay.client_projection import client_session
        for status, goal, queue, requests, recovery in (
            ('running', {'status': 'active'}, [], [], False),
            ('waiting', {'status': 'active'}, [{'id': 'queued', 'status': 'waiting'}], [{'key': 'approval'}], False),
            ('idle', {'status': 'completed'}, [], [], False),
            ('unknown', None, [{'id': 'uncertain', 'status': 'unknown'}], [], True),
        ):
            with self.subTest(status=status):
                source = {'thread': {'id': 'thread', 'turns': [{'id': 'turn', 'items': []}]},
                          'nativeHeadRevision': 'same-head', 'nativeSync': {'state': 'ready'},
                          'status': status, 'goal': goal, 'queue': queue, 'requests': requests,
                          'recoveryRequired': recovery, 'control': {'canOperate': False}}
                result = client_session(source, known_head_revision='same-head', limit=2)
                self.assertTrue(result['historyUnchanged'])
                self.assertNotIn('turns', result['thread'])
                for field in ('status', 'goal', 'queue', 'requests', 'recoveryRequired', 'control'):
                    self.assertEqual(source[field], result[field])
                self.assertIn('turns', source['thread'])
