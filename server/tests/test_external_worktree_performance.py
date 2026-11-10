"""Single-scan reuse must retain native-host and membership boundaries."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
from concurrent.futures import ThreadPoolExecutor
import unittest
from unittest.mock import Mock, patch

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.external_worktrees import ExternalWorktrees, _DISCOVER
from rivo_relay.read_budget import ReadStopped, read_budget, read_lock
from relay_test_support.files import NativeHost


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
        self.assertFalse(self.worktrees._cache)
        self.observe.assert_not_called()

    def response(self):
        return {'exitCode': 0, 'stdout': '{"matches": {}, "missing": []}'}

    def test_alternating_queries_reuse_both_cached_results(self):
        with patch('rivo_relay.codex.external_worktrees.node_command', return_value=self.response()) as probe:
            for _ in range(3):
                self.worktrees.associations(self.threads, self.projects)
                self.worktrees.associations([{'id': 'other', 'cwd': '/node/other'}], self.projects)
            self.assertEqual(2, probe.call_count)

    def test_cached_query_is_not_blocked_by_unrelated_probe(self):
        started, release = threading.Event(), threading.Event()
        def slow(*args, **kwargs):
            started.set()
            if not release.wait(3):
                raise AssertionError('probe was not released')
            return self.response()
        with patch('rivo_relay.codex.external_worktrees.node_command', return_value=self.response()):
            self.worktrees.associations(self.threads, self.projects)
        with ThreadPoolExecutor(max_workers=2) as pool:
            with patch('rivo_relay.codex.external_worktrees.node_command', side_effect=slow):
                future = pool.submit(self.worktrees.associations, [{'id': 'other', 'cwd': '/node/other'}], self.projects)
                try:
                    self.assertTrue(started.wait(2))
                    cached = pool.submit(self.worktrees.associations, self.threads, self.projects)
                    self.assertEqual({}, cached.result(timeout=1))
                finally:
                    release.set()
                future.result(timeout=2)

    def test_cancelled_waiter_does_not_start_another_native_probe(self):
        request = {'projects': [{'id': 'project', 'roots': ['/node/project']}], 'paths': ['/node/worktree']}
        key = ('first', json.dumps(request, sort_keys=True))
        with self.worktrees._flight(key):
            with patch('rivo_relay.codex.external_worktrees.node_command') as probe:
                with self.assertRaises(ReadStopped), read_budget(seconds=.02):
                    self.worktrees.associations(self.threads, self.projects)
                probe.assert_not_called()
        self.assertFalse(self.worktrees._flights)

    def test_concurrent_identical_queries_share_one_probe(self):
        started, release, waiting = threading.Event(), threading.Event(), threading.Event()
        def slow(*args, **kwargs):
            started.set()
            if not release.wait(3):
                raise AssertionError('probe was not released')
            return self.response()
        @contextlib.contextmanager
        def tracked_lock(lock):
            if lock.locked():
                waiting.set()
            with read_lock(lock):
                yield
        with ThreadPoolExecutor(max_workers=2) as pool:
            with patch('rivo_relay.codex.external_worktrees.node_command', side_effect=slow) as probe, \
                    patch('rivo_relay.codex.external_worktrees.read_lock', side_effect=tracked_lock):
                first = pool.submit(self.worktrees.associations, self.threads, self.projects)
                try:
                    self.assertTrue(started.wait(2))
                    second = pool.submit(self.worktrees.associations, self.threads, self.projects)
                    self.assertTrue(waiting.wait(2))
                finally:
                    release.set()
                self.assertEqual({}, first.result(timeout=2))
                self.assertEqual({}, second.result(timeout=2))
                self.assertEqual(1, probe.call_count)
        self.assertFalse(self.worktrees._flights)

    def test_cache_expiry_generation_and_capacity(self):
        with patch('rivo_relay.codex.external_worktrees.node_command', return_value=self.response()) as probe:
            with patch('rivo_relay.codex.external_worktrees.time.monotonic', return_value=0):
                self.worktrees.associations(self.threads, self.projects)
            with patch('rivo_relay.codex.external_worktrees.time.monotonic', return_value=16):
                self.worktrees.associations(self.threads, self.projects)
                self.native.generation = 'second'
                self.worktrees.associations(self.threads, self.projects)
                self.assertEqual(3, probe.call_count)
                for i in range(12):
                    self.worktrees.associations([{'id': str(i), 'cwd': f'/node/{i}'}], self.projects)
                self.assertEqual(8, len(self.worktrees._cache))
        self.assertFalse(self.worktrees._flights)
