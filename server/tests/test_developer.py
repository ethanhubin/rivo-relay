"""Real Git in disposable directories, behind the selected-host command RPC contract."""
from __future__ import annotations

import copy
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import threading
import unittest

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.developer import (
    Workspaces, directory_fingerprint, project_git, review_context,
    validate_review, workspace_diff,
)
from rivo_relay.database import Database
from rivo_relay.codex.sessions import CodexService


class LocalNative:
    """Runs only inside this test's temporary selected-host root; never user repositories."""
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.calls = []
        self.after_archive = None
        self.fail_archive = False
        self.fail_remove = False
        self.fail_add = False
        self.fail_add_after_success = False
        self.unknown_add_after_success = False
        self.unknown_remove_after_success = False
        self.environment = dict(os.environ, GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
                                GIT_AUTHOR_NAME='Relay Test', GIT_AUTHOR_EMAIL='relay@example.invalid',
                                GIT_COMMITTER_NAME='Relay Test', GIT_COMMITTER_EMAIL='relay@example.invalid')

    def run(self, argv, cwd):
        cwd = Path(cwd).resolve()
        if not cwd.is_relative_to(self.root):
            raise AssertionError('Test command escaped disposable native host')
        return subprocess.run(argv, cwd=cwd, env=self.environment, capture_output=True, timeout=20)

    def request(self, method, params, **kwargs):
        self.calls.append((method, copy.deepcopy(params)))
        if method == 'fs/createDirectory':
            target = Path(params['path']).resolve()
            if not target.is_relative_to(self.root):
                raise AssertionError('Test mkdir escaped disposable native host')
            target.mkdir(parents=params.get('recursive', False), exist_ok=True)
            return {}
        if method != 'command/exec':
            raise AssertionError(method)
        argv = params['command']
        if (self.fail_archive and argv[0] == 'tar' or self.fail_remove and 'worktree' in argv and 'remove' in argv
                or self.fail_add and 'worktree' in argv and 'add' in argv):
            return {'exitCode': 1, 'stdout': '', 'stderr': 'injected test command failure'}
        result = self.run(argv, params.get('cwd') or self.root)
        if 'worktree' in argv and 'add' in argv and result.returncode == 0:
            if self.unknown_add_after_success:
                raise CodexError('lost add reply', request_sent=True)
            if self.fail_add_after_success:
                return {'exitCode': 1, 'stdout': '', 'stderr': 'injected post-checkout failure'}
        if 'worktree' in argv and 'remove' in argv and result.returncode == 0 and self.unknown_remove_after_success:
            raise CodexError('lost remove reply', request_sent=True)
        if argv[0] == 'tar' and result.returncode == 0 and self.after_archive:
            self.after_archive()
        cap = params.get('outputBytesCap', 2 * 1024 * 1024)
        return {'exitCode': result.returncode, 'stdout': result.stdout[:cap].decode('utf-8', errors='replace'),
                'stderr': result.stderr[:cap].decode('utf-8', errors='replace')}


class WorkspaceService:
    def __init__(self, connection):
        self.connection = connection
        self._operations = threading.RLock()
        self.snapshot = {'readOnly': False, 'activeTurnId': None, 'requests': [], 'status': 'idle'}
        self.archived = []

    def get_session(self, thread_id):
        return copy.deepcopy(self.snapshot)

    def archive_session(self, thread_id, archived):
        self.archived.append((thread_id, archived))
        return {'threadId': thread_id}


class DeveloperTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='rivo-developer-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        self.native = LocalNative(self.root)
        self.git('init', '-b', 'main')
        self.git('config', 'core.hooksPath', os.devnull)
        self.git('config', 'commit.gpgsign', 'false')
        (self.source / 'tracked.txt').write_text('before\n', encoding='utf-8')
        (self.source / 'binary.bin').write_bytes(b'\x00original\xff')
        (self.source / '.gitignore').write_text('ignored*.txt\n', encoding='utf-8')
        self.git('add', '--', '.')
        self.git('commit', '-m', 'Initial fixture')
        self.head = self.git('rev-parse', 'HEAD').strip()
        self.db = Database(':memory:')
        self.addCleanup(self.db.close)
        self.service = WorkspaceService(self.native)
        self.workspaces = Workspaces(self.db, self.service)

    def git(self, *args):
        result = self.native.run(['git', *args], self.source)
        self.assertEqual(0, result.returncode, result.stderr.decode())
        return result.stdout.decode()

    def create(self, thread='thread'):
        identity, path = self.workspaces.create('project', str(self.source), self.head)
        self.workspaces.link(identity, thread)
        return Path(path)

    def assert_code(self, code, fn):
        with self.assertRaises(CodexError) as raised:
            fn()
        self.assertEqual(code, raised.exception.code)

    def session_service(self, outcome):
        self.native.generation = 'developer-test-runtime'
        self.native.start = lambda: {'connected': True}
        native_request = self.native.request

        def request(method, params, **kwargs):
            if method == 'project/read':
                self.native.calls.append((method, copy.deepcopy(params)))
                return {'project': {'id': 'project', 'roots': [{'path': str(self.source)}]}}
            if method == 'thread/start':
                self.native.calls.append((method, copy.deepcopy(params)))
                if outcome == 'reject':
                    raise CodexError('native thread rejected', code='native_rejected', http_status=409)
                if outcome == 'lost':
                    raise CodexError('lost thread reply', request_sent=True)
                return {'thread': {}}
            return native_request(method, params, **kwargs)

        self.native.request = request
        service = CodexService(connection=self.native)
        service.configure_storage(self.db)
        return service

    def test_diff_includes_tracked_and_untracked_without_staging(self):
        (self.source / 'tracked.txt').write_text('after\n', encoding='utf-8')
        (self.source / 'new file.txt').write_text('new content\n', encoding='utf-8')
        diff = workspace_diff(self.native, str(self.source))
        self.assertEqual({'tracked.txt', 'new file.txt'}, {item['path'] for item in diff['files']})
        self.assertIn('-before', diff['patch'])
        self.assertIn('+after', diff['patch'])
        self.assertIn('+new content', diff['patch'])
        self.assertFalse(diff['truncated'])
        self.assertEqual('', self.git('diff', '--cached'))
        commands = [params for method, params in self.native.calls if method == 'command/exec']
        self.assertTrue(all('sandboxPolicy' not in params and 'approvalPolicy' not in params for params in commands))

    def test_git_refs_and_review_context_use_selected_repository(self):
        self.git('branch', 'feature')
        info = project_git(self.native, str(self.source))
        self.assertTrue(info['available'])
        self.assertEqual(self.head, info['head'])
        self.assertIn('feature', {item['name'] for item in info['refs']})
        context = review_context(self.native, str(self.source))
        self.assertEqual('main', context['branch'])
        self.assertIn('feature', context['branches'])
        self.assertEqual(self.head, context['commits'][0]['id'])

    def test_invalid_ref_creates_no_directory_or_state(self):
        self.assert_code('invalid_worktree_ref', lambda: self.workspaces.create('project', str(self.source), '--bad-ref'))
        with self.db.read() as connection:
            self.assertEqual(0, connection.execute('SELECT COUNT(*) FROM workspaces').fetchone()[0])
        self.assertFalse((self.root / '.rivo-worktrees').exists())

    def test_failed_add_with_no_checkout_leaves_no_live_workspace(self):
        self.native.fail_add = True
        self.assert_code('git_operation_failed', lambda: self.workspaces.create('project', str(self.source), self.head))
        with self.db.read() as connection:
            row = connection.execute('SELECT * FROM workspaces').fetchone()
        self.assertEqual('removed', row['status'])
        self.assertFalse(Path(row['path']).exists())

    def test_partial_add_failure_reclaims_only_clean_checkout(self):
        self.native.fail_add_after_success = True
        self.assert_code('git_operation_failed', lambda: self.workspaces.create('project', str(self.source), self.head))
        with self.db.read() as connection:
            row = connection.execute('SELECT * FROM workspaces').fetchone()
        self.assertEqual('removed', row['status'])
        self.assertFalse(Path(row['path']).exists())
        removes = [params['command'] for method, params in self.native.calls if method == 'command/exec' and 'remove' in params['command']]
        self.assertEqual(1, len(removes))
        self.assertNotIn('--force', removes[0])

    def test_unknown_add_result_preserves_checkout_without_replay(self):
        self.native.unknown_add_after_success = True
        self.assert_code('workspace_create_unknown', lambda: self.workspaces.create('project', str(self.source), self.head))
        with self.db.read() as connection:
            row = connection.execute('SELECT * FROM workspaces').fetchone()
        self.assertEqual('creation_unknown', row['status'])
        self.assertTrue(Path(row['path']).is_dir())
        self.assertFalse(any(method == 'command/exec' and 'remove' in params['command'] for method, params in self.native.calls))

    def test_definite_thread_start_failure_reclaims_unlinked_workspace(self):
        identity, path = self.workspaces.create('project', str(self.source), self.head)
        result = self.workspaces.abandon(identity)
        self.assertEqual('removed', result['status'])
        self.assertFalse(Path(path).exists())

    def test_uncertain_thread_start_or_changed_unlinked_workspace_is_retained(self):
        for kind in ('uncertain', 'dirty', 'ignored', 'new_commit'):
            with self.subTest(kind=kind):
                identity, raw_path = self.workspaces.create('project', str(self.source), self.head)
                path = Path(raw_path)
                if kind == 'dirty':
                    (path / 'tracked.txt').write_text('keep this edit')
                elif kind == 'ignored':
                    (path / 'ignored-local.txt').write_text('keep ignored data')
                elif kind == 'new_commit':
                    (path / 'tracked.txt').write_text('new work')
                    self.assertEqual(0, self.native.run(['git', 'add', '.'], path).returncode)
                    self.assertEqual(0, self.native.run(['git', '-c', 'commit.gpgsign=false', 'commit', '-m', 'Unlinked work'], path).returncode)
                result = self.workspaces.abandon(identity, uncertain=kind == 'uncertain')
                self.assertEqual('creation_unknown' if kind == 'uncertain' else 'orphaned', result['status'])
                self.assertTrue(path.is_dir())

    def test_abandon_never_removes_a_linked_thread_workspace(self):
        path = self.create()
        row = self.workspaces.row('thread')
        self.assertEqual('ready', self.workspaces.abandon(row['id'])['status'])
        self.assertTrue(path.is_dir())
        self.assertEqual('thread', self.workspaces.row('thread')['thread_id'])

    def test_native_thread_start_rejection_reclaims_checkout_without_thread_identity(self):
        service = self.session_service('reject')
        self.assert_code('native_rejected', lambda: service.create_session('project', worktree_ref=self.head))
        with self.db.read() as connection:
            row = connection.execute('SELECT * FROM workspaces').fetchone()
        self.assertEqual('removed', row['status'])
        self.assertIsNone(row['thread_id'])
        self.assertFalse(Path(row['path']).exists())
        self.assertEqual(1, sum(method == 'thread/start' for method, _ in self.native.calls))
        self.assertEqual({}, service._created)

    def test_native_thread_start_unknown_or_missing_identity_retains_checkout(self):
        for outcome in ('lost', 'missing'):
            with self.subTest(outcome=outcome):
                service = self.session_service(outcome)
                with self.assertRaises(CodexError) as raised:
                    service.create_session('project', worktree_ref=self.head)
                self.assertEqual('workspace_thread_unknown', raised.exception.code)
                self.assertFalse(raised.exception.definite)
                with self.db.read() as connection:
                    rows = [dict(row) for row in connection.execute('SELECT * FROM workspaces')]
                self.assertTrue(all(row['status'] == 'creation_unknown' and row['thread_id'] is None for row in rows))
                self.assertTrue(all(Path(row['path']).is_dir() for row in rows))
                self.assertTrue(any(row['path'] in str(raised.exception) for row in rows))
                self.assertEqual({}, service._created)
        self.assertEqual(2, sum(method == 'thread/start' for method, _ in self.native.calls))
        self.assertFalse(any(method == 'command/exec' and 'remove' in params['command'] for method, params in self.native.calls))

    def test_clean_worktree_create_and_cleanup_preserve_source(self):
        path = self.create()
        self.assertTrue(path.is_dir())
        self.assertEqual('before\n', (path / 'tracked.txt').read_text())
        current = self.workspaces.inspect('thread')
        self.assertFalse(current['dirty'])
        self.assertEqual('ready', current['status'])
        self.workspaces.cleanup('thread', current['revision'], False)
        self.assertFalse(path.exists())
        self.assertEqual('removed', self.workspaces.inspect('thread')['status'])
        self.assertEqual([('thread', True)], self.service.archived)
        self.assertEqual('before\n', (self.source / 'tracked.txt').read_text())
        self.assertEqual('', self.git('status', '--porcelain'))

    def test_dirty_cleanup_requires_confirmation_and_keeps_archive(self):
        path = self.create()
        (path / 'notes.txt').write_text('keep these changes\n')
        current = self.workspaces.inspect('thread')
        self.assertTrue(current['dirty'])
        self.assert_code('workspace_dirty', lambda: self.workspaces.cleanup('thread', current['revision'], False))
        self.assertTrue(path.exists())
        self.assertEqual([], self.service.archived)
        self.workspaces.cleanup('thread', current['revision'], True)
        stored = self.workspaces.row('thread')
        self.assertEqual('removed', stored['status'])
        self.assertTrue(Path(stored['archive_path']).is_file())
        with tarfile.open(stored['archive_path']) as archive:
            self.assertEqual(b'keep these changes\n', archive.extractfile('./notes.txt').read())

    def test_fingerprint_detects_binary_ignored_and_symlink_changes(self):
        path = self.create()
        initial = directory_fingerprint(self.native, str(path))
        (path / 'binary.bin').write_bytes(b'\x00different\xff')
        binary = directory_fingerprint(self.native, str(path))
        self.assertNotEqual(initial, binary)
        (path / 'ignored-private.txt').write_text('first')
        ignored = directory_fingerprint(self.native, str(path))
        self.assertNotEqual(binary, ignored)
        (path / 'ignored-private.txt').write_text('other')
        self.assertNotEqual(ignored, directory_fingerprint(self.native, str(path)))
        (path / 'link').symlink_to('tracked.txt')
        symlink = directory_fingerprint(self.native, str(path))
        (path / 'link').unlink()
        (path / 'link').symlink_to('binary.bin')
        self.assertNotEqual(symlink, directory_fingerprint(self.native, str(path)))

    def test_ignored_files_also_require_explicit_dirty_confirmation(self):
        path = self.create()
        (path / 'ignored-private.txt').write_text('keep ignored data')
        current = self.workspaces.inspect('thread')
        self.assertTrue(current['dirty'])
        self.assert_code('workspace_dirty', lambda: self.workspaces.cleanup('thread', current['revision'], False))
        self.assertTrue(path.exists())

    def test_edits_after_confirmation_and_during_archive_block_removal(self):
        path = self.create()
        (path / 'binary.bin').write_bytes(b'\x00first\xff')
        before = self.workspaces.inspect('thread')
        (path / 'binary.bin').write_bytes(b'\x00second\xff')
        self.assert_code('workspace_changed', lambda: self.workspaces.cleanup('thread', before['revision'], True))
        current = self.workspaces.inspect('thread')
        self.native.after_archive = lambda: (path / 'binary.bin').write_bytes(b'\x00latest\xff')
        self.assert_code('workspace_changed', lambda: self.workspaces.cleanup('thread', current['revision'], True))
        self.assertEqual(b'\x00latest\xff', (path / 'binary.bin').read_bytes())
        stored = self.workspaces.row('thread')
        self.assertEqual('ready', stored['status'])
        with tarfile.open(stored['archive_path']) as archive:
            self.assertEqual(b'\x00second\xff', archive.extractfile('./binary.bin').read())

    def test_active_turn_requests_and_external_writer_block_cleanup(self):
        path = self.create()
        current = self.workspaces.inspect('thread')
        for state in ({'activeTurnId': 'turn'}, {'readOnly': True}, {'requests': [{'key': 'approval'}]}):
            with self.subTest(state=state):
                self.service.snapshot = {'readOnly': False, 'activeTurnId': None, 'requests': [], **state}
                self.assert_code('workspace_busy', lambda: self.workspaces.cleanup('thread', current['revision'], False))
                self.assertTrue(path.exists())
        self.assertEqual([], self.service.archived)

    def test_archive_failure_keeps_workspace_retryable(self):
        path = self.create()
        (path / 'notes.txt').write_text('not archived yet')
        current = self.workspaces.inspect('thread')
        self.native.fail_archive = True
        self.assert_code('git_operation_failed', lambda: self.workspaces.cleanup('thread', current['revision'], True))
        self.assertTrue(path.is_dir())
        self.assertEqual('ready', self.workspaces.row('thread')['status'])
        self.native.fail_archive = False
        latest = self.workspaces.inspect('thread')
        self.workspaces.cleanup('thread', latest['revision'], True)
        self.assertFalse(path.exists())

    def test_remove_failure_keeps_workspace_retryable(self):
        path = self.create()
        current = self.workspaces.inspect('thread')
        self.native.fail_remove = True
        self.assert_code('git_operation_failed', lambda: self.workspaces.cleanup('thread', current['revision'], False))
        self.assertTrue(path.is_dir())
        self.assertEqual('ready', self.workspaces.row('thread')['status'])
        self.native.fail_remove = False
        latest = self.workspaces.inspect('thread')
        self.workspaces.cleanup('thread', latest['revision'], False)
        self.assertFalse(path.exists())

    def test_lost_remove_reply_reconciles_by_reading_without_second_remove(self):
        path = self.create()
        current = self.workspaces.inspect('thread')
        self.native.unknown_remove_after_success = True
        self.assert_code('native_unavailable', lambda: self.workspaces.cleanup('thread', current['revision'], False))
        self.assertEqual('removing', self.workspaces.row('thread')['status'])
        self.assertFalse(path.exists())
        self.assertEqual('removed', self.workspaces.inspect('thread')['status'])
        removes = [params for method, params in self.native.calls if method == 'command/exec' and 'remove' in params['command']]
        self.assertEqual(1, len(removes))

    def test_failure_state_compensation_keeps_operation_lock_held(self):
        path = self.create()
        (path / 'notes.txt').write_text('dirty')
        current = self.workspaces.inspect('thread')
        self.native.fail_archive = True
        compensation_ownership = []
        self.db._connection.set_trace_callback(lambda sql: compensation_ownership.append(self.service._operations._is_owned())
            if "SET status='ready'" in sql else None)
        self.addCleanup(lambda: self.db._connection.set_trace_callback(None))
        self.assert_code('git_operation_failed', lambda: self.workspaces.cleanup('thread', current['revision'], True))
        self.assertTrue(compensation_ownership)
        self.assertTrue(all(compensation_ownership))

    def test_queued_work_blocks_cleanup_inside_workspace_operation(self):
        path = self.create()
        current = self.workspaces.inspect('thread')
        with self.db.transaction() as connection:
            connection.execute("INSERT INTO connections VALUES ('phone','digest','Phone',1,NULL)")
            connection.execute("INSERT INTO queued_tasks(id,connection_id,thread_id,payload_json,status,position,created_at) VALUES ('item','phone','thread','{}','waiting',1,1)")
        for status in ('waiting', 'running', 'dispatching', 'unknown'):
            with self.subTest(status=status):
                with self.db.transaction() as connection:
                    connection.execute('UPDATE queued_tasks SET status=?', (status,))
                self.assert_code('workspace_busy', lambda: self.workspaces.cleanup('thread', current['revision'], False))
                self.assertTrue(path.exists())
        self.assertEqual([], self.service.archived)

    def test_review_targets_are_bounded_and_keep_native_shape(self):
        self.assertEqual({'type': 'uncommittedChanges'}, validate_review({'type': 'uncommittedChanges'}))
        self.assertEqual({'type': 'baseBranch', 'branch': 'main'}, validate_review({'type': 'baseBranch', 'branch': ' main '}))
        self.assertEqual(self.head, validate_review({'type': 'commit', 'sha': self.head})['sha'])
        self.assertEqual('Security review', validate_review({'type': 'custom', 'instructions': 'Security review'})['instructions'])
        for target in (None, {}, {'type': 'custom', 'instructions': ''}, {'type': 'custom', 'instructions': 'x' * 20001}, {'type': 'unknown', 'branch': 'main'}):
            self.assert_code('invalid_review', lambda target=target: validate_review(target))


if __name__ == '__main__':
    unittest.main()
