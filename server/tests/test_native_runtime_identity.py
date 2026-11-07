"""macOS history identity must use the kernel image, never a short ps name."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from rivo_relay.codex.native_history_host import verify_runtime
from rivo_relay.codex.native_reprojection import NativeHistoryReprojectionError


class RuntimeIdentityTests(unittest.TestCase):
    def check(self, *, actual='/opt/homebrew/Caskroom/codex/0.159.0/bin/codex', size=None):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        descriptor = {'pid': 4321, 'processStartMethod': 'ps-lstart', 'processStart': 'same-start',
                      'executable': '/opt/homebrew/Caskroom/codex/0.159.0/bin/codex',
                      'codexHome': temporary.name}
        def kernel_path(pid, buffer, capacity):
            self.assertEqual(4321, pid)
            buffer.value = actual.encode()
            return len(buffer.value) if size is None else size
        library = Mock()
        library.proc_pidpath.side_effect = kernel_path
        with patch('rivo_relay.codex.native_history_host.sys.platform', 'darwin'), \
             patch('rivo_relay.codex.native_history_host.subprocess.run', return_value=Mock(returncode=0, stdout='same-start\n')), \
             patch('rivo_relay.codex.native_history_host.subprocess.check_output', side_effect=AssertionError('ps comm is not an executable path')), \
             patch('ctypes.CDLL', return_value=library):
            self.assertEqual(Path(temporary.name).resolve(), verify_runtime(descriptor))

    def test_path_launched_daemon_is_verified_without_ps_comm(self):
        self.check()

    def test_actual_image_change_is_still_rejected(self):
        with self.assertRaisesRegex(NativeHistoryReprojectionError, '可执行文件已变化'):
            self.check(actual='/different/bin/codex')

    def test_missing_truncated_or_relative_kernel_identity_fails_closed(self):
        for values in ({'size': 0}, {'size': 4096}, {'actual': 'codex'}):
            with self.subTest(values=values), self.assertRaisesRegex(NativeHistoryReprojectionError, '无法核实'):
                self.check(**values)
