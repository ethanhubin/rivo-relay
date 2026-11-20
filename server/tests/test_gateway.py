from relay_test_support.gateway import GatewayFixture
import asyncio
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from aiohttp import web
from rivo_relay.gateway.config import decode_key, gateway_url, issue_key, node_gateway, encode_key
from unittest.mock import patch
from rivo_relay.gateway.connector import Connector
from rivo_relay.gateway.server import Gateway


class GatewayTests(GatewayFixture, unittest.IsolatedAsyncioTestCase):
    async def test_request_log_has_route_category_without_path_or_query(self):
        with patch('rivo_relay.gateway.server.event') as log:
            async with self.client.get(self.url + '/v1/projects/private-id?secret=private-value') as response:
                self.assertEqual(200, response.status)
                await response.read()
            for _ in range(20):
                if any(c.args[0] == 'gateway_request_finished' for c in log.call_args_list):
                    break
                await asyncio.sleep(.01)
            record = next(c.kwargs for c in log.call_args_list if c.args[0] == 'gateway_request_finished')
            self.assertEqual('projects', record['route'])
            self.assertEqual('GET', record['method'])
            self.assertNotIn('private', str(log.call_args_list))

    async def test_projects_preserves_valid_client_trace(self):
        with patch('rivo_relay.gateway.server.event') as log:
            async with self.client.get(self.url + '/v1/projects', headers={'X-Rivo-Request-Id': 'b' * 32}) as response:
                await response.read()
            record = await self.finished_record(log)
            self.assertEqual('b' * 32, record['request_id'])
            self.assertEqual('projects', record['route'])

    async def finished_record(self, log):
        for _ in range(100):
            records = [c.kwargs for c in log.call_args_list if c.args[0] == 'gateway_request_finished']
            if records:
                return records[-1]
            await asyncio.sleep(.01)
        self.fail('Gateway completion event was not emitted')

    async def test_request_log_separates_tunnel_headers_and_downstream_body(self):
        transfer = self.connector.transfer
        original_write = web.StreamResponse.write
        async def delayed_transfer(*args, **kwargs):
            await asyncio.sleep(.04)
            return await transfer(*args, **kwargs)
        async def delayed_write(response, data):
            if response._req and response._req.path.startswith('/relay/'):
                await asyncio.sleep(.04)
            return await original_write(response, data)
        with patch.object(self.connector, 'transfer', delayed_transfer), \
                patch.object(web.StreamResponse, 'write', delayed_write), \
                patch('rivo_relay.gateway.server.event') as log:
            async with self.client.post(self.url + '/v1/staged?private=secret', data=b'private-body',
                                        headers={'Authorization': 'Bearer private-token'}) as response:
                self.assertEqual(200, response.status)
                self.assertEqual(b'private-response', await response.read())
            record = await self.finished_record(log)
            self.assertGreaterEqual(record['data_connect_ms'], 30)
            self.assertGreaterEqual(record['response_headers_ms'], 30)
            self.assertGreaterEqual(record['response_body_ms'], 60)
            self.assertGreaterEqual(record['downstream_write_ms'], 30)
            self.assertLessEqual(record['downstream_write_ms'], record['response_body_ms'] + .01)
            self.assertEqual(len(b'private-body'), record['request_body_bytes'])
            self.assertEqual(len(b'private-response'), record['response_body_bytes'])
            self.assertEqual(record['response_body_bytes'], record['downstream_body_bytes'])
            self.assertEqual('completed', record['outcome'])
            self.assertNotIn('private', str(log.call_args_list))

    async def test_offline_log_does_not_invent_unreached_stage_timings(self):
        with patch('rivo_relay.gateway.server.event') as log:
            async with self.client.get(self.base + '/relay/unknown/health') as response:
                self.assertEqual(503, response.status)
                await response.read()
            record = await self.finished_record(log)
            for field in ('data_connect_ms', 'response_headers_ms', 'response_body_ms', 'downstream_write_ms'):
                self.assertIsNone(record[field])
            self.assertEqual(0, record['request_body_bytes'])
            self.assertEqual(0, record['response_body_bytes'])
            self.assertEqual(0, record['downstream_body_bytes'])

    async def test_cancelled_stream_logs_partial_body_and_elapsed_phase(self):
        with patch('rivo_relay.gateway.server.event') as log:
            async with self.client.get(self.url + '/v1/events') as response:
                self.assertEqual(await response.content.readline(), b'data: 0\n')
            record = await self.finished_record(log)
            self.assertEqual('CancelledError', record['outcome'])
            self.assertEqual(len(b'data: 0\n\n'), record['response_body_bytes'])
            self.assertEqual(record['response_body_bytes'], record['downstream_body_bytes'])
            self.assertGreaterEqual(record['response_body_ms'], 0)
            self.assertGreaterEqual(record['downstream_write_ms'], 0)




    async def test_auth_and_unknown_node(self):
        async with self.client.get(self.base + '/gateway/control/' + self.key['slot']) as response:
            self.assertEqual(response.status, 401)
        async with self.client.get(self.base + '/relay/unknown/health') as response:
            self.assertEqual(response.status, 503)
        async with self.client.get(self.url + '/private') as response:
            self.assertEqual(response.status, 404)

    async def test_streaming_upload_headers_query_and_download(self):
        data = b'abcd' * 1024 * 1024
        async with self.client.post(self.url + '/v1/echo?name=a%2Bb%20c', data=data, headers={'Authorization': 'Bearer test'}) as response:
            self.assertEqual(response.status, 200)
            result = await response.json()
            self.assertEqual(result['sha256'], hashlib.sha256(data).hexdigest())
            self.assertEqual(result['auth'], 'Bearer test')
            self.assertEqual(result['query'], 'a+b c')
        self.assertEqual(self.writes, 1)
        async with self.client.get(self.url + '/v1/download') as response:
            self.assertEqual(response.headers['ETag'], 'test')
            self.assertEqual(await response.read(), b'z' * 200000)

    async def test_sse_does_not_block_other_requests_and_cancel(self):
        async with self.client.get(self.url + '/v1/events') as response:
            self.assertEqual(await response.content.readline(), b'data: 0\n')
            async with self.client.get(self.url + '/health') as health:
                self.assertEqual(health.status, 200)
        await asyncio.wait_for(self.cancelled.wait(), 3)
        await asyncio.sleep(.1)
        self.assertEqual(len(self.gateway.pending), 0)

    async def test_managed_readiness_tracks_disconnect_and_reconnect(self):
        from rivo_relay.gateway.update_status import write_ready, is_ready
        path = self.directory / 'connected.json'
        changed = []
        def state(value):
            changed.append(value)
            write_ready(path, 'generation', value)
        self.connector.on_ready = state
        self.connector.set_ready(True)
        self.assertTrue(is_ready(path, 'generation'))
        await self.gateway.nodes[self.key['slot']].close()
        for _ in range(50):
            if False in changed: break
            await asyncio.sleep(.02)
        self.assertIn(False, changed)
        self.assertFalse(is_ready(path, 'generation'))
        await asyncio.wait_for(self.connector.ready.wait(), 4)
        self.assertTrue(is_ready(path, 'generation'))

    async def test_many_live_transfers_do_not_starve_local_or_control_connections(self):
        responses = []
        try:
            # 40 WS + 40 local HTTP + control exceeds the old shared pool of 64.
            for _ in range(40):
                response = await self.client.get(self.url + '/v1/events')
                responses.append(response)
                self.assertEqual(response.status, 200)
                self.assertEqual(await response.content.readline(), b'data: 0\n')
            async with self.client.get(self.url + '/health') as response:
                self.assertEqual(response.status, 200)
            self.assertTrue(self.connector.ready.is_set())
        finally:
            for response in responses:
                response.close()

    async def test_slow_response_drain_does_not_start_sender_close_timeout(self):
        import aiohttp
        from unittest.mock import patch
        original_connect = aiohttp.ClientSession.ws_connect
        original_write = web.StreamResponse.write
        delayed = False
        written = 0
        blocked, release = asyncio.Event(), asyncio.Event()
        def connect(session, *args, **kwargs):
            kwargs['timeout'] = aiohttp.ClientWSTimeout(ws_close=.01)
            return original_connect(session, *args, **kwargs)
        async def slow_write(response, data):
            nonlocal delayed, written
            if response._req and response._req.path.endswith('/v1/large'):
                written += len(data)
                if not delayed and written >= 8 * 1024 * 1024:
                    delayed = True
                    blocked.set()
                    await release.wait()
            return await original_write(response, data)
        async def download():
            async with self.client.get(self.url + '/v1/large') as response:
                self.assertEqual(response.status, 200)
                self.assertEqual(await response.read(), b'z' * (8 * 1024 * 1024))
        with patch.object(aiohttp.ClientSession, 'ws_connect', connect), patch.object(web.StreamResponse, 'write', slow_write):
            task = asyncio.create_task(download())
            try:
                await asyncio.wait_for(blocked.wait(), 3)
                await asyncio.sleep(.1)
                self.assertTrue(self.connector.tasks, 'Sender closed before downstream delivery finished')
            finally:
                release.set()
                await task
        self.assertTrue(delayed)
        async with self.client.get(self.url + '/health') as response:
            self.assertEqual(response.status, 200)

    async def test_offline_and_reconnect(self):
        await self.gateway.nodes[self.key['slot']].close()
        await asyncio.sleep(.1)
        async with self.client.get(self.url + '/health') as response:
            self.assertEqual(response.status, 503)
        await asyncio.wait_for(self.connector.ready.wait(), 5)
        async with self.client.get(self.url + '/health') as response:
            self.assertEqual(response.status, 200)

    async def test_put_is_never_replayed_after_disconnect(self):
        async with self.client.put(self.url + '/v1/disconnect', data=b'write') as response:
            self.assertEqual(response.status, 502)
        self.assertEqual(self.writes, 1)

    async def test_ticket_cannot_be_claimed_by_another_node(self):
        other = decode_key(issue_key(self.directory, self.key['pin'], '127.0.0.1:80'))
        ticket = 'private-test-ticket'
        future = asyncio.get_running_loop().create_future()
        self.gateway.pending[ticket] = (self.key['slot'], future, asyncio.Event())
        try:
            async with self.client.get(self.base + '/gateway/data/' + other['slot'],
                    headers={'Authorization': 'Bearer ' + other['secret'], 'X-Rivo-Ticket': ticket}) as response:
                self.assertEqual(response.status, 404)
            self.assertFalse(future.done())
        finally:
            self.gateway.pending.pop(ticket)
            future.cancel()

    async def test_wrong_pin_rejected(self):
        wrong = Connector(self.base, dict(self.key, pin='00' * 32), self.local_port)
        task = asyncio.create_task(wrong.run())
        try:
            with self.assertRaises(TimeoutError):
                await asyncio.wait_for(wrong.ready.wait(), .3)
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)


class GatewayConfigTests(unittest.TestCase):
    def test_address_validation(self):
        self.assertEqual(gateway_url('134.175.156.219'), 'https://134.175.156.219:80')
        for value in ['http://1.2.3.4', 'https://a:b@1.2.3.4', 'example.com', 'https://1.2.3.4/path']:
            with self.assertRaises(ValueError):
                gateway_url(value)

    def test_direct_only_pairing_binding_needs_no_gateway_key(self):
        from rivo_relay.gateway.config import pairing_route_binding, save_private
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            save_private(workspace / 'gateway-client.json', {'mode': 'direct', 'direct': 'https://node.example'})
            expected = hashlib.sha256(b'https://node.example\n\n').hexdigest()
            self.assertEqual(pairing_route_binding(workspace), expected)


class GatewayCredentialTests(unittest.TestCase):
    def setUp(self):
        self.bundle = {'v': 2, 'slot': 'a' * 32, 'secret': 's' * 43, 'pin': 'b' * 64, 'gateway': 'https://134.175.156.219:80'}
        self.credential = encode_key(self.bundle)

    def test_single_paste_never_prompts_for_ip(self):
        with patch('getpass.getpass', return_value=self.credential) as prompt, patch('builtins.input', side_effect=AssertionError('Unexpected IP prompt')):
            base, key, saved = node_gateway({})
        prompt.assert_called_once()
        self.assertEqual(base, 'https://134.175.156.219:80')
        self.assertEqual(saved, self.credential)
        for field in ('slot', 'secret', 'pin'):
            self.assertEqual(key[field], self.bundle[field])

    def test_existing_configs_do_not_prompt(self):
        with patch('getpass.getpass', side_effect=AssertionError()), patch('builtins.input', side_effect=AssertionError()):
            for stored in ({'key': self.credential},):
                self.assertEqual(node_gateway(stored)[0], 'https://134.175.156.219:80')

    def test_legacy_key_is_rejected_without_ip_prompt(self):
        import base64
        legacy = 'rivo1.' + base64.urlsafe_b64encode(json.dumps(dict(self.bundle, v=1)).encode()).decode()
        with patch('builtins.input', side_effect=AssertionError()), self.assertRaises(ValueError):
            node_gateway({'key': legacy, 'gateway': '134.175.156.219'})

    def test_ipv6_and_custom_port(self):
        key = decode_key(encode_key(dict(self.bundle, gateway='https://[2001:db8::1]:8443')))
        self.assertEqual(key['gateway'], 'https://[2001:db8::1]:8443')

    def test_invalid_bundle_is_rejected_without_echoing_secret(self):
        for value in ['rivo2.!!!!', 'rivo2.W10', self.credential.replace('rivo2.', 'rivo1.'),
                      encode_key(dict(decode_key(self.credential), gateway='http://1.2.3.4'))]:
            with self.subTest(value=value[:6]), self.assertRaisesRegex(ValueError, '^Invalid gateway access credential$'):
                decode_key(value)

    def test_issue_credential_registers_same_authorization(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'keys.json').write_text('{}')
            credential = issue_key(path, 'b' * 64, '134.175.156.219:8443')
            key = decode_key(credential)
            self.assertEqual(key['gateway'], 'https://134.175.156.219:8443')
            self.assertEqual(json.loads((path / 'keys.json').read_text())[key['slot']], hashlib.sha256(key['secret'].encode()).hexdigest())

    def test_issue_cli_includes_port_and_does_not_log_credential(self):
        import io
        from contextlib import redirect_stdout
        from rivo_relay.gateway.__main__ import main
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'keys.json').write_text('{}')
            output = io.StringIO()
            with patch('sys.argv', ['gateway', 'issue-key', '--workspace', directory, '--ip', '134.175.156.219', '--port', '8443']), patch('rivo_relay.gateway.__main__.initialize', return_value='b' * 64), redirect_stdout(output):
                main()
            value = json.loads((path / 'access-key.json').read_text())['key']
            self.assertEqual(decode_key(value)['gateway'], 'https://134.175.156.219:8443')
            self.assertNotIn(value, output.getvalue())
            self.assertEqual((path / 'access-key.json').stat().st_mode & 0o777, 0o600)

class NodePairingCliTests(unittest.IsolatedAsyncioTestCase):
    async def test_pair_only_keeps_gateway_key_with_runtime_options(self):
        from argparse import Namespace
        from urllib.parse import urlsplit, parse_qs
        from rivo_relay.config import Config
        from rivo_relay.gateway.__main__ import node
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            config = Config(workspace=workspace)
            config.prepare()
            key = encode_key({'v': 2, 'slot': 'a' * 32, 'secret': 's' * 32,
                              'pin': 'b' * 64, 'gateway': 'https://1.2.3.4:80'})
            (workspace / 'gateway-client.json').write_text(json.dumps({'key': key}))
            app = web.Application()
            async def health(request):
                return web.json_response({'status': 'ok', 'nodeId': config.node_id})
            app.router.add_get('/health', health)
            runner = web.AppRunner(app)
            await runner.setup()
            site = web.TCPSite(runner, '127.0.0.1', 0)
            await site.start()
            port = site._server.sockets[0].getsockname()[1]
            try:
                await node(Namespace(workspace=workspace, mode='auto', direct_url='https://example.ts.net',
                    configure=False, ip=None, codex_runtime='stdio', codex_executable=Path('/bin/codex'),
                    codex_home=None, update_source=False, local_port=port, attach=True,
                    pair_only=True, no_pair=False))
                link = json.loads((workspace / 'artifacts/pairing.json').read_text())['url']
                fields = parse_qs(urlsplit(link).fragment)
                self.assertEqual(fields['relay'], ['https://1.2.3.4:80/relay/' + 'a' * 32])
                self.assertEqual(fields['pin'], ['b' * 64])
                self.assertEqual(fields['direct'], ['https://example.ts.net'])
            finally:
                await runner.cleanup()


class DataHandshakeRaceTests(unittest.IsolatedAsyncioTestCase):
    async def check_cleanup(self, fail_prepare=False):
        from unittest.mock import AsyncMock, Mock
        gateway = Gateway(Path("unused"))
        future = asyncio.get_running_loop().create_future()
        done = asyncio.Event()
        gateway.pending["ticket"] = ("slot", future, done)
        request = Mock(headers={"X-Rivo-Ticket": "ticket"})
        ws = Mock(close=AsyncMock())
        async def prepare(_):
            if fail_prepare:
                raise ConnectionResetError()
            future.cancel()  # Phone cancels while the WS handshake yields.
            gateway.pending.pop("ticket")
            done.set()
        ws.prepare = prepare
        with patch.object(gateway, "authorize", return_value="slot"), \
                patch("rivo_relay.gateway.server.web.WebSocketResponse", return_value=ws):
            if fail_prepare:
                with self.assertRaises(ConnectionResetError):
                    await gateway.data(request)
            else:
                await gateway.data(request)
        self.assertNotIn("ticket", gateway.claimed)
        self.assertFalse(gateway.active.get("slot"))
        ws.close.assert_awaited()

    async def test_request_cancelled_during_data_handshake(self):
        await self.check_cleanup()

    async def test_failed_data_handshake_releases_claim(self):
        await self.check_cleanup(fail_prepare=True)
