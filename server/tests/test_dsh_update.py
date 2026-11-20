"""DSH update readiness must come from a fresh read, never cached activity."""
import copy
import os
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import test_dsh as fixtures
from relay_test_support.http import RelayHTTPFixture
from rivo_relay.codex.connection import CodexError
from rivo_relay.dsh import DshService


class DshUpdateTests(unittest.TestCase):
    def setUp(self):
        self.result = {'generation': 'host-1', 'cursor': 4, 'idle': True}
        self.calls = []
        self.during_read = lambda: None
        self.service = DshService('/unused', transport=SimpleNamespace(call=self.call))
        self.service._admit('host-1')
        self.service._cursor = 4

    def call(self, method, params=None, *, timeout=25):
        self.calls.append((method, params, timeout))
        self.during_read()
        return copy.deepcopy(self.result)

    def test_idle_without_a_previously_opened_session_uses_bounded_metadata_read(self):
        self.assertFalse(self.service._known)
        self.assertTrue(self.service.idle_for_update())
        self.assertEqual([('updateStatus', None, 2)], self.calls)
        self.assertFalse(self.service._known)
        self.assertIsNone(self.service._worker)

    def test_busy_unknown_and_malformed_reply_never_authorize_update(self):
        for result in (None, {}, {'idle': True}, {**self.result, 'idle': False},
                       {**self.result, 'idle': 1}, {**self.result, 'idle': None},
                       {**self.result, 'generation': 'old-host'},
                       {**self.result, 'cursor': 5}, {**self.result, 'cursor': '4'}):
            with self.subTest(result=result):
                self.result = result
                self.assertFalse(self.service.idle_for_update())

    def test_disconnect_and_transport_timeout_fail_closed(self):
        self.service._disconnected()
        self.assertFalse(self.service.idle_for_update())
        self.assertEqual([], self.calls)
        self.service._admit('host-1')
        self.during_read = lambda: (_ for _ in ()).throw(TimeoutError())
        self.assertFalse(self.service.idle_for_update())

    def test_approval_or_live_output_blocks_update_until_event_state_catches_up(self):
        for name in ('_requests', '_live'):
            with self.subTest(name=name):
                getattr(self.service, name)['pending'] = {}
                self.assertFalse(self.service.idle_for_update())
                getattr(self.service, name).clear()
        self.assertTrue(self.service.idle_for_update())

    def test_state_change_during_probe_cannot_authorize_from_an_old_idle_reply(self):
        for change in (
            lambda: self.service._disconnected(),
            lambda: self.service._admit('host-2'),
            lambda: self.service._event({'type': 'status', 'sessionId': 's', 'running': True}),
            lambda: self.service._event({'type': 'approval', 'request': {'key': 'k', 'sessionId': 's'}}),
            lambda: self.service._stop.set(),
        ):
            with self.subTest(change=change):
                self.setUp()
                self.during_read = change
                self.assertFalse(self.service.idle_for_update())

    def test_closed_service_cannot_reconfirm_idle(self):
        self.service.close()
        self.assertFalse(self.service.idle_for_update())
        self.assertEqual([], self.calls)


class DshUpdateHttpTests(RelayHTTPFixture, unittest.TestCase):
    _start = fixtures.DshHttpTests._start

    def setUp(self):
        super().setUp()
        rpc = self.bridge.rpc
        self.readiness = True

        def with_update_status(method, params):
            if method == 'updateStatus':
                return {'generation': self.bridge.generation, 'cursor': 0, 'idle': self.readiness}
            return rpc(method, params)

        self.bridge.rpc = with_update_status
        self.token_patch = patch.dict(os.environ, RIVO_UPDATE_TOKEN='isolated-update-test')
        self.token_patch.start()
        self.addCleanup(self.token_patch.stop)

    def lease(self):
        return self.request('POST', '/internal/update-lease', {}, token='isolated-update-test')

    def test_busy_then_idle_can_lease_without_repairing_or_stopping_native_work(self):
        self.readiness = False
        self.assertEqual(409, self.lease()[0])
        self.readiness = True
        status, response, _ = self.lease()
        self.assertEqual(200, status)
        self.assertTrue(response['ready'])
        self.assertTrue(response['lease'])
        self.assertEqual({}, self.bridge.prompts)
        self.assertIsNone(self.bridge.last_response)

    def test_pending_or_unknown_submission_still_blocks_even_when_dsh_is_idle(self):
        self.app.submissions.claim('pending', self.paired['deviceId'], 'thread.turns', 's', {})
        self.assertEqual(409, self.lease()[0])
        self.app.submissions.mark_unknown('pending')
        self.assertEqual(409, self.lease()[0])

    def test_slow_bridge_probe_has_a_short_transport_timeout(self):
        from rivo_relay.dsh.transport import BridgeTransport
        rpc = self.bridge.rpc
        release = threading.Event()

        def delayed(method, params):
            if method == 'updateStatus':
                release.wait(1)
            return rpc(method, params)

        self.bridge.rpc = delayed
        transport = BridgeTransport(self.native.transport.path)
        started = time.monotonic()
        try:
            with self.assertRaises(CodexError) as caught:
                transport.call('updateStatus', timeout=.03)
            self.assertEqual('dsh_disconnected', caught.exception.code)
            self.assertLess(time.monotonic() - started, .5)
        finally:
            release.set()


if __name__ == '__main__':
    unittest.main()
