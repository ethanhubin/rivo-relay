import base64
import copy
import json
import unittest
from contextlib import nullcontext
from unittest.mock import patch

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.image_inputs import prepare_image_inputs, MAX_IMAGE_BYTES, MAX_TOTAL_IMAGE_BYTES
from rivo_relay.codex.sessions import CodexService
from relay_test_support.native import FakeNative
from relay_test_support.media import png


def image(raw=None, mime='image/png'):
    return {'type': 'image', 'url': 'data:' + mime + ';base64,' + base64.b64encode(raw or png()).decode()}


class ImageInputTests(unittest.TestCase):
    def setUp(self):
        def auxiliary(native):
            helper = copy.copy(native)
            helper.auxiliary_connection = None
            helper.native_host_info = lambda: {'codexHome': '/remote/native/.codex'}
            return nullcontext(helper)
        lease = patch.object(FakeNative, 'auxiliary_connection', auxiliary, create=True)
        lease.start()
        self.addCleanup(lease.stop)

    def test_native_turn_and_steer_keep_client_identity_stage_on_actual_host(self):
        for operation in ('send', 'steer'):
            native = FakeNative()
            native.thread['cwd'] = '/remote/native/project'
            service = CodexService(connection=native)
            inputs = [{'type': 'text', 'text': 'explain'}, image()]
            original = copy.deepcopy(inputs)
            if operation == 'send': service.send('thread', '', client_message_id='canonical-client-id', input_items=inputs)
            else: service.steer('thread', '', 'turn', client_message_id='canonical-client-id', input_items=inputs)
            params = next(p for method, p in native.calls if method == ('turn/start' if operation == 'send' else 'turn/steer'))
            self.assertEqual(original, inputs)
            self.assertEqual('canonical-client-id', params['clientUserMessageId'])
            self.assertEqual('localImage', params['input'][1]['type'])
            self.assertTrue(params['input'][1]['path'].startswith('/remote/native/.codex/rivo-relay-attachments/'))
            writes = [p for method, p in native.calls if method == 'fs/writeFile']
            self.assertEqual(png(), base64.b64decode(writes[0]['dataBase64']))
            self.assertFalse({'sandboxPolicy', 'approvalPolicy'} & params.keys())

    def test_large_image_native_writes_are_chunked_below_socket_budget(self):
        native = FakeNative()
        original_request = native.request
        def request(method, params, **kwargs):
            result = original_request(method, params, **kwargs)
            return {'exitCode': 0} if method == 'command/exec' else result
        native.request = request
        raw = png() + b'\0' * (9 * 1024 * 1024)
        result = prepare_image_inputs(native, '/remote/project', [image(raw)])
        self.assertEqual('localImage', result[0]['type'])
        writes = [p for method, p in native.calls if method == 'fs/writeFile']
        self.assertGreater(len(writes), 1)
        self.assertTrue(all(len(json.dumps(p)) < 6 * 1024 * 1024 for p in writes))
        self.assertTrue(any(method == 'command/exec' for method, _ in native.calls))

    def test_format_invalid_base64_count_and_per_image_size_fail_before_any_writes(self):
        self.assertEqual(20 * 1024 * 1024, MAX_IMAGE_BYTES)
        self.assertEqual(40 * 1024 * 1024, MAX_TOTAL_IMAGE_BYTES)
        cases = [[image(mime='image/jpeg')], [image(b'not an image')], [image()] * 11,
                 [{'type': 'image', 'url': 'data:image/png;base64,invalid!'}],
                 [image(png() + b'\0' * MAX_IMAGE_BYTES)]]
        for inputs in cases:
            native = FakeNative()
            with self.assertRaises(CodexError): prepare_image_inputs(native, '/native/project', inputs)
            self.assertEqual([], native.calls)

    def test_aggregate_limit_validates_whole_batch_before_first_upload(self):
        native = FakeNative()
        # Full-size regression: three individually valid 15 MiB PNGs exceed 40 MiB.
        raw = png() + b'\0' * (15 * 1024 * 1024)
        item = image(raw)
        with self.assertRaises(CodexError) as failure:
            prepare_image_inputs(native, '/native/project', [item, item, item])
        self.assertEqual('images_too_large', failure.exception.code)
        self.assertEqual([], native.calls)

    def test_failed_batch_cleans_only_newly_staged_directories(self):
        native = FakeNative()
        calls = []
        def upload(connection, cwd, name, stream, size):
            calls.append(name)
            if len(calls) == 2: raise CodexError('synthetic upload failure')
            return {'path': '/remote/native/.codex/rivo-relay-attachments/project-scope/new-uuid/image.png'}
        with patch('rivo_relay.codex.image_inputs.upload_attachment_stream', side_effect=upload):
            with self.assertRaises(CodexError): prepare_image_inputs(native, '/remote/project', [image(), image()])
        self.assertEqual([('fs/remove', {'path': '/remote/native/.codex/rivo-relay-attachments/project-scope/new-uuid', 'recursive': True})], native.calls)


if __name__ == '__main__':
    unittest.main()
