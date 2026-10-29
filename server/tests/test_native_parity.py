"""Native parity behavior: mode permissions, observations and ordered reviews."""
import copy
import unittest

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.sessions import CodexService
from rivo_relay.database import Database
from rivo_relay.events import EventBus
from rivo_relay.queue import ExecutionQueue, QueueConflict
from test_codex import FakeNative


class ParityNative(FakeNative):
    def __init__(self):
        super().__init__()
        self.sandbox = {'type': 'workspaceWrite', 'writableRoots': ['/workspace'], 'networkAccess': False,
                        'excludeTmpdirEnvVar': True, 'excludeSlashTmp': True}
        self.fail = None

    def request(self, method, params, **kwargs):
        result = super().request(method, params, **kwargs)
        if method in ('thread/start', 'thread/resume'):
            result['sandbox'] = copy.deepcopy(self.sandbox)
            result['collaborationMode'] = {'mode': 'default', 'settings': {'model': 'native-model'}}
        if method == 'turn/start' and self.fail:
            raise self.fail
        if method == 'review/start':
            return {'turn': {'id': 'review-turn'}}
        if method == 'account/rateLimits/read':
            return {'rateLimits': {'primary': {'usedPercent': 25}}, 'accountId': 'private-id'}
        return result


class NativeParityTests(unittest.TestCase):
    def setUp(self):
        self.db = Database(':memory:')
        self.addCleanup(self.db.close)
        self.native = ParityNative()
        self.service = CodexService(connection=self.native)
        self.service.configure_storage(self.db)

    def turns(self):
        return [p for m, p in self.native.calls if m == 'turn/start']

    def test_plan_reduces_permissions_and_default_restores_exact_original_once(self):
        self.service.send('thread', 'think', mode='plan')
        plan = self.turns()[-1]
        self.assertEqual('plan', plan['collaborationMode']['mode'])
        self.assertEqual({'type': 'readOnly', 'networkAccess': False}, plan['sandboxPolicy'])
        self.assertNotIn('approvalPolicy', plan)
        self.service.send('thread', 'do', mode='default')
        self.assertEqual(self.native.sandbox, self.turns()[-1]['sandboxPolicy'])
        self.assertNotIn(('modeBaseline', ''), self.service.observations_for('thread'))
        self.service.send('thread', 'continue', mode='default')
        self.assertNotIn('sandboxPolicy', self.turns()[-1])

    def test_default_preserves_permissions_changed_by_another_client(self):
        self.service.send('thread', 'think', mode='plan')
        self.service._event({'method': 'thread/settings/updated', 'params': {'threadId': 'thread',
            'threadSettings': {'model': 'native-model', 'sandboxPolicy': {'type': 'readOnly', 'networkAccess': False},
                               'collaborationMode': {'mode': 'default'}}}})
        self.service.send('thread', 'continue', mode='default')
        self.assertNotIn('sandboxPolicy', self.turns()[-1])
        self.assertNotIn(('modeBaseline', ''), self.service.observations_for('thread'))

    def test_rejected_plan_does_not_leave_permission_restore_marker(self):
        self.native.fail = CodexError('rejected', code='native_rejected', http_status=409)
        with self.assertRaises(CodexError):
            self.service.send('thread', 'think', mode='plan')
        self.assertNotIn(('modeBaseline', ''), self.service.observations_for('thread'))
        self.assertEqual(self.native.sandbox, self.service._settings['thread']['sandbox'])

    def test_inherited_mode_never_sends_permissions_or_collaboration_override(self):
        self.service.send('thread', 'continue')
        self.assertFalse({'sandboxPolicy', 'permissions', 'approvalPolicy', 'collaborationMode'} & self.turns()[-1].keys())

    def test_accepted_first_turn_can_be_read_before_rollout_materializes(self):
        self.service.create_session('project')
        self.service.send('thread', 'first', mode='plan')
        request = self.native.request
        def before_rollout(method, params, **kwargs):
            if method in ('thread/read', 'thread/turns/list'):
                raise CodexError('failed to read session metadata: rollout at /test/rollout.jsonl is empty', code='native_rejected', http_status=409)
            return request(method, params, **kwargs)
        self.native.request = before_rollout
        snapshot = self.service.get_session('thread')
        self.assertEqual('turn', snapshot['activeTurnId'])
        self.assertEqual('running', snapshot['status'])
        self.assertEqual('turn', snapshot['thread']['turns'][0]['id'])
        def after_rollout(method, params, **kwargs):
            if method == 'thread/turns/list':
                return {'data': [{'id': 'turn', 'status': 'completed', 'items': []}], 'nextCursor': None}
            return request(method, params, **kwargs)
        self.native.request = after_rollout
        self.service.get_session('thread')
        self.assertNotIn('thread', self.service._materializing)

    def test_observations_survive_service_recreation_and_latest_usage_wins(self):
        self.service.start()
        self.service._event({'method': 'turn/plan/updated', 'params': {'threadId': 'thread', 'turnId': 'turn',
            'plan': [{'step': 'inspect', 'status': 'inProgress'}]}})
        for turn, total in [('one', 10), ('two', 20), ('one', 30)]:
            self.service._event({'method': 'thread/tokenUsage/updated', 'params': {'threadId': 'thread', 'turnId': turn,
                'tokenUsage': {'last': {'totalTokens': total}, 'modelContextWindow': 100}}})
        recreated = CodexService(connection=ParityNative())
        recreated.configure_storage(self.db)
        observations = recreated.observations_for('thread')
        self.assertEqual('inspect', observations[('plan', 'turn')]['plan'][0]['step'])
        self.assertEqual(30, recreated.get_session('thread')['tokenUsage']['last']['totalTokens'])

    def test_quota_does_not_expose_account_identifiers(self):
        self.assertEqual({'rateLimits': {'primary': {'usedPercent': 25}}}, self.service.usage())

    def test_accepted_turn_settings_and_reroute_are_observed_without_guessing_history(self):
        self.assertEqual([], self.service.get_session('thread')['turnSettings'])
        self.service.send('thread', 'test', model='chosen-model', effort='high')
        self.assertEqual([{'turnId': 'turn', 'model': 'chosen-model', 'effort': 'high'}], self.service.get_session('thread')['turnSettings'])
        self.service._event({'method': 'model/rerouted', 'params': {'threadId': 'thread', 'turnId': 'turn',
            'fromModel': 'chosen-model', 'toModel': 'native-fallback', 'reason': 'rateLimited'}})
        self.assertEqual('native-fallback', self.service.get_session('thread')['turnSettings'][0]['model'])

    def test_reroute_arriving_before_start_response_keeps_native_selected_model(self):
        request = self.native.request
        def with_reroute(method, params, **kwargs):
            if method == 'turn/start':
                self.service._event({'method': 'model/rerouted', 'params': {'threadId': 'thread', 'turnId': 'turn',
                    'fromModel': 'chosen-model', 'toModel': 'native-fallback'}})
            return request(method, params, **kwargs)
        self.native.request = with_reroute
        self.service.send('thread', 'test', model='chosen-model')
        self.assertEqual('native-fallback', self.service.get_session('thread')['turnSettings'][0]['model'])

    def test_archive_rejects_native_running_and_queue_under_same_lock(self):
        self.native.thread['status'] = {'type': 'active'}
        with self.assertRaises(CodexError):
            self.service.archive_session('thread', True)
        self.native.thread['status'] = {'type': 'idle'}
        with self.db.transaction() as c:
            c.execute("INSERT INTO connections VALUES ('phone','digest','Phone',1,NULL)")
        queue = ExecutionQueue(self.db, self.service, EventBus())
        queue.enqueue('thread', 'queued', 'phone', {'input': [{'type': 'text', 'text': 'wait'}]})
        with self.assertRaises(CodexError):
            self.service.archive_session('thread', True)
        self.assertNotIn('thread/archive', [m for m, _ in self.native.calls])
        queue.change('thread', 'queued', 0, 'remove')
        self.service.archive_session('thread', True)
        self.service.archive_session('thread', False)
        self.assertEqual(['thread/archive', 'thread/unarchive'], [m for m, _ in self.native.calls if m in ('thread/archive', 'thread/unarchive')])

    def test_review_queue_dispatches_once_via_native_review_not_message(self):
        with self.db.transaction() as c:
            c.execute("INSERT INTO connections VALUES ('phone','digest','Phone',1,NULL)")
        queue = ExecutionQueue(self.db, self.service, EventBus())
        queue.enqueue('thread', 'review', 'phone', {'input': [{'type': 'text', 'text': 'Review'}],
            'review': {'type': 'uncommittedChanges'}})
        with self.assertRaises(QueueConflict):
            queue.change('thread', 'review', 0, 'edit', 'changed')
        queue.tick()
        self.assertEqual('running', queue.list('thread')[0]['status'])
        self.assertEqual('review-turn', queue.list('thread')[0]['turnId'])
        self.assertEqual(1, len([m for m, _ in self.native.calls if m == 'review/start']))
        self.assertEqual([], self.turns())


if __name__ == '__main__':
    unittest.main()
