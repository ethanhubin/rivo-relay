import io
import json
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
from rivo_relay.codex.predictions import predict_message

class PredictionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        home = Path(self.temp.name)
        (home / 'auth.json').write_text(json.dumps({'tokens': {'access_token': 'test'}}))
        self.snapshot = {'thread': {'turns': [{'id': 'turn'}]}, 'settings': {}}
        self.service = SimpleNamespace(_assert_workspace=Mock(), get_session=Mock(return_value=self.snapshot),
            writers=SimpleNamespace(home=home), connection=SimpleNamespace(generation=1, sample_prediction=Mock(return_value={'text': '{"suggestion":"继续测试"}'})),
            _prediction_lock=threading.Lock(), _prediction_cache={}, _prediction_epochs={}, _prediction_inflight=set())

    def test_official_config_sample_and_cache(self):
        with patch('rivo_relay.codex.predictions.urlopen', return_value=io.BytesIO(b'{"is_enabled":true,"prompt":"predict"}')) as fetch:
            self.assertEqual('继续测试', predict_message(self.service, 'thread', 'turn')['text'])
            self.assertEqual('继续测试', predict_message(self.service, 'thread', 'turn')['text'])
            fetch.assert_called_once()
        params = self.service.connection.sample_prediction.call_args.args[0]
        self.assertEqual('turn', params['expectedTurnId'])
        self.assertTrue(params['disableMcp'])
        self.assertIn('Return only JSON', params['prompt'])
        self.snapshot['activeTurnId'] = 'new'
        self.assertIsNone(predict_message(self.service, 'thread', 'turn')['text'])

    def test_stale_or_active_never_samples(self):
        for change in ({'activeTurnId': 'turn'}, {'thread': {'turns': [{'id': 'new'}]}}):
            self.service.get_session.return_value = {**self.snapshot, **change}
            self.assertIsNone(predict_message(self.service, 'thread', 'turn')['text'])
        self.service.connection.sample_prediction.assert_not_called()

    def test_unavailable_config_fails_silently(self):
        with patch('rivo_relay.codex.predictions.urlopen', side_effect=OSError('offline')):
            self.assertIsNone(predict_message(self.service, 'thread', 'turn')['text'])
        self.service.connection.sample_prediction.assert_not_called()

    def test_sampling_never_creates_writer(self):
        from rivo_relay.codex.owned_writer import SessionWriters
        from test_owned_writer import Backend
        backend = Backend()
        pool = SessionWriters(backend)
        self.addCleanup(pool.close)
        def request(method, params, **kwargs):
            if method == 'thread/loaded/list': return {'data': ['loaded']}
            self.assertEqual('thread/sample', method)
            return {'text': '{"suggestion":"next"}'}
        with patch.object(backend, 'request', side_effect=request), patch.object(pool, '_ensure_metadata'), patch.object(pool, '_new_writer', side_effect=AssertionError('no takeover')):
            self.assertEqual({}, pool.sample_prediction({'threadId': 'missing'}))
            self.assertIn('text', pool.sample_prediction({'threadId': 'loaded'}))
            self.assertFalse(pool.owns('loaded'))

    def test_failure_retries_after_recovery(self):
        with patch('rivo_relay.codex.predictions.urlopen', side_effect=OSError('offline')):
            self.assertIsNone(predict_message(self.service, 'thread', 'turn')['text'])
        with patch('rivo_relay.codex.predictions.urlopen', return_value=io.BytesIO(b'{"is_enabled":true,"prompt":"predict"}')):
            self.assertEqual('继续测试', predict_message(self.service, 'thread', 'turn')['text'])

    def test_plan_and_account_change_do_not_reuse_cache(self):
        with patch('rivo_relay.codex.predictions.urlopen', side_effect=lambda *a, **k: io.BytesIO(b'{"is_enabled":true,"prompt":"predict"}')) as fetch:
            predict_message(self.service, 'thread', 'turn')
            self.snapshot['settings'] = {'collaborationMode': {'mode': 'plan'}}
            self.assertIsNone(predict_message(self.service, 'thread', 'turn')['text'])
            self.snapshot['settings'] = {}
            (self.service.writers.home / 'auth.json').write_text(json.dumps({'tokens': {'access_token': 'new-account'}}))
            predict_message(self.service, 'thread', 'turn')
            self.assertEqual(2, fetch.call_count)

    def test_new_turn_during_sample_discards_result(self):
        def sample(params):
            self.service._prediction_epochs['thread'] = 1
            return {'text': '{"suggestion":"old"}'}
        self.service.connection.sample_prediction.side_effect = sample
        with patch('rivo_relay.codex.predictions.urlopen', return_value=io.BytesIO(b'{"is_enabled":true,"prompt":"predict"}')):
            self.assertIsNone(predict_message(self.service, 'thread', 'turn')['text'])
        self.assertFalse(self.service._prediction_cache)

    def test_different_threads_are_not_serialized(self):
        entered = threading.Event()
        release = threading.Event()
        def sample(params):
            if params['threadId'] == 'first':
                entered.set()
                release.wait(3)
            return {'text': '{"suggestion":"next"}'}
        self.service.connection.sample_prediction.side_effect = sample
        with patch('rivo_relay.codex.predictions.urlopen', side_effect=lambda *a, **k: io.BytesIO(b'{"is_enabled":true,"prompt":"predict"}')):
            worker = threading.Thread(target=predict_message, args=(self.service, 'first', 'turn'))
            worker.start()
            try:
                self.assertTrue(entered.wait(2))
                self.assertEqual('next', predict_message(self.service, 'second', 'turn')['text'])
                self.assertIsNone(predict_message(self.service, 'first', 'turn')['text'])
            finally:
                release.set()
                worker.join(3)

    def test_native_timeout_and_disconnect_send_exact_cancel_id(self):
        from rivo_relay.codex.connection import NativeConnection, CodexError
        from rivo_relay.read_budget import ReadStopped, read_budget
        for method in ('thread/sample', 'prediction'):
            for disconnected in (False, True):
                native = NativeConnection()
                frames = []
                native._socket = SimpleNamespace(send=lambda raw: frames.append(json.loads(raw)))
                if disconnected:
                    with self.assertRaises(ReadStopped), read_budget(seconds=.01):
                        native.request(method, {'threadId': 'test'}, timeout=1)
                else:
                    with self.assertRaises(CodexError):
                        native.request(method, {'threadId': 'test'}, timeout=.01)
                self.assertEqual(method + '/cancel', frames[-1]['method'])
                self.assertEqual(frames[0]['id'], frames[-1]['params']['requestId'])
                self.assertFalse(native._pending)
