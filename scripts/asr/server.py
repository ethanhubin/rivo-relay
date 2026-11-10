"""Private Tailscale ASR adapter for Rivo; model worker copied from local deployment."""
import hmac
import json
import re
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from mac_asr import SpeechTranscriptionService, AsrInvalidResponse, AsrUnavailable

ROOT = Path(__file__).resolve().parent
TOKEN = ''
service = None

class Handler(BaseHTTPRequestHandler):
    def reply(self, status, value):
        raw = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def authorized(self):
        if not TOKEN or not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + TOKEN):
            self.reply(401, {'error': 'unauthorized'})
            return False
        return True

    def do_GET(self):
        if not self.authorized(): return
        if self.path != '/health': return self.reply(404, {'error': 'not_found'})
        state = service.health_snapshot()
        self.reply(200 if state['ok'] else 503, state)

    def do_POST(self):
        if not self.authorized(): return
        self.connection.settimeout(15)
        try:
            match = re.fullmatch(r'/v1/speech/streams/([A-Za-z0-9_-]{1,128})/(chunks|finish|cancel)', self.path)
            upload = self.path == '/v1/speech/transcriptions'
            if not match and not upload and self.path != '/v1/speech/streams':
                return self.reply(404, {'error': 'not_found'})
            limit = 12 * 1024 * 1024 + 65536 if upload else 65536
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 <= length <= limit: return self.reply(413, {'error': 'body_too_large'})
            body = self.rfile.read(length)
            if len(body) != length: raise ValueError('incomplete body')
            if upload:
                content_type = self.headers.get('Content-Type', '')
                if not content_type.startswith('multipart/form-data;'): raise ValueError('multipart required')
                message = BytesParser(policy=policy.default).parsebytes(('Content-Type: ' + content_type + '\r\n\r\n').encode() + body)
                parts = list(message.iter_parts())
                files = [p for p in parts if p.get_param('name', header='content-disposition') == 'file']
                contexts = [p for p in parts if p.get_param('name', header='content-disposition') == 'context']
                if len(files) != 1 or len(contexts) > 1 or len(parts) != len(files) + len(contexts): raise ValueError('invalid multipart fields')
                context = contexts[0].get_payload(decode=True).decode() if contexts else ''
                result = service.transcribe(files[0].get_payload(decode=True), files[0].get_filename(), context)
            elif match:
                stream_id, action = match.groups()
                if action == 'chunks':
                    if self.headers.get('Content-Type') != 'application/octet-stream': raise ValueError('raw PCM required')
                    result = service.feed_stream(stream_id, body)
                elif action == 'finish': result = service.finish_stream(stream_id)
                else: result = service.cancel_stream(stream_id)
            else:
                data = json.loads(body or b'{}')
                if not isinstance(data, dict): raise ValueError('JSON object required')
                context = data.get('context', '')
                if not isinstance(context, str): raise ValueError('invalid context')
                result = service.start_stream(context)
            self.reply(200, result)
        except (ValueError, AsrInvalidResponse) as error:
            self.reply(400, {'error': str(error)})
        except AsrUnavailable as error:
            self.reply(503, {'error': str(error)})
        except Exception:
            self.reply(500, {'error': 'transcription failed'})

if __name__ == '__main__':
    TOKEN = (ROOT / 'token').read_text().strip()
    if not TOKEN: raise RuntimeError('Empty ASR token')
    service = SpeechTranscriptionService(SimpleNamespace(local_asr_model=ROOT / 'models/Qwen3-ASR-1.7B-8bit', local_asr_temp=ROOT / 'tmp'))
    ThreadingHTTPServer(('127.0.0.1', 19092), Handler).serve_forever()
