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
            writers=SimpleNamespace(home=home), connection=SimpleNamespace(sample_prediction=Mock(return_value={'text': '{"suggestion":"继续测试"}'})),
            _prediction_lock=threading.Lock(), _prediction_cache={})

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
