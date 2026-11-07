"""Permission selection must reach native calls without changing inherited defaults."""
import unittest
from test_codex import FakeNative
from rivo_relay.codex.sessions import CodexService
from rivo_relay.codex.connection import CodexError


class PermissionSettingsTests(unittest.TestCase):
    def test_creation_passes_each_sandbox_and_independent_approval(self):
        for sandbox in ('read-only', 'workspace-write', 'danger-full-access'):
            native = FakeNative()
            service = CodexService(connection=native)
            service.create_session('project', sandbox=sandbox, approval_policy='on-request')
            params = next(p for m, p in native.calls if m == 'thread/start')
            self.assertEqual(sandbox, params['sandbox'])
            self.assertEqual('on-request', params['approvalPolicy'])
            self.assertNotIn('permissions', params)

    def test_invalid_values_fail_before_native_side_effects(self):
        for options in ({'sandbox': 'full'}, {'sandbox': {}}, {'approval_policy': 'yes'}, {'approval_policy': []}):
            native = FakeNative()
            service = CodexService(connection=native)
            with self.assertRaises(CodexError):
                service.create_session('project', **options)
            with self.assertRaises(CodexError):
                service.update_settings('thread', **options)
            self.assertEqual([], native.calls)

    def test_update_translates_sandbox_and_preserves_network_choice(self):
        native = FakeNative()
        service = CodexService(connection=native)
        service.create_session('project')
        service._settings['thread']['sandbox'] = {'type': 'readOnly', 'networkAccess': True}
        service.update_settings('thread', sandbox='workspace-write', approval_policy='untrusted')
        params = next(p for m, p in native.calls if m == 'thread/settings/update')
        self.assertEqual({'type': 'workspaceWrite', 'networkAccess': True}, params['sandboxPolicy'])
        self.assertEqual('untrusted', params['approvalPolicy'])
        self.assertEqual(params['sandboxPolicy'], service._settings['thread']['sandbox'])

    def test_approval_only_does_not_override_sandbox_or_plan_baseline(self):
        native = FakeNative()
        service = CodexService(connection=native)
        service.create_session('project')
        service._settings['thread']['collaborationMode'] = {'mode': 'plan'}
        service.update_settings('thread', approval_policy='never')
        params = next(p for m, p in native.calls if m == 'thread/settings/update')
        self.assertNotIn('sandboxPolicy', params)
        with self.assertRaises(CodexError):
            service.update_settings('thread', sandbox='danger-full-access')
        self.assertEqual(1, sum(m == 'thread/settings/update' for m, _ in native.calls))
