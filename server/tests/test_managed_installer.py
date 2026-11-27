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

    @unittest.skipIf(sys.platform == 'win32', 'launchd user service registration')
    def test_prepares_and_registers_the_saved_desktop_runtime_for_the_login_user(self):
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp)
            workspace = home / 'workspace'; workspace.mkdir()
            profile = home / 'desktop profile'; profile.mkdir()
            project = home / 'project'; project.mkdir()
            client = workspace / 'gateway-client.json'
            client.write_text(json.dumps({'key': 'private-test-only', 'runtime': 'antigravity',
                'antigravity_mode': 'desktop', 'antigravity_profile_dir': str(profile), 'antigravity_project': [str(project)]}))
            original = client.read_bytes()
            arguments = [str(SCRIPT), '--workspace', str(workspace)]
            with patch.object(sys, 'argv', arguments + ['--prepare-only']), patch.object(installer.subprocess, 'run'):
                installer.main()
            prepared = json.loads((workspace / 'auto-update/bootstrap/prepared.json').read_text())
            self.assertEqual(prepared[prepared.index('--no-pair') + 1:],
                ['--runtime', 'antigravity', '--antigravity-mode', 'desktop', '--antigravity-profile-dir', str(profile),
                 '--antigravity-project', str(project)])
            self.assertEqual(client.read_bytes(), original)
            with patch.object(sys, 'argv', arguments + ['--activate']), patch.object(sys, 'platform', 'darwin'), \
                    patch.object(installer.Path, 'home', return_value=home), patch.object(installer.socket, 'socket') as probe, \
                    patch.object(installer.subprocess, 'run') as run:
                probe.return_value.__enter__.return_value.connect_ex.return_value = 1
                installer.main()
                self.assertEqual(run.call_args.args[0][1:3], ['bootstrap', f'gui/{installer.os.getuid()}'])
            target = next((home / 'Library/LaunchAgents').glob('*.plist'))
            self.assertEqual(plistlib.loads(target.read_bytes())['ProgramArguments'][3:], prepared)

    @unittest.skipIf(sys.platform == 'win32', 'macOS account validation')
    def test_system_service_rejects_desktop_runtime_before_install(self):
        import pwd
        user = pwd.getpwuid(__import__('os').getuid()).pw_name
        with patch.object(sys, 'argv', [str(SCRIPT), '--workspace', '/nonexistent',
                                       '--system-service', '--service-user', user,
                                       '--codex-runtime', 'app_server', '--prepare-only']), \
                patch.object(sys, 'platform', 'darwin'), \
                patch.object(installer.subprocess, 'run') as run:
            with self.assertRaises(SystemExit):
                installer.main()
            run.assert_not_called()

    @unittest.skipIf(sys.platform == 'win32', 'macOS account validation')
    def test_system_activation_uses_nonroot_account_and_system_domain(self):
        import types
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp).resolve()
            workspace = home / 'workspace'; workspace.mkdir()
            (workspace / 'gateway-client.json').write_text(json.dumps({'key': 'test-only'}))
            bootstrap = workspace / 'auto-update/bootstrap'; bootstrap.mkdir(parents=True)
            args = ['managed-node', '--workspace', str(workspace), '--local-port', '18765', '--no-pair',
                    '--codex-runtime', 'stdio', '--codex-executable', '/usr/local/bin/codex', '--codex-home', str(home / '.codex')]
            (bootstrap / 'prepared.json').write_text(json.dumps(args))
            account = types.SimpleNamespace(pw_uid=501, pw_name='test-user', pw_dir=str(home))
            with patch.object(sys, 'argv', [str(SCRIPT), *args[1:5], '--codex-runtime', 'stdio',
                    '--codex-executable', '/usr/local/bin/codex', '--codex-home', str(home / '.codex'),
                    '--system-service', '--service-user', 'test-user', '--activate']), \
                    patch.object(sys, 'platform', 'darwin'), patch('pwd.getpwnam', return_value=account), \
                    patch.object(installer.os, 'geteuid', return_value=0), \
                    patch.object(installer, 'LAUNCH_DAEMONS', home / 'daemons'), \
                    patch.object(installer.socket, 'socket') as probe, \
                    patch.object(installer.subprocess, 'run') as run:
                probe.return_value.__enter__.return_value.connect_ex.return_value = 1
                installer.main()
                self.assertEqual(run.call_args.args[0][1:3], ['bootstrap', 'system'])
            target = next((home / 'daemons').glob('*.plist'))
            value = plistlib.loads(target.read_bytes())
            self.assertEqual(value['UserName'], 'test-user')
            self.assertEqual(value['EnvironmentVariables']['HOME'], str(home))
            self.assertEqual(target.stat().st_mode & 0o777, 0o644)
