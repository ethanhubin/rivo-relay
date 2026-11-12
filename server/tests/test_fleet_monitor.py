import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from rivo_relay.presence import Presence
from rivo_relay.gateway.monitor import clean_report, snapshot, handle
from aiohttp import web


class PresenceTests(unittest.TestCase):
    def test_dedup_long_requests_expiry_and_revocation(self):
        now = [0]
        p = Presence(lambda: now[0])
        p.begin('a'); p.begin('a'); p.begin('b')
        self.assertEqual(2, p.snapshot({'a', 'b'})['activePairings'])
        self.assertEqual(2, p.snapshot({'a'})['inFlightRequests'])
        p.end('a'); p.end('a')
        now[0] = 91
        self.assertEqual(1, p.snapshot({'a', 'b'})['activePairings'])
        p.end('b'); now[0] = 182
        self.assertEqual(0, p.snapshot({'a', 'b'})['activePairings'])


class MonitorTests(unittest.IsolatedAsyncioTestCase):
    def test_allowlist(self):
        self.assertIsNone(clean_report([]))
        r = clean_report({'kind': 'relay', 'token': 'secret', 'name': 'a\nb',
                          'apps': {'activePairings': True}, 'releaseCode': -1})
        self.assertEqual({'kind': 'relay', 'name': 'ab'}, r)
        self.assertEqual({}, clean_report({'kind': 'relay', 'update': {'phase': 1, 'reason': False, 'stale': 'no'}})['update'])

    async def test_auth_and_stale_unknown(self):
        with tempfile.TemporaryDirectory() as tmp:
            keys = Path(tmp) / 'keys.json'; keys.write_text(json.dumps({'a': 'secret', 'b': 'secret'}))
            g = SimpleNamespace(keys_file=keys, nodes={'a': SimpleNamespace(closed=False)},
                                reports={'a': ({'kind': 'relay', 'apps': {'activePairings': 2}}, 100)}, active={})
            with patch('rivo_relay.gateway.monitor.time.time', return_value=110):
                report = snapshot(g)
                self.assertEqual(1, report['onlineSlots'])
                self.assertEqual(2, report['activePairings'])
                self.assertIsNone(report['nodes'][1]['apps'])
            with patch('rivo_relay.gateway.monitor.time.time', return_value=150):
                self.assertFalse(snapshot(g)['appCoverageComplete'])
                self.assertIsNone(snapshot(g)['nodes'][0]['apps'])
            req = SimpleNamespace(app={'fleet_gateway': g}, headers={})
            with self.assertRaises(web.HTTPForbidden): await handle(req)
            (Path(tmp) / 'monitor-token').write_text('x' * 64)
            req.headers = {'Authorization': 'Bearer secret'}
            with self.assertRaises(web.HTTPForbidden): await handle(req)
            req.headers = {'Authorization': 'Bearer ' + 'x' * 64}
            self.assertEqual(200, (await handle(req)).status)

from relay_test_support.http import RelayHTTPFixture


class NodeMonitorTests(RelayHTTPFixture, unittest.TestCase):
    def test_authenticated_reads_count_once_and_monitor_does_not_count(self):
        self.request('GET', '/health')
        status, body, _ = self.request('GET', '/internal/monitor')
        self.assertEqual(200, status)
        if isinstance(body, bytes): body = json.loads(body)
        self.assertEqual(0, body['apps']['activePairings'])
        self.request('GET', '/v1/node', token=self.token)
        self.request('GET', '/v1/node', token=self.token)
        _, body, _ = self.request('GET', '/internal/monitor')
        self.assertEqual(1, body['apps']['activePairings'])
        self.app.pairing.revoke(self.paired['deviceId'])
        _, body, _ = self.request('GET', '/internal/monitor')
        self.assertEqual(0, body['apps']['activePairings'])

from relay_test_support.gateway import GatewayFixture
import asyncio
import sys


class FleetIntegrationTests(GatewayFixture, unittest.IsolatedAsyncioTestCase):
    monitor_report = {'kind': 'relay', 'name': 'test-node', 'nodeId': 'node-one', 'releaseCode': 127,
                      'apps': {'activePairings': 2, 'inFlightRequests': 3, 'activityWindowSeconds': 90},
                      'update': {'phase': 'waiting_idle', 'reason': 'active_requests'}}

    async def test_report_cli_auth_and_internal_route_isolation(self):
        for _ in range(100):
            if self.key['slot'] in self.gateway.reports: break
            await asyncio.sleep(.02)
        self.assertIn(self.key['slot'], self.gateway.reports)
        async with self.client.get(self.base + '/admin/monitor') as response:
            self.assertEqual(403, response.status)
        async with self.client.get(self.url + '/internal/monitor') as response:
            self.assertEqual(404, response.status)
        token = self.directory / 'monitor-token'; token.write_text('x' * 64)
        script = Path(__file__).resolve().parents[2] / 'scripts/fleet-status.py'
        process = await asyncio.create_subprocess_exec(sys.executable, str(script), '--url', self.base,
                    '--token-file', str(token), '--ca-file', str(self.directory / 'server.pem'), '--json',
                    stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        stdout, stderr = await asyncio.wait_for(process.communicate(), 15)
        self.assertEqual(0, process.returncode, stderr.decode())
        report = json.loads(stdout)
        self.assertEqual(2, report['activePairings'])
        self.assertEqual(127, report['nodes'][0]['report']['releaseCode'])
        self.assertNotIn('secret', report['nodes'][0]['report'])
