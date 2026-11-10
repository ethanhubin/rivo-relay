import asyncio
import json
import time
import unittest

from relay_test_support.gateway import GatewayFixture
from relay_test_support.http import RelayHTTPFixture
from rivo_relay.gateway.config import encode_key
from rivo_relay.gateway.speech_access import issue, verify
from rivo_relay.speech import SpeechService


class DirectSpeechTests(GatewayFixture, unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        await super().asyncSetUp()
        (self.directory / 'gateway-client.json').write_text(json.dumps({'key': encode_key(self.key)}))
        self.calls = []
        def upstream(path, body, content_type='application/json'):
            self.calls.append((path, body))
            return {'stream_id': 'upstream', 'text': '测试语音', 'stable_text': '测试语音'}
        self.gateway.speech.service = SpeechService('http://asr.invalid', requester=upstream)
        self.endpoint = self.url + '/v1/speech-direct'
        self.access = issue(self.directory, 'device-a')
        self.auth = {'Authorization': 'Bearer ' + self.access['token']}

    async def test_audio_and_finish_work_without_chat_node_connection(self):
        self.task.cancel()
        await asyncio.gather(self.task, return_exceptions=True)
        for _ in range(100):
            if not self.gateway.nodes: break
            await asyncio.sleep(.01)
        self.assertFalse(self.gateway.nodes)
        async with self.client.get(self.endpoint + '/capabilities', headers=self.auth) as r:
            self.assertTrue((await r.json())['speech']['streaming'])
        async with self.client.post(self.endpoint + '/v1/speech/streams', headers=self.auth, json={}) as r:
            self.assertEqual(200, r.status)
            identity = (await r.json())['stream_id']
        async with self.client.post(self.endpoint + '/v1/speech/streams/' + identity + '/chunks',
                                   headers={**self.auth, 'Content-Type': 'application/octet-stream'}, data=b'\x12\x34') as r:
            self.assertEqual(200, r.status)
        self.assertEqual(b'\x12\x34', self.calls[-1][1])
        async with self.client.post(self.endpoint + '/v1/speech/streams/' + identity + '/finish', headers=self.auth, json={}) as r:
            self.assertEqual('测试语音', (await r.json())['text'])

    async def test_wrong_bearer_tamper_expiry_and_revocation_rejected(self):
        expired = issue(self.directory, 'device-a', now=int(time.time()) - 181)['token']
        token = self.access['token']
        for invalid in ('phone-token', self.key['secret'], expired, token[:-1] + ('0' if token[-1] != '0' else '1')):
            async with self.client.get(self.endpoint + '/capabilities', headers={'Authorization': 'Bearer ' + invalid}) as r:
                self.assertEqual(401, r.status)
        keys = json.loads(self.gateway.keys_file.read_text()); keys.pop(self.key['slot'])
        self.gateway.keys_file.write_text(json.dumps(keys))
        async with self.client.get(self.endpoint + '/capabilities', headers=self.auth) as r:
            self.assertEqual(401, r.status)
        self.assertFalse(self.calls)

    async def test_other_device_and_other_grant_cannot_access_stream(self):
        async with self.client.post(self.endpoint + '/v1/speech/streams', headers=self.auth, json={}) as r:
            identity = (await r.json())['stream_id']
        for device in ('device-b', 'device-a'):
            token = issue(self.directory, device)['token']
            async with self.client.post(self.endpoint + '/v1/speech/streams/' + identity + '/finish',
                                       headers={'Authorization': 'Bearer ' + token}, json={}) as r:
                self.assertEqual(404, r.status)
        self.assertEqual(1, len(self.calls))

    async def test_scope_does_not_grant_arbitrary_relay_or_node_access(self):
        async with self.client.post(self.endpoint + '/v1/threads', headers=self.auth, json={}) as r:
            self.assertEqual(404, r.status)
        async with self.client.get(self.base + '/gateway/speech/' + self.key['slot'] + '/capabilities', headers=self.auth) as r:
            self.assertEqual(401, r.status)
        keys = json.loads(self.gateway.keys_file.read_text())
        with self.assertRaises(ValueError):
            verify(self.access['token'], 'f' * 32, keys)


class SpeechAccessHTTPTests(RelayHTTPFixture, unittest.TestCase):
    def test_only_currently_paired_device_can_obtain_speech_grant(self):
        key = {'v': 2, 'slot': 'a' * 32, 'secret': 's' * 32, 'pin': 'b' * 64, 'gateway': 'https://127.0.0.1:80'}
        (self.app.config.workspace / 'gateway-client.json').write_text(json.dumps({'key': encode_key(key)}))
        self.assertEqual(401, self.request('POST', '/v1/speech/access', {})[0])
        status, result, _ = self.request('POST', '/v1/speech/access', {}, token=self.token)
        self.assertEqual(200, status)
        self.assertTrue(result['token'].startswith('speech1.'))
        self.assertNotIn(key['secret'], json.dumps(result))
        self.assertEqual(401, self.request('GET', '/v1/node', token=result['token'])[0])
        self.app.pairing.revoke(self.app.pairing.authenticate(self.token)['connection_id'])
        self.assertEqual(401, self.request('POST', '/v1/speech/access', {}, token=self.token)[0])
