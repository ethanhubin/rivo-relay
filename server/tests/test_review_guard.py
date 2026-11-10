import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.sessions import CodexService
from rivo_relay.database import Database
from relay_test_support.files import NativeHost


class ReviewGuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.file = self.root / 'tracked.txt'
        self.file.write_text('before\n')
        self.git('init', '-q')
        self.git('add', 'tracked.txt')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture')
        self.native = NativeHost(self.root)
        self.db = Database(':memory:')
        self.addCleanup(self.db.close)
        self.service = CodexService(connection=self.native)
        self.service.configure_storage(self.db)

    def git(self, *args):
        subprocess.run(['git', '-C', str(self.root), *args], capture_output=True, check=True)

    def test_unchanged_review_verifies_and_external_change_warns_without_rollback(self):
        baseline = self.service.review_guard.begin('thread', str(self.root))
        self.service.review_guard.bind('thread', 'review-one', baseline)
        turns = [{'id': 'review-one', 'status': 'inProgress'}]
        self.assertEqual([], self.service.review_guard.diagnostics('thread', turns))
        turns[0]['status'] = 'completed'
        self.assertFalse(self.service.review_guard.diagnostics('thread', turns)[0]['readOnlyViolation'])
        baseline = self.service.review_guard.begin('thread', str(self.root))
        self.service.review_guard.bind('thread', 'review-two', baseline)
        self.file.write_text('external change\n')
        self.git('add', 'tracked.txt')
        restored = CodexService(connection=self.native)
        restored.configure_storage(self.db)
        diagnostics = restored.review_guard.diagnostics('thread', [{'id': 'review-two', 'status': 'completed'}])
        changed = next(item for item in diagnostics if item['turnId'] == 'review-two')
        self.assertEqual('changed', changed['status'])
        self.assertTrue(changed['readOnlyViolation'])
        self.assertEqual('external change\n', self.file.read_text())

    def test_unavailable_postcheck_does_not_claim_readonly_success(self):
        baseline = self.service.review_guard.begin('thread', str(self.root))
        self.service.review_guard.bind('thread', 'review', baseline)
        self.native.request = Mock(side_effect=CodexError('offline'))
        result = self.service.review_guard.diagnostics('thread', [{'id': 'review', 'status': 'completed'}])[0]
        self.assertEqual('unavailable', result['status'])
        self.assertIsNone(result['readOnlyViolation'])

    def test_failed_preflight_never_starts_review(self):
        self.service._assert_submission_ready = Mock()
        self.service.get_session = Mock(return_value={'thread': {'cwd': str(self.root)}, 'requests': []})
        self.native.request = Mock(side_effect=CodexError('fingerprint unavailable'))
        with self.assertRaises(CodexError):
            self.service.review('thread', {'type': 'uncommittedChanges'})
        self.assertFalse(any(call.args[0] == 'review/start' for call in self.native.request.call_args_list))
