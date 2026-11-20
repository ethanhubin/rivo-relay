import subprocess
import unittest
from unittest.mock import patch

from rivo_relay.node_name import NodeName, discover_name, _output


class NodeNameTests(unittest.TestCase):
    @patch('rivo_relay.node_name.platform.system', return_value='Darwin')
    @patch('rivo_relay.node_name.subprocess.run')
    def test_mac_reads_only_system_computer_name(self, run, _system):
        run.return_value.stdout = '  我的 Mac mini\n'
        self.assertEqual('我的 Mac mini', discover_name())
        run.assert_called_once_with(
            ['/usr/sbin/scutil', '--get', 'ComputerName'],
            capture_output=True, text=True, timeout=2, check=True)

    @patch('rivo_relay.node_name.platform.system', return_value='Darwin')
    @patch('rivo_relay.node_name.socket.gethostname', return_value='mac-host')
    @patch('rivo_relay.node_name._output', return_value='')
    def test_missing_computer_name_falls_back_to_hostname(self, *_):
        self.assertEqual('mac-host', discover_name())

    @patch('rivo_relay.node_name.platform.system', return_value='Linux')
    @patch('rivo_relay.node_name.socket.gethostname', return_value=' linux-host ')
    @patch('rivo_relay.node_name.subprocess.run')
    def test_other_system_uses_hostname_without_running_commands(self, run, *_):
        self.assertEqual('linux-host', discover_name())
        run.assert_not_called()

    @patch('rivo_relay.node_name.platform.system', return_value='Linux')
    @patch('rivo_relay.node_name.socket.gethostname', return_value='  ')
    def test_empty_hostname_uses_default_name(self, *_):
        self.assertEqual('Rivo Relay', discover_name())

    @patch('rivo_relay.node_name.subprocess.run')
    def test_cli_timeout_or_missing_binary_is_nonfatal(self, run):
        command = ['/usr/sbin/scutil', '--get', 'ComputerName']
        for error in (subprocess.TimeoutExpired(command, 2), FileNotFoundError()):
            run.side_effect = error
            self.assertEqual('', _output(command))

    @patch('rivo_relay.node_name.time.monotonic')
    @patch('rivo_relay.node_name.discover_name', side_effect=['first', 'renamed'])
    def test_names_refresh_after_bounded_cache(self, discover, clock):
        clock.return_value = 10
        name = NodeName()
        self.assertEqual('first', name.get())
        clock.return_value = 39
        self.assertEqual('first', name.get())
        clock.return_value = 41
        self.assertEqual('renamed', name.get())
        self.assertEqual(2, discover.call_count)
