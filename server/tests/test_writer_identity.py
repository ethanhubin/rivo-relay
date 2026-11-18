import unittest
from unittest.mock import Mock, patch

from rivo_relay.codex.writers import WriterProcesses


class WriterIdentityTests(unittest.TestCase):
    def test_macos_missing_pidpath_uses_unique_kernel_text_mapping(self):
        for mappings, expected in [
            ('n/old.upgrading/bin/codex\nn/usr/lib/dyld\n', '/old.upgrading/bin/codex'),
            ('n/a/codex\nn/b/codex\n', None),
            ('n/usr/lib/dyld\n', None),
        ]:
            with self.subTest(mappings=mappings):
                manager = WriterProcesses('/tmp/codex-fixture')
                manager.run = Mock(side_effect=['start', 'codex app-server', 'codex', mappings])
                library = Mock()
                library.proc_pidpath.return_value = 0
                with patch('sys.platform', 'darwin'), patch('ctypes.CDLL', return_value=library):
                    result = manager.identity(12345)
                self.assertEqual(expected, result['executable'] if result else None)
                self.assertEqual(['lsof', '-nP', '-a', '-p', '12345', '-d', 'txt', '-Fn'],
                                 manager.run.call_args.args[0])
