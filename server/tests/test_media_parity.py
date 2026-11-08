"""Isolated media, provenance, public-origin and authenticated Range regressions."""
import base64
import hashlib
import http.client
import io
import json
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import quote

from PIL import Image

from rivo_relay.media import MediaService, MediaError, public_target, public_media, media_type, atomic_json, read_record
from rivo_relay.speech import SpeechService
import test_api


def png(size=(32, 16), color='red'):
    output = io.BytesIO()
    Image.new('RGB', size, color).save(output, format='PNG')
    return output.getvalue()


class NativeMedia:
    def __init__(self, raw=None):
        self.raw = raw or png()
        self.calls = []

    def prepare_download(self, path):
        self.calls.append(path)
        return {'size': len(self.raw), 'etag': '"' + hashlib.sha256(self.raw).hexdigest() + '"', 'chunks': iter([self.raw])}


class MediaParityTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.native = NativeMedia()
        self.media = MediaService(self.directory.name, self.native)
        self.media.close()  # Deterministic manual outbox drains, no production state.

    def event(self, **changes):
        item = {'id': 'image-item', 'type': 'imageGeneration', 'status': 'completed',
                'result': base64.b64encode(self.native.raw).decode(), 'revisedPrompt': 'Native revision',
                'model': 'native-recorded-model', 'secret': 'must-not-be-persisted'} | changes
        return {'method': 'item/completed', 'params': {'threadId': 'thread', 'turnId': 'turn', 'item': item}}

    def test_view_is_not_import_and_thumbnail_profile_native_host_change(self):
        self.native.raw = png((3000, 2000))
        path, mime, etag = self.media.resolve('/remote/node/source.png', thumbnail=True)
        self.assertEqual('image/webp', mime)
        self.assertIn('max-512-webp-q78-v3', path.name)
        with Image.open(path) as image:
            self.assertEqual((512, 341), image.size)
        self.assertEqual([], self.media.list())
        self.assertEqual(['/remote/node/source.png'], self.native.calls)
        self.native.raw = png(color='blue')
        new_path, _, new_etag = self.media.resolve('/remote/node/source.png', thumbnail=True)
        self.assertNotEqual(etag, new_etag)
        self.assertNotEqual(path, new_path)

    def test_generated_exact_identity_native_facts_favorite_and_delete_survive_restart(self):
        event = self.event()
        self.media.observe_event(event)
        self.media.observe_event(event)
        self.media.drain()
        record, = self.media.list(thread_id='thread', generated=True)
        self.assertEqual(('thread', 'turn', 'image-item'), tuple(record[key] for key in ('threadId', 'turnId', 'itemId')))
        self.assertEqual('ready', record['status'])
        self.assertTrue(record['hasGenerationInfo'])
        self.assertEqual('revised', record['provenance']['promptKind'])
        self.assertNotIn('secret', json.dumps(record))
        self.assertNotIn('prompt', record['provenance']['facts'])
        self.assertEqual('image/png', record['mimeType'])
        self.assertEqual('image', record['mediaType'])
        self.assertEqual(record['contentPath'], record['originalUrl'])
        self.assertTrue(self.media.favorite(record['id'], True)['favorite'])
        restored = MediaService(self.directory.name, self.native)
        restored.close()
        self.assertTrue(restored.info(record['id'])['favorite'])
        restored.delete(record['id'])
        restored.observe_event(event)
        restored.drain()
        self.assertEqual([], restored.list())
        with self.assertRaises(MediaError): restored.stored(record['id'])
        # Item IDs may repeat across turns; exact provenance prevents collision.
        event['params']['turnId'] = 'another-turn'
        restored.observe_event(event); restored.drain()
        self.assertNotEqual(record['id'], restored.list()[0]['id'])

    def test_failed_and_running_generation_are_not_imported(self):
        for status in ('failed', 'canceled', 'cancelled', 'error', 'in_progress', 'inProgress', 'running'):
            self.media.observe_event(self.event(status=status))
        self.media.observe_event(self.event(failure={'message': 'synthetic failure'}))
        self.media.drain()
        self.assertEqual([], self.media.list())

    def test_generated_result_can_be_native_path_public_url_or_wrapped_base64(self):
        sources = ('/remote/generated.png', 'https://example.org/generated.png',
                   'data:image/png;base64,\n' + base64.b64encode(self.native.raw).decode() + '\n')
        def fetch(source, target):
            target.write_bytes(self.native.raw)
            return 'image/png'
        self.media.fetch_public = fetch
        for index, source in enumerate(sources):
            self.media.observe_event(self.event(id='result-' + str(index), result=source))
        self.media.drain()
        records = self.media.list()
        self.assertEqual(3, len(records))
        self.assertTrue(all(record['status'] == 'ready' for record in records))
        self.assertEqual(1, len(self.media.list(source=sources[0])))
        self.assertEqual(1, len(self.media.list(source=sources[1])))

    def test_inline_native_result_wins_over_mutable_saved_path(self):
        confirmed = png(color='blue')
        self.media.observe_event(self.event(savedPath='/native/generated.png', result=base64.b64encode(confirmed).decode()))
        self.media.drain()
        record, = self.media.list(source='/native/generated.png')
        original, _, _ = self.media.stored(record['id'])
        self.assertEqual(confirmed, original.read_bytes())
        self.assertEqual([], self.native.calls)

    def test_materialization_restart_recovers_existing_file_but_never_recreates_missing(self):
        self.media.observe_event(self.event()); self.media.drain()
        record, = self.media.list()
        manifest = self.media.library / (record['id'] + '.json')
        durable = read_record(manifest)
        durable['status'] = 'pending'
        atomic_json(manifest, durable)
        self.media.drain()
        self.assertEqual('ready', self.media.info(record['id'])['status'])
        (self.media.library / durable['file']).unlink()
        durable['status'] = 'pending'; atomic_json(manifest, durable)
        self.media.drain()
        self.assertEqual([], self.media.list())

    def test_explicit_import_lookup_is_exact_and_preview_never_duplicates(self):
        self.assertEqual([], self.media.list(source='/native/a.png'))
        first = self.media.import_source('/native/a.png', 'thread')
        self.assertEqual(first['id'], self.media.import_source('/native/a.png')['id'])
        self.assertEqual(first['id'], self.media.list(source='/native/a.png')[0]['id'])
        self.assertEqual([], self.media.list(source='/native/a.png?other'))
        self.media.resolve('/native/a.png')
        self.assertEqual(1, len(self.media.list()))
        self.assertFalse(first['hasGenerationInfo'])
        self.media.favorite(first['id'], True)
        self.assertEqual(1, len(self.media.list(favorite=True)))

    def test_bad_images_html_svg_and_changed_owned_files_are_rejected(self):
        target = Path(self.directory.name) / 'content'
        for body in (b'<html>page</html>', b'<svg></svg>', b'\x89PNG\r\ntruncated'):
            target.write_bytes(body)
            with self.assertRaises(MediaError): media_type(target)
        record = self.media.import_source('/native/image.png')
        owned, _, _ = self.media.stored(record['id'])
        owned.write_bytes(png(color='blue'))
        with self.assertRaises(MediaError) as failure: self.media.stored(record['id'])
        self.assertEqual('media_changed', failure.exception.code)

    def test_open_representation_survives_cache_prune_or_library_delete(self):
        stream, mime, etag = self.media.open_source('/native/image.png')
        with stream:
            Path(stream.name).unlink()
            self.assertEqual(self.native.raw, stream.read())
        record = self.media.import_source('/native/image.png')
        stream, mime, etag = self.media.open_stored(record['id'])
        with stream:
            self.media.delete(record['id'])
            self.assertEqual(self.native.raw, stream.read())
        with self.assertRaises(MediaError): self.media.open_stored(record['id'])

    def test_supported_audio_video_remain_original_and_cannot_be_image_thumbnails(self):
        for raw, mime in ((b'RIFF\x20\0\0\0WAVE' + b'\0' * 20, 'audio/wav'),
                          (b'\0\0\0\x20ftypisom' + b'\0' * 20, 'video/mp4')):
            self.native.raw = raw
            path, actual, _ = self.media.resolve('/native/media')
            self.assertEqual(mime, actual)
            self.assertEqual(raw, path.read_bytes())
            with self.assertRaises(MediaError): self.media.resolve('/native/media', thumbnail=True)


class PublicMediaTests(unittest.TestCase):
    def test_public_body_size_encoding_and_truncation_are_bounded(self):
        class Response:
            status = 200
            headers = {}
            raw = b'fixture'
            def getheader(self, name): return self.headers.get(name)
            def read(self, limit):
                block, self.raw = self.raw[:limit], self.raw[limit:]
                return block
        class Connection:
            response = None
            def __init__(self, *args): pass
            def request(self, *args, **kwargs): pass
            def getresponse(self): return self.response
            def close(self): pass
        answers = [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('93.184.215.14', 443))]
        cases = [({'Content-Type': 'image/png', 'Content-Length': '100'}, b'fixture', 'media_too_large'),
                 ({'Content-Type': 'image/png'}, b'x' * 65, 'media_too_large'),
                 ({'Content-Type': 'image/png', 'Content-Length': '8'}, b'fixture', 'media_incomplete'),
                 ({'Content-Type': 'image/png', 'Content-Encoding': 'gzip'}, b'fixture', 'media_encoding')]
        with tempfile.TemporaryDirectory() as directory, patch('rivo_relay.media._PinnedHTTP', Connection), patch(
                'rivo_relay.media.socket.getaddrinfo', return_value=answers), patch('rivo_relay.media.PROXY_IMAGE_LIMIT', 64):
            for headers, body, code in cases:
                Connection.response = Response()
                Connection.response.headers, Connection.response.raw = headers, body
                with self.assertRaises(MediaError) as failure:
                    public_media('https://example.org/image', Path(directory) / 'image')
                self.assertEqual(code, failure.exception.code)

    def test_public_dns_is_pinned_private_mixed_credentials_and_local_names_rejected(self):
        public = [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('93.184.215.14', 443))]
        with patch('rivo_relay.media.socket.getaddrinfo', return_value=public):
            self.assertEqual(['93.184.215.14'], public_target('https://example.org/image.png')[3])
            for url in ('https://u:p@example.org/image.png', 'file:///tmp/image', 'https://localhost/a',
                        'https://host.local/a', 'https://host.internal/a', 'https://example.org:bad/a'):
                with self.assertRaises(MediaError): public_target(url)
        for address in ('127.0.0.1', '10.0.0.1', '169.254.169.254', '::1', '::ffff:127.0.0.1', '100.100.100.100',
                        '224.0.0.1', 'ff02::1', '64:ff9b::7f00:1', '2002:7f00:1::'):
            answers = public + [(socket.AF_INET, socket.SOCK_STREAM, 6, '', (address, 443))]
            with patch('rivo_relay.media.socket.getaddrinfo', return_value=answers):
                with self.assertRaises(MediaError): public_target('https://example.org/image')

    def test_redirect_revalidates_target_and_never_sends_node_credentials(self):
        class Response:
            status = 302
            def getheader(self, name): return 'http://127.0.0.1/private' if name == 'Location' else None
        calls = []
        class Connection:
            def __init__(self, *args): calls.append(args)
            def request(self, *args, **kwargs): calls.append(kwargs)
            def getresponse(self): return Response()
            def close(self): pass
        def dns(host, *args, **kwargs):
            address = '127.0.0.1' if host == '127.0.0.1' else '93.184.215.14'
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, '', (address, 80))]
        with tempfile.TemporaryDirectory() as directory, patch('rivo_relay.media._PinnedHTTP', Connection), patch('rivo_relay.media.socket.getaddrinfo', side_effect=dns):
            with self.assertRaises(MediaError): public_media('http://example.org/image', Path(directory) / 'image')
        self.assertEqual(2, len(calls))  # No second connection to private redirect.
        self.assertFalse({'Authorization', 'Cookie', 'Proxy-Authorization'} & calls[1]['headers'].keys())
        self.assertEqual(['93.184.215.14'], calls[0][2])


class MediaHTTPTests(unittest.TestCase):
    setUp, _start, _stop, _pair, request = test_api.ApiTests.setUp, test_api.ApiTests._start, test_api.ApiTests._stop, test_api.ApiTests._pair, test_api.ApiTests.request

    def binary(self, path, headers=None, authenticated=True):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=5)
        try:
            connection.request('GET', path, headers=({'Authorization': 'Bearer ' + self.token} if authenticated else {}) | (headers or {}))
            response = connection.getresponse()
            return response.status, response.read(), dict(response.getheaders())
        finally: connection.close()

    def test_auth_range_strong_etag_source_lookup_and_no_implicit_import(self):
        native = NativeMedia()
        self.native.prepare_download = native.prepare_download
        path = '/v1/media/content?source=' + quote('/native/test.png', safe='')
        self.assertEqual(401, self.binary(path, authenticated=False)[0])
        self.assertEqual([], native.calls)
        status, raw, headers = self.binary(path)
        self.assertEqual((200, native.raw), (status, raw))
        self.assertEqual('image/png', headers['Content-Type'])
        self.assertEqual('nosniff', headers['X-Content-Type-Options'])
        status, raw, partial = self.binary(path, {'Range': 'bytes=5-12', 'If-Range': headers['ETag']})
        self.assertEqual((206, native.raw[5:13]), (status, raw))
        self.assertEqual(f'bytes 5-12/{len(native.raw)}', partial['Content-Range'])
        self.assertEqual(200, self.binary(path, {'Range': 'bytes=5-12', 'If-Range': '"different"'})[0])
        self.assertEqual(304, self.binary(path, {'If-None-Match': headers['ETag']})[0])
        status, versions, _ = self.request('GET', '/v1/media/versions?source=%2Fnative%2Ftest.png', token=self.token)
        self.assertEqual(200, status)
        self.assertEqual(headers['ETag'], versions['original'])
        self.assertEqual(200, self.binary(path, {'If-Match': versions['original']})[0])
        native.raw = png(color='blue')
        self.assertEqual(412, self.binary(path, {'If-Match': versions['original']})[0])
        _, changed, _ = self.request('GET', '/v1/media/versions?source=%2Fnative%2Ftest.png', token=self.token)
        self.assertNotEqual(versions['original'], changed['original'])
        self.assertNotEqual(versions['thumbnail'], changed['thumbnail'])
        self.assertEqual(200, self.binary(path, {'If-Match': changed['original']})[0])

        self.assertEqual(416, self.binary(path, {'Range': 'bytes=999999-'})[0])
        status, listing, _ = self.request('GET', '/v1/media/library?source=%2Fnative%2Ftest.png', token=self.token)
        self.assertEqual((200, {'items': []}), (status, listing))
        status, record, _ = self.request('POST', '/v1/media/library', {'source': '/native/test.png'}, token=self.token)
        self.assertEqual(200, status)
        self.assertEqual(200, self.binary(record['originalUrl'])[0])
        self.assertEqual(200, self.binary(record['thumbnailUrl'])[0])
        self.assertEqual(record['id'], self.request('GET', '/v1/media/library?source=%2Fnative%2Ftest.png', token=self.token)[1]['items'][0]['id'])
        self.assertFalse(self.request('GET', '/v1/media/library/' + record['id'] + '/provenance', token=self.token)[1]['hasGenerationInfo'])

    def test_capabilities_are_authenticated_and_discoverable_without_starting_models(self):
        self.assertEqual(401, self.request('GET', '/v1/media/capabilities')[0])
        status, capabilities, _ = self.request('GET', '/v1/media/capabilities', token=self.token)
        self.assertEqual(200, status)
        self.assertTrue(capabilities['media']['generatedProvenance'])
        self.assertFalse(capabilities['speech']['streaming'])
        node = self.request('GET', '/v1/node', token=self.token)[1]
        self.assertEqual(capabilities['media'], node['capabilities']['media'])

    def test_speech_http_routes_raw_pcm_auth_and_device_isolation(self):
        calls = []
        def upstream(path, body, content_type='application/json'):
            calls.append((path, body, content_type))
            return {'stream_id': 'upstream', 'text': 'recognized', 'stable_text': 'recognized', 'ok': True}
        self.app.speech = SpeechService('http://configured.invalid', requester=upstream)
        path = '/v1/speech/streams'
        self.assertEqual(401, self.request('POST', path, {})[0])
        status, created, _ = self.request('POST', path, {'context': 'Codex'}, token=self.token)
        self.assertEqual(200, status)
        identity = created['stream_id']
        chunks = path + '/' + identity + '/chunks'
        other = self._pair()['token']
        headers = {'Content-Type': 'application/octet-stream'}
        self.assertEqual(404, self.request('POST', chunks, token=other, raw=b'\0\0', extra_headers=headers)[0])
        self.assertEqual(415, self.request('POST', chunks, token=self.token, raw=b'\0\0', extra_headers={'Content-Type': 'audio/pcm'})[0])
        status, value, _ = self.request('POST', chunks, token=self.token, raw=b'\0\0', extra_headers=headers)
        self.assertEqual((200, 'recognized'), (status, value['stable_text']))
        self.assertEqual(b'\0\0', calls[-1][1])
        finish = path + '/' + identity + '/finish'
        self.assertEqual(200, self.request('POST', finish, {}, token=self.token)[0])
        self.assertEqual(404, self.request('POST', finish, {}, token=self.token)[0])


if __name__ == '__main__':
    unittest.main()
