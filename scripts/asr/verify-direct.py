"""Verify live node-issued speech grants and the short VPS path with a supplied WAV."""
import argparse
import asyncio
import json
import time
import wave
from pathlib import Path

import aiohttp
from rivo_relay.database import Database
from rivo_relay.pairing import PairingStore
from rivo_relay.gateway.config import decode_key


async def verify(workspace, audio):
    key = decode_key(json.loads((workspace / 'gateway-client.json').read_text())['key'])
    base = key['gateway'] + '/relay/' + key['slot']
    with wave.open(str(audio)) as source:
        assert (source.getnchannels(), source.getsampwidth(), source.getframerate()) == (1, 2, 16000)
        pcm = source.readframes(source.getnframes())
    assert len(pcm) > 32000
    database = Database(workspace / 'data/relay.sqlite3')
    pairing = PairingStore(database)
    device = pairing.exchange(pairing.create()['code'], 'Direct ASR verification')
    times = []
    try:
        connector = aiohttp.TCPConnector(ssl=aiohttp.Fingerprint(bytes.fromhex(key['pin'])))
        async with aiohttp.ClientSession(connector=connector, timeout=aiohttp.ClientTimeout(total=15)) as client:
            async with client.post(base + '/v1/speech/access', json={},
                                   headers={'Authorization': 'Bearer ' + device['token']}, allow_redirects=False) as response:
                assert response.status == 200, response.status
                access = await response.json()
            headers = {'Authorization': 'Bearer ' + access['token']}
            async def request(path, body=None, content_type='application/json'):
                start = time.monotonic()
                async with client.request('GET' if body is None else 'POST', base + '/v1/speech-direct' + path,
                                          data=body, headers={**headers, 'Content-Type': content_type}, allow_redirects=False) as response:
                    assert response.status == 200, response.status
                    result = await response.json()
                times.append(time.monotonic() - start)
                return result
            assert (await request('/capabilities'))['speech']['streaming']
            stream = (await request('/v1/speech/streams', b'{}'))['stream_id']
            for offset in range(0, len(pcm), 16000):
                await request('/v1/speech/streams/' + stream + '/chunks', pcm[offset:offset + 16000], 'application/octet-stream')
            result = await request('/v1/speech/streams/' + stream + '/finish', b'{}')
            assert '语音' in result['text'] and '任务' in result['text']
            print(json.dumps({'passed': True, 'route': 'App-compatible sender -> VPS -> E2',
                              'grantIssuedByLiveNode': True, 'text': result['text'],
                              'maxRequestSeconds': round(max(times), 3), 'finishSeconds': round(times[-1], 3)}, ensure_ascii=False))
    finally:
        assert pairing.revoke(device['connection_id'])
        database.close()
        print('Temporary pairing revoked; no chat message sent')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--audio', type=Path, required=True)
    args = parser.parse_args()
    asyncio.run(verify(args.workspace, args.audio))
