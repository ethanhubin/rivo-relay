"""Quota redemption contracts with isolated HTTP, SQLite and native transports."""
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import Mock

from relay_test_support.http import RelayHTTPFixture
from relay_test_support.native import FakeNative
from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.sessions import CodexService


class UsageResetHTTPTests(RelayHTTPFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.native.usage_reset_scope = Mock(return_value='account-fingerprint')
        self.native.reset_usage = Mock(side_effect=lambda sid, scope: {'requestId': sid, 'resetOutcome': 'reset'})

    def reset(self, sid='reset-1', suffix=''):
        return self.request('POST', '/v1/usage/reset' + suffix, {'submissionId': sid}, token=self.token)

    def test_auth_validation_duplicate_and_receipt_owner(self):
        self.assertEqual(401, self.request('POST', '/v1/usage/reset', {'submissionId': 'x'})[0])
        for body in ({}, {'submissionId': ''}, {'submissionId': 'x', 'creditId': 'other'}):
            self.assertEqual(400, self.request('POST', '/v1/usage/reset', body, token=self.token)[0])
        first = self.reset()
        self.assertEqual('reset', first[1]['receipt']['resetOutcome'])
        self.assertEqual(first[1], self.reset()[1])
        self.native.reset_usage.assert_called_once_with('reset-1', 'account-fingerprint')
        other = self._pair()['token']
        self.assertEqual(409, self.request('POST', '/v1/usage/reset', {'submissionId': 'reset-1'}, token=other)[0])
        self.assertEqual(404, self.request('GET', '/v1/submissions/reset-1', token=other)[0])
        self.assertEqual(404, self.request('POST', '/v1/usage/reset/reconcile', {'submissionId': 'reset-1'}, token=other)[0])
        self.assertEqual(1, self.native.reset_usage.call_count)

    def test_concurrent_duplicate_dispatches_once(self):
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: self.reset(), range(2)))
        self.assertTrue(all(r[1]['status'] == 'accepted' for r in results))
        self.assertEqual(1, self.native.reset_usage.call_count)

    def test_timeout_blocks_new_ids_and_recovery_reuses_native_key_after_restart(self):
        self.native.reset_usage.side_effect = CodexError('timeout', request_sent=True)
        self.assertEqual('unknown', self.reset()[1]['status'])
        self.assertEqual('unknown', self.reset()[1]['status'])
        self.assertEqual(409, self.reset('reset-2')[0])
        self.assertEqual('unknown', self.request('GET', '/v1/submissions/reset-1', token=self.token)[1]['status'])
        self.assertEqual(1, self.native.reset_usage.call_count)  # reads and duplicate POSTs never redeem
        self._stop(); self._start()
        self.native.reset_usage = Mock(return_value={'requestId': 'reset-1', 'resetOutcome': 'alreadyRedeemed'})
        result = self.reset(suffix='/reconcile')[1]
        self.assertEqual('accepted', result['status'])
        self.native.reset_usage.assert_called_once_with('reset-1', 'account-fingerprint')
        self.assertEqual(result, self.reset(suffix='/reconcile')[1])
        self.assertEqual(1, self.native.reset_usage.call_count)

    def test_failed_recovery_cannot_erase_original_uncertainty(self):
        self.native.reset_usage.side_effect = CodexError('timeout', request_sent=True)
        self.reset()
        self.native.reset_usage.side_effect = CodexError('changed account', code='usage_reset_account_changed')
        result = self.reset(suffix='/reconcile')[1]
        self.assertEqual('unknown', result['status'])
        self.assertEqual('usage_reset_account_changed', result['recoveryErrorCode'])
        self.assertEqual(409, self.reset('different')[0])

    def test_missing_recovery_fences_late_request(self):
        result = self.reset(suffix='/reconcile')[1]
        self.assertEqual('failed', result['status'])
        self.assertEqual('submission_not_received', result['errorCode'])
        self.assertEqual(result, self.reset()[1])
        self.native.reset_usage.assert_not_called()

    def test_native_outcomes_and_unsupported_are_terminal(self):
        for outcome in ('reset', 'alreadyRedeemed', 'nothingToReset', 'noCredit'):
            self.native.reset_usage.side_effect = None
            self.native.reset_usage.return_value = {'requestId': outcome, 'resetOutcome': outcome}
            result = self.reset(outcome)[1]
            self.assertEqual('accepted', result['status'])
            self.assertEqual(outcome, result['receipt']['resetOutcome'])
        self.native.reset_usage.side_effect = CodexError('unsupported', code='usage_reset_unsupported')
        result = self.reset('old-node')[1]
        self.assertEqual('failed', result['status'])
        self.assertEqual('usage_reset_unsupported', result['errorCode'])


class UsageResetNativeTests(unittest.TestCase):
    def setUp(self):
        self.native = FakeNative()
        self.service = CodexService(connection=self.native)
        self.account = {'type': 'chatgpt', 'email': 'isolated@example.test', 'planType': 'plus'}
        self.outcome = 'reset'
        self.consume = []
        def request(method, params, **kwargs):
            if method == 'account/read': return {'account': self.account}
            if method == 'account/rateLimits/read':
                return {'rateLimits': None, 'rateLimitResetCredits': {'availableCount': 3, 'credits': None}, 'accountId': 'private'}
            if method == 'account/rateLimitResetCredit/consume':
                self.consume.append(params)
                if isinstance(self.outcome, Exception): raise self.outcome
                return {'outcome': self.outcome}
            return {}
        self.native.request = request

    def test_usage_preserves_count_only_and_omits_account_identifiers(self):
        result = self.service.usage()
        self.assertTrue(result['resetSupported'])
        self.assertEqual({'availableCount': 3, 'credits': None}, result['rateLimitResetCredits'])
        self.assertNotIn('accountId', result)

    def test_same_native_key_and_closed_outcomes(self):
        scope = self.service.usage_reset_scope()
        self.assertNotIn('example', scope)
        for outcome in ('reset', 'alreadyRedeemed', 'nothingToReset', 'noCredit'):
            self.outcome = outcome
            self.assertEqual(outcome, self.service.reset_usage('original-key', scope)['resetOutcome'])
        self.assertTrue(all(params == {'idempotencyKey': 'original-key'} for params in self.consume))
        self.outcome = 'future-value'
        with self.assertRaises(CodexError) as error: self.service.reset_usage('original-key', scope)
        self.assertTrue(error.exception.request_sent)

    def test_changed_or_unverified_account_never_consumes(self):
        scope = self.service.usage_reset_scope()
        self.account = {**self.account, 'email': 'other@example.test'}
        with self.assertRaises(CodexError): self.service.reset_usage('key', scope)
        self.account = {'type': 'apiKey'}
        with self.assertRaises(CodexError): self.service.usage_reset_scope()
        self.assertEqual([], self.consume)

    def test_plan_change_does_not_create_a_new_account_scope(self):
        scope = self.service.usage_reset_scope()
        self.account = {**self.account, 'planType': 'pro'}
        self.assertEqual(scope, self.service.usage_reset_scope())

    def test_unsupported_native_method_is_definite_failure(self):
        scope = self.service.usage_reset_scope()
        self.outcome = CodexError('method not found', native_code=-32601, request_sent=True)
        with self.assertRaises(CodexError) as error: self.service.reset_usage('key', scope)
        self.assertEqual('usage_reset_unsupported', error.exception.code)
        self.assertTrue(error.exception.definite)
