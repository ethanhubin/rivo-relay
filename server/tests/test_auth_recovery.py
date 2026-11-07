import importlib.util
import json
from pathlib import Path
import unittest

from rivo_relay.codex.connection import CodexError, NativeConnection

spec = importlib.util.spec_from_file_location('reauthorize', Path(__file__).resolve().parents[2] / 'scripts/reauthorize-codex.py')
reauthorize = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reauthorize)


class AuthRecoveryTests(unittest.TestCase):
    def test_revoked_auth_is_distinct_from_phone_pairing_and_never_replayed(self):
        connection = NativeConnection()
        sent = []
        class Socket:
            def send(self, raw):
                request = json.loads(raw)
                sent.append(request)
                connection._pending[request['id']].put({'error': {
                    'code': -32000, 'message': '401 Unauthorized token_revoked'}})
            def close(self): pass
        connection._socket = Socket()
        try:
            with self.assertRaises(CodexError) as failure:
                connection.request('turn/start', {'threadId': 'thread'})
            self.assertEqual('native_auth_required', failure.exception.code)
            self.assertNotEqual(401, failure.exception.http_status)
            self.assertEqual(1, len(sent))
        finally:
            connection.close()

    def test_restart_check_visits_all_pages_and_refuses_active_thread(self):
        class Connection:
            def request(self, method, params):
                if method == 'thread/loaded/list':
                    return {'data': ['active'], 'nextCursor': None} if params else {'data': ['idle'], 'nextCursor': 'next'}
                return {'thread': {'status': {'type': params['threadId']}, 'turns': []}}
        with self.assertRaises(RuntimeError):
            reauthorize.require_idle(Connection())

    def test_restart_check_refuses_unfinished_turn_even_when_status_is_idle(self):
        class Connection:
            def request(self, method, params):
                if method == 'thread/loaded/list': return {'data': ['t']}
                return {'thread': {'status': {'type': 'idle'}, 'turns': [{'status': 'inProgress'}]}}
        with self.assertRaises(RuntimeError):
            reauthorize.require_idle(Connection())
