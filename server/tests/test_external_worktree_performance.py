"""Single-scan reuse must retain native-host and membership boundaries."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.external_worktrees import ExternalWorktrees, _DISCOVER
from rivo_relay.read_budget import ReadStopped, read_budget
from test_native_files_worktrees import NativeHost


class DiscoveryReuseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / 'main repo'
        self.repo.mkdir()
        self.git(self.repo, 'init', '-q')
        self.git(self.repo, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                 'commit', '--allow-empty', '-qm', 'fixture')
        self.worktree = self.root / 'linked worktree'
        self.git(self.repo, 'worktree', 'add', '--detach', str(self.worktree), 'HEAD')
        self.projects = [{'id': f'p{i}', 'roots': [str(self.repo)]} for i in range(3)]
        self.paths = [str(self.worktree)] + [str(self.root / f'unrelated-{i}') for i in range(100)]

    @staticmethod
    def git(root, *args):
        subprocess.run(['git', '-C', str(root), *args], check=True, capture_output=True)

    def discover(self):
        output = io.StringIO()
        request = {'projects': self.projects, 'paths': self.paths}
        with patch.object(sys, 'argv', ['probe', json.dumps(request)]), contextlib.redirect_stdout(output):
            exec(_DISCOVER, {})
        return json.loads(output.getvalue())

    def test_repeated_roots_and_paths_are_probed_once_without_losing_ambiguity(self):
        with patch('subprocess.Popen', wraps=subprocess.Popen) as popen, \
                patch('os.path.realpath', wraps=os.path.realpath) as realpath:
            result = self.discover()
        self.assertEqual(['p0', 'p1', 'p2'], [row['projectId'] for row in result['matches'][str(self.worktree)]])
        calls = [tuple(call.args[0]) for call in popen.call_args_list]
        self.assertEqual(4, len(calls))
        self.assertEqual(len(calls), len(set(calls)))
        for path in self.paths:
            self.assertEqual(1, sum(call.args[0] == path for call in realpath.call_args_list))
        self.assertEqual(self.paths[1:], result['missing'])

    def test_git_results_do_not_survive_the_next_native_scan(self):
        before = self.discover()['matches'][str(self.worktree)][0]['commit']
        self.git(self.worktree, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                 'commit', '--allow-empty', '-qm', 'next')
        after = self.discover()['matches'][str(self.worktree)][0]['commit']
        self.assertNotEqual(before, after)

    def test_duplicate_project_roots_remain_ambiguous_for_unassigned_threads(self):
        native = NativeHost(self.root)
        observe = Mock()
        worktrees = ExternalWorktrees(native, observe, lambda _: {})
        projects = [{'id': p['id'], 'roots': [{'path': path} for path in p['roots']]} for p in self.projects]
        self.assertEqual({}, worktrees.associations([{'id': 'thread', 'cwd': str(self.worktree)}], projects))
        observe.assert_not_called()


class DiscoveryBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.native = Mock(generation='first')
        self.observe = Mock()
        self.worktrees = ExternalWorktrees(self.native, self.observe, lambda _: {})
        self.threads = [{'id': 'thread', 'cwd': '/node/worktree'}]
        self.projects = [{'id': 'project', 'roots': [{'path': '/node/project'}]}]

    def test_generation_change_during_probe_does_not_cache_or_observe_old_membership(self):
        def probe(*args, **kwargs):
            self.native.generation = 'second'
            return {'exitCode': 0, 'stdout': json.dumps({'matches': {'/node/worktree': [{
                'projectId': 'project', 'projectRoot': '/node/project', 'path': '/node/worktree', 'commit': 'old'}]}, 'missing': []})}
        with patch('rivo_relay.codex.external_worktrees.node_command', side_effect=probe):
            with self.assertRaises(CodexError) as raised:
                self.worktrees.associations(self.threads, self.projects)
        self.assertEqual('history_changed', raised.exception.code)
        self.assertIsNone(self.worktrees._cache)
        self.observe.assert_not_called()

    def test_cancelled_waiter_does_not_start_another_native_probe(self):
        self.worktrees._lock.acquire()
        try:
            with patch('rivo_relay.codex.external_worktrees.node_command') as probe:
                with self.assertRaises(ReadStopped), read_budget(seconds=.02):
                    self.worktrees.associations(self.threads, self.projects)
                probe.assert_not_called()
        finally:
            self.worktrees._lock.release()
