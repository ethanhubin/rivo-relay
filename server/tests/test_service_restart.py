import importlib.util
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest.mock import patch

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
