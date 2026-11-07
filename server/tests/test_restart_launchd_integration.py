"""Opt-in real launchd switch/rollback drill; never targets the production job."""
import os
from pathlib import Path
import plistlib
import socket
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch
from test_service_restart import m


@unittest.skipUnless(os.environ.get('RIVO_TEST_LAUNCHD') == '1', 'explicit isolated launchd drill only')
class LaunchdDrill(unittest.TestCase):
    def test_real_switch_and_failed_health_rollback(self):
        label = 'local.rivo-relay.test.' + uuid.uuid4().hex
        domain = f'gui/{os.getuid()}'
        target = domain + '/' + label
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0))
                port = sock.getsockname()[1]
            server = root / 'server.py'
            server.write_text('''from http.server import BaseHTTPRequestHandler, HTTPServer
import sys
class Handler(BaseHTTPRequestHandler):
 def do_GET(self):
  self.send_response(200); self.end_headers(); self.wfile.write(sys.argv[2].encode())
HTTPServer(('127.0.0.1', int(sys.argv[1])), Handler).serve_forever()
''')
            path, candidate = root / 'service.plist', root / 'candidate.plist'
            config = {'Label': 'local.rivo-relay', 'ProgramArguments': [sys.executable, str(server), str(port), '{"status":"ok"}'], 'RunAtLoad': True, 'KeepAlive': False}
            path.write_bytes(plistlib.dumps(config))
            health = f'http://127.0.0.1:{port}/health'

            def isolated_command(operation, *args):
                # Only job identity/path translation is substituted; launchctl,
                # process termination, HTTP health and rollback are all real.
                if operation == 'bootstrap':
                    actual = plistlib.loads(Path(args[1]).read_bytes())
                    actual['Label'] = label
                    job = root / 'isolated.plist'
                    job.write_bytes(plistlib.dumps(actual))
                    command = ['launchctl', operation, domain, str(job)]
                else:
                    assert operation in ('print', 'bootout')
                    command = ['launchctl', operation, target]
                return subprocess.run(command, capture_output=True, timeout=15).returncode

            try:
                with patch.object(m, 'command', side_effect=isolated_command):
                    m.start(domain, target, path, health)
                    config['EnvironmentVariables'] = {'DRILL_VERSION': '2'}
                    good = plistlib.dumps(config)
                    candidate.write_bytes(good)
                    m.replace(path, candidate, health)
                    self.assertEqual(path.read_bytes(), good)
                    config['ProgramArguments'][-1] = '{"status":"bad"}'
                    candidate.write_bytes(plistlib.dumps(config))
                    with self.assertRaisesRegex(RuntimeError, 'health_failed'):
                        m.replace(path, candidate, health)
                    self.assertEqual(path.read_bytes(), good)
                    m.start(domain, target, path, health)
            finally:
                subprocess.run(['launchctl', 'bootout', target], capture_output=True)
