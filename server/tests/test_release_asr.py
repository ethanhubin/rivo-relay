import base64
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from release_tools.asr_remote import apply, FILES


class AsrReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in FILES:
            (self.root / name).write_bytes(b'old = True\n')
        (self.root / 'requirements.lock').write_bytes(b'locked')
        (self.root / 'token').write_bytes(b'private')
        self.payload = {'commit': 'abc', 'versionCode': 120,
                        'requirementsSha256': hashlib.sha256(b'locked').hexdigest(),
                        'files': {n: base64.b64encode(b'new = True\n').decode() for n in FILES}}
        self.restart, self.health = Mock(), Mock()

    def run_deploy(self):
        return apply(self.root, self.payload, self.restart, self.health)

    def test_success_and_resume_without_model_restart(self):
        report = self.run_deploy()
        self.assertTrue(report['restarted'])
        self.assertEqual((self.root / 'token').read_bytes(), b'private')
        self.assertFalse(self.run_deploy()['restarted'])
        self.restart.assert_called_once()
        self.payload['versionCode'] += 1
        self.assertFalse(self.run_deploy()['restarted'])
        self.assertEqual(json.loads((self.root / 'release-active.json').read_text())['versionCode'], 121)

    def test_health_failure_rolls_back(self):
        self.health.side_effect = [RuntimeError('unhealthy'), None]
        with self.assertRaisesRegex(RuntimeError, 'unhealthy'):
            self.run_deploy()
        for name in FILES:
            self.assertEqual((self.root / name).read_bytes(), b'old = True\n')
        self.assertFalse((self.root / 'release-pending.json').exists())
        self.assertFalse((self.root / 'release-active.json').exists())

    def test_failed_rollback_recovered_on_resume(self):
        self.health.side_effect = RuntimeError('offline')
        with self.assertRaises(RuntimeError):
            self.run_deploy()
        self.assertTrue((self.root / 'release-pending.json').exists())
        self.health.side_effect = None
        self.assertTrue(self.run_deploy()['healthy'])
        self.assertFalse((self.root / 'release-pending.json').exists())

    def test_downgrade_and_same_version_replacement_rejected(self):
        self.run_deploy()
        self.payload['versionCode'] -= 1
        with self.assertRaisesRegex(RuntimeError, 'downgrade'):
            self.run_deploy()
        self.payload['versionCode'] += 1
        self.payload['files']['server.py'] = base64.b64encode(b'other = True').decode()
        with self.assertRaisesRegex(RuntimeError, 'different content'):
            self.run_deploy()

    def test_dependency_change_fails_before_switch(self):
        self.payload['requirementsSha256'] = 'different'
        with self.assertRaisesRegex(RuntimeError, 'dependency'):
            self.run_deploy()
        self.restart.assert_not_called()

    def test_invalid_python_fails_before_switch(self):
        self.payload['files']['server.py'] = base64.b64encode(b'invalid python !').decode()
        with self.assertRaises(SyntaxError):
            self.run_deploy()
        self.restart.assert_not_called()

    def test_external_file_change_requires_restart(self):
        self.run_deploy()
        (self.root / 'server.py').write_bytes(b'changed = True')
        self.assertTrue(self.run_deploy()['restarted'])
        self.assertEqual((self.root / 'server.py').read_bytes(), b'new = True\n')

class AsrCoordinatorTest(unittest.TestCase):
    def test_exact_commit_and_verified_receipt(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        from release_tools import asr
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = SimpleNamespace(root=root, directory=root, state={
                'commit': 'abc123', 'versionCode': 130, 'steps': {}},
                source_unchanged=Mock(), record=Mock())
            raw = b'value = True\n'
            receipt = {'commit': 'abc123', 'versionCode': 130, 'healthy': True,
                       'sha256': {n: hashlib.sha256(raw).hexdigest() for n in FILES}}
            with patch.object(asr.subprocess, 'check_output', return_value=raw) as read, \
                    patch.object(asr.subprocess, 'run', return_value=SimpleNamespace(
                        returncode=0, stdout=json.dumps(receipt), stderr='')) as ssh:
                asr.deploy(release)
                for call in read.call_args_list:
                    self.assertTrue(call.args[0][-1].startswith('abc123:scripts/asr/'))
                self.assertIn('BatchMode=yes', ssh.call_args.args[0])
                self.assertEqual(release.state['steps']['asr'], receipt)
                receipt['sha256']['server.py'] = 'wrong'
                ssh.return_value.stdout = json.dumps(receipt)
                with self.assertRaisesRegex(RuntimeError, '验收不一致'):
                    asr.deploy(release)
