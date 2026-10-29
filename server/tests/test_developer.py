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


class LocalNative:
    """Runs only inside this test's temporary selected-host root; never user repositories."""
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.calls = []
        self.after_archive = None
        self.fail_archive = False
        self.fail_remove = False
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
        if self.fail_archive and argv[0] == 'tar' or self.fail_remove and 'worktree' in argv and 'remove' in argv:
            return {'exitCode': 1, 'stdout': '', 'stderr': 'injected test command failure'}
        result = self.run(argv, params.get('cwd') or self.root)
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
