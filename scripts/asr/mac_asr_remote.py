"""Compatibility client for the existing Mac AI provider; never loads a model."""
import json
import re
import uuid
import urllib.request
from pathlib import Path

class SpeechTranscriptionService:
    def __init__(self, config):
        settings=json.loads((Path(__file__).resolve().parent/'asr-remote.json').read_text())
        self.url=settings['RIVO_SPEECH_BACKEND_URL'].rstrip('/')
        self.token=settings['RIVO_SPEECH_BACKEND_TOKEN']
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def _request(self,path,body=None,content_type='application/json',timeout=120):
        request=urllib.request.Request(self.url+path,data=body,headers={
            'Authorization':'Bearer '+self.token,'Content-Type':content_type})
        with self.opener.open(request,timeout=timeout) as response:
            raw=response.read(1024*1024+1)
            if len(raw)>1024*1024:raise ValueError('ASR response too large')
            return json.loads(raw)

    def transcribe(self,raw,filename,context=''):
        if not 0<len(raw)<=12*1024*1024:raise ValueError('Invalid audio size')
        if not isinstance(context,str) or len(context)>400:raise ValueError('Invalid ASR context')
        boundary='RivoAsr'+uuid.uuid4().hex
        suffix=Path(filename or 'audio.wav').suffix.lower()
        if suffix not in {'.m4a','.mp3','.wav','.webm','.ogg','.flac'}:raise ValueError('Unsupported audio format')
        body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="audio{suffix}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+raw
        body+=(f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="context"\r\n\r\n{context}\r\n--{boundary}--\r\n').encode()
        return self._request('/v1/speech/transcriptions',body,'multipart/form-data; boundary='+boundary)

    def start_stream(self,context=''):
        return self._request('/v1/speech/streams',json.dumps({'context':context}).encode())

    def _stream(self,stream_id,operation,body=b'{}',content_type='application/json'):
        if not isinstance(stream_id,str) or not re.fullmatch('[A-Za-z0-9_-]{1,128}',stream_id):raise ValueError('Invalid stream ID')
        return self._request('/v1/speech/streams/'+stream_id+'/'+operation,body,content_type)

    def feed_stream(self,stream_id,pcm16le):
        return self._stream(stream_id,'chunks',pcm16le,'application/octet-stream')
    def finish_stream(self,stream_id):return self._stream(stream_id,'finish')
    def cancel_stream(self,stream_id):return self._stream(stream_id,'cancel')
    def shutdown(self):pass
    def health_snapshot(self):
        try:
            cap = self._request('/capabilities',timeout=3).get('speech', {})
            return {'ok':cap.get('streaming') is True,'state':'configured' if cap.get('streaming') else 'unavailable','backend':'shared-vps','local_model_loaded':False}
        except Exception:
            return {'ok':False,'state':'unavailable','backend':'shared-vps','local_model_loaded':False,'last_error':'E2 ASR unavailable'}
