import asyncio
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from aiohttp import ClientSession, ClientTimeout, web
from rivo_relay.gateway.config import decode_key, gateway_url, initialize, issue_key, access_credential, node_gateway, encode_key
from unittest.mock import patch
from rivo_relay.gateway.connector import Connector
from rivo_relay.gateway.server import Gateway


class GatewayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        import ssl
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        pin = initialize(self.directory, '127.0.0.1')
        self.key = decode_key(issue_key(self.directory, pin))
        self.writes = 0
        self.cancelled = asyncio.Event()
        async def handler(request):
            if request.path == '/v1/echo':
                data = await request.read()
                self.writes += 1
                return web.json_response({'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data),
                    'auth': request.headers.get('Authorization'), 'query': request.query.get('name')})
            if request.path == '/v1/disconnect':
                self.writes += 1
                request.transport.abort()
                return web.Response()
            if request.path == '/v1/events':
                response = web.StreamResponse(headers={'Content-Type': 'text/event-stream'})
                await response.prepare(request)
                try:
                    await response.write(b'data: 0\n\n')
                    await asyncio.Event().wait()
                finally:
                    self.cancelled.set()
                return response
            if request.path == '/v1/large':
                return web.Response(body=b'z' * (8 * 1024 * 1024))
            if request.path == '/v1/download':
                return web.Response(body=b'z' * 200000, headers={'ETag': 'test'})
            return web.json_response({'status': 'ok'})
        local = web.Application(client_max_size=16*1024*1024)
        local.router.add_route('*', '/{path:.*}', handler)
        self.local_runner, self.local_port = await self.start(local)
        self.gateway = Gateway(self.directory / 'keys.json')
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(self.directory / 'server.pem', self.directory / 'server.key')
        self.runner, port = await self.start(self.gateway.application(), context)
        self.base = f'https://127.0.0.1:{port}'
        self.url = self.base + '/relay/' + self.key['slot']
        self.connector = Connector(self.base, self.key, self.local_port)
        self.task = asyncio.create_task(self.connector.run())
        await asyncio.wait_for(self.connector.ready.wait(), 5)
        import aiohttp
        self.client = ClientSession(connector=aiohttp.TCPConnector(ssl=aiohttp.Fingerprint(bytes.fromhex(pin))), timeout=ClientTimeout(total=10))

    async def start(self, app, ssl_context=None):
        runner = web.AppRunner(app, access_log=None, handler_cancellation=True)
        await runner.setup()
        site = web.TCPSite(runner, '127.0.0.1', 0, ssl_context=ssl_context)
        await site.start()
        return runner, site._server.sockets[0].getsockname()[1]

    async def asyncTearDown(self):
        await self.client.close()
        self.task.cancel()
        await asyncio.gather(self.task, return_exceptions=True)
        await self.runner.cleanup()
        await self.local_runner.cleanup()
        self.temp.cleanup()

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
        other = decode_key(issue_key(self.directory, self.key['pin']))
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
        self.legacy = encode_key({'v': 1, 'slot': 'a' * 32, 'secret': 's' * 43, 'pin': 'b' * 64})
        self.credential = access_credential(self.legacy, '134.175.156.219:80')

    def test_single_paste_never_prompts_for_ip(self):
        with patch('getpass.getpass', return_value=self.credential) as prompt, patch('builtins.input', side_effect=AssertionError('Unexpected IP prompt')):
            base, key, saved = node_gateway({})
        prompt.assert_called_once()
        self.assertEqual(base, 'https://134.175.156.219:80')
        self.assertEqual(saved, self.credential)
        for field in ('slot', 'secret', 'pin'):
            self.assertEqual(key[field], decode_key(self.legacy)[field])

    def test_existing_configs_do_not_prompt(self):
        with patch('getpass.getpass', side_effect=AssertionError()), patch('builtins.input', side_effect=AssertionError()):
            for stored in ({'key': self.legacy, 'gateway': '134.175.156.219'}, {'key': self.credential}):
                self.assertEqual(node_gateway(stored)[0], 'https://134.175.156.219:80')

    def test_legacy_key_can_still_request_ip(self):
        with patch('getpass.getpass', return_value=self.legacy), patch('builtins.input', return_value='134.175.156.219:8443'):
            self.assertEqual(node_gateway({})[0], 'https://134.175.156.219:8443')

    def test_conflicting_explicit_ip_is_rejected(self):
        with self.assertRaises(ValueError):
            node_gateway({'key': self.credential}, '1.2.3.4')

    def test_ipv6_and_custom_port(self):
        key = decode_key(access_credential(self.legacy, 'https://[2001:db8::1]:8443'))
        self.assertEqual(key['gateway'], 'https://[2001:db8::1]:8443')

    def test_invalid_bundle_is_rejected_without_echoing_secret(self):
        for value in ['rivo2.!!!!', 'rivo2.W10', self.legacy.replace('rivo1.', 'rivo2.'),
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
