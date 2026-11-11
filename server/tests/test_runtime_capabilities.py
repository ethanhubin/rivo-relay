"""Operation declarations and actual HTTP admission use the same authority."""
import time
import unittest

from relay_test_support.http import RelayHTTPFixture
from rivo_relay.runtime_capabilities import CapabilityError, declare, require
from rivo_relay.codex.capabilities import capabilities as codex_capabilities
from rivo_relay.dsh.capabilities import capabilities as dsh_capabilities


class CapabilityContractTests(unittest.TestCase):
    def test_unknown_and_unavailable_operations_fail_closed(self):
        declaration = declare({'session.send'})
        require(declaration, 'session.send')
        for name in ('session.models', 'future.unknown'):
            with self.assertRaises(CapabilityError) as caught:
                require(declaration, name)
            self.assertEqual('runtime_unsupported', caught.exception.code)
        with self.assertRaises(CapabilityError) as caught:
            require(declare({'session.send'}, connected=False), 'session.send')
        self.assertEqual('runtime_unavailable', caught.exception.code)
        with self.assertRaises(CapabilityError):
            require({**declaration, 'schemaVersion': 999}, 'session.send')

    def test_adapter_capabilities_and_session_control_remain_distinct(self):
        self.assertTrue(codex_capabilities(connected=True)['operations']['session.queue']['supported'])
        self.assertFalse(dsh_capabilities(connected=True)['operations']['session.queue']['supported'])
        readonly = {'readOnly': True, 'control': {'canOperate': False, 'state': 'external'}}
        for provider in (codex_capabilities, dsh_capabilities):
            descriptor = provider(connected=True, snapshot=readonly)['operations']['session.send']
            self.assertTrue(descriptor['supported'])
            self.assertFalse(descriptor['available'])
        released = {'readOnly': True, 'control': {'canOperate': False, 'state': 'released', 'action': 'resume'}}
        self.assertTrue(codex_capabilities(connected=True, snapshot=released)['operations']['session.send']['available'])
        self.assertFalse(dsh_capabilities(connected=True, snapshot=released)['operations']['session.send']['available'])
        question = {**readonly, 'requests': [{'method': 'item/tool/requestUserInput'}]}
        self.assertTrue(codex_capabilities(connected=True, snapshot=question)['operations']['interaction.respond']['available'])
        self.assertFalse(codex_capabilities(connected=True, snapshot={**question,
            'nativeSync': {'state': 'stale'}})['operations']['interaction.respond']['available'])

    def test_live_approval_does_not_require_finished_durable_history(self):
        snapshot = {'control': {'state': 'writable', 'canOperate': True}, 'readOnly': False,
                    'nativeSync': {'state': 'converging', 'historyReady': True},
                    'requests': [{'method': 'item/commandExecution/requestApproval'}]}
        declaration = codex_capabilities(connected=True, snapshot=snapshot)
        self.assertTrue(declaration['operations']['interaction.respond']['available'])
        self.assertTrue(declaration['operations']['session.interrupt']['available'])
        for sync in ({'state': 'unavailable', 'historyReady': True},
                     {'state': 'converging', 'historyReady': False}):
            self.assertFalse(codex_capabilities(connected=True, snapshot={**snapshot,
                'nativeSync': sync})['operations']['interaction.respond']['available'])

    def test_external_writer_can_recheck_failed_handoff_without_granting_execution(self):
        snapshot = {'readOnly': True, 'control': {'state': 'external', 'canOperate': False, 'action': None},
                    'nativeRuntime': {'runtime': 'app_server', 'connected': True,
                                      'handoffAvailable': False, 'handoffCheckAvailable': False},
                    'nativeSync': {'state': 'converging', 'historyReady': False}}
        declaration = codex_capabilities(connected=True, snapshot=snapshot)['operations']
        self.assertTrue(declaration['session.ownership']['available'])
        for operation in ('session.send', 'session.interrupt', 'interaction.respond'):
            self.assertFalse(declaration[operation]['available'])
        # Shared Desktop and either disconnected transport cannot acquire this
        # recheck exception, even when cached control still says external.
        for runtime in ({'runtime': 'desktop'}, {'desktopShared': True}, {'connected': False}):
            with self.subTest(runtime=runtime):
                blocked = {**snapshot, 'nativeRuntime': {**snapshot['nativeRuntime'], **runtime}}
                self.assertFalse(codex_capabilities(connected=True, snapshot=blocked)
                                 ['operations']['session.ownership']['available'])
        self.assertFalse(codex_capabilities(connected=False, snapshot=snapshot)
                         ['operations']['session.ownership']['available'])
        for state in ('unknown', 'ownership_unverified'):
            with self.subTest(state=state):
                blocked = {**snapshot, 'control': {**snapshot['control'], 'state': state}}
                self.assertFalse(codex_capabilities(connected=True, snapshot=blocked)
                                 ['operations']['session.ownership']['available'])


class CapabilityHttpTests(RelayHTTPFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.native.runtime_capabilities = lambda **kwargs: dsh_capabilities(
            connected=True, snapshot=kwargs.get('snapshot'))

    def test_node_and_session_advertise_same_implemented_operations(self):
        _, node, _ = self.request('GET', '/v1/node', token=self.token)
        _, session, _ = self.request('GET', '/v1/threads/thread-1', token=self.token)
        self.assertEqual(1, node['capabilities']['schemaVersion'])
        self.assertEqual(node['capabilities']['operations'], session['capabilities']['operations'])
        self.assertFalse(node['capabilities']['todoAttachmentUpload'])
        self.assertFalse(node['capabilities']['media']['proxy'])
        self.assertTrue(node['capabilities']['operations']['session.send']['available'])

    def test_unsupported_reads_rejected_before_adapter_internals(self):
        for path in ('/v1/sites', '/v1/files?path=/tmp/a', '/v1/project-directories',
                     '/v1/media/content?source=/tmp/a', '/v1/media/library',
                     '/v1/projects/project-1/git', '/v1/threads/thread-1/fork-context',
                     '/v1/threads/thread-1/workspace'):
            with self.subTest(path=path):
                status, body, _ = self.request('GET', path, token=self.token)
                self.assertEqual(409, status)
                self.assertEqual('runtime_unsupported', body['error']['code'])
        self.assertEqual([], self.native.history_reads)

    def test_unsupported_input_options_do_not_reach_native(self):
        options = ({'model': 'other'}, {'mode': 'plan'}, {'requireGoalSupport': True},
                   {'input': [{'type': 'image', 'url': 'https://example.com/image.png'}]},
                   {'input': [{'type': 'mention', 'name': 'file', 'path': '/tmp/file'}]})
        for index, extra in enumerate(options):
            status, result, _ = self.request('POST', '/v1/threads/thread-1/turns',
                {'submissionId': 'unsupported-' + str(index), 'text': 'hello', **extra}, token=self.token)
            self.assertEqual(200, status)
            self.assertEqual('failed', result['status'])
            self.assertEqual('runtime_unsupported', result['errorCode'])
        self.assertEqual([], self.native.sent)
        _, result, _ = self.request('POST', '/v1/threads',
            {'submissionId': 'create-options', 'projectId': 'project-1', 'sandbox': 'workspace-write'}, token=self.token)
        self.assertEqual('failed', result['status'])
        self.assertEqual([], self.native.created)

    def test_current_availability_blocks_new_writes_but_not_receipt_replays(self):
        path = '/v1/threads/thread-1/turns'
        payload = {'submissionId': 'first', 'text': 'hello'}
        first = self.request('POST', path, payload, token=self.token)
        self.assertEqual('accepted', first[1]['status'])
        self.native.runtime_capabilities = lambda **kwargs: declare({'session.send'}, connected=False)
        replay = self.request('POST', path, payload, token=self.token)
        self.assertEqual(first[1], replay[1])
        _, second, _ = self.request('POST', path, {**payload, 'submissionId': 'second'}, token=self.token)
        self.assertEqual('failed', second['status'])
        self.assertEqual('runtime_unavailable', second['errorCode'])
        self.assertEqual(1, len(self.native.sent))

    def test_existing_download_ticket_does_not_bypass_changed_capability(self):
        self.app.download_links['old-ticket'] = ('/tmp/test-only-file', time.time() + 60)
        status, body, _ = self.request('GET', '/v1/download-links/old-ticket')
        self.assertEqual(409, status)
        self.assertEqual('runtime_unsupported', body['error']['code'])

    def test_backend_without_provider_is_closed(self):
        self.native.runtime_capabilities = None
        with self.assertRaises(CapabilityError):
            self.app.require_capability('session.send')

    def test_unknown_and_composite_mutations_cannot_skip_capabilities(self):
        called = []
        self.native.runtime_capabilities = lambda **kwargs: declare({'session.queue', 'todo.dispatch', 'session.send', 'session.create'})
        for operation, payload in (
            ('new.unmapped', {}), ('thread.queue.steer', {}),
            ('todo.dispatch', {'details': {'attachments': [{'name': 'a', 'path': '/tmp/a'}]}}),
        ):
            result = self.app.submit(self.paired['deviceId'], operation, operation, 'thread-1', payload,
                                     lambda: called.append(operation) or {'threadId': 'thread-1'})
            self.assertEqual('failed', result['status'])
            self.assertEqual('runtime_unsupported', result['errorCode'])
        self.assertEqual([], called)
