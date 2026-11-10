"""Exercise a paired Relay through VPS, then revoke the temporary test device."""
import argparse,json,time,urllib.request,wave
from pathlib import Path
from rivo_relay.database import Database
from rivo_relay.pairing import PairingStore
from rivo_relay.gateway.config import decode_key
parser=argparse.ArgumentParser();parser.add_argument('--workspace',type=Path,required=True);parser.add_argument('--audio',type=Path,required=True)
args=parser.parse_args()
key=decode_key(json.loads((args.workspace/'gateway-client.json').read_text())['key'])
base='https://134.175.156.219:8443/relay/'+key['slot']
db=Database(args.workspace/'data/relay.sqlite3');pairing=PairingStore(db)
issued=pairing.create();device=pairing.exchange(issued['code'],'ASR deployment verification')
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(path,body=None,content_type='application/json'):
 headers={'Authorization':'Bearer '+device['token'],'Content-Type':content_type}
 start=time.monotonic()
 with opener.open(urllib.request.Request(base+path,data=body,headers=headers),timeout=15) as response: result=json.load(response)
 elapsed=time.monotonic()-start
 print(json.dumps({'path':path,'seconds':round(elapsed,3),'result':result},ensure_ascii=False),flush=True)
 return result
try:
 cap=call('/v1/media/capabilities')['speech'];assert cap['streaming'] and cap['sampleRate']==16000
 with wave.open(str(args.audio)) as audio:pcm=audio.readframes(audio.getnframes())
 stream=call('/v1/speech/streams',b'{}')['stream_id']
 for offset in range(0,len(pcm),16000):call('/v1/speech/streams/'+stream+'/chunks',pcm[offset:offset+16000],'application/octet-stream')
 result=call('/v1/speech/streams/'+stream+'/finish',b'{}')
 assert '语音' in result['text'] and '任务' in result['text']
 print('PASS: authenticated Relay speech over public VPS, all requests within Android 15-second deadline')
finally:
 assert pairing.revoke(device['connection_id'])
 db.close();print('Temporary test device revoked')
