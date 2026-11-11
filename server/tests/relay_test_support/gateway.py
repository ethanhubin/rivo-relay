"""Local TLS gateway, connector and HTTP origin fixture."""
import asyncio
import hashlib
import tempfile
from pathlib import Path
from aiohttp import ClientSession, ClientTimeout, web
from rivo_relay.gateway.config import initialize, decode_key, issue_key
from rivo_relay.gateway.connector import Connector
from rivo_relay.gateway.server import Gateway



class GatewayFixture:
    async def asyncSetUp(self):
        import ssl
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        pin = initialize(self.directory, '127.0.0.1')
        self.key = decode_key(issue_key(self.directory, pin, "127.0.0.1:80"))
        self.writes = 0
        self.cancelled = asyncio.Event()
        async def handler(request):
            if request.path == '/internal/monitor' and hasattr(self, 'monitor_report'):
                return web.json_response(self.monitor_report)
            if request.path == '/v1/staged':
                await request.read()
                await asyncio.sleep(.04)
                response = web.StreamResponse()
                await response.prepare(request)
                await asyncio.sleep(.04)
                await response.write(b'private-response')
                await response.write_eof()
                return response
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
