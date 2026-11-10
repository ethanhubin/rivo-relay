import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from rivo_relay.codex.activity import ActivityRegistry


def snapshot(tasks=()):
    return {'tasks': list(tasks), 'unknownThreadIds': [], 'knownThreadIds': ['t'],
            'projectByThread': {'t': 'p'}}


def event(method, turn='one', **params):
    return {'method': method, 'params': {'threadId': 't', 'turn': {'id': turn}, **params}}


class ActivityTests(unittest.TestCase):
    def registry(self, loader=lambda: snapshot()):
        return ActivityRegistry(loader, lambda: 'g')

    def test_read_never_invokes_loader(self):
        def load():
            self.fail('read must not load')
        registry = self.registry(load)
        self.assertEqual(registry.snapshot()['tasks'], [])
        registry.receive(event('turn/started', projectId='p'))
        self.assertEqual(registry.snapshot()['tasks'][0]['projectId'], 'p')

    def test_blocked_reconciliation_does_not_block_reads_or_events(self):
        started, release = threading.Event(), threading.Event()
        def load():
            started.set()
            self.assertTrue(release.wait(3))
            return snapshot()
        registry = self.registry(load)
        with ThreadPoolExecutor(max_workers=2) as pool:
            pending = pool.submit(registry.reconcile)
            self.assertTrue(started.wait(1))
            registry.receive(event('turn/started', projectId='p'))
            result = pool.submit(registry.snapshot).result(1)
            self.assertEqual(result['tasks'][0]['status'], 'running')
            release.set()
            self.assertFalse(pending.result(1))
            self.assertEqual(registry.snapshot()['tasks'][0]['status'], 'running')

    def test_delayed_terminal_cannot_clear_new_turn(self):
        registry = self.registry()
        registry.receive(event('turn/started', 'one', projectId='p'))
        registry.receive(event('turn/started', 'two'))
        registry.receive(event('turn/completed', 'one'))
        self.assertEqual(registry.snapshot()['tasks'][0]['turnId'], 'two')
        registry.receive(event('turn/completed', 'two'))
        self.assertEqual(registry.snapshot()['tasks'], [])
        self.assertNotIn('t', registry.snapshot()['unknownThreadIds'])
        self.assertIn('t', registry.snapshot()['knownThreadIds'])

    def test_terminal_reconciles_metadata_activity_without_start_event(self):
        for method in ('turn/completed', 'turn/failed', 'turn/interrupted'):
            with self.subTest(method=method):
                registry = self.registry()
                registry.observe_metadata([{'id': 't', 'status': {'type': 'active'}}], {}, 'g', 0)
                registry.receive(event(method))
                self.assertEqual(registry.snapshot()['tasks'][0]['status'], 'running')
                self.assertIn('t', registry.snapshot()['unknownThreadIds'])
                registry.reconcile()
                self.assertEqual(registry.snapshot()['tasks'], [])

    def test_terminal_respects_reconciled_newer_turn(self):
        registry = self.registry(lambda: snapshot([{'threadId': 't', 'turnId': 'two', 'status': 'running'}]))
        registry.reconcile()
        registry.receive(event('turn/completed', 'one'))
        self.assertEqual(registry.snapshot()['tasks'][0]['turnId'], 'two')
        registry.receive(event('turn/completed', 'two'))
        self.assertEqual(registry.snapshot()['tasks'], [])

    def test_terminal_preserves_active_goal_without_start_event(self):
        registry = self.registry(lambda: snapshot([{'threadId': 't', 'goalActive': True, 'status': 'running'}]))
        registry.reconcile()
        registry.receive(event('turn/completed'))
        self.assertEqual(registry.snapshot()['tasks'][0]['status'], 'running')
        self.assertTrue(registry.snapshot()['tasks'][0]['goalActive'])

    def test_idle_clears_running_without_terminal_event(self):
        registry = self.registry()
        registry.receive(event('turn/started'))
        registry.receive({'method': 'thread/status/changed', 'params': {'threadId': 't', 'status': {'type': 'idle'}}})
        self.assertEqual(registry.snapshot()['tasks'], [])
        self.assertNotIn('t', registry.snapshot()['unknownThreadIds'])

    def test_active_notification_preserves_restored_turn_identity(self):
        registry = self.registry(lambda: snapshot([{'threadId': 't', 'turnId': 'two', 'status': 'running'}]))
        registry.reconcile()
        registry.receive({'method': 'thread/status/changed', 'params': {'threadId': 't', 'status': {'type': 'active'}}})
        registry.receive(event('turn/completed', 'one'))
        self.assertEqual(registry.snapshot()['tasks'][0]['turnId'], 'two')

    def test_idle_preserves_delivery_and_goal(self):
        for extra in ({'deliveryState': 'queued', 'queueDepth': 1}, {'goalActive': True}):
            registry = self.registry(lambda: snapshot([{'threadId': 't', 'status': 'running', **extra}]))
            registry.reconcile()
            registry.receive({'method': 'thread/status/changed', 'params': {'threadId': 't', 'status': {'type': 'idle'}}})
            row = registry.snapshot()['tasks'][0]
            for key, value in extra.items():
                self.assertEqual(row[key], value)
            self.assertEqual(row['displayState'], 'executing' if extra.get('goalActive') else 'queued')

    def test_membership_survives_completion_and_next_start(self):
        registry = self.registry()
        registry.reconcile()
        registry.receive(event('turn/started'))
        self.assertEqual(registry.snapshot()['tasks'][0]['projectId'], 'p')

    def test_generation_change_clears_old_evidence(self):
        generation = ['a']
        registry = ActivityRegistry(lambda: snapshot(), lambda: generation[0])
        registry.receive(event('turn/started', projectId='p'))
        generation[0] = 'b'
        self.assertEqual(registry.snapshot()['tasks'], [])

    def test_unknown_reconciliation_preserves_running_evidence(self):
        registry = self.registry(lambda: {**snapshot(), 'unknownThreadIds': ['t']})
        registry.receive(event('turn/started', projectId='p'))
        registry.reconcile()
        self.assertEqual(registry.snapshot()['tasks'][0]['status'], 'running')

    def test_worker_failure_retains_table_and_close_stops_worker(self):
        failed = threading.Event()
        def load():
            failed.set()
            raise RuntimeError('offline')
        registry = self.registry(load)
        registry.receive(event('turn/started', projectId='p'))
        registry.start()
        self.assertTrue(failed.wait(1))
        registry.close()
        registry.worker.join(1)
        self.assertFalse(registry.worker.is_alive())
        self.assertIn('t', registry.snapshot()['unknownThreadIds'])
        self.assertFalse(registry.snapshot()['reconciled'])
        self.assertEqual(registry.snapshot()['tasks'][0]['status'], 'running')

    def test_metadata_before_slow_probes_publishes_project_activity(self):
        registry = self.registry()
        registry.observe_metadata([{'id': 't', 'cwd': '/p', 'status': {'type': 'active'}}], {'/p': 'p'}, 'g', 0)
        self.assertEqual(registry.snapshot()['tasks'][0]['projectId'], 'p')

    def test_old_metadata_cannot_resurrect_completed_turn(self):
        registry = self.registry()
        revision = registry.revision
        registry.receive(event('turn/started', projectId='p'))
        registry.receive(event('turn/completed'))
        registry.observe_metadata([{'id': 't', 'projectId': 'p', 'status': {'type': 'active'}}], {}, 'g', revision)
        self.assertEqual(registry.snapshot()['tasks'], [])

    def test_service_keeps_detected_running_task_when_goal_probe_fails(self):
        from unittest.mock import Mock
        from rivo_relay.codex.sessions import CodexService
        service = CodexService.__new__(CodexService)
        service.connection = Mock(generation='g')
        service.connection.request.side_effect = lambda method, params: {'data': (
            [{'id': 'p', 'roots': [{'path': '/p'}]}] if method == 'project/list' else
            [{'id': 't', 'projectId': 'p', 'status': {'type': 'active'}}])}
        service.projects = Mock()
        service.projects.external_worktrees.associations.return_value = {}
        service._activity_discovery = None
        service._external_running = Mock(return_value={})
        service._external_execution = Mock(return_value={})
        service.read_goal = Mock(side_effect=RuntimeError('slow goal'))
        service.activity = ActivityRegistry(service._load_activity, lambda: 'g')
        with self.assertRaises(RuntimeError):
            service.activity.reconcile()
        row = service.activity.snapshot()['tasks'][0]
        self.assertEqual((row['threadId'], row['projectId'], row['status']), ('t', 'p', 'running'))
        service.projects.thread_catalog.list.assert_not_called()

    def test_completed_turn_cannot_be_resurrected_by_delayed_start(self):
        registry = self.registry()
        registry.receive(event('turn/started', projectId='p'))
        registry.receive(event('turn/completed'))
        registry.receive(event('turn/started', projectId='p'))
        self.assertEqual(registry.snapshot()['tasks'], [])

    def test_unrelated_active_flag_is_not_waiting(self):
        registry = self.registry()
        registry.receive(event('thread/status/changed', projectId='p',
                               status={'type': 'active', 'activeFlags': ['otherFlag']}))
        self.assertEqual(registry.snapshot()['tasks'][0]['status'], 'running')

    def test_delete_is_confirmed_absence_not_unknown(self):
        registry = self.registry()
        registry.receive(event('turn/started', projectId='p'))
        registry.receive(event('thread/deleted'))
        result = registry.snapshot()
        self.assertEqual(result['tasks'], [])
        self.assertIn('t', result['knownThreadIds'])
        self.assertNotIn('t', result['unknownThreadIds'])

    def test_active_goal_remains_between_turns(self):
        registry = self.registry()
        registry.receive(event('turn/started', projectId='p'))
        registry.tasks['t']['goalActive'] = True
        registry.receive(event('turn/completed'))
        self.assertEqual(registry.snapshot()['tasks'][0]['status'], 'running')

    def test_new_turn_preserves_known_active_goal(self):
        registry = self.registry()
        registry.receive(event('turn/started', projectId='p'))
        registry.tasks['t']['goalActive'] = True
        registry.receive(event('turn/started', 'two'))
        registry.receive(event('turn/completed', 'two'))
        self.assertTrue(registry.snapshot()['tasks'][0]['goalActive'])

    def test_late_approval_cannot_replace_newer_running_turn(self):
        registry = self.registry()
        registry.receive(event('turn/started', 'one', projectId='p'))
        registry.receive(event('turn/completed', 'one'))
        registry.receive(event('turn/started', 'two'))
        registry.receive(event('item/tool/requestApproval', 'one'))
        self.assertEqual(registry.snapshot()['tasks'][0]['status'], 'running')
        self.assertEqual(registry.snapshot()['tasks'][0]['turnId'], 'two')

    def test_metadata_assigns_project_to_event_discovered_task(self):
        registry = self.registry()
        registry.receive(event('turn/started'))
        self.assertEqual(registry.snapshot()['tasks'][0]['projectId'], '')
        registry.observe_metadata([{'id': 't', 'cwd': '/p', 'status': {'type': 'active'}}],
                                  {'/p': 'p'}, 'g', registry.revision)
        self.assertEqual(registry.snapshot()['tasks'][0]['projectId'], 'p')

    def test_service_publishes_event_after_activity_table_update(self):
        import tempfile
        from unittest.mock import Mock
        from rivo_relay.codex.sessions import CodexService
        observed = []
        with tempfile.TemporaryDirectory() as directory:
            native = Mock(generation='g', codex_home=directory)
            service = CodexService(connection=native, on_event=lambda _: observed.append(service.activity.snapshot()))
            service._event(event('turn/started', projectId='p'))
            self.assertEqual(observed[-1]['tasks'][0]['status'], 'running')
            service._event(event('turn/completed'))
            self.assertEqual(observed[-1]['tasks'], [])

    def test_stale_index_cannot_restore_archived_task(self):
        row = {'threadId': 't', 'projectId': 'p', 'status': 'running'}
        registry = self.registry(lambda: snapshot([row]))
        registry.receive(event('turn/started', projectId='p'))
        registry.receive(event('thread/archived'))
        registry.reconcile()
        registry.observe_metadata([{'id': 't', 'projectId': 'p', 'status': {'type': 'active'}}], {}, 'g', registry.revision)
        self.assertEqual(registry.snapshot()['tasks'], [])
        registry.receive(event('thread/unarchived'))
        registry.reconcile()
        self.assertEqual(len(registry.snapshot()['tasks']), 1)

    def test_completed_waiting_turn_with_goal_no_longer_shows_old_prompt(self):
        registry = self.registry()
        registry.receive(event('turn/started', projectId='p'))
        registry.tasks['t']['goalActive'] = True
        registry.receive(event('item/tool/requestUserInput'))
        registry.receive(event('turn/completed'))
        self.assertEqual(registry.snapshot()['tasks'][0]['status'], 'running')

    def test_fast_metadata_uses_previous_worktree_membership(self):
        registry = self.registry()
        registry.reconcile()
        registry.observe_metadata([{'id': 't', 'cwd': '/worktree', 'status': {'type': 'active'}}], {}, 'g', registry.revision)
        self.assertEqual(registry.snapshot()['tasks'][0]['projectId'], 'p')

    def test_failed_reconciliation_does_not_make_confirmed_completion_unknown(self):
        failed = threading.Event()
        def load():
            failed.set()
            raise RuntimeError('offline')
        registry = self.registry(load)
        registry.receive(event('turn/started', projectId='p'))
        registry.receive(event('turn/completed'))
        registry.start()
        self.assertTrue(failed.wait(1))
        registry.close()
        registry.worker.join(1)
        result = registry.snapshot()
        self.assertEqual(result['tasks'], [])
        self.assertNotIn('t', result['unknownThreadIds'])
        registry.receive(event('turn/started', 'two'))
        self.assertEqual(registry.snapshot()['tasks'][0]['turnId'], 'two')


class ActivityDiscoveryTests(unittest.TestCase):
    def service(self):
        from unittest.mock import Mock
        from rivo_relay.codex.sessions import CodexService
        service = CodexService.__new__(CodexService)
        service.start = Mock()
        service.connection = Mock(generation='g')
        service.workspaces = None
        service.projects = Mock()
        service.projects.get.return_value = {'id': 'p', 'roots': [{'path': '/p'}]}
        service.projects.external_worktrees.associations.return_value = {}
        service._activity_discovery = None
        service._external_running = Mock(return_value={})
        service._external_execution = Mock(return_value={})
        service.read_goal = Mock(return_value={'goal': None})
        service.interactions = Mock()
        service.interactions.list.return_value = []
        service.activity = ActivityRegistry(service._load_activity, lambda: 'g')
        return service

    def test_unknown_cached_task_is_not_published_as_running_in_list(self):
        service = self.service()
        service.projects.thread_catalog.list.return_value = [
            {'id': 't', 'projectId': 'p', 'status': {'type': 'notLoaded'}}]
        service.activity.load = lambda: {**snapshot(), 'unknownThreadIds': ['t']}
        service.activity.receive(event('turn/started', projectId='p'))
        service.activity.reconcile()
        self.assertEqual(service.activity.snapshot()['tasks'][0]['status'], 'running')
        row = service.list_sessions('p')[0]
        self.assertEqual(row['status'], 'unknown')
        self.assertEqual(row['nativeExecution'], 'unknown')

    def test_idle_thread_restart_and_new_thread_are_discovered_without_events(self):
        for native in (False, True):
            with self.subTest(native=native):
                service = self.service()
                rows = [{'id': 't', 'projectId': 'p', 'status': {'type': 'notLoaded'}}]
                phase = ['running']
                def request(method, params):
                    if method == 'project/list':
                        return {'data': [{'id': 'p', 'roots': []}]}
                    self.assertEqual(method, 'thread/list')
                    self.assertTrue(params['useStateDbOnly'])
                    return {'data': rows}
                service.connection.request.side_effect = request
                service._external_running.side_effect = lambda threads: (
                    {t['id']: 'running' for t in threads} if phase[0] == 'running' and not native else {})
                service._external_execution.side_effect = lambda threads: {t['id']: phase[0] for t in threads}
                for state in ('running', 'idle', 'running'):
                    phase[0] = state
                    if native:
                        rows[0]['status'] = {'type': 'active' if state == 'running' else 'idle'}
                    self.assertTrue(service.activity.reconcile())
                    tasks = service.activity.snapshot()['tasks']
                    self.assertEqual([t['threadId'] for t in tasks], ['t'] if state == 'running' else [])
                rows.append({'id': 'new', 'projectId': 'p', 'status': {'type': 'active' if native else 'notLoaded'}})
                service.activity.reconcile()
                self.assertEqual({t['threadId'] for t in service.activity.snapshot()['tasks']}, {'t', 'new'})
                # Only project membership uses the longer discovery interval.
                self.assertEqual(sum(call.args[0] == 'project/list' for call in service.connection.request.call_args_list), 1)
                service.projects.thread_catalog.list.assert_not_called()

    def test_active_thread_missing_from_index_is_read_before_completion(self):
        service = self.service()
        service.activity.receive(event('turn/started', projectId='p'))
        def request(method, params):
            if method == 'thread/read':
                return {'thread': {'id': 't', 'projectId': 'p', 'status': {'type': 'active'}}}
            return {'data': []}
        service.connection.request.side_effect = request
        service.activity.reconcile()
        self.assertEqual(service.activity.snapshot()['tasks'][0]['threadId'], 't')
        service.connection.request.assert_any_call('thread/read', {'threadId': 't', 'includeTurns': False})
