import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch
from rivo_relay.codex.host_probe import local_native_host_info
from rivo_relay.codex.connection import CodexError


class LocalHostProbeTests(unittest.TestCase):
    def setUp(self):
        self.connection = SimpleNamespace(codex_home='/fixture', socket_path='/fixture/socket',
                                         generation='one', request=Mock(return_value={'process': {'id': 42}}))
        self.manager = Mock()
        self.manager.identity.return_value = {'pid': 42, 'start': 'started', 'executable': '/bin/codex'}
        self.manager.run.return_value = 'n/fixture/state_5.sqlite\nn/fixture/state_5.sqlite\n'
        self.patcher = patch('rivo_relay.codex.writers.WriterProcesses', return_value=self.manager)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def test_local_socket_and_database_prove_home(self):
        result = local_native_host_info(self.connection)
        self.assertEqual('/fixture', result['codexHome'])
        self.assertEqual('local-socket-and-open-state-database', result['identityMethod'])
        self.assertEqual(2, self.manager.verify_local_runtime.call_count)

    def test_forwarded_socket_rejected_before_home_inspection(self):
        self.manager.verify_local_runtime.side_effect = CodexError('not local')
        with self.assertRaises(CodexError):
            local_native_host_info(self.connection)
        self.manager.run.assert_not_called()

    def test_missing_or_ambiguous_database_rejected(self):
        for output in ('', 'n/a/state_5.sqlite\nn/b/state_5.sqlite\n'):
            self.manager.run.return_value = output
            with self.assertRaises(CodexError):
                local_native_host_info(self.connection)

    def test_process_replacement_rejected(self):
        self.manager.identity.side_effect = [self.manager.identity.return_value, None]
        with self.assertRaises(CodexError):
            local_native_host_info(self.connection)
