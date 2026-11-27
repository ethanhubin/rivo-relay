from copy import deepcopy
import json
from pathlib import Path
import threading
import unittest
from unittest.mock import patch

from relay_test_support.http import RelayHTTPFixture
from rivo_relay.antigravity.desktop_service import AntigravityDesktopService
from rivo_relay.antigravity.desktop_transport import DesktopError, DesktopPeer, _private_registry_file
from rivo_relay.app import RelayApp
from rivo_relay.api import Handler
from rivo_relay.config import Config
from rivo_relay.events import EventBus
from rivo_relay.http_server import BoundedHTTPServer


class DesktopFixturePeer:
    generation = 'verified-desktop-generation'
    version = '2.35.2'

    def __init__(self, root):
        self.uri = root.as_uri()
        self.events = []
        self.offline = False
        self.write_ambiguous = False
        self.change_state_without_effect = False
        self.change_permission_evidence = None
        self.outside_running = False
        self.cascade_config = {'plannerConfig': {'planModel': 'fixture-native-model'}}
        self.defer_echo = False
        self.drop_send_response = False
        self.status = 'CASCADE_RUN_STATUS_RUNNING'
        self.steps = [
            {'type': 'CORTEX_STEP_TYPE_USER_INPUT', 'status': 'CORTEX_STEP_STATUS_DONE',
             'userInput': {'items': [{'text': 'Check the test workspace.'}]}},
            {'type': 'CORTEX_STEP_TYPE_PLANNER_RESPONSE', 'status': 'CORTEX_STEP_STATUS_DONE',
             'plannerResponse': {'response': 'I will run the fixture.'}},
            {'type': 'CORTEX_STEP_TYPE_RUN_COMMAND', 'status': 'CORTEX_STEP_STATUS_WAITING',
             'metadata': {'createdAt': '2026-11-27T00:00:00Z', 'toolCall': {'id': 'fixture-command'}},
             'requestedInteraction': {'runCommand': {'commandLine': 'python test_fixture.py'}},
             'runCommand': {'proposedCommandLine': 'python test_fixture.py', 'cwd': str(root)}},
        ]

    def summaries(self):
        if self.offline:
            raise DesktopError('fixture unavailable')
        return {'trajectorySummaries': {
            'visible': {'status': self.status, 'summary': 'Existing desktop session',
                        'stepCount': len(self.steps), 'trajectoryMetadata': {'workspaceUris': [self.uri]}},
            'outside': {'status': 'CASCADE_RUN_STATUS_RUNNING' if self.outside_running else 'CASCADE_RUN_STATUS_IDLE',
                        'trajectoryMetadata': {'workspaceUris': [self.uri + '-outside']}},
        }}

    def alive(self):
        return not self.offline

    def trajectory(self, session_id):
        self.events.append(('read', session_id))
        return {'status': self.status, 'trajectory': {
            'trajectoryId': 'native-trajectory', 'cascadeId': session_id,
            'metadata': {'workspaceUris': [self.uri],
                         'workspaces': [{'workspaceFolderAbsoluteUri': self.uri}]},
            'executorMetadatas': [{'cascadeConfig': deepcopy(self.cascade_config)}] if self.cascade_config else [],
            'steps': deepcopy(self.steps),
        }}

    def echo(self, text, client_id):
        self.steps.append({'type': 'CORTEX_STEP_TYPE_USER_INPUT', 'status': 'CORTEX_STEP_STATUS_DONE',
                           'userInput': {'items': [{'text': text}], 'clientMessageId': client_id}})

    def generic_command_permission(self, command="printf 'fixture approval'"):
        self.status = 'CASCADE_RUN_STATUS_RUNNING'
        self.steps[2:] = [{
            'type': 'CORTEX_STEP_TYPE_GENERIC',
            'status': 'CORTEX_STEP_STATUS_WAITING',
            'metadata': {'createdAt': '2026-11-27T02:04:36.460424Z',
                         'toolCall': {'id': 'call_fixture', 'name': 'run_command'},
                         'sourceTrajectoryStepInfo': {'trajectoryId': 'native-trajectory', 'stepIndex': 2,
                                                      'cascadeId': 'visible'}},
            'requestedInteraction': {'permission': {
                'resource': {'action': 'command', 'target': command},
                'persistSuggestionType': 'PERSIST_SUGGESTION_TYPE_SUGGESTED',
                'suggestedPersistPattern': 'printf',
                'actionDescription': 'Append native approval token',
                'shellName': 'zsh'}},
            'generic': {'args': {'CommandLine': command, 'WaitMsBeforeAsync': '10000'}},
        }]
        return self.steps[2]

    def send_text(self, session_id, text, client_id, native_config):
        self.events.append(('send', session_id, text, client_id, deepcopy(native_config)))
        if not self.defer_echo:
            self.echo(text, client_id)
        self.status = 'CASCADE_RUN_STATUS_RUNNING'
        if self.drop_send_response:
            raise DesktopError('fixture lost response', sent=True)
        return {}

    def answer_command(self, session_id, trajectory_id, index, command, allow):
        self.events.append(('answer', session_id, trajectory_id, index, command, allow))
        if self.write_ambiguous:
            raise DesktopError('fixture ambiguous', sent=True)
        step = self.steps[index]
        step['status'] = 'CORTEX_STEP_STATUS_DONE'
        step.pop('requestedInteraction')
        step['runCommand'].update(commandLine=command)
        self.status = 'CASCADE_RUN_STATUS_IDLE'
        if not self.change_state_without_effect:
            step['runCommand'].update(userRejected=not allow)
        return {}

    def answer_command_permission(self, session_id, trajectory_id, index, allow):
        self.events.append(('permission-answer', session_id, trajectory_id, index, allow))
        step = self.steps[index]
        request = deepcopy(step.pop('requestedInteraction'))
        step['status'] = 'CORTEX_STEP_STATUS_DONE'
        if allow:
            command = step.pop('generic')['args']['CommandLine']
            step.update(type='CORTEX_STEP_TYPE_RUN_COMMAND', runCommand={'commandLine': command, 'exitCode': 0})
        if not self.change_state_without_effect:
            decision = {'scope': 'PERMISSION_SCOPE_ONCE'}
            if allow:
                decision['allow'] = True
            completed = {'request': request, 'response': {
                'trajectoryId': trajectory_id, 'stepIndex': index, 'permission': decision,
            }}
            if self.change_permission_evidence:
                self.change_permission_evidence(completed)
            step['completedInteractions'] = [completed]
        self.steps.append({'type': 'CORTEX_STEP_TYPE_PLANNER_RESPONSE', 'status': 'CORTEX_STEP_STATUS_DONE',
                           'plannerResponse': {'response': 'Native fixture finished.'}})
        self.status = 'CASCADE_RUN_STATUS_IDLE'
        return {}


class DesktopAntigravityTests(RelayHTTPFixture, unittest.TestCase):
    def _start(self, bus=None):
        root = (self.config.workspace / 'approved-project').resolve()
        root.mkdir()
        self.config = Config(workspace=self.config.workspace, runtime='antigravity', antigravity_mode='desktop',
                             antigravity_projects=(root,))
        self.peer = DesktopFixturePeer(root)
        bus = bus or EventBus()
        self.native = AntigravityDesktopService(self.config.antigravity_profile, self.config.workspace,
                                                bus.publish, project_dirs=(root,),
                                                connect=lambda *_: self.peer)
        self.app = RelayApp(self.config, self.native, bus)
        self.server = BoundedHTTPServer(('127.0.0.1', 0), Handler, app=self.app,
                                        max_workers=6, max_streams=1)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       kwargs={'poll_interval': .01}, daemon=True)
        self.thread.start()

    def call(self, method, path, body=None):
        status, payload, _ = self.request(method, path, body, token=self.token)
        return status, payload

    def current(self):
        code, payload = self.call('GET', '/v1/threads/visible')
        self.assertEqual(200, code)
        return payload

    @staticmethod
    def response(state, submission_id, choice=0):
        approval = state['interactions'][0]
        return {'submissionId': submission_id, 'sessionId': state['sessionId'],
                'runtimeGeneration': state['runtimeGeneration'], 'interactionId': approval['id'],
                'revision': approval['revision'], 'response': {'choiceId': approval['choices'][choice]['id']}}

    def test_native_history_projects_and_workspace_authority(self):
        _, projects = self.call('GET', '/v1/projects')
        project = projects['projects'][0]
        _, catalog = self.call('GET', '/v1/threads?projectId=' + project['id'])
        self.assertEqual(['visible'], [row['id'] for row in catalog['threads']])
        self.assertEqual(404, self.call('GET', '/v1/threads/outside')[0])
        self.assertNotIn(('read', 'outside'), self.peer.events)
        current = self.current()
        self.assertEqual('waiting', current['sessionState']['execution']['status'])
        self.assertEqual(['userMessage', 'agentMessage', 'commandExecution'],
                         [item['type'] for item in current['thread']['turns'][0]['items']])
        self.assertIn('python test_fixture.py', current['sessionState']['interactions'][0]['detail'])
        self.assertTrue(current['capabilities']['operations']['interaction.respond']['available'])
        self.assertFalse(current['capabilities']['operations']['session.send']['available'])

    def test_catalog_with_unverified_root_hides_session_but_defers_busy_update(self):
        _, projects = self.call('GET', '/v1/projects')
        project_id = projects['projects'][0]['id']
        decision = self.response(self.current()['sessionState'], 'catalog-scope-changed')
        self.peer.events.clear()
        catalog = self.peer.summaries()
        catalog['trajectorySummaries']['visible']['trajectoryMetadata']['workspaceUris'].append(
            'file://remote.example/workspace')
        with patch.object(self.peer, 'summaries', return_value=catalog):
            _, payload = self.call('GET', '/v1/threads?projectId=' + project_id)
            self.assertEqual([], payload['threads'])
            self.assertEqual(404, self.call('GET', '/v1/threads/visible')[0])
            self.assertEqual('failed', self.call('POST', '/v1/interactions/respond', decision)[1]['status'])
            self.assertEqual('native_running', self.native.update_blocker())
        self.assertEqual([], self.peer.events)

    def test_detail_checks_every_root_and_accepts_equivalent_local_uris(self):
        detail = self.peer.trajectory('visible')
        metadata = detail['trajectory']['metadata']
        metadata['workspaces'][0]['workspaceFolderAbsoluteUri'] = self.peer.uri.replace(
            'file://', 'file://localhost', 1)
        with patch.object(self.peer, 'trajectory', return_value=detail):
            decision = self.response(self.current()['sessionState'], 'detail-scope-changed')
            self.peer.events.clear()
            metadata['workspaces'].append({'workspaceFolderAbsoluteUri': 'https://example.test/other'})
            self.assertEqual(404, self.call('GET', '/v1/threads/visible')[0])
            self.assertEqual('failed', self.call('POST', '/v1/interactions/respond', decision)[1]['status'])
        self.assertEqual([], self.peer.events)

    def test_idle_send_preserves_native_configuration_and_confirms_identity_before_completion(self):
        self.peer.status = 'CASCADE_RUN_STATUS_IDLE'
        self.peer.drop_send_response = True
        self.assertTrue(self.current()['capabilities']['operations']['session.send']['available'])
        body = {'submissionId': 'rivo-follow-up-id', 'text': 'Follow up.'}
        _, accepted = self.call('POST', '/v1/threads/visible/turns', body)
        self.assertEqual('accepted', accepted['status'])
        self.assertEqual({'kind': 'input-accepted', 'threadId': 'visible', 'submissionId': 'rivo-follow-up-id'},
                         accepted['receipt'])
        self.assertIn(('send', 'visible', 'Follow up.', 'rivo-follow-up-id', self.peer.cascade_config),
                      self.peer.events)
        turn = self.current()['thread']['turns'][-1]
        self.assertEqual('inProgress', turn['status'])
        self.assertEqual('rivo-follow-up-id', turn['items'][0]['clientId'])
        self.peer.steps.append({'type': 'CORTEX_STEP_TYPE_PLANNER_RESPONSE', 'status': 'CORTEX_STEP_STATUS_DONE',
                                'plannerResponse': {'response': 'Partial native output.'}})
        self.peer.steps.append({'type': 'CORTEX_STEP_TYPE_ERROR_MESSAGE', 'status': 'CORTEX_STEP_STATUS_DONE',
                                'errorMessage': {'shouldShowUser': True,
                                                 'error': {'userErrorMessage': 'Native execution failed.'}}})
        self.peer.status = 'CASCADE_RUN_STATUS_IDLE'
        turn = self.current()['thread']['turns'][-1]
        self.assertEqual('failed', turn['status'])
        self.assertEqual('Native execution failed.', turn['items'][-1]['error'])
        self.assertEqual(accepted, self.call('POST', '/v1/threads/visible/turns', body)[1])
        self.assertEqual(1, len([entry for entry in self.peer.events if entry[0] == 'send']))

    def test_late_durable_echo_recovers_unknown_without_repeating_identical_text(self):
        self.peer.status = 'CASCADE_RUN_STATUS_IDLE'
        self.peer.defer_echo = True
        body = {'submissionId': 'mine', 'text': 'Follow up.'}
        with patch('rivo_relay.antigravity.desktop_service.INPUT_CONFIRM_SECONDS', .02):
            self.assertEqual('unknown', self.call('POST', '/v1/threads/visible/turns', body)[1]['status'])
        self.peer.echo('Follow up.', 'some-other-id')
        self.assertEqual('unknown', self.call('GET', '/v1/submissions/mine')[1]['status'])
        self.peer.echo('Follow up.', 'mine')
        self.assertEqual('accepted', self.call('GET', '/v1/submissions/mine')[1]['status'])
        self.assertEqual(1, len([entry for entry in self.peer.events if entry[0] == 'send']))

    def test_busy_or_missing_native_cascade_config_never_dispatches_input(self):
        body = {'submissionId': 'busy', 'text': 'Follow up.'}
        self.assertEqual('antigravity_busy', self.call('POST', '/v1/threads/visible/turns', body)[1]['errorCode'])
        self.peer.status = 'CASCADE_RUN_STATUS_IDLE'
        self.peer.cascade_config = {'plannerConfig': {'toolConfig': {}}}
        current = self.current()
        self.assertFalse(current['capabilities']['operations']['session.send']['available'])
        body['submissionId'] = 'missing-config'
        self.assertEqual('antigravity_desktop_config_missing',
                         self.call('POST', '/v1/threads/visible/turns', body)[1]['errorCode'])
        self.assertFalse(any(entry[0] == 'send' for entry in self.peer.events))

    def test_native_approval_requires_current_scope_and_live_confirmation(self):
        state = self.current()['sessionState']
        stale = self.response(state, 'stale') | {'runtimeGeneration': 'old generation'}
        self.assertEqual('failed', self.call('POST', '/v1/interactions/respond', stale)[1]['status'])
        self.peer.events.clear()
        request = self.response(state, 'decision')
        accepted = self.call('POST', '/v1/interactions/respond', request)[1]
        self.assertEqual('accepted', accepted['status'])
        self.assertEqual('mutation-applied', accepted['receipt']['kind'])
        self.assertEqual([
            ('read', 'visible'), ('answer', 'visible', 'native-trajectory', 2, 'python test_fixture.py', True),
            ('read', 'visible')], self.peer.events)
        self.assertEqual(accepted, self.call('POST', '/v1/interactions/respond', request)[1])
        self.assertEqual([], self.current()['sessionState']['interactions'])
        self.assertEqual(1, len([entry for entry in self.peer.events if entry[0] == 'answer']))

    def test_generic_run_command_permission_exposes_only_one_shot_choices(self):
        step = self.peer.generic_command_permission()
        command = step['generic']['args']['CommandLine']
        current = self.current()
        state = current['sessionState']
        self.assertEqual('waiting', state['execution']['status'])
        self.assertTrue(current['capabilities']['operations']['interaction.respond']['available'])
        approval, = state['interactions']
        self.assertEqual('approval', approval['kind'])
        self.assertIn(command, approval['detail'])
        self.assertEqual(['allow-once', 'deny'], [choice['effect'] for choice in approval['choices']])

        stale = self.response(state, 'generic-stale')
        out_of_scope = self.response(state, 'generic-other') | {'sessionId': 'outside'}
        self.assertEqual('failed', self.call('POST', '/v1/interactions/respond', out_of_scope)[1]['status'])
        changed = "printf 'replacement command'"
        step['generic']['args']['CommandLine'] = changed
        step['requestedInteraction']['permission']['resource']['target'] = changed
        self.assertEqual('failed', self.call('POST', '/v1/interactions/respond', stale)[1]['status'])
        self.assertTrue(all(entry[0] == 'read' for entry in self.peer.events))

    def test_generic_permission_rejects_a_different_tool_action_or_command(self):
        changes = (
            lambda step: step['metadata']['toolCall'].update(name='other_tool'),
            lambda step: step['requestedInteraction']['permission']['resource'].update(action='other_action'),
            lambda step: step['generic']['args'].update(CommandLine="printf 'different command'"),
        )
        for index, change in enumerate(changes):
            with self.subTest(change=index):
                change(self.peer.generic_command_permission())
                current = self.current()
                approval, = current['sessionState']['interactions']
                self.assertEqual('unsupported', approval['kind'])
                self.assertEqual([], approval['choices'])
                self.assertFalse(current['capabilities']['operations']['interaction.respond']['available'])

    def test_generic_permission_actions_route_to_the_native_step_and_confirm(self):
        for index, allow in enumerate((True, False)):
            with self.subTest(allow=allow):
                step = self.peer.generic_command_permission()
                step['metadata']['toolCall']['id'] = f'fixture-permission-{index}'
                self.peer.events.clear()
                state = self.current()['sessionState']
                payload = self.response(state, f'generic-decision-{index}', choice=index)
                accepted = self.call('POST', '/v1/interactions/respond', payload)[1]
                self.assertEqual('accepted', accepted['status'])
                self.assertEqual('mutation-applied', accepted['receipt']['kind'])
                self.assertEqual('visible', accepted['receipt']['threadId'])
                self.assertEqual(payload['revision'], accepted['receipt']['revision'])
                completed = self.current()
                self.assertEqual([], completed['sessionState']['interactions'])
                self.assertEqual('completed', completed['thread']['turns'][-1]['status'])
                self.assertEqual(accepted, self.call('POST', '/v1/interactions/respond', payload)[1])
                self.assertEqual([('permission-answer', 'visible', 'native-trajectory', 2, allow)],
                                 [entry for entry in self.peer.events if entry[0] != 'read'])

    def test_generic_permission_stays_unknown_if_native_loses_decision_evidence(self):
        self.peer.generic_command_permission()
        self.peer.change_state_without_effect = True
        state = self.current()['sessionState']
        payload = self.response(state, 'generic-unconfirmed', choice=1)
        with patch('rivo_relay.antigravity.desktop_service.APPROVAL_CONFIRM_SECONDS', .02):
            result = self.call('POST', '/v1/interactions/respond', payload)[1]
        self.assertEqual('unknown', result['status'])
        self.assertEqual('unknown', self.call('GET', '/v1/submissions/generic-unconfirmed')[1]['status'])
        self.assertEqual([('permission-answer', 'visible', 'native-trajectory', 2, False)],
                         [entry for entry in self.peer.events if entry[0] != 'read'])

    def test_generic_permission_requires_the_exact_completed_native_decision(self):
        changes = {
            'target': lambda evidence: evidence['request']['permission']['resource'].update(target='other command'),
            'scope': lambda evidence: evidence['response']['permission'].update(scope='PERMISSION_SCOPE_ALWAYS'),
            'choice': lambda evidence: evidence['response']['permission'].pop('allow'),
        }
        for field, change in changes.items():
            with self.subTest(field=field):
                step = self.peer.generic_command_permission()
                step['metadata']['toolCall']['id'] = f'generic-mismatch-{field}'
                self.peer.change_permission_evidence = change
                self.peer.events.clear()
                state = self.current()['sessionState']
                payload = self.response(state, f'generic-mismatched-{field}')
                with patch('rivo_relay.antigravity.desktop_service.APPROVAL_CONFIRM_SECONDS', .02):
                    result = self.call('POST', '/v1/interactions/respond', payload)[1]
                self.assertEqual('unknown', result['status'])
                self.assertEqual('unknown', self.call('GET', '/v1/submissions/' + payload['submissionId'])[1]['status'])
                self.assertEqual([('permission-answer', 'visible', 'native-trajectory', 2, True)],
                                 [entry for entry in self.peer.events if entry[0] != 'read'])

    def test_generic_permission_uses_the_native_one_shot_wire_shape(self):
        peer = object.__new__(DesktopPeer)
        for allow in (True, False):
            with self.subTest(allow=allow), patch.object(peer, '_call') as rpc:
                peer.answer_command_permission('visible', 'native-trajectory', 2, allow)
                rpc.assert_called_once_with('HandleCascadeUserInteraction', {
                    'cascadeId': 'visible',
                    'interaction': {'trajectoryId': 'native-trajectory', 'stepIndex': 2,
                                    'permission': {'allow': allow, 'scope': 1}},
                }, limit=65536, mutation=True)

    def test_denial_needs_exact_native_denial_evidence(self):
        state = self.current()['sessionState']
        self.peer.change_state_without_effect = True
        request = self.response(state, 'denial', 1)
        with patch('rivo_relay.antigravity.desktop_service.APPROVAL_CONFIRM_SECONDS', .02):
            result = self.call('POST', '/v1/interactions/respond', request)[1]
        self.assertEqual('unknown', result['status'])
        self.assertEqual('unknown', self.call('GET', '/v1/submissions/denial')[1]['status'])

    def test_ambiguous_write_never_repeats_on_new_claim_for_same_pending_step(self):
        state = self.current()['sessionState']
        self.peer.write_ambiguous = True
        first = self.call('POST', '/v1/interactions/respond', self.response(state, 'first'))[1]
        second = self.call('POST', '/v1/interactions/respond', self.response(state, 'another'))[1]
        self.assertEqual('unknown', first['status'])
        self.assertEqual('failed', second['status'])
        self.assertEqual(1, len([entry for entry in self.peer.events if entry[0] == 'answer']))

    def test_external_resolution_invalidates_open_session_controls(self):
        with patch('rivo_relay.antigravity.desktop_service.POLL_SECONDS', .02):
            self.assertEqual('waiting', self.current()['sessionState']['execution']['status'])
            cursor = self.app.events.cursor(self.app.events._sequence)
            # The desktop, not Relay, resolves its native request.
            self.peer.steps[-1]['status'] = 'CORTEX_STEP_STATUS_DONE'
            self.peer.status = 'CASCADE_RUN_STATUS_IDLE'
            _, events, reset = self.app.events.read(cursor, timeout=2)
        self.assertFalse(reset)
        self.assertTrue(any(event['method'] == 'session/invalidated' for event in events))
        self.assertEqual([], self.current()['sessionState']['interactions'])
        self.assertFalse(any(entry[0] == 'answer' for entry in self.peer.events))

    def test_update_checks_fresh_project_activity_and_allows_unrelated_native_work(self):
        self.assertEqual('native_running', self.native.update_blocker())
        self.peer.status = 'CASCADE_RUN_STATUS_IDLE'
        self.peer.outside_running = True
        self.assertIsNone(self.native.update_blocker())
        self.peer.status = 'UNRECOGNIZED'
        self.assertEqual('native_state_unknown', self.native.update_blocker())
        self.peer.offline = True
        self.assertEqual('native_state_unknown', self.native.update_blocker())

    def test_registry_refuses_public_files_and_symlinks(self):
        path = self.config.workspace / 'fixture-registry.json'
        path.write_text(json.dumps({'pid': 10, 'httpsPort': 1234,
                                    'lsVersion': 'fixture', 'csrfToken': 'fixture-private-nonce'}))
        path.chmod(0o644)
        self.assertIsNone(_private_registry_file(path))
        path.chmod(0o600)
        record = _private_registry_file(path)
        self.assertIsNotNone(record)
        self.assertNotIn('fixture-private-nonce', repr(record))
        alias = Path(path.parent, 'fixture-alias.json')
        alias.symlink_to(path)
        with self.assertRaises(OSError):
            _private_registry_file(alias)
