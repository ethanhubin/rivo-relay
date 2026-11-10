"""Shared ASR authenticated with existing node credentials, never phone tokens."""
import asyncio
import json
import re

from aiohttp import web

from ..media import MediaError
from ..speech import SpeechService, MAX_CHUNK_BYTES, MAX_SPEECH_BYTES


class SharedSpeech:
    def __init__(self, gateway):
        self.gateway = gateway
        path = gateway.keys_file.parent / 'speech-backend.json'
        config = json.loads(path.read_text()) if path.exists() else {}
        self.service = SpeechService(config.get('url', ''), config.get('token', ''))
        self.active = 0

    async def run(self, function, *args):
        if self.active >= 8:
            raise web.HTTPTooManyRequests()
        self.active += 1
        task = asyncio.create_task(asyncio.to_thread(function, *args))
        def completed(done):
            self.active -= 1
            # Retrieve exceptions even if a disconnected caller stopped awaiting.
            if not done.cancelled():
                done.exception()
        task.add_done_callback(completed)
        return await asyncio.shield(task)

    async def direct(self, request):
        from .speech_access import verify
        authorization = request.headers.get('Authorization', '')
        try:
            if not authorization.startswith('Bearer '):
                raise ValueError()
            owner = verify(authorization[7:], request.match_info['slot'],
                           json.loads(self.gateway.keys_file.read_text()))
        except ValueError:
            return web.json_response({'error': {'code': 'speech_access_expired',
                'message': '语音授权无效或已过期，请重新录音'}}, status=401)
        return await self.handle(request, owner=owner)

    async def handle(self, request, *, owner=None):
        owner = self.gateway.authorize(request) if owner is None else owner
        path = request.match_info['path']
        if request.method == 'GET' and path == 'capabilities':
            return web.json_response({'speech': self.service.capabilities()})
        if request.method != 'POST':
            raise web.HTTPMethodNotAllowed(request.method, ['POST'])
        stream = re.fullmatch(r'v1/speech/streams/([A-Za-z0-9_-]{1,128})/(chunks|finish|cancel)', path)
        upload = path == 'v1/speech/transcriptions'
        if not stream and not upload and path != 'v1/speech/streams':
            raise web.HTTPNotFound()
        limit = MAX_SPEECH_BYTES + 65536 if upload else MAX_CHUNK_BYTES if stream and stream[2] == 'chunks' else 4096
        if request.content_length is not None and request.content_length > limit:
            raise web.HTTPRequestEntityTooLarge(max_size=limit, actual_size=request.content_length)
        body = bytearray()
        async for chunk in request.content.iter_chunked(65536):
            body.extend(chunk)
            if len(body) > limit:
                raise web.HTTPRequestEntityTooLarge(max_size=limit, actual_size=len(body))
        try:
            if upload:
                result = await self.run(self.service.transcribe, bytes(body), request.headers.get('Content-Type', ''))
            elif stream:
                if stream[2] == 'chunks' and request.headers.get('Content-Type', '').split(';', 1)[0].strip() != 'application/octet-stream':
                    raise web.HTTPUnsupportedMediaType()
                result = await self.run(self.service.stream, owner, stream[1], stream[2], bytes(body))
            else:
                value = json.loads(body or b'{}')
                if not isinstance(value, dict):
                    raise ValueError('JSON object required')
                result = await self.run(self.service.start, owner, value.get('context', ''))
            return web.json_response(result)
        except MediaError as error:
            return web.json_response({'error': {'code': error.code, 'message': str(error)}}, status=error.http_status)
        except (ValueError, UnicodeError):
            raise web.HTTPBadRequest(text='Invalid speech request') from None


def routes(app, gateway):
    service = SharedSpeech(gateway)
    app.router.add_route('*', '/gateway/speech/{slot}/{path:.*}', service.handle)
    app.router.add_route('*', '/relay/{slot}/v1/speech-direct/{path:.*}', service.direct)
    return service
