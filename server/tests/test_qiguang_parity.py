"""QiGuang parity invariants exercised against isolated native RPC fixtures."""
import copy
import io
import json
import unittest
from unittest.mock import Mock, patch

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.interactions import Interactions
from rivo_relay.codex.projects import ProjectCatalog
from rivo_relay.codex.projection import execution_projection
from rivo_relay.codex.sessions import CodexService
from rivo_relay.codex.models import ModelCatalog
from rivo_relay.codex.observations import Observations
from rivo_relay.database import Database
from rivo_relay.events import EventBus
from rivo_relay.queue import ExecutionQueue, QueueConflict
from test_codex import FakeNative
from test_queue import QueueNative


class RecoveryParityTests(unittest.TestCase):
    def setUp(self):
        self.db = Database(':memory:')
        self.addCleanup(self.db.close)
        with self.db.transaction() as c:
            c.execute("INSERT INTO connections VALUES ('phone','digest','Phone',1,NULL)")
        self.native = QueueNative()
        self.queue = ExecutionQueue(self.db, self.native, EventBus())

    def enqueue(self, identity, **extra):
        return self.queue.enqueue('thread', identity, 'phone', {
            'input': [{'type': 'text', 'text': 'original irreversible instruction'}, {'type': 'image', 'url': 'private attachment'}],
            'model': 'model', 'effort': 'high', 'serviceTier': 'priority', **extra})

    def test_recovery_is_new_safe_continuation_before_remaining_queue(self):
        self.enqueue('old', mode='plan', review={'type': 'uncommittedChanges'})
        self.enqueue('later')
        self.queue._set('old', 'unknown')
        old = self.queue.list('thread')[0]
        self.queue.recover('thread', 'old', old['revision'], 'continuation', 'phone')
        new, later = self.queue.list('thread')
        self.assertEqual(['continuation', 'later'], [new['id'], later['id']])
        self.assertEqual('plan', new['mode'])
        self.assertEqual('priority', new['serviceTier'])
        self.assertIsNone(new['review'])
        self.assertEqual(1, len(new['input']))
        self.assertNotIn('original irreversible instruction', new['text'])
        self.assertIn('检查', new['text'])
        with self.assertRaises(QueueConflict):
            self.queue.recover('thread', 'old', old['revision'], 'duplicate', 'phone')
        self.assertFalse(self.native.sent)

    def test_failed_started_turn_cannot_be_edited_and_replayed(self):
        self.enqueue('old')
        self.queue._set('old', 'failed', turn_id='native-turn')
        old = self.queue.list('thread')[0]
        self.assertTrue(old['recoveryRequired'])
        with self.assertRaises(QueueConflict):
            self.queue.change('thread', 'old', old['revision'], 'edit', 'replay')
        self.native.snapshot['status'] = 'running'
        with self.assertRaises(QueueConflict):
            self.queue.recover('thread', 'old', old['revision'], 'new', 'phone')

    def test_unknown_queue_reconciles_only_exact_native_client_identity_without_replay(self):
        self.enqueue('uncertain')
        self.queue._set('uncertain', 'unknown')
        self.native.snapshot.update(readOnly=True, thread={'turns': [{'id': 'native', 'status': 'inProgress',
            'items': [{'type': 'userMessage', 'clientId': 'different', 'content': [{'type': 'text', 'text': 'original irreversible instruction'}]}]}]})
        self.queue.tick()
        self.assertEqual('unknown', self.queue.list('thread')[0]['status'])
        self.native.snapshot['thread']['turns'][0]['items'][0]['clientId'] = 'uncertain'
        self.queue.tick()
        self.assertEqual(('running', 'native'), (self.queue.list('thread')[0]['status'], self.queue.list('thread')[0]['turnId']))
        self.native.snapshot['thread']['turns'][0]['status'] = 'completed'
        self.queue.tick()
        self.assertEqual([], self.queue.list('thread'))
        self.assertEqual([], self.native.sent)

    def test_unknown_review_uses_saved_turn_id_and_converging_history_waits(self):
        self.enqueue('review', review={'type': 'uncommittedChanges'})
        self.queue._set('review', 'unknown', turn_id='review-turn')
        self.native.snapshot.update(nativeSync={'state': 'converging'}, thread={'turns': [{'id': 'review-turn', 'status': 'completed', 'items': []}]})
        self.queue.tick()
        self.assertEqual('unknown', self.queue.list('thread')[0]['status'])
        self.native.snapshot['nativeSync']['state'] = 'ready'
        self.queue.tick()
        self.assertEqual([], self.queue.list('thread'))
        self.assertEqual([], self.native.sent)

    def test_unknown_ambiguous_client_identity_stays_quarantined(self):
        self.enqueue('uncertain')
        self.queue._set('uncertain', 'unknown')
        self.native.snapshot['thread'] = {'turns': [{'id': identity, 'status': 'completed',
            'items': [{'type': 'userMessage', 'clientId': 'uncertain'}]} for identity in ('one', 'two')]}
        self.queue.tick()
        self.assertEqual('unknown', self.queue.list('thread')[0]['status'])
        self.assertEqual([], self.native.sent)

    def test_async_question_consumes_only_one_answer_even_before_history_updates(self):
        self.native.snapshot.update(activeTurnId='active', status='running', thread={'turns': [{
            'id': 'active', 'items': [{'id': 'question', 'type': 'agentMessage', 'delivery': 'async',
                                      'questions': [{'title': 'Which?'}]}]}]})
        self.enqueue('answer')
        self.enqueue('followup')
        self.assertEqual(1, len(self.native.sent))
        self.assertEqual('answer', self.native.sent[0]['client_message_id'])
        self.assertEqual(['followup'], [item['id'] for item in self.queue.list('thread')])

    def test_uncertain_async_answer_enters_recovery_and_is_not_consumed_twice(self):
        self.native.snapshot.update(activeTurnId='active', status='running', thread={'turns': [{
            'id': 'active', 'items': [{'id': 'question', 'type': 'agentMessage', 'delivery': 'async', 'questions': [{}]}]}]})
        self.native.error = TimeoutError()
        self.enqueue('answer')
        self.enqueue('later')
        self.assertEqual(1, len(self.native.sent))
        self.assertTrue(self.queue.list('thread')[0]['recoveryRequired'])

    def test_definite_steer_rejection_restores_waiting_item(self):
        self.enqueue('answer')
        self.native.snapshot['activeTurnId'] = 'active'
        self.native.error = CodexError('rejected', http_status=409)
        with self.assertRaises(CodexError):
            self.queue.steer('thread', 'answer', 0, 'active')
        self.assertEqual('waiting', self.queue.list('thread')[0]['status'])


class NativeParityTests(unittest.TestCase):
    def test_pending_project_upload_uses_native_root_without_creating_or_resuming_thread(self):
        native = FakeNative()
        service = CodexService(connection=native)
        result = service.upload_project_attachment_stream('selected-project', 'report.txt', io.BytesIO(b'report'), 6)
        self.assertEqual('mention', result['type'])
        self.assertTrue(result['path'].startswith('/workspace/.rivo-attachments/'))
        project_read = next(params for method, params in native.calls if method == 'project/read')
        self.assertEqual('selected-project', project_read['projectId'])
        write = next(params for method, params in native.calls if method == 'fs/writeFile')
        self.assertEqual(result['path'], write['path'])
        self.assertFalse(any(method in ('thread/start', 'thread/resume', 'turn/start') for method, _ in native.calls))

    def test_todo_upload_uses_shared_native_storage_without_project_or_thread(self):
        native = FakeNative()
        native.native_host_info = lambda: {'codexHome': '/native/.codex'}
        service = CodexService(connection=native)
        result = service.upload_todo_attachment_stream('report.txt', io.BytesIO(b'report'), 6)
        self.assertTrue(result['path'].startswith('/native/.codex/rivo-relay-todo-media/'))
        write = next(params for method, params in native.calls if method == 'fs/writeFile')
        self.assertEqual(result['path'], write['path'])
        self.assertFalse(any(method.startswith(('project/', 'thread/', 'turn/')) for method, _ in native.calls))

    def test_new_active_turn_can_accept_queue_without_dismissing_old_recovery(self):
        native = FakeNative()
        service = CodexService(connection=native)
        db = Database(':memory:')
        self.addCleanup(db.close)
        with db.transaction() as c:
            c.execute("INSERT INTO connections VALUES ('phone','digest','Phone',1,NULL)")
        service.configure_storage(db)
        queue = ExecutionQueue(db, service, EventBus())
        queue.enqueue('thread', 'old', 'phone', {'input': [{'type': 'text', 'text': 'old task'}]})
        queue._set('old', 'unknown')
        with self.assertRaises(CodexError):
            queue.enqueue('thread', 'blocked', 'phone', {'input': [{'type': 'text', 'text': 'new'}]})
        native.thread['status'] = {'type': 'active'}
        queue.enqueue('thread', 'active-followup', 'phone', {'input': [{'type': 'text', 'text': 'new'}]})
        self.assertEqual(['old', 'active-followup'], [item['id'] for item in queue.list('thread')])
        self.assertTrue(queue.list('thread')[0]['recoveryRequired'])
        native.thread['status'] = {'type': 'idle'}
        with patch.object(service, 'read_goal', return_value={'goal': {'status': 'active'}}):
            queue.enqueue('thread', 'goal-followup', 'phone', {'input': [{'type': 'text', 'text': 'next'}]})
        self.assertEqual(['old', 'active-followup', 'goal-followup'], [item['id'] for item in queue.list('thread')])
        self.assertTrue(queue.list('thread')[0]['recoveryRequired'])
        with self.assertRaises(CodexError):
            service.send('thread', 'cannot bypass recovery with new turn')

    def test_missing_workspace_rebind_requires_preview_and_keeps_native_permissions(self):
        native = FakeNative()
        native.socket_path = '/fixture/native.sock'
        native.status = lambda: {'runtimeId': 20}
        native.native_host_info = lambda: {'generation': native.generation, 'pid': 20, 'codexHome': '/fixture'}
        native.thread.update(id='thread', cwd='/gone/worktree', projectId='project')
        original = native.request
        def request(method, params, **kwargs):
            if method == 'fs/getMetadata':
                native.calls.append((method, copy.deepcopy(params)))
                if params['path'] == '/gone/worktree':
                    raise CodexError('No such file or directory', code='native_rejected', http_status=409)
                return {'isDirectory': True}
            if method == 'thread/resume' and params.get('cwd'):
                native.thread['cwd'] = params['cwd']
            return original(method, params, **kwargs)
        native.request = request
        service = CodexService(connection=native)
        writers = Mock()
        writers.preview_scope.return_value = ['thread']
        service.writers = writers
        writers.preview.return_value = {'ticket': 'preview', 'affectedThreadIds': ['thread'], 'requiresConfirmation': False}
        writers.consume.return_value = {'threadId': 'thread', 'holders': []}
        writers.restart_command.return_value = None
        writers.snapshot.return_value = {'holders': [{'pid': 20}]}
        preview = service.preview_handoff('thread', 'takeover', 'phone')
        self.assertEqual({'path': '/workspace', 'missingPath': '/gone/worktree'}, preview['workspaceRebind'])
        self.assertTrue(preview['requiresConfirmation'])
        with self.assertRaises(CodexError):
            service.handoff('thread', 'takeover', owner='phone', ticket='preview', confirmed=True, rebind_workspace='/other')
        writers.terminate.assert_not_called()
        service.preview_handoff('thread', 'takeover', 'phone')
        service.handoff('thread', 'takeover', owner='phone', ticket='preview', confirmed=True, rebind_workspace='/workspace')
        resume = next(params for method, params in native.calls if method == 'thread/resume')
        self.assertEqual({'threadId': 'thread', 'excludeTurns': True, 'cwd': '/workspace'}, resume)
        self.assertFalse(any(method == 'turn/start' for method, _ in native.calls))

    def test_request_resolution_is_generation_bound_and_legacy_thread_key_expires(self):
        native = FakeNative()
        interactions = Interactions(native)
        interactions.receive({'id': 1, 'method': 'item/tool/requestUserInput', 'params': {'conversationId': 'thread'}})
        interactions.receive({'method': 'serverRequest/resolved', 'connectionId': 'old', 'params': {'requestId': 1}})
        self.assertEqual(1, len(interactions.list('thread')))
        interactions.receive({'method': 'serverRequest/resolved', 'params': {'requestId': '1'}})
        self.assertEqual(1, len(interactions.list('thread')))
        interactions.receive({'method': 'turn/completed', 'params': {'threadId': 'thread', 'turn': {'id': 'turn'}}})
        self.assertEqual([], interactions.list('thread'))

    def test_settings_change_and_next_turn_keep_service_tier(self):
        native = FakeNative()
        service = CodexService(connection=native)
        service.update_settings('thread', service_tier='priority')
        service.send('thread', 'hello')
        self.assertEqual('priority', next(params for method, params in native.calls if method == 'turn/start')['serviceTier'])
        self.assertEqual('priority', service.get_session('thread')['settings']['serviceTier'])
        self.assertFalse(any('approvalPolicy' in params or 'sandbox' in params for method, params in native.calls if method == 'thread/settings/update'))

    def test_explicit_message_resumes_released_session_with_recovery_record(self):
        native = FakeNative()
        service = CodexService(connection=native)
        service.start()
        service._observe('thread', 'ownership', '', {'released': True})
        service.execution_queue = Mock()
        service.execution_queue.list.return_value = [{'id': 'old', 'recoveryRequired': True}]
        with self.assertRaises(CodexError) as raised:
            service.send('thread', 'automatic dispatch')
        self.assertEqual('recovery_required', raised.exception.code)
        service.send('thread', '继续检查当前状态', acquire_released=True)
        turns = [params for method, params in native.calls if method == 'turn/start']
        self.assertEqual(1, len(turns))
        self.assertEqual('继续检查当前状态', turns[0]['input'][0]['text'])
        self.assertFalse(service.observations_for('thread').get(('ownership', ''), {}).get('released'))
        service.execution_queue.change.assert_not_called()

    def test_pending_question_blocks_new_turn_before_rpc(self):
        native = FakeNative()
        service = CodexService(connection=native)
        service.start()
        service._event({'id': 1, 'method': 'item/tool/requestUserInput', 'params': {'threadId': 'thread', 'questions': [{'id': 'q'}]}})
        with self.assertRaises(CodexError) as raised:
            service.send('thread', 'new task', acquire_released=True)
        self.assertEqual('interaction_pending', raised.exception.code)
        self.assertFalse(any(method == 'turn/start' for method, _ in native.calls))

    def test_live_head_deltas_preserve_verified_history_paging(self):
        native = FakeNative()
        original = native.request
        def request(method, params, **kwargs):
            if method == 'thread/turns/list':
                return {'data': [], 'nextCursor': 'older'}
            return original(method, params, **kwargs)
        native.request = request
        service = CodexService(connection=native)
        service.start()
        service._event({'method': 'turn/started', 'params': {'threadId': 'thread', 'turn': {'id': 'turn', 'status': 'inProgress', 'items': []}}})
        service._event({'method': 'item/agentMessage/delta', 'params': {'threadId': 'thread', 'turnId': 'turn', 'itemId': 'answer', 'delta': 'live text'}})
        result = service.history('thread')
        self.assertEqual('converging', result['nativeSync']['state'])
        self.assertEqual('older', result['nextCursor'])
        self.assertTrue(result['nativeSync']['historyReady'])
        self.assertEqual('live text', result['turns'][0]['items'][0]['text'])

    def test_completion_ahead_of_persisted_history_does_not_publish_ready_empty_page(self):
        native = FakeNative()
        service = CodexService(connection=native)
        service.start()
        service._event({'method': 'turn/started', 'params': {'threadId': 'thread', 'turn': {'id': 'turn', 'status': 'inProgress', 'items': []}}})
        service._event({'method': 'item/agentMessage/delta', 'params': {'threadId': 'thread', 'turnId': 'turn', 'itemId': 'answer', 'delta': 'finished answer'}})
        service._event({'method': 'turn/completed', 'params': {'threadId': 'thread', 'turn': {'id': 'turn', 'status': 'completed', 'items': []}}})
        waiting = service.history('thread')
        self.assertEqual('converging', waiting['nativeSync']['state'])
        self.assertEqual('finished answer', waiting['turns'][0]['items'][0]['text'])
        canonical = {'id': 'turn', 'status': 'completed', 'items': [{'id': 'answer', 'type': 'agentMessage', 'text': 'finished answer'}]}
        native.request = lambda *args, **kwargs: {'data': [canonical], 'nextCursor': None}
        settled = service.history('thread')
        self.assertEqual('ready', settled['nativeSync']['state'])
        self.assertFalse(service._pending_terminal)

    def test_revision_changes_on_item_content_not_only_identity(self):
        native = FakeNative()
        turn = {'id': 'turn', 'status': 'completed', 'items': [{'id': 'item', 'type': 'agentMessage', 'text': 'first'}]}
        native.request = lambda *args, **kwargs: {'data': [copy.deepcopy(turn)], 'nextCursor': 'older'}
        service = CodexService(connection=native)
        first = service.history('thread')
        turn['items'][0]['text'] = 'changed'
        with self.assertRaises(CodexError) as raised:
            service.history('thread', cursor='older', native_revision=first['nativeRevision'])
        self.assertEqual('history_changed', raised.exception.code)

    def test_projection_keeps_native_execution_separate_from_queue_recovery(self):
        recovery = {'status': 'unknown', 'recoveryRequired': True}
        result = execution_projection('running', [recovery, {'status': 'waiting'}])
        self.assertEqual('running', result['nativeExecution'])
        self.assertEqual('executing_with_queue', result['displayState'])
        self.assertTrue(result['recoveryRequired'])
        self.assertEqual(1, result['queueDepth'])

    def test_questions_require_exact_answer_ids_and_preserve_request_on_invalid_response(self):
        native = FakeNative()
        interactions = Interactions(native)
        interactions.receive({'id': 1, 'method': 'item/tool/requestUserInput', 'params': {'threadId': 'thread', 'questions': [{'id': 'q'}]}})
        key = interactions.list('thread')[0]['key']
        with self.assertRaises(CodexError):
            interactions.respond(key, {'answers': {'other': {'answers': ['wrong']}}})
        self.assertEqual(1, len(interactions.list('thread')))
        interactions.respond(key, {'answers': {'q': {'answers': ['answer']}}})
        self.assertEqual(1, len(native.replies))


class ProjectTrustParityTests(unittest.TestCase):
    def test_explicit_trust_uses_node_config_and_exact_quoted_path(self):
        native = FakeNative()
        path = '/node/with."quoted"/project'
        responses = [
            {'path': path}, {'config': {'projects': None}, 'layers': [{'name': {'type': 'user'}, 'version': 'v1'}]},
            {'status': 'ok'}, {'config': {'projects': {path: {'trust_level': 'trusted'}}}},
            {'project': {'id': 'project', 'name': 'Project', 'roots': [{'path': path}]}}]
        def request(method, params, **kwargs):
            native.calls.append((method, copy.deepcopy(params)))
            return responses.pop(0)
        native.request = request
        ProjectCatalog(native).add(path, trust_confirmed=True)
        write = next(params for method, params in native.calls if method == 'config/batchWrite')
        self.assertNotIn('filePath', write)
        self.assertEqual('v1', write['expectedVersion'])
        self.assertEqual('projects.' + json.dumps(path, ensure_ascii=False) + '.trust_level', write['edits'][0]['keyPath'])
        self.assertEqual('project/create', native.calls[-1][0])

    def test_unverified_trust_cannot_register_project(self):
        native = FakeNative()
        responses = [{'path': '/node/project'}, {'config': {}}, {'status': 'okOverridden'}]
        def request(method, params, **kwargs):
            native.calls.append((method, copy.deepcopy(params)))
            return responses.pop(0)
        native.request = request
        with self.assertRaises(CodexError):
            ProjectCatalog(native).add('/node/project', trust_confirmed=True)
        self.assertFalse(any(method == 'project/create' for method, _ in native.calls))


class ModelCatalogParityTests(unittest.TestCase):
    def test_cached_catalog_is_copied_and_refresh_failure_uses_last_good(self):
        calls = []
        available = [{'id': 'dynamic-model', 'model': 'native-name', 'supportedReasoningEfforts': [{'reasoningEffort': 'high'}]}]
        def loader():
            calls.append(True)
            return available
        catalog = ModelCatalog(loader, lambda: 'runtime', clock=lambda: 100)
        first = catalog.public()
        first['models'].clear()
        self.assertEqual('dynamic-model', catalog.public()['models'][0]['id'])
        self.assertEqual(1, len(calls))
        available.clear()
        fallback = catalog.public(refresh=True)
        self.assertTrue(fallback['catalog']['stale'])
        self.assertEqual('last_known_good', fallback['catalog']['source'])
        self.assertEqual('dynamic-model', fallback['models'][0]['id'])

    def test_last_good_catalog_survives_service_recreation_without_guessed_models(self):
        db = Database(':memory:')
        self.addCleanup(db.close)
        observations = Observations(db)
        first = ModelCatalog(lambda: [{'id': 'node-model'}], lambda: 'runtime-a')
        first.configure(observations)
        first.public()
        def unavailable():
            raise CodexError('offline')
        restored = ModelCatalog(unavailable, lambda: 'runtime-b')
        restored.configure(observations)
        result = restored.public()
        self.assertEqual([{'id': 'node-model'}], result['models'])
        self.assertTrue(result['catalog']['stale'])
        with self.assertRaises(CodexError):
            ModelCatalog(unavailable, lambda: 'other').public()
