import json
import unittest

from rivo_relay.media import MediaError
from rivo_relay.speech import SpeechService, MAX_CHUNK_BYTES


class SpeechParityTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        def request(path, body, content_type='application/json'):
            self.calls.append((path, body, content_type))
            if path == '/v1/speech/streams': return {'stream_id': 'native_stream', 'sample_rate': 16000}
            return {'text': 'recognized', 'stable_text': 'recognized', 'ok': True}
        self.speech = SpeechService('http://configured-asr.invalid', requester=request)

    def test_capabilities_are_opt_in_and_stream_is_owned_by_paired_device(self):
        self.assertFalse(SpeechService('').capabilities()['transcription'])
        self.assertTrue(self.speech.capabilities()['streaming'])
        created = self.speech.start('device-a', 'context')
        identity = created['stream_id']
        self.assertNotEqual('native_stream', identity)
        with self.assertRaises(MediaError): self.speech.stream('device-b', identity, 'chunks', b'\0\0')
        self.assertEqual(1, len(self.calls))
        self.assertEqual('recognized', self.speech.stream('device-a', identity, 'chunks', b'\0\0')['text'])
        self.assertEqual('/v1/speech/streams/native_stream/chunks', self.calls[-1][0])
        self.assertEqual('application/octet-stream', self.calls[-1][2])
        self.assertEqual(identity, self.speech.stream('device-a', identity, 'finish')['stream_id'])
        with self.assertRaises(MediaError): self.speech.stream('device-a', identity, 'finish')

    def test_stream_chunk_bounds_max_duration_and_idle_expiry(self):
        identity = self.speech.start('device')['stream_id']
        for raw in (b'', b'x', b'\0' * (MAX_CHUNK_BYTES + 2)):
            with self.assertRaises(MediaError): self.speech.stream('device', identity, 'chunks', raw)
        self.assertEqual(1, len(self.calls))
        self.speech._streams[identity]['bytes'] = 16000 * 2 * 120
        with self.assertRaises(MediaError) as failure: self.speech.stream('device', identity, 'chunks', b'\0\0')
        self.assertEqual(413, failure.exception.http_status)
        identity = self.speech.start('device')['stream_id']
        self.speech._streams[identity]['touched'] -= 31
        with self.assertRaises(MediaError): self.speech.stream('device', identity, 'finish')

    def test_uncertain_chunk_retires_identity_and_is_never_replayed(self):
        identity = self.speech.start('device')['stream_id']
        attempts = []
        def failed(*args):
            attempts.append(args)
            raise TimeoutError('synthetic unknown upstream append')
        self.speech.requester = failed
        with self.assertRaises(TimeoutError): self.speech.stream('device', identity, 'chunks', b'\0\0')
        with self.assertRaises(MediaError): self.speech.stream('device', identity, 'chunks', b'\0\0')
        self.assertEqual(1, len(attempts))

    def test_four_stream_limit_context_and_cancel(self):
        identities = [self.speech.start('device')['stream_id'] for _ in range(4)]
        with self.assertRaises(MediaError) as failure: self.speech.start('other-device')
        self.assertEqual(429, failure.exception.http_status)
        self.speech.stream('device', identities[0], 'cancel')
        self.speech.start('other-device')
        for context in ('a' * 401, None):
            with self.assertRaises(MediaError): self.speech.start('device', context)

    def test_multipart_single_file_context_and_exact_transcription(self):
        content_type = 'multipart/form-data; boundary=fixture-boundary'
        body = (b'--fixture-boundary\r\nContent-Disposition: form-data; name="file"; filename="recording.wav"\r\n'
                b'Content-Type: audio/wav\r\n\r\nRIFFfixture\r\n--fixture-boundary\r\n'
                b'Content-Disposition: form-data; name="context"\r\n\r\nCodex\r\n--fixture-boundary--\r\n')
        self.assertEqual('recognized', self.speech.transcribe(body, content_type)['text'])
        self.assertEqual(('/v1/speech/transcriptions', body, content_type), self.calls[-1])
        with self.assertRaises(MediaError): self.speech.transcribe(body.replace(b'name="file"', b'name="wrong"'), content_type)
        with self.assertRaises(MediaError): self.speech.transcribe(body, 'audio/wav')


if __name__ == '__main__':
    unittest.main()
