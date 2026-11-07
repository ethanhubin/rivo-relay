import json
import subprocess
import unittest
from unittest.mock import patch

from rivo_relay.node_name import NodeName, discover_name, _output


class NodeNameTests(unittest.TestCase):
    @patch('rivo_relay.node_name.shutil.which', return_value='/bin/tailscale')
    @patch('rivo_relay.node_name._output')
    def test_uses_self_dns_machine_name_not_os_hostname_or_peer(self, output, which):
        output.return_value = json.dumps({'Self': {'DNSName': 'renamed.tail123.ts.net.',
            'HostName': 'old-os-name'}, 'Peer': {'peer': {'DNSName': 'other.tail123.ts.net.'}}})
        self.assertEqual('renamed', discover_name())
        output.assert_called_once_with(['/bin/tailscale', 'status', '--json'])

    @patch('rivo_relay.node_name.shutil.which', return_value='/bin/tailscale')
    @patch('rivo_relay.node_name.platform.system', return_value='Darwin')
    @patch('rivo_relay.node_name._output')
    def test_missing_or_invalid_tailscale_falls_back_to_computer_name(self, output, system, which):
        for value in ('', 'not json', '{}', '[]', '{"Self":null}', '{"Self":{"DNSName":""}}'):
            output.side_effect = [value, '我的 Mac mini']
            self.assertEqual('我的 Mac mini', discover_name())

    @patch('rivo_relay.node_name.shutil.which', return_value=None)
    @patch('rivo_relay.node_name.Path.is_file', return_value=False)
    @patch('rivo_relay.node_name.platform.system', return_value='Linux')
    @patch('rivo_relay.node_name.socket.gethostname', return_value='linux-host')
    def test_without_tailscale_uses_os_hostname(self, *_):
        self.assertEqual('linux-host', discover_name())

    @patch('rivo_relay.node_name.shutil.which', return_value=None)
    @patch('rivo_relay.node_name.Path.is_file', return_value=True)
    @patch('rivo_relay.node_name._output', return_value='{"Self":{"DNSName":"mac.tail.ts.net."}}')
    def test_launchagent_without_tailscale_in_path(self, output, *_):
        self.assertEqual('mac', discover_name())
        self.assertEqual('/usr/local/bin/tailscale', output.call_args.args[0][0])

    @patch('rivo_relay.node_name.subprocess.run')
    def test_cli_timeout_or_missing_binary_is_nonfatal(self, run):
        for error in (subprocess.TimeoutExpired('tailscale', 2), FileNotFoundError()):
            run.side_effect = error
            self.assertEqual('', _output(['tailscale', 'status', '--json']))

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
