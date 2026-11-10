import unittest
from unittest.mock import Mock, patch
from contextlib import nullcontext
from rivo_relay.codex.sessions import CodexService
from rivo_relay.codex.connection import CodexError
from relay_test_support.native import FakeNative

class ForkNative(FakeNative):
    def request(self, method, params, **kwargs):
        if method == 'thread/fork':
            self.calls.append((method, params))
            return {'thread': {'id': 'child', 'cwd': params['cwd']}}
        return super().request(method, params, **kwargs)

class ForkTests(unittest.TestCase):
    def setUp(self):
        self.native = ForkNative()
        self.native.thread.update(cwd='/existing-worktree', projectId='project')
        self.service = CodexService(connection=self.native)
        self.service.workspaces = Mock()
        self.service.workspaces.create.return_value = ('workspace', '/new-worktree')

    def test_fork_preserves_source_cwd_and_boundary(self):
        self.assertEqual({'threadId': 'child'}, self.service.fork_session('thread', 'turn-2'))
        params = next(p for m, p in self.native.calls if m == 'thread/fork')
        self.assertEqual({'threadId': 'thread', 'lastTurnId': 'turn-2', 'cwd': '/existing-worktree', 'excludeTurns': True}, params)
        self.service.workspaces.create.assert_not_called()
        self.assertIn('child', self.service._joined)

    def test_worktree_created_from_source_and_linked(self):
        self.service.fork_session('thread', 'turn-2', 'commit')
        self.service.workspaces.create.assert_called_once_with('project', '/existing-worktree', 'commit')
        self.service.workspaces.link.assert_called_once_with('workspace', 'child')
        self.assertEqual('/new-worktree', next(p for m, p in self.native.calls if m == 'thread/fork')['cwd'])

    def test_failure_preserves_uncertain_workspace(self):
        for uncertain in (True, False):
            request = self.native.request
            def fail(method, params, **kwargs):
                if method == 'thread/fork':
                    raise CodexError('failed', request_sent=uncertain)
                return request(method, params, **kwargs)
            self.native.request = fail
            with self.assertRaises(CodexError):
                self.service.fork_session('thread', 'turn-2', 'commit')
            self.service.workspaces.abandon.assert_called_with('workspace', uncertain=uncertain)
            self.native.request = request
        self.service.workspaces.link.assert_not_called()

    def test_link_failure_after_native_creation_is_not_retryable(self):
        self.service.workspaces.link.side_effect = CodexError('link failed')
        with self.assertRaises(CodexError) as caught:
            self.service.fork_session('thread', 'turn-2', 'commit')
        self.assertFalse(caught.exception.definite)
        self.assertEqual('workspace_thread_unknown', caught.exception.code)
        self.service.workspaces.abandon.assert_not_called()
        self.assertIn('child', self.service._joined)

    def test_fork_reads_persisted_history_instead_of_empty_new_thread(self):
        self.service.fork_session('thread', 'turn-2')
        self.assertNotIn('child', self.service._created)
        self.assertNotIn('child', self.service._unmaterialized)
        # Reaching native history inspection proves the new-thread empty shortcut
        # is not used, even before the first input to the fork.
        with patch.object(self.service.native_journal, 'read_scope', return_value=nullcontext()), \
             patch.object(self.service, '_native_history_state', side_effect=RuntimeError('native history reached')):
            with self.assertRaisesRegex(RuntimeError, 'native history reached'):
                self.service.history.__wrapped__(self.service, 'child')

    def test_shared_reservation_only_cancelled_on_definite_failure(self):
        self.service.workspaces.reserve_shared_directory.return_value = ('owner', 'reservation')
        original = self.native.request
        for uncertain in (False, True):
            self.service.workspaces.cancel_shared_directory.reset_mock()
            def fail(method, params, **kwargs):
                if method == 'thread/fork':
                    raise CodexError('failure', request_sent=uncertain)
                return original(method, params, **kwargs)
            self.native.request = fail
            with self.assertRaises(CodexError):
                self.service.fork_session('thread', 'turn-2')
            self.assertEqual(not uncertain, self.service.workspaces.cancel_shared_directory.called)
