import importlib.util
from pathlib import Path
import plistlib
import runpy
import sys
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))

spec = importlib.util.spec_from_file_location('restart', Path(__file__).resolve().parents[2] / 'scripts/restart-service.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class RestartTest(unittest.TestCase):
    def test_failed_health_restores_previous_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'service.plist'
            candidate = Path(directory) / 'candidate.plist'
            old = plistlib.dumps({'Label': 'local.rivo-relay', 'ProgramArguments': ['/bin/echo'], 'KeepAlive': True})
            path.write_bytes(old)
            candidate.write_bytes(plistlib.dumps({'Label': 'local.rivo-relay', 'ProgramArguments': ['/bin/echo']}))
            with patch.object(m, 'stop'), patch.object(m, 'start', side_effect=[RuntimeError('health_failed'), None]) as start:
                with self.assertRaisesRegex(RuntimeError, 'health_failed'):
                    m.replace(path, candidate, 'http://localhost/health')
                self.assertEqual(path.read_bytes(), old)
                self.assertEqual(start.call_count, 2)

    def test_bootstrap_failure_retries(self):
        with patch.object(m, 'command', side_effect=[1, 5, 1, 0]), patch.object(m.time, 'sleep'), patch.object(m.urllib.request, 'build_opener') as opener:
            opener.return_value.open.return_value.__enter__.return_value.read.return_value = b'{"status":"ok"}'
            m.start('gui/501', 'gui/501/local.rivo-relay', Path('/tmp/test'), 'http://localhost/health')

    def test_unload_waits_until_job_disappears(self):
        with patch.object(m, 'command', side_effect=[0, 0, 0, 0, 113]) as command, patch.object(m.time, 'sleep') as sleep:
            m.stop('gui/501/local.rivo-relay')
            self.assertEqual(command.call_count, 5)
            self.assertEqual(sleep.call_count, 2)
            self.assertEqual(command.call_args.args[0], 'print')

    def test_unload_timeout_prevents_start(self):
        with patch.object(m, 'command', return_value=0), patch.object(m.time, 'monotonic', side_effect=[0, 31]):
            with self.assertRaisesRegex(RuntimeError, 'unload_timeout'):
                m.stop('gui/501/local.rivo-relay')

    def test_rollback_failure_is_explicit(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'service.plist'
            path.write_bytes(plistlib.dumps({'Label': 'local.rivo-relay', 'ProgramArguments': ['/bin/echo']}))
            with patch.object(m, 'stop'), patch.object(m, 'start', side_effect=RuntimeError('failed')):
                with self.assertRaisesRegex(RuntimeError, 'Deployment and rollback failed'):
                    m.replace(path, path, 'http://localhost/health')

    def test_invalid_rollback_executable_prevents_stopping_service(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'service.plist'
            candidate = Path(directory) / 'candidate.plist'
            path.write_bytes(plistlib.dumps({'Label': 'local.rivo-relay', 'ProgramArguments': ['/missing/python']}))
            candidate.write_bytes(plistlib.dumps({'Label': 'local.rivo-relay', 'ProgramArguments': ['/bin/echo']}))
            with patch.object(m, 'stop') as stop:
                with self.assertRaisesRegex(ValueError, 'Executable unavailable'):
                    m.replace(path, candidate, 'http://localhost/health')
                stop.assert_not_called()

    def test_termination_during_start_rolls_back(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'service.plist'
            path.write_bytes(plistlib.dumps({'Label': 'local.rivo-relay', 'ProgramArguments': ['/bin/echo']}))
            with patch.object(m, 'stop'), patch.object(m, 'start', side_effect=[InterruptedError('terminated'), None]) as start:
                with self.assertRaises(InterruptedError):
                    m.replace(path, path, 'http://localhost/health')
                self.assertEqual(start.call_count, 2)

    def test_stale_deployment_cannot_overwrite_a_new_service_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path, candidate, expected = (root / name for name in ('service.plist', 'candidate.plist', 'before.plist'))
            config = {'Label': 'local.rivo-relay', 'ProgramArguments': ['/bin/echo']}
            expected.write_bytes(plistlib.dumps(config))
            candidate.write_bytes(plistlib.dumps(dict(config, Candidate=True)))
            current = plistlib.dumps(dict(config, OtherDeployment=True))
            path.write_bytes(current)
            with patch.object(m, 'stop') as stop:
                with self.assertRaisesRegex(ValueError, 'configuration changed'):
                    m.replace(path, candidate, 'http://localhost/health', expected)
                stop.assert_not_called()
            self.assertEqual(path.read_bytes(), current)
            self.assertFalse(path.with_suffix('.rollback.plist').exists())


class SubmissionTest(unittest.TestCase):
    def test_worker_is_submitted_as_separate_launchd_job(self):
        with tempfile.TemporaryDirectory() as directory:
            args = ['restart-service.py', '--candidate', directory + '/candidate.plist',
                    '--submit', '--log-dir', directory]
            args += ['--expected-plist', directory + '/before.plist']
            with patch.object(sys, 'argv', args), patch('subprocess.run', return_value=SimpleNamespace(returncode=0, stderr='')) as run:
                runpy.run_path(str(Path(m.__file__)), run_name='__main__')
            command = run.call_args.args[0]
            self.assertEqual(command[:2], ['launchctl', 'bootstrap'])
            config = plistlib.loads(Path(command[-1]).read_bytes())
            self.assertIs(config['KeepAlive'], False)
            command = config['ProgramArguments']
            self.assertTrue(config['Label'].startswith('local.rivo-relay.deploy.'))
            self.assertIn('--candidate', command)
            self.assertEqual(command[command.index('--expected-plist') + 1], str((Path(directory) / 'before.plist').resolve()))
            self.assertNotIn('--submit', command)

    def test_submission_failure_is_reported_without_stopping_service(self):
        with tempfile.TemporaryDirectory() as directory:
            args = ['restart-service.py', '--candidate', directory + '/candidate.plist',
                    '--submit', '--log-dir', directory]
            with patch.object(sys, 'argv', args), patch('subprocess.run', return_value=SimpleNamespace(returncode=5, stderr='failed')) as run:
                with self.assertRaises(SystemExit) as error:
                    runpy.run_path(str(Path(m.__file__)), run_name='__main__')
                self.assertEqual(error.exception.code, 5)
                self.assertEqual(run.call_count, 1)
                event = __import__('json').loads((Path(directory) / 'deployment.log').read_text())
                self.assertEqual(event['event'], 'submission_failed')
