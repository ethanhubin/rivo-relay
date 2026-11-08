"""Windows kernel integration and portable node configuration regressions."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from rivo_relay.config import Config
from rivo_relay.codex import native_history_support as locks
from rivo_relay.codex.developer import project_directory, worktree_presence
from rivo_relay.codex.owned_writer import _ProcessSocket


class NodePlatformTests(unittest.TestCase):
    def test_stdio_requires_explicit_executable(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ValueError, 'executable'):
                Config(Path(root), codex_runtime='stdio').validate()
            Config(Path(root), codex_runtime='stdio', codex_executable=Path(sys.executable)).validate()
            with self.assertRaisesRegex(ValueError, 'stdio'):
                Config(Path(root), codex_executable=Path(sys.executable)).validate()

    def test_windows_drive_and_unc_parents(self):
        for path, parent in [('C:\\', 'C:\\'), ('C:\\work\\repo', 'C:\\work'),
                             ('\\\\server\\share\\folder', '\\\\server\\share\\')]:
            with self.subTest(path=path), patch('rivo_relay.codex.developer.read_directory', return_value={}):
                self.assertEqual(parent, project_directory(Mock(), path)['parentPath'])

    def test_windows_worktree_git_forward_slashes_and_case(self):
        with patch('rivo_relay.codex.developer.git', return_value='worktree C:/Work/repo\0'), \
             patch('rivo_relay.codex.developer.command', return_value=json.dumps({'path': 'c:\\work\\repo', 'exists': True})):
            self.assertEqual((True, True), worktree_presence(Mock(), 'C:\\Work', 'c:\\work\\repo'))


@unittest.skipUnless(os.name == 'nt', 'Windows kernel integration')
class WindowsKernelTests(unittest.TestCase):
    def test_exclusive_lock_matches_other_process_and_releases(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'writer.lock'; path.touch()
            script = "from rivo_relay.codex import native_history_support as l; import sys; f=open(sys.argv[1],'r+b'); l.flock(f.fileno(),l.LOCK_EX); print('ready',flush=True); sys.stdin.readline()"
            child = subprocess.Popen([sys.executable, '-c', script, str(path)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
            try:
                self.assertEqual('ready', child.stdout.readline().strip())
                with path.open('rb') as stream:
                    with self.assertRaises(BlockingIOError):
                        locks.flock(stream.fileno(), locks.LOCK_EX | locks.LOCK_NB)
                child.communicate('\n', timeout=5)
                with path.open('rb') as stream:
                    locks.flock(stream.fileno(), locks.LOCK_EX | locks.LOCK_NB)
                    locks.flock(stream.fileno(), locks.LOCK_UN)
            finally:
                if child.poll() is None: child.kill(); child.wait()
                child.stdin.close(); child.stdout.close()

    def test_job_kills_orphan_child_after_parent_exits(self):
        from rivo_relay.codex.windows_process import spawn_owned
        script = "import subprocess,sys; p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(90)']); print(p.pid,flush=True)"
        process = spawn_owned([sys.executable, '-c', script], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        sock = _ProcessSocket(process)
        try:
            child = int(process.stdout.readline())
            process.wait(timeout=5)
            self.assertIsNotNone(locks.windows_identity(child))
            sock.close()
            self.assertIsNone(locks.windows_identity(child))
            sock.close()
        finally:
            sock.close(); process.stdout.close()

    def test_pipe_unicode_and_identity(self):
        from rivo_relay.codex.windows_process import spawn_owned
        process = spawn_owned([sys.executable, '-X', 'utf8', '-c', 'import sys; print(sys.stdin.readline().strip(),flush=True)'],
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding='utf-8')
        sock = _ProcessSocket(process)
        try:
            identity = locks.windows_identity(process.pid)
            self.assertEqual(process.pid, identity['pid'])
            self.assertEqual(Path(sys.executable).resolve(), Path(identity['executable']).resolve())
            sock.send('中文 空格 ✓')
            self.assertEqual('中文 空格 ✓', process.stdout.readline().strip())
        finally:
            sock.close(); process.stdout.close()



class HostCommandTests(unittest.TestCase):
    def test_explicit_process_api_collects_output_before_spawn_reply(self):
        from rivo_relay.codex.host_commands import NativeProcessCommands
        class Connection:
            codex_home = Path('/fixture')
            def request(self, method, params, **kwargs):
                self.seen = (method, params)
                self.commands.event({'method': 'process/exited', 'params': {
                    'processHandle': params['processHandle'], 'exitCode': 0,
                    'stdout': 'hello', 'stderr': ''}})
                return {}
        connection = Connection(); commands = connection.commands = NativeProcessCommands(connection)
        result = commands.run({'command': ['git', 'status'], 'outputBytesCap': 3})
        self.assertEqual({'exitCode': 0, 'stdout': 'hel', 'stderr': ''}, result)
        self.assertEqual('process/spawn', connection.seen[0])
        self.assertFalse(connection.seen[1]['tty'])
        self.assertEqual({}, commands.pending)

    def test_timeout_kills_exact_handle_without_replay(self):
        from rivo_relay.codex.host_commands import NativeProcessCommands
        from rivo_relay.codex.connection import CodexError
        connection = Mock(codex_home=Path('/fixture'))
        commands = NativeProcessCommands(connection)
        with self.assertRaises(CodexError):
            commands.run({'command': ['fixture'], 'timeoutMs': 1})
        self.assertEqual(['process/spawn', 'process/kill'], [call.args[0] for call in connection.request.call_args_list])
        first, last = connection.request.call_args_list
        self.assertEqual(first.args[1]['processHandle'], last.args[1]['processHandle'])
        self.assertEqual({}, commands.pending)

    def test_non_windows_transport_keeps_original_command_rpc(self):
        from rivo_relay.codex.host_commands import node_command
        connection = Mock(); connection.status.return_value = {'transport': 'unix-websocket'}
        node_command(connection, {'command': ['git', 'status']}, timeout=3)
        connection.request.assert_called_once_with('command/exec', {'command': ['git', 'status']}, timeout=3)


if __name__ == '__main__': unittest.main()
