import unittest
from rivo_relay.codex.projection import control_projection


class ControlProjectionTests(unittest.TestCase):
    def test_control_states_and_recovery_actions(self):
        for read_only, released, unavailable, state, action in [
            (False, False, False, 'writable', 'release'),
            (True, False, False, 'external', 'takeover'),
            (True, True, False, 'released', 'resume'),
            (True, True, True, 'workspace_unavailable', 'takeover'),
        ]:
            with self.subTest(state=state):
                result = control_projection(read_only=read_only, released=released,
                    workspace_unavailable=unavailable, runtime={'handoffAvailable': True})
                self.assertEqual(state, result['state'])
                self.assertEqual(state == 'writable', result['canOperate'])
                self.assertEqual(action, result['action'])

    def test_unavailable_handoff_explains_why(self):
        result = control_projection(read_only=True, released=False, workspace_unavailable=False,
            runtime={'handoffAvailable': False, 'handoffUnavailableReason': 'remote host'})
        self.assertIsNone(result['action'])
        self.assertEqual('remote host', result['unavailableReason'])

    def test_disconnected_runtime_never_grants_control(self):
        result = control_projection(read_only=False, released=False, workspace_unavailable=False,
            runtime={'connected': False, 'handoffAvailable': True})
        self.assertEqual('unknown', result['state'])
        self.assertFalse(result['canOperate'])
        self.assertIsNone(result['action'])
