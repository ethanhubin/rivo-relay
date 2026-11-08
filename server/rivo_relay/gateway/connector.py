from __future__ import annotations

import asyncio
import contextlib
import json
import logging

import aiohttp
from aiohttp import WSMsgType
from yarl import URL

from .server import CHUNK, headers

log = logging.getLogger(__name__)


class Connector:
    def __init__(self, base: str, key: dict, local_port: int):
        self.base, self.key = base, key
        self.local = f'http://127.0.0.1:{local_port}'
        self.ready = asyncio.Event()
        self.tasks = set()

    async def transfer(self, session, ticket):
        auth = {'Authorization': 'Bearer ' + self.key['secret'], 'X-Rivo-Ticket': ticket}
        try:
            async with session.ws_connect(self.base + '/gateway/data/' + self.key['slot'],
                    headers=auth, ssl=aiohttp.Fingerprint(bytes.fromhex(self.key['pin'])),
                    heartbeat=20, max_msg_size=CHUNK + 8192) as ws:
                spec = await asyncio.wait_for(ws.receive_json(), 15)
                path = spec['path']
                if not path.startswith(('/v1/', '/health')) or path.startswith('//'):
                    raise ValueError('Invalid local target')

                upload_done = asyncio.Event()
                async def body():
                    async for message in ws:
                        if message.type == WSMsgType.BINARY:
                            yield message.data
                        elif message.type == WSMsgType.TEXT and json.loads(message.data).get('eof'):
                            upload_done.set()
                            return
                        else:
                            break
                    raise ConnectionError('Upload interrupted')

                fields = headers(spec['headers'])
                length = next((int(v) for k, v in fields if k.lower() == 'content-length'), 0)
                data = body() if length else None
                if not length:
                    end = await ws.receive_json()
                    if not end.get('eof'):
                        raise ValueError('Unexpected request body')
                if not length:
                    upload_done.set()
                async def forward():
                    async with session.request(spec['method'], URL(self.local + path, encoded=True),
                            headers=fields, data=data, allow_redirects=False,
                            timeout=aiohttp.ClientTimeout(total=None, sock_connect=5, sock_read=180)) as response:
                        await ws.send_json({'status': response.status, 'headers': headers(list(response.headers.items()))})
                        async for chunk in response.content.iter_chunked(CHUNK):
                            await ws.send_bytes(chunk)
                        await ws.send_json({'eof': True})

                async def disconnected():
                    await upload_done.wait()
                    # The upload iterator has released the sole WS reader. This
                    # monitor propagates cancellation even while upstream is idle.
                    await ws.receive()

                forwarding = asyncio.create_task(forward())
                watcher = asyncio.create_task(disconnected())
                try:
                    finished, _ = await asyncio.wait({forwarding, watcher}, return_when=asyncio.FIRST_COMPLETED)
                    if forwarding in finished:
                        await forwarding
                finally:
                    forwarding.cancel(); watcher.cancel()
                    await asyncio.gather(forwarding, watcher, return_exceptions=True)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            # Never log request paths, headers, keys, pairing data, or message bodies.
            log.warning('gateway_transfer_failed: %s', type(error).__name__)

    async def run(self):
        delay = 1
        async with aiohttp.ClientSession(auto_decompress=False, skip_auto_headers={'Accept-Encoding'},
                connector=aiohttp.TCPConnector(limit=64, force_close=True)) as session:
            # aiohttp 3.14 retries PUT/DELETE after a disconnect by default.
            # Relay mutations must be reconciled by submissionId, never replayed.
            session._retry_connection = False
            while True:
                try:
                    async with session.ws_connect(self.base + '/gateway/control/' + self.key['slot'],
                            headers={'Authorization': 'Bearer ' + self.key['secret']},
                            ssl=aiohttp.Fingerprint(bytes.fromhex(self.key['pin'])), heartbeat=20,
                            max_msg_size=4096, timeout=aiohttp.ClientWSTimeout(ws_close=5)) as ws:
                        hello = await asyncio.wait_for(ws.receive_json(), 10)
                        if not hello.get('ready'):
                            raise ValueError('Unexpected gateway handshake')
                        self.ready.set()
                        delay = 1
                        log.info('gateway_connected')
                        async for message in ws:
                            if message.type == WSMsgType.TEXT:
                                ticket = json.loads(message.data).get('ticket')
                                if isinstance(ticket, str) and len(ticket) <= 128 and len(self.tasks) < 48:
                                    task = asyncio.create_task(self.transfer(session, ticket))
                                    self.tasks.add(task)
                                    task.add_done_callback(self.tasks.discard)
                except asyncio.CancelledError:
                    raise
                except Exception as error:
                    log.warning('gateway_reconnecting: %s', type(error).__name__)
                finally:
                    self.ready.clear()
                    active = list(self.tasks)
                    for task in active:
                        task.cancel()
                    await asyncio.gather(*active, return_exceptions=True)
                await asyncio.sleep(delay)
                delay = min(delay * 2, 30)
