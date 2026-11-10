import asyncio
import os
import socket
import unittest
from unittest.mock import patch, AsyncMock
from aiohttp import web, WSMsgType
from rivo_relay.gateway.sites_tunnel import public_address, socks_connect
import test_gateway


class DestinationTests(unittest.IsolatedAsyncioTestCase):
    async def test_rejects_private_mixed_answers_and_invalid_hosts(self):
        loop = asyncio.get_running_loop()
        for addresses in [('127.0.0.1',), ('224.0.0.1',), ('100.64.0.1',), ('169.254.169.254',), ('1.1.1.1', '192.168.0.1')]:
            entries = [(socket.AF_INET, socket.SOCK_STREAM, 6, '', (a, 443)) for a in addresses]
            with patch.object(loop, 'getaddrinfo', AsyncMock(return_value=entries)):
                with self.assertRaises(web.HTTPForbidden): await public_address('example.com')
        for host in ('localhost:80', 'user@example.com', '', '[::1]', 'x\r\nHost: z'):
            with self.assertRaises(web.HTTPBadRequest): await public_address(host)

    async def test_pins_public_address(self):
        with patch.object(asyncio.get_running_loop(), 'getaddrinfo', AsyncMock(return_value=[(2, 1, 6, '', ('1.1.1.1', 443))])):
            self.assertEqual('1.1.1.1', await public_address('example.com'))


class TunnelTests(unittest.IsolatedAsyncioTestCase):
    asyncSetUp = test_gateway.GatewayTests.asyncSetUp
    asyncTearDown = test_gateway.GatewayTests.asyncTearDown
    start = test_gateway.GatewayTests.start
    # The real gateway/control/data/Relay path is exercised for device authentication.
    async def test_sites_requires_bearer_before_opening_proxy(self):
        with patch.dict(os.environ, RIVO_SITES_SOCKS_PORT='17890'), patch('rivo_relay.gateway.sites_tunnel.socks_connect', AsyncMock()) as connect:
            async with self.client.get(self.url + '/v1/sites/tunnel?host=example.com') as response:
                self.assertEqual(401, response.status)
            connect.assert_not_called()

    async def test_sites_transports_bytes_without_forwarding_bearer(self):
        seen = []
        async def socks(reader, writer):
            try:
                seen.append(await reader.readexactly(3))
                writer.write(b'\x05\x00'); await writer.drain()
                seen.append(await reader.readexactly(10))
                writer.write(b'\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00'); await writer.drain()
                while data := await reader.read(65536):
                    seen.append(data); writer.write(data); await writer.drain()
            finally:
                writer.close(); await writer.wait_closed()
        server = await asyncio.start_server(socks, '127.0.0.1', 0)
        port = server.sockets[0].getsockname()[1]
        try:
            with patch.dict(os.environ, RIVO_SITES_SOCKS_PORT=str(port)), patch('rivo_relay.gateway.sites_tunnel.public_address', AsyncMock(return_value='1.1.1.1')):
                async with self.client.ws_connect(self.url + '/v1/sites/tunnel?host=example.com', headers={'Authorization': 'Bearer private-test-token'}) as ws:
                    await ws.send_bytes(b'website TLS bytes')
                    message = await ws.receive()
                    self.assertEqual(WSMsgType.BINARY, message.type)
                    self.assertEqual(b'website TLS bytes', message.data)
            self.assertNotIn(b'private-test-token', b''.join(seen))
            self.assertEqual(b'\x05\x01\x00\x01\x01\x01\x01\x01\x01\xbb', seen[1])
        finally:
            server.close(); await server.wait_closed()
