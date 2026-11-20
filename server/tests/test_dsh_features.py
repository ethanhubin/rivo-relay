"""DSH model, attachment and native-history media contracts without user data."""
import base64
import hashlib
import http.client
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.parse import quote

from relay_test_support.media import png
from relay_test_support.http import RelayHTTPFixture
from rivo_relay.codex.connection import CodexError
from rivo_relay.dsh import DshService
from rivo_relay.dsh.capabilities import OPTIONAL
from rivo_relay.dsh.media import download, is_media_source, parse_source, source_url
from rivo_relay.dsh.models import catalog, model_id, selection
from rivo_relay.dsh.projection import project
from rivo_relay.dsh.uploads import UploadStore
import test_dsh
from test_dsh import FakeBridge, event


class FeaturesBridge(FakeBridge):
    def __init__(self):
        super().__init__()
        self.features = list(OPTIONAL)
        self.selected = {'provider': 'provider-a', 'model': 'reasoner', 'reasoningEffort': 'high'}
        self.calls, self.uploads = [], []
        self.raw = png()
        self.media = []

    def rpc(self, method, params):
        self.calls.append((method, params))
        if method == 'status':
            return {**super().rpc(method, params), 'capabilities': self.features}
        if method == 'models':
            return {'default': self.selected, 'groups': [
                {'id': provider, 'name': provider, 'models': [{'id': 'reasoner', 'name': 'Reasoner',
                    'reasoning': {'efforts': [{'id': 'off', 'name': 'Off'}, {'id': 'high', 'name': 'High'}], 'defaultEffort': 'off'}}]}
                for provider in ('provider-a', 'provider-b')], 'failures': []}
        if method == 'snapshot':
            return {**super().rpc(method, params), 'selected': dict(self.selected), 'media': self.media}
        if method == 'selectModel':
            self.selected.update(params['selection'])
            return {'selected': dict(self.selected)}
        if method == 'upload':
            self.uploads.append(params)
            return {'receiptId': 'receipt-' + str(len(self.uploads)), 'file': {'name': params['name']}}
        if method == 'media':
            return {'generation': self.generation, 'data': base64.b64encode(self.raw).decode(),
                    'sha256': hashlib.sha256(self.raw).hexdigest(), 'size': len(self.raw), 'mediaType': 'image/png'}
        return super().rpc(method, params)


class DshFeaturesTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.bridge = FeaturesBridge()
        class Transport:
            call = staticmethod(self.bridge.rpc)
        self.service = DshService('/tmp/unused', transport=Transport())
        self.service.configure_attachments(Path(self.directory.name) / 'uploads')
        self.service._refresh_status()

    def test_native_plan_selection_roundtrip_and_old_bridge_gate(self):
        self.service.send('s', 'plan this', client_message_id='plan-input', mode='plan')
        self.assertEqual('plan', self.bridge.prompts['plan-input']['collaborationMode'])
        self.service.send('s', 'execute', client_message_id='default-input', mode='default')
        self.assertEqual('default', self.bridge.prompts['default-input']['collaborationMode'])
        original = self.bridge.rpc
        self.service.transport.call = lambda method, params=None: ({**original(method, params),
            'plan': {'active': False, 'pending': True}} if method == 'snapshot' else original(method, params))
        self.assertEqual('plan', self.service.get_session('s')['settings']['collaborationMode']['mode'])
        self.service._features.discard('session.plan')
        with self.assertRaises(CodexError):
            self.service.send('s', 'no support', client_message_id='blocked-plan', mode='plan')
        self.assertNotIn('blocked-plan', self.bridge.prompts)
        self.assertFalse(self.service.runtime_capabilities()['operations']['session.goal']['supported'])

    def test_model_catalog_keeps_provider_identity_and_native_efforts(self):
        result = self.service.capabilities()
        first, second = result['models']
        self.assertNotEqual(first['model'], second['model'])
        self.assertEqual({'provider': 'provider-a', 'model': 'reasoner'}, selection(first['model']))
        self.assertEqual(['off', 'high'], [e['reasoningEffort'] for e in first['supportedReasoningEfforts']])
        self.assertTrue(first['isDefault'])
        self.assertFalse(second['isDefault'])
        self.assertEqual('high', first['defaultReasoningEffort'])
        self.assertEqual('off', second['defaultReasoningEffort'])

    def test_settings_are_actual_native_selection_and_effort_only_stays_partial(self):
        self.service.update_settings('s', effort='off')
        self.assertIn(('selectModel', {'sessionId': 's', 'selection': {'reasoningEffort': 'off'}}), self.bridge.calls)
        settings = self.service.get_session('s')['settings']
        self.assertEqual(model_id('provider-a', 'reasoner'), settings['model'])
        self.assertEqual('off', settings['reasoningEffort'])
        self.service.update_settings('s', model=model_id('provider-b', 'reasoner'), effort='high')
        self.assertEqual('provider-b', self.bridge.selected['provider'])

    def test_model_send_remains_one_prompt_rpc_with_native_request_identity(self):
        result = self.service.send('s', 'hello', client_message_id='send-1',
                                   model=model_id('provider-b', 'reasoner'), effort='off')
        self.assertEqual('input-accepted', result['kind'])
        prompt = self.bridge.prompts['send-1']
        self.assertEqual({'provider': 'provider-b', 'model': 'reasoner', 'reasoningEffort': 'off'}, prompt['selection'])
        self.assertFalse(any(method == 'selectModel' for method, _ in self.bridge.calls))
        self.assertNotIn('turnId', result)

    def test_unknown_model_identity_and_unsupported_settings_never_dispatch(self):
        for options in ({'model': 'guessed-model'}, {'effort': ''}, {'service_tier': 'priority'}, {'sandbox': 'read-only'}):
            with self.subTest(options=options), self.assertRaises(CodexError):
                self.service.update_settings('s', **options)
        self.assertFalse(any(method == 'selectModel' for method, _ in self.bridge.calls))

    def test_old_bridge_and_disconnect_revoke_new_features(self):
        self.assertEqual(set(OPTIONAL), self.service._features)
        self.bridge.features = []
        self.service._refresh_status()
        self.assertEqual([], self.service.capabilities()['models'])
        with self.assertRaises(CodexError): self.service.update_settings('s', effort='high')
        with self.assertRaises(CodexError): self.service.upload_attachment_stream('s', 'a.txt', io.BytesIO(b'a'), 1)
        self.service._features = set(OPTIONAL)
        self.service._disconnected()
        self.assertEqual(set(), self.service._features)

    def test_poll_refreshes_feature_declaration_after_generation_change(self):
        service, bridge = self.service, self.bridge
        polls = []
        class Transport:
            call = staticmethod(bridge.rpc)
            def poll(self, generation, cursor):
                polls.append(generation)
                if len(polls) == 1:
                    bridge.generation = 'generation-2'
                    return {'generation': bridge.generation, 'cursor': 0, 'reset': True, 'events': []}
                service._stop.set()
                return {}
        service.transport = Transport()
        service._run()
        self.assertEqual(['generation-1', 'generation-2'], polls)
        self.assertEqual('generation-2', service._features_generation)
        self.assertEqual(set(OPTIONAL), service._features)

    def test_image_input_is_native_content_not_text_and_order_is_preserved(self):
        url = 'data:image/png;base64,' + base64.b64encode(self.bridge.raw).decode()
        items = [{'type': 'text', 'text': 'before'}, {'type': 'image', 'url': url}, {'type': 'text', 'text': 'after'}]
        self.service.send('s', '', client_message_id='image-1', input_items=items)
        content = self.bridge.prompts['image-1']['content']
        self.assertEqual(['text', 'image', 'text'], [item['type'] for item in content])
        self.assertEqual(self.bridge.raw, base64.b64decode(content[1]['data']))
        self.assertNotIn('url', content[1])
        self.assertEqual(url, items[1]['url'])

    def test_invalid_image_and_unowned_path_fail_before_upload_or_prompt(self):
        staged = self.service.upload_attachment_stream('s', 'a.txt', io.BytesIO(b'a'), 1)
        inputs = [
            [staged, {'type': 'image', 'url': 'data:image/png;base64,aGVsbG8='}],
            [{'type': 'mention', 'name': 'secret', 'path': '/etc/passwd'}],
            [{'type': 'image', 'url': 'https://example.org/image.png'}],
        ]
        for index, items in enumerate(inputs):
            with self.assertRaises(CodexError): self.service.send('s', '', client_message_id=str(index), input_items=items)
        self.assertEqual([], self.bridge.uploads)
        self.assertEqual({}, self.bridge.prompts)

    def test_session_and_project_upload_scopes_native_receipts_are_request_bound(self):
        staged = self.service.upload_project_attachment_stream('p', 'report.txt', io.BytesIO(b'report'), 6)
        self.assertTrue(staged['path'].startswith('dsh-upload:'))
        self.assertEqual([], self.bridge.uploads)
        self.service.send('s', '', client_message_id='first', input_items=[staged])
        self.service.send('s', '', client_message_id='first', input_items=[staged])
        self.assertEqual(1, len(self.bridge.uploads))
        self.assertEqual('receipt-1', self.bridge.prompts['first']['content'][0]['receiptId'])
        self.service.send('s', '', client_message_id='second', input_items=[staged])
        self.assertEqual(2, len(self.bridge.uploads))
        self.assertEqual('receipt-2', self.bridge.prompts['second']['content'][0]['receiptId'])
        with self.assertRaises(CodexError) as caught:
            self.service.send('another', '', client_message_id='wrong', input_items=[staged])
        self.assertEqual('attachment_scope', caught.exception.code)
        session_file = self.service.upload_attachment_stream('s', 'note.txt', io.BytesIO(b'x'), 1)
        with self.assertRaises(CodexError): self.service.send('another', '', client_message_id='wrong2', input_items=[session_file])

    def test_media_download_is_exact_locator_verified_bytes_and_range(self):
        source = source_url('s', 2, 1, 3, media_type='image/png')
        result = self.service.prepare_download(source, 'bytes=2-5')
        self.assertEqual(self.bridge.raw[2:6], b''.join(result['chunks']))
        self.assertTrue(result['partial'])
        self.assertIn(('media', {'sessionId': 's', 'eventSeq': 2, 'blockIndex': 1, 'imageIndex': 3}), self.bridge.calls)
        whole = self.service.prepare_download(source, 'bytes=2-5', 'stale-etag')
        self.assertEqual(self.bridge.raw, b''.join(whole['chunks']))
        self.assertFalse(whole['partial'])

    def test_media_changed_generation_or_digest_cannot_be_downloaded(self):
        result = self.bridge.rpc('media', {})
        for update in ({'generation': 'different'}, {'sha256': '0' * 64}, {'size': len(self.bridge.raw) + 1}):
            with self.subTest(update=update), self.assertRaises(CodexError):
                download({**result, **update}, generation=self.bridge.generation)


class DshMediaProjectionTests(unittest.TestCase):
    def test_source_canonicalization_and_network_lookalikes(self):
        source = source_url('session /中文', 3, 2, 1, media_type='image/jpeg')
        self.assertTrue(is_media_source(source))
        self.assertEqual('session /中文', parse_source(source)['sessionId'])
        for bad in (source.replace('https:', 'http:'), source.replace('.invalid/', '.invalid:443/'),
                    source.replace('.invalid/', '.invalid.evil/'), source.replace('https://', 'https://a@'),
                    source + '?x=1', source + '#fragment', source.replace('/3/', '/03/'),
                    source.replace('%2F', '%2f'), '/tmp/image.png'):
            with self.subTest(bad=bad): self.assertFalse(is_media_source(bad))
        for mime, extension in [('image/bmp', 'bmp'), ('image/x-icon', 'ico'),
                                ('image/svg+xml', 'svg'), ('image/avif', 'avif')]:
            source = source_url('s', 3, 2, 1, media_type=mime)
            self.assertTrue(source.endswith('image.' + extension))
            self.assertTrue(is_media_source(source))

    def test_user_assistant_and_tool_images_have_distinct_native_positions(self):
        events = [event(0, 'turn/start', {'turn': 1}),
            event(1, 'user/message', {'source': {'kind': 'user'}, 'content': [{'type': 'text', 'text': 'before'}, {'type': 'image', 'attachment': 'native-ref'}, {'type': 'text', 'text': 'after'}]}),
            event(2, 'assistant/message', {'turn': 1, 'message': {'content': [{'type': 'image', 'attachment': 'other'}]}}),
            event(3, 'tool/call', {'turn': 1, 'callId': 'tool', 'name': 'read', 'arguments': '{}'}),
            event(4, 'tool/result', {'turn': 1, 'message': {'toolCallId': 'tool', 'content': [{'type': 'image', 'attachment': 'tool-ref'}]}})]
        media = [{'eventSeq': seq, 'blockIndex': block, 'imageIndex': 0, 'kind': 'attachment', 'mediaType': 'image/png'}
                 for seq, block in ((1, 1), (2, 0), (4, 0))]
        items = project(events, session_id='s', media=media)[0]['items']
        self.assertEqual(['text', 'image', 'text'], [part['type'] for part in items[0]['content']])
        self.assertEqual(source_url('s', 1, 1), items[0]['content'][1]['url'])
        self.assertIn(source_url('s', 2, 0), items[1]['text'])
        self.assertEqual('dynamicToolCall', items[2]['type'])
        self.assertTrue(items[3]['text'].startswith('工具返回图片'))
        self.assertIn(source_url('s', 4, 0), items[3]['text'])
        self.assertNotIn('native-ref', str(items))

    def test_markdown_multiple_images_use_codepoint_offsets_and_keep_context(self):
        body = '🙂 中文 ![第一张](/tmp/a.png) between ![第二张](/tmp/b.png) end'
        media = [{'eventSeq': 1, 'blockIndex': 0, 'imageIndex': index, 'kind': 'markdown',
                  'start': body.index(path), 'end': body.index(path) + len(path), 'name': path}
                 for index, path in enumerate(('/tmp/a.png', '/tmp/b.png'))]
        events = [event(0, 'turn/start', {'turn': 1}),
                  event(1, 'assistant/message', {'message': {'content': [{'type': 'text', 'text': body}]}})]
        text = project(events, session_id='s', media=media)[0]['items'][0]['text']
        self.assertEqual(body.replace('/tmp/a.png', source_url('s', 1, 0, 0)).replace('/tmp/b.png', source_url('s', 1, 0, 1)), text)
        media[0]['end'] = len(body) + 1
        with self.assertRaises(CodexError): project(events, session_id='s', media=media)


class DshUploadStoreTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.now = 100000.0
        self.store = UploadStore(self.directory.name, clock=lambda: self.now)

    def test_unknown_upload_survives_restart_without_automatic_duplicate(self):
        staged = self.store.stage({'sessionId': 's'}, 'file.txt', io.BytesIO(b'x'), 1)
        calls = []
        def upload(raw, name):
            calls.append((raw, name))
            raise CodexError('reply lost', request_sent=True)
        with self.assertRaises(CodexError) as caught:
            self.store.receipt(staged['path'], 's', 'request', project_contains=lambda *_: False, upload=upload)
        self.assertTrue(caught.exception.definite)  # Prompt never dispatched.
        restored = UploadStore(self.directory.name, clock=lambda: self.now)
        with self.assertRaises(CodexError) as caught:
            restored.receipt(staged['path'], 's', 'request', project_contains=lambda *_: False, upload=upload)
        self.assertEqual('attachment_upload_unknown', caught.exception.code)
        self.assertEqual(1, len(calls))

    def test_size_budget_expiry_and_incomplete_upload_only_remove_owned_staging(self):
        sentinel = Path(self.directory.name) / 'keep.txt'
        sentinel.write_text('not owned')
        with patch('rivo_relay.dsh.uploads.MAX_STAGED_BYTES', 2):
            staged = self.store.stage({'sessionId': 's'}, 'file.txt', io.BytesIO(b'12'), 2)
            with self.assertRaises(CodexError): self.store.stage({'sessionId': 's'}, 'next.txt', io.BytesIO(b'x'), 1)
            self.now += 86401
            with self.assertRaises(CodexError): self.store.receipt(staged['path'], 's', 'r', project_contains=lambda *_: False, upload=lambda *_: {})
            self.store.stage({'sessionId': 's'}, 'new.txt', io.BytesIO(b'x'), 1)
            with self.assertRaises(CodexError): self.store.stage({'sessionId': 's'}, 'short.txt', io.BytesIO(b''), 1)
        self.assertEqual('not owned', sentinel.read_text())
        self.assertEqual(1, len(list(Path(self.directory.name).glob('*.bin'))))
        self.assertEqual(0o600, next(Path(self.directory.name).glob('*.bin')).stat().st_mode & 0o777)


class DshFeatureHttpTests(RelayHTTPFixture, unittest.TestCase):
    def _start(self, bus=None):
        test_dsh.DshHttpTests._start(self, bus)
        self.bridge = FeaturesBridge()
        self.bridge_server.bridge = self.bridge
        self.native._refresh_status()

    def test_models_settings_project_upload_and_mixed_send_through_http(self):
        status, models, _ = self.request('GET', '/v1/models', token=self.token)
        self.assertEqual(200, status)
        model = models['models'][1]['model']
        status, changed, _ = self.request('POST', '/v1/threads/s/settings',
            {'submissionId': 'setting', 'model': model, 'effort': 'off'}, token=self.token)
        self.assertEqual(200, status)
        self.assertEqual('accepted', changed['status'])
        status, staged, _ = self.request('POST', '/v1/projects/p/attachments?name=report.txt',
            raw=b'report', token=self.token, extra_headers={'Content-Type': 'application/octet-stream'})
        self.assertEqual(200, status)
        url = 'data:image/png;base64,' + base64.b64encode(self.bridge.raw).decode()
        payload = {'submissionId': 'mixed', 'model': model, 'effort': 'off',
                   'input': [{'type': 'text', 'text': 'read these'}, staged, {'type': 'image', 'url': url}]}
        first = self.request('POST', '/v1/threads/s/turns', payload, token=self.token)
        again = self.request('POST', '/v1/threads/s/turns', payload, token=self.token)
        self.assertEqual(200, first[0])
        self.assertEqual('input-accepted', first[1]['receipt']['kind'])
        self.assertEqual(first[1], again[1])
        self.assertEqual(1, len(self.bridge.uploads))
        self.assertEqual(1, len(self.bridge.prompts))
        self.assertEqual(['text', 'file', 'image'], [item['type'] for item in self.bridge.prompts['mixed']['content']])

    def test_history_media_download_uses_bridge_without_general_files_permission(self):
        self.bridge.events[2]['data']['message']['content'] = [{'type': 'image', 'attachment': 'native-ref'}]
        self.bridge.media = [{'eventSeq': 2, 'blockIndex': 0, 'imageIndex': 0,
                              'kind': 'attachment', 'mediaType': 'image/png'}]
        status, snapshot, _ = self.request('GET', '/v1/threads/s', token=self.token)
        self.assertEqual(200, status)
        self.assertTrue(snapshot['capabilities']['operations']['media.read']['available'])
        self.assertFalse(snapshot['capabilities']['operations']['files.read']['available'])
        source = source_url('s', 2, 0)
        self.assertIn(source, snapshot['thread']['turns'][0]['items'][1]['text'])
        connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=4)
        try:
            with patch.object(self.app.media, 'fetch_public', side_effect=AssertionError('virtual media must never use network')):
                connection.request('GET', '/v1/media/content?source=' + quote(source, safe=''),
                    headers={'Authorization': 'Bearer ' + self.token, 'Range': 'bytes=1-8'})
                response = connection.getresponse()
                self.assertEqual(206, response.status)
                self.assertEqual(self.bridge.raw[1:9], response.read())
        finally:
            connection.close()
        status, _, _ = self.request('GET', '/v1/files?path=/etc/passwd', token=self.token)
        self.assertEqual(409, status)
