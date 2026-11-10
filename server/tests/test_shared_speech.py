import asyncio
import json
import threading
import unittest
from unittest.mock import patch

from relay_test_support.gateway import GatewayFixture
from rivo_relay.gateway.config import encode_key, issue_key, decode_key
from rivo_relay.speech import SpeechService, MAX_CHUNK_BYTES
from rivo_relay.media import MediaError


class SharedSpeechTests(GatewayFixture, unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        await super().asyncSetUp()
        self.calls = []
        def upstream(path, body, content_type='application/json'):
            self.calls.append((path, body, content_type))
            if path == '/v1/speech/streams':
                return {'stream_id': 'upstream', 'text': '', 'stable_text': ''}
            return {'text': '语音', 'stable_text': '语音'}
        self.gateway.speech.service = SpeechService('http://backend.invalid', requester=upstream)
        self.endpoint = self.base + '/gateway/speech/' + self.key['slot']
        self.auth = {'Authorization': 'Bearer ' + self.key['secret']}

    async def post(self, path, **kwargs):
        return await self.client.post(self.endpoint + path, headers=self.auth, **kwargs)

    async def test_node_credentials_required_and_revocation_is_immediate(self):
        path = self.endpoint + '/capabilities'
        async with self.client.get(path) as r:
            self.assertEqual(401, r.status)
        async with self.client.get(path, headers=self.auth) as r:
            self.assertTrue((await r.json())['speech']['streaming'])
        keys = json.loads(self.gateway.keys_file.read_text())
        keys.pop(self.key['slot'])
        self.gateway.keys_file.write_text(json.dumps(keys))
        async with self.client.get(path, headers=self.auth) as r:
            self.assertEqual(401, r.status)

    async def test_gateway_enforces_node_stream_ownership(self):
        async with await self.post('/v1/speech/streams', json={}) as r:
            identity = (await r.json())['stream_id']
        other = decode_key(issue_key(self.directory, self.key['pin'], self.base))
        other_url = self.base + '/gateway/speech/' + other['slot']
        async with self.client.post(other_url + '/v1/speech/streams/' + identity + '/finish',
                                   headers={'Authorization': 'Bearer ' + other['secret']}, json={}) as r:
            self.assertEqual(404, r.status)
        self.assertEqual(1, len(self.calls))
        async with await self.post('/v1/speech/streams/' + identity + '/chunks', data=b'\0\0',
                                   skip_auto_headers={'Content-Type'}) as r:
            self.assertEqual(415, r.status)
        async with self.client.post(self.endpoint + '/v1/speech/streams/' + identity + '/chunks',
                                   headers={**self.auth, 'Content-Type': 'application/octet-stream'}, data=b'\0\0') as r:
            self.assertEqual(200, r.status)
        self.assertEqual(b'\0\0', self.calls[-1][1])
        async with await self.post('/v1/speech/streams/' + identity + '/finish', json={}) as r:
            self.assertEqual('语音', (await r.json())['text'])

    async def test_default_node_uses_gateway_and_ignores_legacy_backend_env(self):
        key = {**self.key, 'gateway': self.base}
        (self.directory / 'gateway-client.json').write_text(json.dumps({'key': encode_key(key)}))
        with patch.dict('os.environ', {'RIVO_SPEECH_BACKEND_URL': 'http://old-node.invalid',
                                       'RIVO_SPEECH_BACKEND_TOKEN': 'old-token'}):
            service = SpeechService(workspace=self.directory)
        self.assertTrue(service.shared)
        cap = await asyncio.to_thread(service.capabilities)
        self.assertTrue(cap['streaming'])
        identity = (await asyncio.to_thread(service.start, 'paired-device'))['stream_id']
        result = await asyncio.to_thread(service.stream, 'paired-device', identity, 'chunks', b'\0\0')
        self.assertEqual('语音', result['text'])
        await asyncio.to_thread(service.stream, 'paired-device', identity, 'cancel')
        self.assertNotIn('old-token', str(self.calls))

    async def test_whole_file_forwarding_and_invalid_gateway_fail_closed(self):
        content_type = 'multipart/form-data; boundary=test'
        body = b'--test\r\nContent-Disposition: form-data; name="file"; filename="a.wav"\r\n\r\nRIFF\r\n--test--\r\n'
        async with self.client.post(self.endpoint + '/v1/speech/transcriptions', data=body,
                                   headers={**self.auth, 'Content-Type': content_type}) as r:
            self.assertEqual(200, r.status)
            self.assertEqual('语音', (await r.json())['text'])
        self.assertEqual(('/v1/speech/transcriptions', body, content_type), self.calls[-1])
        (self.directory / 'gateway-client.json').write_text('{"key":"invalid"}')
        with patch.dict('os.environ', {'RIVO_SPEECH_BACKEND_URL': 'http://old.invalid'}):
            self.assertFalse(SpeechService(workspace=self.directory).capabilities()['streaming'])

    async def test_certificate_mismatch_sends_no_node_credentials(self):
        key = {**self.key, 'gateway': self.base, 'pin': '0' * 64}
        (self.directory / 'gateway-client.json').write_text(json.dumps({'key': encode_key(key)}))
        service = SpeechService(workspace=self.directory)
        with patch.object(self.gateway, 'authorize', wraps=self.gateway.authorize) as authorize:
            cap = await asyncio.to_thread(service.capabilities)
            self.assertFalse(cap['streaming'])
            authorize.assert_not_called()

    async def test_bad_request_limits_and_unconfigured_capability(self):
        async with await self.post('/v1/speech/streams', json=[]) as r:
            self.assertEqual(400, r.status)
        async with await self.post('/v1/speech/streams', json={'context': 'x' * 401}) as r:
            self.assertEqual(400, r.status)
        async with await self.post('/v1/speech/streams/id/chunks', data=b'x' * (MAX_CHUNK_BYTES + 1)) as r:
            self.assertEqual(413, r.status)
        async with await self.post('/v1/arbitrary', json={}) as r:
            self.assertEqual(404, r.status)
        self.gateway.speech.service = SpeechService('')
        async with self.client.get(self.endpoint + '/capabilities', headers=self.auth) as r:
            self.assertFalse((await r.json())['speech']['streaming'])

    async def test_cancelled_http_caller_keeps_work_count_until_worker_finishes(self):
        started, release = threading.Event(), threading.Event()
        def blocking():
            started.set()
            release.wait(5)
        task = asyncio.create_task(self.gateway.speech.run(blocking))
        try:
            await asyncio.to_thread(started.wait, 1)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            self.assertEqual(1, self.gateway.speech.active)
        finally:
            release.set()
        for _ in range(50):
            if not self.gateway.speech.active:
                break
            await asyncio.sleep(.01)
        self.assertEqual(0, self.gateway.speech.active)


if __name__ == '__main__':
    unittest.main()
