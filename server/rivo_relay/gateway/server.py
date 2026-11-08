from __future__ import annotations

import asyncio
import contextlib
import hashlib
import hmac
import json
from pathlib import Path
import secrets

from aiohttp import WSMsgType, web

CHUNK = 65536
MAX_BODY = 1024 ** 3
HOP = {'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
       'te', 'trailer', 'transfer-encoding', 'upgrade', 'host', 'expect'}


def headers(items):
    excluded = HOP.copy()
    for name, value in items:
        if name.lower() == 'connection':
            excluded.update(part.strip().lower() for part in value.split(','))
    return [(k, v) for k, v in items if k.lower() not in excluded and not k.lower().startswith('x-forwarded-')]


class Gateway:
    def __init__(self, keys_file: Path):
        self.keys_file = keys_file
        self.nodes = {}
        self.registering = set()
        self.claimed = set()
        self.pending = {}
        self.active = {}

    def authorize(self, request):
        slot = request.match_info['slot']
        supplied = request.headers.get('Authorization', '').removeprefix('Bearer ')
        keys = json.loads(self.keys_file.read_text())
        if not hmac.compare_digest(keys.get(slot, ''), hashlib.sha256(supplied.encode()).hexdigest()):
            raise web.HTTPUnauthorized()
        return slot

    async def control(self, request):
        slot = self.authorize(request)
        if slot in self.nodes or slot in self.registering:
            raise web.HTTPConflict(text='This access key already has an online node')
        ws = web.WebSocketResponse(heartbeat=20, max_msg_size=4096, compress=False)
        self.registering.add(slot)
        try:
            await ws.prepare(request)
            self.nodes[slot] = ws
            await ws.send_json({'ready': True})
            async for _ in ws:
                pass
        finally:
            self.registering.discard(slot)
            if self.nodes.get(slot) is ws:
                self.nodes.pop(slot, None)
            for ticket, (owner, future, done) in list(self.pending.items()):
                if owner == slot:
                    if not future.done():
                        future.set_exception(ConnectionError('Node disconnected'))
                    done.set()
            for data in list(self.active.get(slot, set())):
                await data.close()
        return ws

    async def data(self, request):
        slot = self.authorize(request)
        ticket = request.headers.get('X-Rivo-Ticket', '')
        pending = self.pending.get(ticket)
        if pending is None or pending[0] != slot or pending[1].done() or ticket in self.claimed:
            raise web.HTTPNotFound()
        self.claimed.add(ticket)
        ws = web.WebSocketResponse(heartbeat=20, max_msg_size=CHUNK + 8192, compress=False)
        await ws.prepare(request)
        self.active.setdefault(slot, set()).add(ws)
        pending[1].set_result(ws)
        try:
            await pending[2].wait()
        finally:
            self.active[slot].discard(ws)
            await ws.close()
        return ws

    async def proxy(self, request):
        slot = request.match_info['slot']
        control = self.nodes.get(slot)
        if control is None or control.closed:
            return web.json_response({'error': {'code': 'node_offline', 'message': '桌面节点离线'}}, status=503)
        # Only Relay endpoints can cross this tunnel. No arbitrary host/port forwarding.
        path = '/' + request.match_info['path']
        if not (path == '/health' or path.startswith('/v1/')) or '..' in path or '\\' in path:
            raise web.HTTPNotFound()
        if request.method not in {'GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD'}:
            raise web.HTTPMethodNotAllowed(request.method, ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD'])
        if request.content_length is not None and request.content_length > MAX_BODY:
            raise web.HTTPRequestEntityTooLarge(max_size=MAX_BODY, actual_size=request.content_length)
        # Relay requires a known length for uploads. Reject ambiguous framing.
        if request.headers.get('Transfer-Encoding'):
            raise web.HTTPLengthRequired()
        if len(self.pending) >= 256 or sum(p[0] == slot for p in self.pending.values()) >= 48:
            raise web.HTTPServiceUnavailable()
        ticket = secrets.token_urlsafe(32)
        future, done = asyncio.get_running_loop().create_future(), asyncio.Event()
        self.pending[ticket] = (slot, future, done)
        upload = None
        ws = None
        response = None
        try:
            await control.send_json({'ticket': ticket})
            ws = await asyncio.wait_for(future, 10)
            # raw_path preserves encoded file names and query strings byte-for-byte.
            raw_path = request.raw_path.split('/relay/' + slot, 1)[1]
            await ws.send_json({'method': request.method, 'path': raw_path,
                                'headers': headers(list(request.headers.items()))})

            async def send_body():
                total = 0
                async for chunk in request.content.iter_chunked(CHUNK):
                    total += len(chunk)
                    if total > MAX_BODY:
                        raise ValueError('Upload too large')
                    await ws.send_bytes(chunk)
                await ws.send_json({'eof': True})

            upload = asyncio.create_task(send_body())
            async def close_failed(task):
                if not task.cancelled() and task.exception() is not None:
                    await ws.close()
            upload.add_done_callback(lambda task: asyncio.create_task(close_failed(task)))
            message = await asyncio.wait_for(ws.receive(), 120)
            if message.type != WSMsgType.TEXT:
                raise ConnectionError('No response headers')
            head = json.loads(message.data)
            response = web.StreamResponse(status=head['status'], headers=headers(head['headers']))
            await response.prepare(request)
            async for message in ws:
                if message.type == WSMsgType.BINARY:
                    await response.write(message.data)
                elif message.type == WSMsgType.TEXT and json.loads(message.data).get('eof'):
                    await response.write_eof()
                    return response
                else:
                    break
            raise ConnectionError('Incomplete upstream response')
        except (ConnectionError, OSError, TimeoutError, ValueError, KeyError):
            if response is not None and response.prepared:
                if request.transport:
                    request.transport.close()
                return response
            return web.json_response({'error': {'code': 'tunnel_unavailable', 'message': '节点连接中断，请重试'}}, status=502)
        finally:
            self.pending.pop(ticket, None)
            self.claimed.discard(ticket)
            done.set()
            if upload is not None:
                upload.cancel()
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await upload
            if ws is not None:
                await ws.close()

    async def shutdown(self, app):
        for ws in list(self.nodes.values()):
            await ws.close()
        for group in list(self.active.values()):
            for ws in list(group):
                await ws.close()
        for _, future, done in list(self.pending.values()):
            done.set()
            if not future.done():
                future.cancel()

    def application(self):
        app = web.Application(client_max_size=MAX_BODY)
        app.router.add_get('/gateway/control/{slot}', self.control)
        app.router.add_get('/gateway/data/{slot}', self.data)
        app.router.add_route('*', '/relay/{slot}/{path:.*}', self.proxy)
        async def health(_):
            return web.json_response({'status': 'ok', 'service': 'rivo-gateway'})
        app.router.add_get('/health', health)
        app.on_shutdown.append(self.shutdown)
        return app
