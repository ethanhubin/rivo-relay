import importlib.util
import json
from pathlib import Path
import plistlib
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts/install-managed-node.py'
spec = importlib.util.spec_from_file_location('managed_installer', SCRIPT)
installer = importlib.util.module_from_spec(spec); spec.loader.exec_module(installer)


class InstallerTests(unittest.TestCase):
    def test_prepare_keeps_running_service_and_activate_reuses_installed_environment(self):
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp); workspace = home / 'workspace'; workspace.mkdir()
            (workspace / 'gateway-client.json').write_text(json.dumps({'key': 'private-test-only'}))
            arguments = [str(SCRIPT), '--workspace', str(workspace), '--codex-runtime', 'app_server', '--native-socket', str(home / 'native.sock')]
            with patch.object(sys, 'argv', arguments + ['--prepare-only']), patch.object(installer.subprocess, 'run') as run, \
                    patch.object(installer.socket, 'socket') as socket:
                installer.main()
                socket.assert_not_called()
                self.assertEqual(run.call_count, 3)
                self.assertTrue((workspace / 'auto-update/bootstrap/prepared.json').exists())
                self.assertFalse((home / 'Library').exists())
            fake_socket = Mock(); fake_socket.__enter__ = Mock(return_value=Mock(connect_ex=Mock(return_value=1)))
            fake_socket.__exit__ = Mock(return_value=False)
            with patch.object(sys, 'argv', arguments + ['--activate']), patch.object(sys, 'platform', 'darwin'), \
                    patch.object(installer.Path, 'home', return_value=home), patch.object(installer.socket, 'socket', return_value=fake_socket), \
                    patch.object(installer.subprocess, 'run') as run:
                installer.main()
                self.assertEqual(run.call_count, 1)
                self.assertEqual(run.call_args.args[0][:2], ['launchctl', 'bootstrap'])
            plists = list((home / 'Library/LaunchAgents').glob('*.plist'))
            value = plistlib.loads(plists[0].read_bytes())
            self.assertIn('managed-node', value['ProgramArguments'])
            self.assertIn('--native-socket', value['ProgramArguments'])
            self.assertIn('PATH', value['EnvironmentVariables'])
