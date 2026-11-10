"""Live ASR smoke test with synthetic Mandarin audio, never microphone capture."""
import base64,json,time,urllib.request,urllib.error,wave
from pathlib import Path
root=Path(__file__).resolve().parent
token=(root/'token').read_text().strip()
raw=json.loads((root/'gateway.json').read_text())['key'].split('.',1)[1]
key=json.loads(base64.urlsafe_b64decode(raw+'='*(-len(raw)%4)))
base='https://134.175.156.219:8443/relay/'+key['slot']
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(path,body=None,content_type='application/json',auth=True,endpoint=base):
 headers={'Content-Type':content_type}
 if auth:headers['Authorization']='Bearer '+token
 req=urllib.request.Request(endpoint+path,data=body,headers=headers)
 started=time.monotonic()
 with opener.open(req,timeout=120) as response: result=json.load(response)
 print(json.dumps({'path':path,'seconds':round(time.monotonic()-started,3),'result':result},ensure_ascii=False))
 return result
for endpoint in ['http://127.0.0.1:19092',base]:
 try: call('/health',auth=False,endpoint=endpoint);raise AssertionError('anonymous access accepted')
 except urllib.error.HTTPError as error: assert error.code==401
 call('/health',endpoint=endpoint)
stream=call('/v1/speech/streams',b'{}')['stream_id']
call('/v1/speech/streams/'+stream+'/cancel',b'{}')
stream=call('/v1/speech/streams',b'{}')['stream_id']
try:
 call('/v1/speech/streams/'+stream+'/chunks',b'1','application/octet-stream');raise AssertionError('odd PCM accepted')
except urllib.error.HTTPError as error: assert error.code==400
call('/v1/speech/streams/'+stream+'/cancel',b'{}')
with wave.open(str(root/'test-zh.wav')) as f:
 assert (f.getnchannels(),f.getsampwidth(),f.getframerate())==(1,2,16000)
 pcm=f.readframes(f.getnframes())
assert len(pcm)>32000, 'Test audio must contain speech, not an empty file'
stream=call('/v1/speech/streams',b'{}')['stream_id']
for offset in range(0,len(pcm),16000):
 call('/v1/speech/streams/'+stream+'/chunks',pcm[offset:offset+16000],'application/octet-stream')
final=call('/v1/speech/streams/'+stream+'/finish',b'{}')
assert '任务' in final['text'] and '语音' in final['text'],final
boundary='RivoAsrSmokeBoundary'
body=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="test.wav"\r\nContent-Type: audio/wav\r\n\r\n').encode()+(root/'test-zh.wav').read_bytes()+('\r\n--'+boundary+'--\r\n').encode()
result=call('/v1/speech/transcriptions',body,'multipart/form-data; boundary='+boundary)
assert '任务' in result['text'] and '语音' in result['text'],result
print('PASS: loopback + public VPS HTTPS, auth, cancel, malformed PCM, streaming, whole-file Mandarin ASR')
