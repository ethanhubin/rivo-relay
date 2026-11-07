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
