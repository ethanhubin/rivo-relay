import http.client
import json
import threading
import unittest
from unittest.mock import Mock
import server

class AdapterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        server.TOKEN='test-token'
        server.service=Mock()
        cls.http=server.ThreadingHTTPServer(('127.0.0.1',0),server.Handler)
        cls.thread=threading.Thread(target=cls.http.serve_forever,daemon=True);cls.thread.start()
    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown();cls.http.server_close();cls.thread.join()
    def request(self,path,body=b'{}',auth=True,content_type='application/json',extra=None):
        c=http.client.HTTPConnection('127.0.0.1',self.http.server_port)
        headers={'Content-Type':content_type}
        if auth:headers['Authorization']='Bearer test-token'
        headers.update(extra or {})
        c.request('POST',path,body,headers)
        r=c.getresponse();result=(r.status,r.read());c.close();return result
    def test_auth_before_dispatch(self):
        server.service.reset_mock()
        self.assertEqual(self.request('/v1/speech/streams',auth=False)[0],401)
        server.service.start_stream.assert_not_called()
    def test_start_forwards_each_client_context_including_empty(self):
        server.service.start_stream.return_value = {'stream_id': 'test'}
        for context in ['固定词表：ComfyUI、LoRA\n编辑图片', '固定词表：Merge、棋子', '']:
            body = json.dumps({'context': context}).encode()
            self.assertEqual(self.request('/v1/speech/streams', body)[0], 200)
            server.service.start_stream.assert_called_with(context)

    def test_pcm_forwarded_without_conversion(self):
        pcm=b'\x01\x00\xff\x7f'
        server.service.feed_stream.return_value={'text':'测试','stable_text':''}
        self.assertEqual(self.request('/v1/speech/streams/test/chunks',pcm,content_type='application/octet-stream')[0],200)
        server.service.feed_stream.assert_called_with('test',pcm)
        self.assertEqual(self.request('/v1/speech/streams/test/chunks',pcm)[0],400)
    def test_reject_invalid_payload_and_route(self):
        for body in [b'[]',b'{',b'{"context":1}']:
            self.assertEqual(self.request('/v1/speech/streams',body)[0],400)
        self.assertEqual(self.request('/v1/arbitrary')[0],404)
        self.assertEqual(self.request('/v1/speech/streams',extra={'Content-Length':'65537'})[0],413)
    def test_multipart_dispatch(self):
        server.service.transcribe.return_value={'text':'测试'}
        body=b'--test\r\nContent-Disposition: form-data; name="file"; filename="test.wav"\r\n\r\nRIFF\r\n--test--\r\n'
        self.assertEqual(self.request('/v1/speech/transcriptions',body,content_type='multipart/form-data; boundary=test')[0],200)
        server.service.transcribe.assert_called_with(b'RIFF','test.wav','')

if __name__=='__main__':unittest.main()
