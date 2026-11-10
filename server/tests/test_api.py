"""Real local HTTP + temporary SQLite with a controlled native stub.

These tests verify Relay contracts, never claim Desktop or emulator acceptance.
No user's Codex installation, credentials, projects, or services are accessed.
"""
from relay_test_support.http import NativeFailure, RelayHTTPFixture

from concurrent.futures import ThreadPoolExecutor
from contextlib import nullcontext
import http.client
import base64
import json
from pathlib import Path
import threading
import time
import unittest
from unittest.mock import Mock, patch

from rivo_relay.api import Handler
from rivo_relay.codex.sessions import CodexService
from rivo_relay.codex.interactions import Interactions
from rivo_relay.events import EventBus
from rivo_relay.pairing import PairingStore


class PausingEventBus(EventBus):
    """Pause just after the SSE handler's initial authorization check."""
    def __init__(self):
        super().__init__()
        self.entered = threading.Event()
        self.resume = threading.Event()
        self.pause_next = True

    def read(self, cursor, timeout=15):
        if cursor is not None and self.pause_next:
            self.pause_next = False
            self.entered.set()
            self.resume.wait(3)
            timeout = 0
        return super().read(cursor, timeout)

    def close(self):
        self.resume.set()
        super().close()


class ApiTests(RelayHTTPFixture, unittest.TestCase):

    def test_route_families_preserve_authentication_and_unknown_endpoints(self):
        paths = (
            '/v1/node/unknown', '/v1/media/unknown', '/v1/speech/unknown',
            '/v1/files/unknown', '/v1/projects/unknown/unknown',
            '/v1/threads/unknown/unknown', '/v1/todos/unknown/unknown',
            '/v1/submissions/unknown/unknown', '/v1/unknown',
            '/v2/projects', '/projects', '/v1',
        )
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual(401, self.request('GET', path)[0])
                status, result, _ = self.request('GET', path, token=self.token)
                self.assertEqual(404, status)
                self.assertEqual({'code': 'not_found', 'message': 'Unknown endpoint'}, result['error'])

    def test_unknown_thread_operation_keeps_validation_order(self):
        path = '/v1/threads/thread-1/unknown'
        for body, expected_status, message in (
            ({}, 400, 'submissionId is required'),
            ({'submissionId': 'unknown-operation'}, 404, 'Unknown operation'),
        ):
            with self.subTest(body=body):
                status, result, _ = self.request('POST', path, body, token=self.token)
                self.assertEqual(expected_status, status)
                self.assertEqual(message, result['error']['message'])

    def test_attachment_routes_reject_json_after_authentication(self):
        for resource in ('threads', 'projects'):
            path = f'/v1/{resource}/target-1/attachments'
            with self.subTest(resource=resource):
                self.assertEqual(401, self.request('POST', path, {}, token=None)[0])
                status, result, _ = self.request('POST', path, {'name': 'a', 'dataBase64': 'YQ=='}, token=self.token)
                self.assertEqual(415, status)
                self.assertEqual('invalid_request', result['error']['code'])

    def test_sites_requires_auth_and_exposes_only_list(self):
        self.app.codex.connection = Mock()
        with patch('rivo_relay.codex.sites.list_sites', return_value={'items': [], 'cursor': None}) as sites:
            self.assertEqual(401, self.request('GET', '/v1/sites')[0])
            sites.assert_not_called()
            status, result, _ = self.request('GET', '/v1/sites?cursor=opaque', token=self.token)
            self.assertEqual(200, status)
            self.assertEqual([], result['items'])
            self.assertEqual('opaque', sites.call_args.args[1])
            self.assertEqual(404, self.request('POST', '/v1/sites', {}, token=self.token)[0])
            self.assertEqual(1, sites.call_count)

    def test_update_lease_requires_local_agent_and_blocks_new_requests(self):
        self.assertEqual(403, self.request('POST', '/internal/update-lease', {})[0])
        self.app.codex.idle_for_update = lambda: True
        with patch.dict('os.environ', {'RIVO_UPDATE_TOKEN': 'test-agent-only'}):
            self.assertEqual(403, self.request('POST', '/internal/update-lease', {}, token=self.token)[0])
            self.assertEqual(200, self.request('POST', '/internal/update-lease', {}, token='test-agent-only')[0])
            self.assertEqual(503, self.request('GET', '/v1/node', token=self.token)[0])
            self.assertEqual(200, self.request('GET', '/health')[0])
            self.assertEqual(200, self.request('POST', '/internal/update-activate', {}, token='test-agent-only')[0])
            self.assertEqual(200, self.request('GET', '/v1/node', token=self.token)[0])

    def test_update_status_requires_authentication_and_is_sanitized(self):
        directory = self.config.workspace / 'auto-update'
        directory.mkdir()
        (directory / 'status.json').write_text(json.dumps({'phase': 'waiting_idle',
            'reason': 'submission_pending_or_unknown', 'token': 'private', 'python': '/private'}))
        self.assertEqual(self.request('GET', '/v1/node-updates/status')[0], 401)
        status, data, _ = self.request('GET', '/v1/node-updates/status', token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(data['reason'], 'submission_pending_or_unknown')
        self.assertNotIn('private', json.dumps(data))

    def test_expired_stop_permit_does_not_shutdown(self):
        self.app.codex.idle_for_update = lambda: True
        with patch.dict('os.environ', {'RIVO_UPDATE_TOKEN': 'test-agent-only'}):
            status, permit, _ = self.request('POST', '/internal/update-lease', {}, token='test-agent-only')
            self.assertEqual(status, 200)
            self.app.update_gate.until = 0
            with patch.object(self.server, 'shutdown') as stop:
                status, result, _ = self.request('POST', '/internal/update-stop',
                    {'lease': permit['lease']}, token='test-agent-only')
                self.assertEqual(status, 409)
                self.assertEqual(result['reason'], 'lease_expired')
                stop.assert_not_called()
            self.assertEqual(self.request('GET', '/health')[0], 200)

    def test_node_rename_preserves_existing_authorization(self):
        self.assertEqual('test-machine', self.paired['node']['name'])
        with patch('rivo_relay.node_name.discover_name', return_value='renamed-tailnet-node'):
            self.app.node_name._expires = 0
            status, node, _ = self.request('GET', '/v1/node', token=self.token)
        self.assertEqual(200, status)
        self.assertEqual('renamed-tailnet-node', node['name'])
        self.assertEqual(self.paired['node']['id'], node['id'])
        self.assertEqual(401, self.request('GET', '/v1/node')[0])

    def test_mobile_session_and_history_strip_command_payloads(self):
        command = {"id": "cmd", "type": "commandExecution", "status": "completed",
                   "exitCode": 0, "command": "private command", "aggregatedOutput": "x" * 100000}
        turn = {"id": "turn", "items": [command]}
        approval = {"key": "approval", "method": "item/commandExecution/requestApproval",
                    "params": {"command": "private command"}}
        self.app.codex.get_session = lambda *args, **kwargs: {
            "thread": {"id": "thread-1", "turns": [turn]}, "requests": [approval]}
        self.app.codex.history = lambda *args, **kwargs: {"turns": [turn], "nextCursor": "next"}
        for suffix in ("", "/history"):
            status, result, _ = self.request("GET", "/v1/threads/thread-1" + suffix, token=self.token)
            self.assertEqual(status, 200)
            turns = result["turns"] if suffix else result["thread"]["turns"]
            self.assertEqual(turns[0]["items"][0], {
                "id": "cmd", "type": "commandExecution", "status": "completed", "exitCode": 0})
            if not suffix:
                self.assertEqual(result["requests"], [approval])
            else:
                self.assertEqual(result["nextCursor"], "next")
        self.assertEqual(command["command"], "private command")
        self.assertEqual(len(command["aggregatedOutput"]), 100000)

    def test_generated_image_registered_before_compaction_and_failed_registration_keeps_bytes(self):
        item = {'id': 'image', 'type': 'imageGeneration', 'status': 'completed', 'result': 'A' * 10000}
        turn = {'id': 'turn', 'status': 'completed', 'items': [item]}
        self.app.codex.get_session = lambda *args, **kwargs: {'thread': {'id': 'thread-1', 'turns': [turn]}}
        self.app.codex.history = lambda *args, **kwargs: {'turns': [turn], 'nextCursor': 'next'}
        def observe(thread):
            self.assertEqual('thread-1', thread['id'])
            self.assertEqual('A' * 10000, thread['turns'][0]['items'][0]['result'])
        for suffix in ('', '/history'):
            with patch.object(self.app.media, 'observe_thread', side_effect=observe) as registered:
                status, result, _ = self.request('GET', '/v1/threads/thread-1' + suffix, token=self.token)
            self.assertEqual(200, status)
            registered.assert_called_once()
            projected = (result if suffix else result['thread'])['turns'][0]['items'][0]
            self.assertTrue(projected['mediaRef'])
            self.assertTrue(projected['result'].startswith('/v1/media/library/'))
            with patch.object(self.app.media, 'observe_thread', side_effect=OSError('fixture')):
                status, result, _ = self.request('GET', '/v1/threads/thread-1' + suffix, token=self.token)
            self.assertEqual(200, status)
            self.assertEqual(item, (result if suffix else result['thread'])['turns'][0]['items'][0])

    def test_unchanged_head_omits_large_history_but_rechecks_control(self):
        calls = []
        source = {"thread": {"id": "thread-1", "turns": [{"id": "turn", "items": [
            {"id": "answer", "type": "agentMessage", "text": "large reply " * 100000}]}]},
            "nativeHeadRevision": "content-1", "nativeRevision": "history-1", "nativeSync": {"state": "ready"},
            "control": {"canOperate": True}, "requests": [], "nextCursor": "older"}
        def read(*args, **kwargs):
            calls.append(kwargs)
            return dict(source)
        self.app.codex.get_session = read
        status, full, _ = self.request("GET", "/v1/threads/thread-1?limit=2", token=self.token)
        self.assertEqual(200, status)
        source["control"] = {"canOperate": False, "state": "external"}
        source["requests"] = [{"key": "new-approval"}]
        path = "/v1/threads/thread-1?limit=2&knownHeadRevision=content-1"
        status, compact, _ = self.request("GET", path, token=self.token)
        self.assertEqual(200, status)
        self.assertTrue(compact["historyUnchanged"])
        self.assertNotIn("turns", compact["thread"])
        self.assertEqual(source["control"], compact["control"])
        self.assertEqual(source["requests"], compact["requests"])
        self.assertEqual("older", compact["nextCursor"])
        self.assertLess(len(json.dumps(compact)), len(json.dumps(full)) // 100)
        self.assertEqual(2, len(calls))
        self.assertEqual(2, calls[-1]["limit"])
        for suffix in ("&cursor=older", "&limit=30"):
            # Use an actual larger page parameter rather than duplicate query keys.
            changed = path.replace("limit=2", "limit=30") if suffix == "&limit=30" else path + suffix
            _, result, _ = self.request("GET", changed, token=self.token)
            self.assertIn("turns", result["thread"])
        source["nativeSync"] = {"state": "converging"}
        self.assertIn("turns", self.request("GET", path, token=self.token)[1]["thread"])
        source["nativeSync"] = {"state": "ready"}
        source["nativeHeadRevision"] = "content-2"
        self.assertIn("turns", self.request("GET", path, token=self.token)[1]["thread"])
        self.assertIn("turns", source["thread"])

    def test_public_updates_are_scoped_to_published_artifacts(self):
        import hashlib
        root = self.config.workspace / 'updates'
        (root / 'apks').mkdir(parents=True)
        payload = b'update fixture'
        digest = hashlib.sha256(payload).hexdigest()
        (root / 'apks' / (digest + '.apk')).write_bytes(payload)
        (root / 'latest.json').write_text(json.dumps({'version': '0.2.13', 'versionCode': 25,
            'sha256': digest, 'size': len(payload), 'notes': 'fixture'}))
        status, manifest, headers = self.request('GET', '/v1/app-updates/latest')
        self.assertEqual(200, status)
        self.assertEqual('no-store', headers['Cache-Control'])
        self.assertEqual(digest, manifest['sha256'])
        for range_header, expected, code in [(None, payload, 200), ('bytes=2-5', payload[2:6], 206)]:
            client = http.client.HTTPConnection('127.0.0.1', self.port, timeout=4)
            try:
                client.request('GET', manifest['apkPath'], headers={'Range': range_header} if range_header else {})
                response = client.getresponse()
                self.assertEqual(code, response.status)
                self.assertEqual(expected, response.read())
            finally:
                client.close()
        self.assertEqual(404, self.request('GET', '/v1/app-updates/apks/private.apk')[0])
        (root / 'apks' / (digest + '.apk')).write_bytes(b'changed')
        self.assertEqual(503, self.request('GET', '/v1/app-updates/latest')[0])
        self.assertFalse(self.native.created)

    def test_goal_capability_probe_requires_authentication(self):
        calls = []
        self.native.goal_capabilities = lambda: calls.append('probe') or {'goalSupported': True}
        self.assertEqual(401, self.request('GET', '/v1/goals/capabilities')[0])
        self.assertFalse(calls)
        status, body, _ = self.request('GET', '/v1/goals/capabilities', token=self.token)
        self.assertEqual(200, status)
        self.assertTrue(body['goalSupported'])
        self.assertEqual(['probe'], calls)
        self.assertFalse(self.native.created)

    def test_question_answer_requires_auth_and_is_deduplicated(self):
        path = '/v1/threads/thread-1/answers'
        body = {'submissionId': 'answer-one', 'expectedTurnId': 'turn',
                'answers': [{'questionItemId': 'question', 'answer': 'A'}]}
        calls = []
        def answer(thread, turn, answers, submission):
            calls.append((thread, turn, answers, submission))
            return {'threadId': thread, 'turnId': turn}
        self.native.answer_questions = answer
        self.assertEqual(401, self.request('POST', path, body)[0])
        first = self.request('POST', path, body, token=self.token)
        second = self.request('POST', path, body, token=self.token)
        self.assertEqual('accepted', first[1]['status'])
        self.assertEqual(first[:2], second[:2])
        self.assertEqual([('thread-1', 'turn', body['answers'], 'answer-one')], calls)
        self.assertEqual(400, self.request('POST', path, {'submissionId': 'missing'}, token=self.token)[0])

    def test_uncertain_question_answer_is_not_replayed(self):
        path = '/v1/threads/thread-1/answers'
        body = {'submissionId': 'answer-unknown', 'expectedTurnId': 'turn', 'answers': []}
        self.native.answer_questions = Mock(side_effect=TimeoutError('lost response'))
        first = self.request('POST', path, body, token=self.token)
        second = self.request('POST', path, body, token=self.token)
        self.assertEqual('unknown', first[1]['status'])
        self.assertEqual(first[:2], second[:2])
        self.assertEqual(1, self.native.answer_questions.call_count)

    def test_continue_empty_input_is_deduplicated_and_requires_target(self):
        path = '/v1/threads/thread-1/continue'
        body = {'submissionId': 'continue-one', 'expectedTurnId': 'interrupted-turn'}
        first = self.request('POST', path, body, token=self.token)
        second = self.request('POST', path, body, token=self.token)
        self.assertEqual('accepted', first[1]['status'])
        self.assertEqual(first[:2], second[:2])
        self.assertEqual(1, len(self.native.sent))
        thread, text, options = self.native.sent[0]
        self.assertEqual('', text)
        self.assertEqual([], options['input_items'])
        self.assertEqual('interrupted-turn', options['resume_turn_id'])
        self.assertEqual(400, self.request('POST', path, {'submissionId': 'missing'}, token=self.token)[0])

    def test_goal_required_turn_flag_is_validated_and_forwarded(self):
        path = '/v1/threads/thread-1/turns'
        body = {'submissionId': 'first-goal-turn', 'text': 'Task', 'requireGoalSupport': True}
        status, result, _ = self.request('POST', path, body, token=self.token)
        self.assertEqual(200, status)
        self.assertEqual('accepted', result['status'])
        self.assertTrue(self.native.sent[0][2]['require_goal_support'])
        for value in ('true', 1, None):
            status, _, _ = self.request('POST', path, {**body, 'requireGoalSupport': value}, token=self.token)
            self.assertEqual(400, status)
        self.assertEqual(1, len(self.native.sent))

    def test_interaction_without_optional_thread_identity_is_accepted_once(self):
        native = Mock(spec=['respond', 'generation'])
        native.generation = 'test-generation'
        interactions = Interactions(native)
        self.native.respond = interactions.respond
        for index, params in enumerate(({}, {'threadId': None}, {'conversationId': 'thread-1'})):
            interactions.receive({'id': index, 'method': 'item/commandExecution/requestApproval', 'params': params})
            key = 'test-generation:int:' + str(index)
            body = {'submissionId': 'answer-' + str(index), 'key': key, 'response': {'decision': 'decline'}}
            for _ in range(2):
                status, result, _ = self.request('POST', '/v1/requests/respond', body, token=self.token)
                self.assertEqual(200, status)
                self.assertEqual('accepted', result['status'])
                expected = {'requestId': key}
                if params.get('conversationId'): expected['threadId'] = 'thread-1'
                self.assertEqual(expected, result['receipt'])
            self.assertEqual(index + 1, native.respond.call_count)

    def test_native_goal_responses_are_accepted_and_receipt_polling_finishes(self):
        native = Mock(spec=['request'])
        service = CodexService(connection=native)
        self.native.update_goal = service.update_goal
        with patch.object(service, 'start'), patch.object(service, '_assert_workspace'), \
             patch.object(service, '_resume'), patch.object(service, 'operation_gate', side_effect=lambda _: nullcontext()):
            for operation in ('set', 'pause', 'resume', 'clear'):
                with self.subTest(operation=operation):
                    native.request.return_value = {} if operation == 'clear' else {
                        'goal': {'objective': 'Finish', 'status': 'paused' if operation == 'pause' else 'active'}}
                    sid = 'native-goal-' + operation
                    body = {'submissionId': sid, 'operation': operation}
                    if operation == 'set':
                        body['objective'] = 'Finish'
                    before = native.request.call_count
                    for _ in range(2):
                        status, result, _ = self.request('POST', '/v1/threads/thread-1/goal', body, token=self.token)
                        self.assertEqual(200, status)
                        self.assertEqual('accepted', result['status'])
                        self.assertEqual({'threadId': 'thread-1'}, result['receipt'])
                    self.assertEqual(before + 1, native.request.call_count)
                    status, result, _ = self.request('GET', '/v1/submissions/' + sid, token=self.token)
                    self.assertEqual(200, status)
                    self.assertEqual('accepted', result['status'])

    def test_goal_controls_validate_authentication_and_deduplicate(self):
        writes = []
        def update(thread_id, operation, **kwargs):
            writes.append((thread_id, operation, kwargs))
            return {'threadId': thread_id}
        self.native.update_goal = update
        path = '/v1/threads/thread-1/goal'
        body = {'submissionId': 'goal-set', 'operation': 'set', 'objective': 'Finish', 'tokenBudget': 1000}
        self.assertEqual(401, self.request('POST', path, body)[0])
        for _ in range(2):
            self.assertEqual(200, self.request('POST', path, body, token=self.token)[0])
        self.assertEqual([('thread-1', 'set', {'objective': 'Finish', 'token_budget': 1000})], writes)
        for invalid in (0, -1, True, '100', 1.5, 2**63):
            self.assertEqual(400, self.request('POST', path, {**body, 'tokenBudget': invalid}, token=self.token)[0])
        for operation in ('pause', 'resume', 'clear'):
            self.assertEqual(200, self.request('POST', path, {'submissionId': 'goal-' + operation, 'operation': operation}, token=self.token)[0])
        self.assertEqual(400, self.request('POST', path, {**body, 'operation': 'unknown'}, token=self.token)[0])
        self.assertEqual(400, self.request('POST', path, {**body, 'objective': ''}, token=self.token)[0])

    def test_permission_creation_and_settings_are_independent_and_deduplicated(self):
        writes = []
        def create(project, **kwargs):
            writes.append(('create', kwargs))
            return {'threadId': 'thread-1'}
        def update(thread, **kwargs):
            writes.append(('update', kwargs))
            return {'threadId': thread}
        self.native.create_session = create
        self.native.update_settings = update
        for path in ('/v1/threads', '/v1/threads/thread-1/settings'):
            body = {'submissionId': path, 'projectId': 'p', 'sandbox': 'read-only', 'approvalPolicy': 'on-request'}
            for _ in range(2):
                self.assertEqual(200, self.request('POST', path, body, token=self.token)[0])
            for invalid in ('invalid', {}, [], True):
                self.assertEqual(400, self.request('POST', path, {**body, 'sandbox': invalid}, token=self.token)[0])
                self.assertEqual(400, self.request('POST', path, {**body, 'approvalPolicy': invalid}, token=self.token)[0])
        self.assertEqual(2, len(writes))
        for _, values in writes:
            self.assertEqual('read-only', values['sandbox'])
            self.assertEqual('on-request', values['approval_policy'])

    def test_fast_settings_are_validated_and_deduplicated(self):
        writes = []
        def update(thread_id, **kwargs):
            writes.append((thread_id, kwargs))
            return {'threadId': thread_id}
        self.native.update_settings = update
        path = '/v1/threads/thread-1/settings'
        body = {'submissionId': 'settings-parity', 'serviceTier': 'priority', 'effort': 'high'}
        for _ in range(2):
            status, result, _ = self.request('POST', path, body, token=self.token)
            self.assertEqual(200, status)
            self.assertEqual('accepted', result['status'])
        self.assertEqual(1, len(writes))
        self.assertEqual('priority', writes[0][1]['service_tier'])
        for invalid in ('fast', True, 1):
            self.assertEqual(400, self.request('POST', path, {**body, 'serviceTier': invalid}, token=self.token)[0])

    def test_pending_project_attachment_auth_and_stream_use_selected_project(self):
        uploads = []
        def upload(project, name, stream, size):
            uploads.append((project, name, stream.read(size)))
            return {'type': 'mention', 'name': name, 'path': '/node/project/.rivo-attachments/random/movie.mp4'}
        self.native.upload_project_attachment_stream = upload
        url = '/v1/projects/selected-project/attachments?name=movie.mp4'
        headers = {'Content-Type': 'application/octet-stream'}
        self.assertEqual(401, self.request('POST', url, raw=b'video-bytes', extra_headers=headers)[0])
        self.assertFalse(uploads)
        status, result, _ = self.request('POST', url, token=self.token, raw=b'video-bytes', extra_headers=headers)
        self.assertEqual(200, status)
        self.assertEqual('mention', result['type'])
        self.assertEqual([('selected-project', 'movie.mp4', b'video-bytes')], uploads)
        self.assertFalse(self.native.created)

    def test_ten_full_size_images_fit_json_budget_but_eleventh_is_rejected(self):
        image = {'type': 'image', 'url': 'data:image/jpeg;base64,' + base64.b64encode(b'\0' * (1024 * 1024)).decode()}
        body = {'submissionId': 'ten-images-parity', 'input': [image] * 10}
        status, result, _ = self.request('POST', '/v1/threads/thread-1/turns', body, token=self.token)
        self.assertEqual(200, status)
        self.assertEqual('accepted', result['status'])
        self.assertEqual(10, len(self.native.sent[0][2]['input_items']))
        too_many = {'submissionId': 'eleven-images-parity', 'input': [{'type': 'image', 'url': 'https://example.invalid/image.jpg'}] * 11}
        self.assertEqual(400, self.request('POST', '/v1/threads/thread-1/turns', too_many, token=self.token)[0])
        self.assertEqual(1, len(self.native.sent))

    def test_recovery_receipt_remains_confirmed_and_deduplicated(self):
        from rivo_relay.queue import ExecutionQueue
        from relay_test_support.native import QueueNative
        native = QueueNative()
        queue = ExecutionQueue(self.app.db, native, self.app.events)
        self.app.queue = queue
        with self.app.db.read() as c:
            owner = c.execute('SELECT id FROM connections WHERE revoked_at IS NULL').fetchone()[0]
        queue.enqueue('thread-1', 'old-parity', owner, {'input': [{'type': 'text', 'text': 'original'}]})
        queue._set('old-parity', 'unknown')
        old = queue.list('thread-1')[0]
        body = {'submissionId': 'recovery-parity', 'operation': 'recover', 'itemId': old['id'], 'revision': old['revision']}
        for _ in range(2):
            status, result, _ = self.request('POST', '/v1/threads/thread-1/queue', body, token=self.token)
            self.assertEqual(200, status)
            self.assertEqual('accepted', result['status'])
            self.assertEqual('recovery-parity', result['receipt']['itemId'])
        self.assertEqual(1, len(queue.list('thread-1')))

    def test_structured_diff_pages_require_matching_revision(self):
        self.native.diff = lambda thread: {'revision': 'head-one', 'patch': 'diff --git a/test.txt b/test.txt\n--- a/test.txt\n+++ b/test.txt\n@@ -1 +1 @@\n-before\n+after\n', 'truncated': False}
        path = '/v1/threads/thread-1/diff'
        status, result, _ = self.request('GET', path + '?limit=2', token=self.token)
        self.assertEqual(200, status)
        self.assertEqual('test.txt', result['diff']['files'][0]['path'])
        self.assertEqual(2, result['diff']['nextOffset'])
        status, page, _ = self.request('GET', path + '?revision=head-one&offset=2&limit=2', token=self.token)
        self.assertEqual(200, status)
        self.assertNotIn('patch', page['diff'])
        self.assertEqual(['deletion', 'addition'], [line['kind'] for line in page['diff']['lines']])
        self.assertEqual(409, self.request('GET', path + '?revision=old&offset=2', token=self.token)[0])
        for query in ('?offset=2', '?offset=nope', '?limit=NaN', '?limit=0'):
            self.assertEqual(400, self.request('GET', path + query, token=self.token)[0])

    def test_handoff_preview_auth_binding_and_post_are_idempotent(self):
        previews, writes = [], []
        def preview(thread, action, owner):
            previews.append((thread, action, owner))
            return {"ticket": "fixture-ticket", "requiresConfirmation": True, "affectedThreads": []}
        def handoff(thread, action, **kwargs):
            writes.append((thread, action, kwargs))
            return {"threadId": thread, "released": True, "releaseScope": "relay-subscription"}
        self.native.preview_handoff = preview
        self.native.handoff = handoff
        path = "/v1/threads/thread-1/ownership"
        self.assertEqual(401, self.request("GET", path)[0])
        self.assertFalse(previews)
        status, result, _ = self.request("GET", path + "?operation=release", token=self.token)
        self.assertEqual(200, status)
        self.assertEqual("release", previews[0][1])
        body = {"submissionId": "handoff-fixture", "operation": "release", "ticket": result["ticket"], "confirmed": True}
        self.assertEqual(401, self.request("POST", path, body)[0])
        for _ in range(2):
            status, result, _ = self.request("POST", path, body, token=self.token)
            self.assertEqual(200, status)
            self.assertEqual("accepted", result["status"])
        self.assertEqual(1, len(writes))
        self.assertEqual(previews[0][2], writes[0][2]["owner"])
        self.assertTrue(writes[0][2]["confirmed"])
        self.assertEqual("fixture-ticket", writes[0][2]["ticket"])

    def test_directory_route_requires_auth_and_uses_native_host(self):
        self.native.read_directory = lambda path: {"path": path, "entries": [{"fileName": "a.pdf", "isFile": True, "isDirectory": False}]}
        self.assertEqual(401, self.request("GET", "/v1/directories?path=%2Fremote")[0])
        status, body, _ = self.request("GET", "/v1/directories?path=%2Fremote", token=self.token)
        self.assertEqual(200, status)
        self.assertEqual("a.pdf", body["entries"][0]["fileName"])

    def test_binary_transfer_and_expiring_scoped_link(self):
        data = b"\x00\xff" * (3 * 1024 * 1024)
        uploads = []
        self.native.upload_attachment_stream = lambda thread, name, stream, size: uploads.append(stream.read(size)) or {"path": "/remote/test.zip"}
        status, _, _ = self.request("POST", "/v1/threads/thread-1/attachments?name=test.zip", token=self.token,
            raw=data, extra_headers={"Content-Type": "application/octet-stream"})
        self.assertEqual(200, status)
        self.assertEqual([data], uploads)
        status, _, _ = self.request("POST", "/v1/threads/thread-1/attachments?name=test.zip", token=self.token,
            raw=b"", extra_headers={"Content-Type": "application/octet-stream", "Content-Length": str(1024 * 1024 * 1024 + 1)})
        self.assertEqual(413, status)
        self.assertEqual(1, len(uploads))
        reads = []
        self.native.prepare_download = lambda path, *args: reads.append(path) or {
            'size': len(data), 'start': 0, 'end': len(data) - 1, 'partial': False,
            'etag': '"fixture"', 'chunks': iter([data])}
        self.assertEqual(401, self.request("GET", "/v1/files/download?path=%2Fremote%2Ftest.zip")[0])
        status, link, _ = self.request("POST", "/v1/download-links", {"path": "/remote/test.zip"}, token=self.token)
        self.assertEqual(200, status)
        path = "/v1/download-links/" + link["url"].rsplit("/", 1)[-1]
        conn = http.client.HTTPConnection("127.0.0.1", self.port)
        try:
            conn.request("GET", path)
            response = conn.getresponse()
            self.assertEqual(200, response.status)
            self.assertIn("attachment", response.getheader("Content-Disposition"))
            self.assertEqual(data, response.read())
        finally:
            conn.close()
        self.assertEqual(["/remote/test.zip"], reads)
        key = path.rsplit("/", 1)[-1]
        self.app.download_links[key] = ("/remote/test.zip", 0)
        self.assertEqual(404, self.request("GET", path)[0])
        self.assertEqual(401, self.request("POST", "/v1/download-links", {"path": "/private"})[0])

    def test_download_ranges_validators_and_ticket_contract(self):
        from relay_test_support.files import NativeHost
        from rivo_relay.codex.native_download import prepare_download
        from urllib.parse import quote
        local = Path(self.directory.name) / 'download 空格.bin'
        local.write_bytes(b'0123456789')
        host = NativeHost(self.directory.name)
        self.native.prepare_download = lambda path, range_header, if_range: prepare_download(host, path, range_header, if_range)
        path = '/v1/files/download?path=' + quote(str(local), safe='')
        def download(url, headers=None):
            client = http.client.HTTPConnection('127.0.0.1', self.port, timeout=4)
            try:
                client.request('GET', url, headers={'Authorization': 'Bearer ' + self.token, **(headers or {})})
                response = client.getresponse()
                return response.status, response.read(), dict(response.getheaders())
            finally:
                client.close()
        status, data, headers = download(path)
        self.assertEqual((200, b'0123456789'), (status, data))
        etag = headers['ETag']
        self.assertEqual('bytes', headers['Accept-Ranges'])
        status, data, headers = download(path, {'Range': 'bytes=3-6', 'If-Range': etag})
        self.assertEqual((206, b'3456', 'bytes 3-6/10'), (status, data, headers['Content-Range']))
        status, data, headers = download(path, {'Range': 'bytes=10-', 'If-Range': etag})
        self.assertEqual((416, b'', 'bytes */10', etag), (status, data, headers['Content-Range'], headers['ETag']))
        self.assertEqual(416, download(path, {'Range': 'bytes=0-1,3-4'})[0])
        local.write_bytes(b'abcdefghij')
        status, data, headers = download(path, {'Range': 'bytes=3-', 'If-Range': etag})
        self.assertEqual((200, b'abcdefghij'), (status, data))
        self.assertNotEqual(etag, headers['ETag'])
        status, link, _ = self.request('POST', '/v1/download-links', {'path': str(local)}, token=self.token)
        ticket = '/v1/download-links/' + link['url'].rsplit('/', 1)[-1]
        status, data, headers = download(ticket, {'Range': 'bytes=-3'})
        self.assertEqual((206, b'hij', 'bytes 7-9/10'), (status, data, headers['Content-Range']))

    def test_disconnected_get_releases_waiting_handler(self):
        import queue
        from unittest.mock import patch
        from rivo_relay.read_budget import read_reply
        started, finished = threading.Event(), threading.Event()
        def blocked(*args):
            started.set()
            try:
                read_reply(queue.Queue(), 30)
            finally:
                finished.set()
        with patch.object(Handler, 'route', blocked):
            client = http.client.HTTPConnection('127.0.0.1', self.port, timeout=2)
            client.request('GET', '/v1/projects', headers={'Authorization': 'Bearer ' + self.token})
            self.assertTrue(started.wait(1))
            client.close()
            self.assertTrue(finished.wait(2))
        status, _, _ = self.request('GET', '/health')
        self.assertEqual(status, 200)

    def test_read_deadline_returns_explicit_error(self):
        import queue
        from unittest.mock import patch
        from rivo_relay.read_budget import read_budget, read_reply
        with patch('rivo_relay.api.event') as log, \
                patch('rivo_relay.api.read_budget', lambda sock: read_budget(sock, seconds=.02)), \
                patch.object(Handler, 'route', lambda *args: read_reply(queue.Queue(), 30)):
            status, result, _ = self.request('GET', '/v1/projects', token=self.token)
        self.assertEqual(status, 504)
        self.assertEqual(result['error']['code'], 'read_deadline')
        response = next(call.kwargs for call in log.call_args_list if call.args[0] == 'http_response')
        self.assertEqual('read_deadline', response['error_code'])
        self.assertEqual('deadline', response['read_stop_reason'])

    def test_error_diagnostics_keep_conflict_code_but_not_private_details(self):
        from unittest.mock import patch
        from rivo_relay.api import ApiError
        for code, expected in [('workspace_busy', 'workspace_busy'), ('private_secret', 'other')]:
            with patch('rivo_relay.api.event') as log, \
                    patch.object(Handler, 'route', side_effect=ApiError(409, code, 'private message')):
                status, _, _ = self.request('GET', '/v1/projects', token=self.token)
            self.assertEqual(409, status)
            response = next(call.kwargs for call in log.call_args_list if call.args[0] == 'http_response')
            self.assertEqual(expected, response['error_code'])
            self.assertNotIn('private', str(log.call_args_list))





    def open_stream(self, *, token=None):
        client = http.client.HTTPConnection("127.0.0.1", self.port, timeout=4)
        client.request("GET", "/v1/events", headers={"Authorization": "Bearer " + (token or self.token)})
        response = client.getresponse()
        self.addCleanup(client.close)
        self.addCleanup(response.close)
        return response

    def event(self, response):
        fields = {}
        for _ in range(100):
            line = response.fp.readline().decode("utf-8").rstrip("\r\n")
            if not line:
                if fields:
                    return fields
                continue
            if line.startswith(":"):
                continue
            key, _, value = line.partition(":")
            fields[key] = value.lstrip()
        self.fail("No complete SSE event received")

    def create_todo(self):
        status, result, _ = self.request("POST", "/v1/todos", {"id": "todo-1", "title": "Test task", "notes": "Acceptance notes"}, token=self.token)
        self.assertEqual(status, 200)
        return result["todo"]

    def test_queue_enqueue_is_idempotent_and_stale_edit_fails_definitely(self):
        self.native.validate_queue_target = lambda thread: None
        body = {"submissionId": "queue-a", "operation": "enqueue", "input": [{"type": "text", "text": "queued"}]}
        path = "/v1/threads/thread-1/queue"
        first = self.request("POST", path, body, token=self.token)
        second = self.request("POST", path, body, token=self.token)
        self.assertEqual("accepted", first[1]["status"])
        self.assertEqual(first[1], second[1])
        self.assertEqual(1, len(self.app.queue.list("thread-1")))
        edit = {"submissionId": "edit-a", "operation": "edit", "itemId": "queue-a", "revision": 99, "text": "updated"}
        self.assertEqual("failed", self.request("POST", path, edit, token=self.token)[1]["status"])
        self.assertEqual("queued", self.app.queue.list("thread-1")[0]["text"])
        self.assertEqual(1, len(self.request("GET", "/v1/threads/thread-1", token=self.token)[1]["queue"]))
        self.assertFalse(self.native.sent)

    def test_projects_are_metadata_only_and_thread_views_remain_supported(self):
        self.native.list_projects = Mock(return_value=[{'id': 'p', 'name': 'Project', 'path': '/p', 'roots': ['/p']}])
        self.native.list_sessions = Mock(return_value=[{'id': 't', 'status': 'unknown'}])
        status, data, _ = self.request('GET', '/v1/projects', token=self.token)
        self.assertEqual(200, status)
        self.assertEqual(['p'], [p['id'] for p in data['projects']])
        self.assertNotIn('threadCount', data['projects'][0])
        self.native.list_projects.assert_called_once_with()
        status, data, _ = self.request('GET', '/v1/threads?projectId=p&archived=true&view=summary', token=self.token)
        self.assertEqual(200, status)
        self.assertFalse(data['catalogComplete'])
        self.native.list_sessions.assert_called_once_with('p', archived=True, summary=True)
        self.native.list_sessions.reset_mock()
        self.assertTrue(self.request('GET', '/v1/threads?projectId=p', token=self.token)[1]['catalogComplete'])
        self.native.list_sessions.assert_called_once_with('p')
        for url in ('/v1/projects?view=unknown', '/v1/projects?view=summary', '/v1/projects?view=full', '/v1/threads?projectId=p&view=unknown'):
            self.assertEqual(400, self.request('GET', url, token=self.token)[0])

    def test_health_is_public_but_project_data_requires_valid_token(self):
        self.assertEqual(self.request("GET", "/health")[0], 200)
        for token in (None, "invalid"):
            self.assertEqual(self.request("GET", "/v1/projects", token=token)[0], 401)
        self.assertEqual(self.native.read_count, 0)
        status, result, headers = self.request("GET", "/v1/projects", token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(result["projects"][0]["id"], "project-1")
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.app.pairing.revoke(self.paired["deviceId"])
        self.assertEqual(self.request("GET", "/v1/projects", token=self.token)[0], 401)
        self.assertEqual(self.native.read_count, 1)

    def test_pairing_http_is_one_time_expires_and_rate_limits(self):
        now = [100.0]
        self.app.pairing = PairingStore(self.app.db, clock=lambda: now[0], attempt_limit=4)
        code = self.app.pairing.create()["code"]
        body = {"code": code, "deviceName": "Phone"}
        self.assertEqual(self.request("POST", "/v1/pair", body)[0], 200)
        self.assertEqual(self.request("POST", "/v1/pair", body)[0], 401)
        expired = self.app.pairing.create(ttl_seconds=1)["code"]
        now[0] += 1
        self.assertEqual(self.request("POST", "/v1/pair", {**body, "code": expired})[0], 401)
        self.assertEqual(self.request("POST", "/v1/pair", {**body, "code": "wrong"})[0], 401)
        status, result, _ = self.request("POST", "/v1/pair", {**body, "code": "wrong"})
        self.assertEqual(status, 429)
        self.assertEqual(result["error"]["code"], "pairing_rate_limited")

    def test_write_duplicate_and_changed_payload_do_not_repeat_native_call(self):
        body = {"submissionId": "send-1", "text": "Check isolated fixture"}
        path = "/v1/threads/thread-1/turns"
        first = self.request("POST", path, body, token=self.token)
        second = self.request("POST", path, body, token=self.token)
        self.assertEqual(first[:2], second[:2])
        self.assertEqual(first[1]["status"], "accepted")
        self.assertEqual(len(self.native.sent), 1)
        status, result, _ = self.request("POST", path, {**body, "text": "Changed"}, token=self.token)
        self.assertEqual(status, 409)
        self.assertEqual(result["error"]["code"], "submission_conflict")
        self.assertEqual(len(self.native.sent), 1)

    def test_transport_uncertainty_and_definite_failure_never_auto_replay(self):
        body = {"submissionId": "unknown-1", "text": "Synthetic task"}
        path = "/v1/threads/thread-1/turns"
        self.native.send_error = TimeoutError("lost reply")
        self.assertEqual(self.request("POST", path, body, token=self.token)[1]["status"], "unknown")
        self.native.send_error = None
        self.assertEqual(self.request("POST", path, body, token=self.token)[1]["status"], "unknown")
        self.assertEqual(len(self.native.sent), 1)
        self.native.send_error = NativeFailure()
        body["submissionId"] = "failed-1"
        result = self.request("POST", path, body, token=self.token)[1]
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["errorCode"], "native_rejected")
        self.assertEqual(self.request("POST", path, body, token=self.token)[1], result)
        self.assertEqual(len(self.native.sent), 2)

    def test_restart_marks_pending_unknown_preserves_checkpoint_and_does_not_dispatch(self):
        body = {"submissionId": "pending-1", "text": "Synthetic task"}
        self.app.submissions.claim("pending-1", self.paired["deviceId"], "thread.turns", "thread-1", body)
        self.app.submissions.checkpoint("pending-1", {"threadId": "thread-1"})
        self._stop()
        self._start()
        status, record, _ = self.request("GET", "/v1/submissions/pending-1", token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(record["status"], "unknown")
        self.assertEqual(record["receipt"], {"threadId": "thread-1"})
        self.assertEqual(self.request("POST", "/v1/threads/thread-1/turns", body, token=self.token)[1], record)
        self.assertEqual(self.native.sent, [])

    def test_missing_submission_reconcile_fences_late_post(self):
        path = "/v1/submissions/missing-create"
        self.assertEqual(self.request("GET", path, token=self.token)[0], 404)
        status, result, _ = self.request("POST", path + "/reconcile", {}, token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["errorCode"], "submission_not_received")
        self.assertEqual(self.request("GET", path, token=self.token)[1], result)
        body = {"submissionId": "missing-create", "text": "Synthetic task"}
        self.assertEqual(self.request("POST", "/v1/threads/thread-1/turns", body, token=self.token)[0], 409)
        self.assertEqual(self.native.sent, [])
        other = self._pair()
        self.assertEqual(self.request("POST", path + "/reconcile", {}, token=other["token"])[0], 404)

    def test_submission_lookup_is_scoped_to_originating_connection(self):
        body = {"submissionId": "private-1", "text": "Synthetic task"}
        self.request("POST", "/v1/threads/thread-1/turns", body, token=self.token)
        other = self._pair()
        self.assertEqual(self.request("GET", "/v1/submissions/private-1", token=other["token"])[0], 404)

    def test_todo_dispatch_checkpoints_before_sending_and_keeps_manual_completion(self):
        self.create_todo()
        observed = {}

        def at_send(*_):
            observed["submission"] = self.app.submissions.get("dispatch-1")
            observed["todo"] = self.app.todos.get("todo-1")

        self.native.on_send = at_send
        body = {"projectId": "project-1", "submissionId": "dispatch-1"}
        first = self.request("POST", "/v1/todos/todo-1/dispatch", body, token=self.token)
        self.assertEqual(first[0], 200)
        self.assertEqual(first[1]["status"], "accepted")
        self.assertEqual(observed["submission"]["status"], "pending")
        self.assertEqual(observed["submission"]["receipt"], {"threadId": "created-1"})
        self.assertEqual(observed["todo"]["thread_id"], "created-1")
        self.assertFalse(observed["todo"]["completed"])
        self.assertEqual(self.request("POST", "/v1/todos/todo-1/dispatch", body, token=self.token)[:2], first[:2])
        self.assertEqual(len(self.native.created), 1)
        self.assertEqual(len(self.native.sent), 1)
        status, _, _ = self.request("POST", "/v1/todos/todo-1/dispatch", {**body, "submissionId": "dispatch-2"}, token=self.token)
        self.assertEqual(status, 409)
        self.assertEqual(len(self.native.created), 1)

    def test_two_distinct_dispatch_ids_cannot_create_two_threads(self):
        self.create_todo()
        self.native.create_gate = threading.Event()
        start = threading.Barrier(2)

        def dispatch(index):
            start.wait()
            return self.request("POST", "/v1/todos/todo-1/dispatch", {
                "projectId": "project-1", "submissionId": f"concurrent-{index}"
            }, token=self.token)

        with ThreadPoolExecutor(max_workers=2) as pool:
            pending = [pool.submit(dispatch, number) for number in range(2)]
            self.assertTrue(self.native.first_create.wait(2))
            self.native.second_create.wait(0.2)
            self.native.create_gate.set()
            responses = [future.result(timeout=4) for future in pending]
        self.assertEqual(sorted(response[0] for response in responses), [200, 409])
        self.assertEqual(len(self.native.created), 1)
        self.assertEqual(len(self.native.sent), 1)

    def test_todo_details_round_trip_and_dispatch_native_mentions(self):
        details = {"dueDate": "2026-11-05", "repeatRule": "weekly", "reminderAt": 1793869200000,
                   "subtasks": [{"id": "subtask-a", "title": "Inspect the changes", "completed": False}],
                   "attachments": [{"type": "file", "name": "设计稿.md", "path": "/remote/project/设计稿.md"}]}
        status, created, _ = self.request("POST", "/v1/todos", {"id": "details-todo", "title": "Review files", "notes": "Read carefully", "details": details}, token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(created["todo"]["details"], details)
        status, loaded, _ = self.request("GET", "/v1/todos/details-todo", token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(loaded["todo"]["details"], details)
        payload = {"projectId": "project-1", "submissionId": "dispatch-details"}
        status, receipt, _ = self.request("POST", "/v1/todos/details-todo/dispatch", payload, token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(receipt["status"], "accepted")
        _, text, kwargs = self.native.sent[-1]
        self.assertIn("[ ] Inspect the changes", text)
        self.assertEqual(kwargs["input_items"][0]["text"], text)
        self.assertEqual(kwargs["input_items"][1], {"type": "mention", "name": "设计稿.md", "path": "/remote/project/设计稿.md"})
        self.assertFalse(self.app.todos.get("details-todo")["completed"])
        self.assertEqual(len(self.app.todos.list()), 1)
        self.request("POST", "/v1/todos/details-todo/dispatch", payload, token=self.token)
        self.assertEqual(len(self.native.sent), 1)
        status, _, _ = self.request("PATCH", "/v1/todos/details-todo", {"details": {"dueDate": "2026-02-30"}}, token=self.token)
        self.assertEqual(status, 400)
        self.assertEqual(self.app.todos.get("details-todo")["details"], details)

    def test_agent_completion_endpoint_uses_only_scoped_credential(self):
        self.app.todos.create("Complete target", todo_id="agent-completion")
        self.app.todos.link("agent-completion", "project", "thread")
        credential = self.app.todo_completion.issue(self.app.todos.get("agent-completion"))
        status, _, _ = self.request("POST", "/v1/todo-completion", {}, token="invalid")
        self.assertEqual(status, 401)
        status, result, _ = self.request("POST", "/v1/todo-completion", {}, token=credential)
        self.assertEqual(status, 200)
        self.assertTrue(result["todo"]["completed"])
        status, _, _ = self.request("GET", "/v1/todos", token=credential)
        self.assertEqual(status, 401)

    def test_uploaded_todo_attachment_is_delivered_without_mutating_todo(self):
        name = 'photo.jpg'
        path = '/native/.codex/rivo-relay-todo-media/id/photo.jpg'
        uploads = []

        def upload(filename, stream, size):
            uploads.append((filename, stream.read(size)))
            return {'type': 'mention', 'name': filename, 'path': path}

        self.native.upload_todo_attachment_stream = upload
        url = '/v1/todos/attachments?name=photo.jpg'
        headers = {'Content-Type': 'application/octet-stream'}
        self.assertEqual(401, self.request('POST', url, raw=b'photo', extra_headers=headers)[0])
        status, result, _ = self.request('POST', url, token=self.token, raw=b'photo', extra_headers=headers)
        self.assertEqual(200, status)
        self.assertEqual([(name, b'photo')], uploads)
        self.assertEqual(path, result['path'])
        self.assertFalse(self.native.created)

        self.app.todos.create('Review photo', todo_id='photo-todo')
        url = '/v1/todos/photo-todo/dispatch'
        payload = {'projectId': 'project-1', 'submissionId': 'dispatch-photo',
                   'uploadedAttachments': [{'name': name, 'path': path}]}
        invalid = {**payload, 'uploadedAttachments': [{'name': name, 'path': 'content://phone/photo'}]}
        self.assertEqual(400, self.request('POST', url, invalid, token=self.token)[0])
        for _ in range(2):
            status, receipt, _ = self.request('POST', url, payload, token=self.token)
            self.assertEqual(200, status)
            self.assertEqual('accepted', receipt['status'])
        self.assertEqual(1, len(self.native.sent))
        self.assertEqual({'type': 'mention', 'name': name, 'path': path}, self.native.sent[0][2]['input_items'][1])
        self.assertFalse(self.app.todos.get('photo-todo')['details'].get('attachments'))

    def test_completed_todo_cannot_start_a_new_dispatch_but_existing_receipt_can_be_read(self):
        self.create_todo()
        self.request("PATCH", "/v1/todos/todo-1", {"completed": True}, token=self.token)
        payload = {"projectId": "project-1", "submissionId": "completed-dispatch"}
        status, _, _ = self.request("POST", "/v1/todos/todo-1/dispatch", payload, token=self.token)
        self.assertEqual(status, 409)
        self.assertEqual(self.native.created, [])
        self.assertEqual(self.native.sent, [])
        self.request("PATCH", "/v1/todos/todo-1", {"completed": False}, token=self.token)
        status, original, _ = self.request("POST", "/v1/todos/todo-1/dispatch", payload, token=self.token)
        self.assertEqual(status, 200)
        self.request("PATCH", "/v1/todos/todo-1", {"completed": True}, token=self.token)
        status, replay, _ = self.request("POST", "/v1/todos/todo-1/dispatch", payload, token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(replay, original)
        self.assertEqual(len(self.native.sent), 1)

    def test_todo_dispatch_rejects_receipt_without_details_digest(self):
        self.create_todo()
        todo = self.app.todos.get("todo-1")
        payload = {"projectId": "project-1", "submissionId": "pre-upgrade-dispatch"}
        legacy = {**payload, "title": todo["title"], "notes": todo["notes"]}
        self.app.submissions.claim(payload["submissionId"], self.paired["deviceId"], "todo.dispatch", "todo-1", legacy)
        self.app.submissions.mark_accepted(payload["submissionId"], {"threadId": "old-native-thread", "turnId": "old-native-turn"})
        status, receipt, _ = self.request("POST", "/v1/todos/todo-1/dispatch", payload, token=self.token)
        self.assertEqual(status, 409)
        self.assertEqual(self.native.sent, [])
        self.app.todos.update("todo-1", notes="Different content")
        self.assertEqual(self.request("POST", "/v1/todos/todo-1/dispatch", payload, token=self.token)[0], 409)
        self.app.todos.update("todo-1", notes=todo["notes"], details={"dueDate": "2026-11-01"})
        self.assertEqual(self.request("POST", "/v1/todos/todo-1/dispatch", payload, token=self.token)[0], 409)
        self.assertEqual(self.native.created, [])
        self.assertEqual(self.native.sent, [])

    def test_unknown_dispatch_after_restart_blocks_new_id_even_before_todo_link(self):
        self.create_todo()
        self.app.submissions.claim("crashed-dispatch", self.paired["deviceId"], "todo.dispatch", "todo-1", {"test": "crash"})
        self.app.submissions.checkpoint("crashed-dispatch", {"threadId": "already-created"})
        self._stop()
        self._start()
        status, _, _ = self.request("POST", "/v1/todos/todo-1/dispatch", {
            "projectId": "project-1", "submissionId": "new-id"
        }, token=self.token)
        self.assertEqual(status, 409)
        self.assertEqual(self.native.created, [])
        self.assertEqual(self.native.sent, [])

    def test_sse_rechecks_revocation_after_waiting_before_emitting_native_data(self):
        self._stop()
        bus = PausingEventBus()
        self._start(bus)
        stream = self.open_stream()
        self.assertEqual(stream.status, 200)
        self.assertEqual(self.event(stream)["event"], "reset")
        self.assertTrue(bus.entered.wait(2))
        self.app.pairing.revoke(self.paired["deviceId"])
        bus.publish({"method": "synthetic/private-after-revocation"})
        bus.resume.set()
        self.assertEqual(self.event(stream)["event"], "revoked")

    def test_idle_sse_disconnect_releases_slot_before_next_heartbeat(self):
        first = self.open_stream()
        self.assertEqual(self.event(first)["event"], "reset")
        first.close()
        deadline = time.monotonic() + 3
        released = False
        while time.monotonic() < deadline:
            if self.server.stream_slots.acquire(blocking=False):
                self.server.stream_slots.release()
                released = True
                break
            time.sleep(0.02)
        self.assertTrue(released, "Disconnected idle stream retained its slot")
        second = self.open_stream()
        self.assertEqual(second.status, 200)
        self.assertEqual(self.event(second)["event"], "reset")

    def test_sse_limit_reserves_capacity_for_ordinary_requests(self):
        first = self.open_stream()
        self.assertEqual(first.status, 200)
        self.assertEqual(self.event(first)["event"], "reset")
        second = self.open_stream()
        self.assertEqual(second.status, 429)
        self.assertEqual(json.loads(second.read())["error"]["code"], "stream_limit")
        self.assertEqual(self.request("GET", "/v1/projects", token=self.token)[0], 200)

    def test_validation_and_native_error_status_are_sanitized(self):
        status, result, _ = self.request("POST", "/v1/todos", token=self.token,
            raw=b"{", extra_headers={"Content-Type": "application/json"})
        self.assertEqual(status, 400)
        self.assertEqual(result["error"]["code"], "invalid_json")
        self.assertEqual(self.request("POST", "/v1/todos", raw=b"{}", token=self.token)[0], 415)
        self.assertEqual(self.request("GET", "/v1/todos/missing", token=self.token)[0], 404)
        self.native.get_error = NativeFailure("thread_not_found", http_status=404)
        status, result, _ = self.request("GET", "/v1/threads/missing", token=self.token)
        self.assertEqual(status, 404)
        self.assertEqual(result["error"]["code"], "thread_not_found")
        self.assertNotIn("private/test-only-path", json.dumps(result))

    def test_history_failure_is_not_mislabeled_as_desktop_disconnection(self):
        self.native.get_error = NativeFailure('native_history_unavailable', http_status=503)
        status, result, _ = self.request('GET', '/v1/threads/thread-1', token=self.token)
        self.assertEqual(503, status)
        self.assertEqual('原生历史暂不可用，请稍后重试', result['error']['message'])
        self.assertNotIn('private/test-only-path', json.dumps(result))

    def test_duplicate_content_length_is_rejected_before_reading_body(self):
        client = http.client.HTTPConnection("127.0.0.1", self.port, timeout=4)
        try:
            client.putrequest("POST", "/v1/todos")
            client.putheader("Authorization", "Bearer " + self.token)
            client.putheader("Content-Type", "application/json")
            client.putheader("Content-Length", "2")
            client.putheader("Content-Length", "3")
            client.endheaders(b"{}")
            response = client.getresponse()
            self.assertEqual(response.status, 411)
            response.read()
        finally:
            client.close()

    def test_history_cursor_and_limit_are_forwarded_and_bad_limit_is_rejected(self):
        status, page, _ = self.request("GET", "/v1/threads/thread-1", token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(page["nextCursor"], "next-page")
        self.assertEqual(self.native.history_reads[-1], ("thread-1", None, 8))
        status, page, _ = self.request("GET", "/v1/threads/thread-1?cursor=page-a&limit=7", token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(page["nextCursor"], "next-page")
        self.assertEqual(self.native.history_reads[-1], ("thread-1", "page-a", 7))
        status, page, _ = self.request("GET", "/v1/threads/thread-1/history?cursor=page-b&limit=4", token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(page["turns"][0]["id"], "synthetic-turn")
        self.assertEqual(self.native.history_reads[-1], ("thread-1", "page-b", 4))
        self.assertEqual(self.request("GET", "/v1/threads/thread-1?limit=invalid", token=self.token)[0], 400)

    def test_confirmed_failure_before_thread_creation_allows_explicit_new_submission(self):
        self.create_todo()
        self.native.create_error = NativeFailure()
        body = {"projectId": "project-1", "submissionId": "failed-create"}
        failed = self.request("POST", "/v1/todos/todo-1/dispatch", body, token=self.token)
        self.assertEqual(failed[1]["status"], "failed")
        self.assertIsNone(failed[1]["receipt"])
        self.native.create_error = None
        body["submissionId"] = "explicit-new-create"
        accepted = self.request("POST", "/v1/todos/todo-1/dispatch", body, token=self.token)
        self.assertEqual(accepted[1]["status"], "accepted")
        self.assertEqual(len(self.native.created), 2)
        self.assertEqual(len(self.native.sent), 1)

    def test_failed_submission_with_thread_checkpoint_blocks_new_dispatch_after_restart(self):
        self.create_todo()
        self.app.submissions.claim("failed-with-thread", self.paired["deviceId"], "todo.dispatch", "todo-1", {})
        self.app.submissions.checkpoint("failed-with-thread", {"threadId": "already-created"})
        self.app.submissions.mark_failed("failed-with-thread", "send_rejected")
        self._stop()
        self._start()
        status, _, _ = self.request("POST", "/v1/todos/todo-1/dispatch", {
            "projectId": "project-1", "submissionId": "must-not-create"
        }, token=self.token)
        self.assertEqual(status, 409)
        self.assertEqual(self.native.created, [])
        self.assertEqual(self.native.sent, [])

    def test_existing_thread_must_belong_to_selected_project_before_claim(self):
        self.create_todo()
        status, result, _ = self.request("POST", "/v1/todos/todo-1/dispatch", {
            "projectId": "project-1", "threadId": "thread-2", "submissionId": "wrong-project"
        }, token=self.token)
        self.assertEqual(status, 409)
        self.assertEqual(result["error"]["code"], "submission_conflict")
        self.assertEqual(self.native.created, [])
        self.assertEqual(self.native.sent, [])
        self.assertIsNone(self.app.todos.get("todo-1")["thread_id"])
        self.assertEqual(self.request("GET", "/v1/submissions/wrong-project", token=self.token)[0], 404)

    def test_duplicate_existing_thread_dispatch_does_not_depend_on_catalog_changes(self):
        self.create_todo()
        body = {"projectId": "project-1", "threadId": "thread-1", "submissionId": "existing-thread"}
        first = self.request("POST", "/v1/todos/todo-1/dispatch", body, token=self.token)
        self.assertEqual(first[0], 200)
        self.assertEqual(first[1]["status"], "accepted")
        self.assertEqual(self.native.session_reads, ["project-1"])
        self.native.catalog_threads["project-1"] = []
        duplicate = self.request("POST", "/v1/todos/todo-1/dispatch", body, token=self.token)
        self.assertEqual(duplicate[:2], first[:2])
        self.assertEqual(self.native.session_reads, ["project-1"])
        self.assertEqual(self.native.created, [])
        self.assertEqual(len(self.native.sent), 1)


if __name__ == "__main__":
    unittest.main()
