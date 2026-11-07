"""Real temporary files/Git, accessed only through a controlled native RPC stub."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.external_worktrees import ExternalWorktrees
from rivo_relay.codex.native_download import prepare_download
from rivo_relay.codex.sessions import CodexService
from rivo_relay.database import Database


class NativeHost:
    generation = 'test-host'

    def __init__(self, root):
        self.root = Path(root)
        self.projects = []
        self.threads = []

    def start(self):
        return {}

    def request(self, method, params, **kwargs):
        if method == 'command/exec':
            result = subprocess.run(params['command'], cwd=params.get('cwd') or self.root,
                                    capture_output=True, text=True, timeout=35)
            return {'exitCode': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr}
        if method == 'fs/getMetadata':
            path = Path(params['path'])
            if not path.exists():
                raise CodexError('No such file', code='native_rejected', http_status=404, native_code=-1)
            return {'isFile': path.is_file(), 'isDirectory': path.is_dir(), 'isSymlink': path.is_symlink()}
        if method == 'project/list':
            return {'data': self.projects, 'nextCursor': None}
        if method == 'project/read':
            return {'project': next(project for project in self.projects if project['id'] == params['projectId'])}
        if method == 'thread/list':
            threads = self.threads
            if params.get('projectId'):
                threads = [thread for thread in threads if thread.get('projectId') == params['projectId']]
            return {'data': threads, 'nextCursor': None}
        if method == 'thread/read':
            return {'thread': next(thread for thread in self.threads if thread['id'] == params['threadId'])}
        raise AssertionError((method, params))


class NativeDownloadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.native = NativeHost(self.temp.name)
        self.file = Path(self.temp.name) / "file 空格 ' quote.bin"
        self.data = b'0123456789abcdef'
        self.file.write_bytes(self.data)

    def prepare(self, header=None, validator=None):
        return prepare_download(self.native, str(self.file), header, validator)

    def test_full_and_single_ranges_have_strong_content_validator(self):
        full = self.prepare()
        self.assertEqual('"sha256-' + hashlib.sha256(self.data).hexdigest() + '"', full['etag'])
        self.assertEqual(self.data, b''.join(full['chunks']))
        for header, expected, start, end in [('bytes=2-5', b'2345', 2, 5), ('bytes=12-', b'cdef', 12, 15),
                                             ('bytes=-4', b'cdef', 12, 15), ('bytes=14-999', b'ef', 14, 15)]:
            result = self.prepare(header, full['etag'])
            self.assertTrue(result['partial'])
            self.assertEqual((start, end), (result['start'], result['end']))
            self.assertEqual(expected, b''.join(result['chunks']))

    def test_changed_same_length_file_forces_full_restart_not_append(self):
        old = self.prepare()['etag']
        self.file.write_bytes(b'x' * len(self.data))
        new = self.prepare('bytes=8-', old)
        self.assertFalse(new['partial'])
        self.assertNotEqual(old, new['etag'])
        self.assertEqual(b'x' * len(self.data), b''.join(new['chunks']))

    def test_modified_between_chunks_aborts_before_mixed_bytes(self):
        with patch('rivo_relay.codex.native_download.TRANSFER_CHUNK_BYTES', 4):
            result = self.prepare()
            self.assertEqual(b'0123', next(result['chunks']))
            self.file.write_bytes(b'X' * len(self.data))
            with self.assertRaises(CodexError):
                next(result['chunks'])

    def test_invalid_multiranges_and_empty_files(self):
        for header in ('bytes=0-2,4-5', 'items=0-1', 'bytes=-0', 'bytes=99-', 'bytes=8-4', 'bytes=-', 'bytes=' + '9' * 100 + '-'):
            with self.assertRaises(CodexError) as caught:
                self.prepare(header)
            self.assertEqual(416, caught.exception.http_status)
            self.assertEqual(len(self.data), caught.exception.range_size)
        self.file.write_bytes(b'')
        self.assertEqual(b'', b''.join(self.prepare()['chunks']))
        with self.assertRaises(CodexError):
            self.prepare('bytes=0-')


class ExternalWorktreeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'main repo'
        self.repo.mkdir()
        self.git(self.repo, 'init', '-q')
        self.git(self.repo, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                 'commit', '--allow-empty', '-qm', 'fixture')
        self.worktree = self.root / 'external 中文'
        self.git(self.repo, 'worktree', 'add', '--detach', str(self.worktree), 'HEAD')
        self.native = NativeHost(self.root)
        self.native.projects = [{'id': 'project', 'name': 'Fixture', 'roots': [{'path': str(self.repo)}]}]
        self.native.threads = [{'id': 'legacy', 'cwd': str(self.worktree), 'updatedAt': 1, 'status': {'type': 'idle'}}]
        self.db = Database(':memory:')
        self.addCleanup(self.db.close)
        self.service = CodexService(connection=self.native)
        self.service.configure_storage(self.db)

    @staticmethod
    def git(cwd, *args):
        subprocess.run(['git', '-C', str(cwd), *args], check=True, capture_output=True)

    def test_external_membership_lists_counts_and_persists_deleted_association(self):
        sessions = self.service.list_sessions('project')
        self.assertEqual(['legacy'], [thread['id'] for thread in sessions])
        self.assertEqual('ready', sessions[0]['externalWorkspace']['status'])
        self.assertFalse(sessions[0]['externalWorkspace']['managed'])
        self.assertEqual(sessions[0]['externalWorkspace'], self.service.external_workspace('legacy'))
        self.assertEqual(1, self.service.list_projects()[0]['threadCount'])
        shutil.rmtree(self.worktree)
        # Recreate service to prove association is durable across Relay restart.
        restored = CodexService(connection=self.native)
        restored.configure_storage(self.db)
        missing = restored.list_sessions('project')
        self.assertEqual(['legacy'], [thread['id'] for thread in missing])
        self.assertEqual('missing', missing[0]['externalWorkspace']['status'])
        self.assertIsNone(missing[0]['workspace'])
        self.assertEqual({'path': str(self.repo), 'missingPath': str(self.worktree)},
                         restored._workspace_rebind(self.native.threads[0]))
        self.native.threads[0]['cwd'] = str(self.root / 'different missing path')
        self.assertEqual([], restored.list_sessions('project'))
        self.assertIsNone(restored._workspace_rebind(self.native.threads[0]))

    def test_subfolder_unrelated_clone_and_existing_replacement_are_not_imported(self):
        nested = self.repo / 'nested'
        nested.mkdir()
        self.native.projects[0]['roots'] = [{'path': str(nested)}]
        self.assertEqual([], self.service.list_sessions('project'))
        self.native.projects[0]['roots'] = [{'path': str(self.repo)}]
        self.assertEqual(1, len(self.service.list_sessions('project')))
        self.git(self.repo, 'worktree', 'remove', '--force', str(self.worktree))
        self.worktree.mkdir()
        self.git(self.worktree, 'init', '-q')
        restored = CodexService(connection=self.native)
        restored.configure_storage(self.db)
        self.assertEqual([], restored.list_sessions('project'))

    def test_missing_never_verified_worktree_is_not_guessed(self):
        shutil.rmtree(self.worktree)
        self.assertEqual([], self.service.list_sessions('project'))
