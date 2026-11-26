import unittest
from types import SimpleNamespace
from unittest.mock import patch
from rivo_relay.codex.files import _command
from rivo_relay.codex.connection import CodexError

class PythonSelectionTests(unittest.TestCase):
    def test_fallback_cache_and_reconnect(self):
        c = SimpleNamespace(generation='a')
        calls = []
        def run(connection, params, **kwargs):
            command = params['command']; calls.append(command)
            if command[-1] == 'print("rivo-python-ok")':
                return {'exitCode': 0, 'stdout': 'rivo-python-ok\n'} if command[0] == '/opt/homebrew/bin/python3' else {'exitCode': 69, 'stderr': 'Xcode license'}
            return {'exitCode': 0, 'stdout': 'image bytes'}
        with patch('rivo_relay.codex.files.node_command', side_effect=run):
            self.assertEqual(_command(c, ['python3', '-c', 'read'], '/tmp'), 'image bytes')
            count = len(calls)
            _command(c, ['python3', '-c', 'read'], '/tmp')
            self.assertEqual(len(calls), count + 1)
            self.assertEqual(calls[-1][0], '/opt/homebrew/bin/python3')
            c.generation = 'b'
            _command(c, ['python3', '-c', 'read'], '/tmp')
            self.assertGreater(len(calls), count + 2)

    def test_real_failure_survives(self):
        with patch('rivo_relay.codex.files.node_command', return_value={'exitCode': 7, 'stderr': 'actual read failure'}):
            with self.assertRaisesRegex(CodexError, 'exit=7.*actual read failure'):
                _command(SimpleNamespace(), ['/bin/sh', '-c', 'exit 7'], '/tmp')

    def test_no_interpreter_reports_probe_error(self):
        with patch('rivo_relay.codex.files.node_command', return_value={'exitCode': 69, 'stderr': 'Xcode license'}):
            with self.assertRaisesRegex(CodexError, 'Xcode license'):
                _command(SimpleNamespace(), ['python3', '-c', 'read'], '/tmp')
