"""Authenticated, bounded HTTPS byte tunnels for the Sites WebView.

The gateway never terminates website TLS or adds Relay credentials to it.
Destinations are resolved and pinned to public IPv4 addresses before SOCKS connect.
"""
from __future__ import annotations
import asyncio
import contextlib
import ipaddress
import json
import os
import re
import secrets
import socket
from aiohttp import WSMsgType, web


async def public_address(host):
    if not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?', host):
        raise web.HTTPBadRequest(text='Invalid HTTPS host')
    try:
        entries = await asyncio.wait_for(asyncio.get_running_loop().getaddrinfo(
            host, 443, family=socket.AF_INET, type=socket.SOCK_STREAM), 10)
    except (OSError, TimeoutError):
        raise web.HTTPBadGateway(text='Destination DNS unavailable')
    addresses = list(dict.fromkeys(e[4][0] for e in entries))
    if not addresses or any((not ipaddress.ip_address(a).is_global or ipaddress.ip_address(a).is_multicast) for a in addresses):
        raise web.HTTPForbidden(text='Only public HTTPS destinations are permitted')
    return addresses[0]


async def socks_connect(address, port):
    reader, writer = await asyncio.open_connection('127.0.0.1', port)
    try:
        writer.write(b'\x05\x01\x00'); await writer.drain()
        if await reader.readexactly(2) != b'\x05\x00':
            raise OSError('Proxy authentication failed')
        writer.write(b'\x05\x01\x00\x01' + socket.inet_aton(address) + b'\x01\xbb')
        await writer.drain()
        head = await reader.readexactly(4)
        if head[:2] != b'\x05\x00':
            raise OSError('Proxy connection failed')
        size = {1: 4, 4: 16}.get(head[3])
        if head[3] == 3:
            size = (await reader.readexactly(1))[0]
        if size is None:
            raise OSError('Invalid proxy reply')
        await reader.readexactly(size + 2)
        return reader, writer
    except BaseException:
        writer.close()
        await writer.wait_closed()
        raise


class SitesTunnel:
    def __init__(self, gateway):
        self.gateway = gateway
        self.active = set()
        self.counts = {}

    async def authorize(self, request):
        """Ask the owning Relay to validate its current device bearer, without caching."""
        g = self.gateway
        slot = request.match_info['slot']
        token = request.headers.get('Authorization', '')
        if not token.startswith('Bearer ') or len(token) > 1024:
            raise web.HTTPUnauthorized()
        control = g.nodes.get(slot)
        if control is None or control.closed:
            raise web.HTTPServiceUnavailable(text='Node offline')
        if len(g.pending) >= 256 or sum(p[0] == slot for p in g.pending.values()) >= 48:
            raise web.HTTPServiceUnavailable()
        ticket = secrets.token_urlsafe(32)
        future, done = asyncio.get_running_loop().create_future(), asyncio.Event()
        g.pending[ticket] = (slot, future, done)
        ws = None
        try:
            async with asyncio.timeout(15):
                await control.send_json({'ticket': ticket})
                ws = await future
                await ws.send_json({'method': 'GET', 'path': '/v1/node', 'headers': [['Authorization', token]]})
                await ws.send_json({'eof': True})
                message = await ws.receive()
                if message.type != WSMsgType.TEXT:
                    raise web.HTTPBadGateway()
                status = json.loads(message.data).get('status')
                if status in (401, 403):
                    raise web.HTTPUnauthorized()
                if status != 200:
                    raise web.HTTPBadGateway()
        finally:
            g.pending.pop(ticket, None); g.claimed.discard(ticket); done.set()
            if ws is not None:
                await ws.close()

    async def handle(self, request):
        port = int(os.environ.get('RIVO_SITES_SOCKS_PORT', '0'))
        if not port:
            raise web.HTTPServiceUnavailable(text='Sites proxy is not configured')
        slot = request.match_info['slot']
        if sum(self.counts.values()) >= 128 or self.counts.get(slot, 0) >= 32:
            raise web.HTTPServiceUnavailable(text='Sites tunnel limit reached')
        self.counts[slot] = self.counts.get(slot, 0) + 1
        writer = ws = None
        tasks = []
        try:
            await self.authorize(request)
            address = await public_address(request.query.get('host', ''))
            reader, writer = await asyncio.wait_for(socks_connect(address, port), 15)
            ws = web.WebSocketResponse(heartbeat=20, max_msg_size=65536, compress=False)
            await ws.prepare(request)
            self.active.add(ws)
            async def upstream():
                async for message in ws:
                    if message.type != WSMsgType.BINARY:
                        break
                    writer.write(message.data)
                    await writer.drain()
            async def downstream():
                while data := await reader.read(65536):
                    await ws.send_bytes(data)
            tasks = [asyncio.create_task(upstream()), asyncio.create_task(downstream())]
            # Bounded lifetime also limits continued access after device revocation.
            await asyncio.wait(tasks, timeout=300, return_when=asyncio.FIRST_COMPLETED)
            return ws
        except (OSError, TimeoutError, ValueError):
            if ws is None or not ws.prepared:
                raise web.HTTPBadGateway(text='Sites proxy unavailable')
            return ws
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            if writer is not None:
                writer.close()
                with contextlib.suppress(OSError):
                    await writer.wait_closed()
            if ws is not None:
                self.active.discard(ws)
                await ws.close()
            self.counts[slot] -= 1
            if not self.counts[slot]:
                self.counts.pop(slot)

    async def shutdown(self, app):
        await asyncio.gather(*(ws.close() for ws in list(self.active)))
