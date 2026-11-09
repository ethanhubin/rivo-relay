"""Batch activity reads share only a bounded, re-attested locality proof."""
import unittest
from unittest.mock import patch

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.host_probe import local_native_host_info
from rivo_relay.codex.native_journal import NativeJournal


class Native:
    generation = 'generation-one'

    def native_host_info(self):
        return local_native_host_info(self)


class ActivityReadScopeTests(unittest.TestCase):
    def setUp(self):
        self.native = Native()
        self.descriptor = {'generation': self.native.generation,
                           'identityMethod': 'local-socket-and-open-state-database',
                           'pid': 123, 'processStart': 'original'}
        self.journal = NativeJournal(self.native, None)
        self.ids = [str(index) for index in range(70)]

    def response(self, request):
        return {'states': {tid: 'running' for tid in request['threadIds']}, 'inspections': {}}

    def test_multibatch_read_proves_socket_at_start_and_end_and_not_across_reads(self):
        with patch('rivo_relay.codex.host_probe._local_native_host_info', return_value=self.descriptor) as proof, \
                patch('rivo_relay.codex.native_history_host.dispatch', side_effect=self.response) as dispatch:
            self.assertEqual(set(self.ids), set(self.journal.execution_states(self.ids, running_only=True)))
            self.assertEqual(2, proof.call_count)
            self.assertEqual(3, dispatch.call_count)
            self.assertTrue(all(call.args[0]['runningOnly'] for call in dispatch.call_args_list))
            self.assertTrue(all(call.args[0]['runtime'] == self.descriptor for call in dispatch.call_args_list))
            self.journal.execution_states(self.ids)
            self.assertEqual(4, proof.call_count)
            self.assertEqual(6, dispatch.call_count)
            self.assertTrue(all('runningOnly' not in call.args[0] for call in dispatch.call_args_list[3:]))

    def test_final_socket_proof_change_or_denial_discards_all_collected_states(self):
        for final in ({**self.descriptor, 'pid': 456}, CodexError('no longer a local socket')):
            with self.subTest(final=type(final).__name__), \
                    patch('rivo_relay.codex.host_probe._local_native_host_info', side_effect=[self.descriptor, final]), \
                    patch('rivo_relay.codex.native_history_host.dispatch', side_effect=self.response):
                self.assertEqual({}, self.journal.execution_states(self.ids, running_only=True))
                self.assertNotIn(self.native.generation, self.journal._hosts)

    def test_generation_change_mid_batch_cannot_return_old_states(self):
        def changed(request):
            self.native.generation = 'generation-two'
            return self.response(request)
        with patch('rivo_relay.codex.host_probe._local_native_host_info', return_value=self.descriptor), \
                patch('rivo_relay.codex.native_history_host.dispatch', side_effect=changed):
            with self.assertRaises(CodexError):
                self.journal.execution_states(self.ids, running_only=True)

    def test_failed_initial_socket_proof_does_not_dispatch(self):
        with patch('rivo_relay.codex.host_probe._local_native_host_info', side_effect=CodexError('denied')), \
                patch('rivo_relay.codex.native_history_host.dispatch') as dispatch:
            self.assertEqual({}, self.journal.execution_states(self.ids, running_only=True))
            dispatch.assert_not_called()
