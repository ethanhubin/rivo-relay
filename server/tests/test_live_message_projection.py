"""Native streaming/history projection must preserve content, identity and finality."""
import copy
import unittest
from rivo_relay.codex.sessions import CodexService
from relay_test_support.native import FakeNative


class LiveMessageProjectionTests(unittest.TestCase):
    def setUp(self):
        self.events = []
        self.service = CodexService(connection=FakeNative(), on_event=self.events.append)

    def event(self, method, **params):
        self.service._event({'method': method, 'params': {'threadId': 't', 'turnId': 'r', **params}})

    def items(self):
        return self.service._live[('t', 'r')]['items']

    def test_item_completion_keeps_native_order_and_late_delta_cannot_reopen(self):
        self.event('item/started', item={'id': 'a', 'type': 'agentMessage', 'text': ''})
        self.event('item/started', item={'id': 'b', 'type': 'commandExecution', 'status': 'inProgress'})
        final = {'id': 'a', 'type': 'agentMessage', 'text': 'final'}
        self.event('item/completed', item=final)
        self.event('item/agentMessage/delta', itemId='a', delta='late')
        self.event('item/started', item={'id': 'a', 'type': 'agentMessage', 'text': ''})
        self.assertEqual(['a', 'b'], [i['id'] for i in self.items()])
        self.assertEqual(final, self.items()[0])

    def test_stateless_completion_reaches_client_without_changing_native_item(self):
        from rivo_relay.client_projection import client_history
        final = {'id': 'a', 'type': 'agentMessage', 'text': 'final'}
        self.event('item/started', item={**final, 'text': ''})
        before = self.service._merge_live('t', [])
        self.assertNotIn('relayCompleted', before[0]['items'][0])
        self.event('item/completed', item=final)
        # Cover missing turn, missing item, and replacement of an existing item.
        for persisted in ([], [{'id': 'r', 'status': 'inProgress', 'items': []}],
                          [{'id': 'r', 'status': 'inProgress', 'items': [{**final, 'text': ''}]}]):
            with self.subTest(persisted=persisted):
                turns = self.service._merge_live('t', copy.deepcopy(persisted))
                self.service._mark_completed_items('t', turns)
                projected = client_history({'id': 't', 'turns': turns})
                self.assertEqual('inProgress', projected['turns'][0]['status'])
                self.assertEqual({**final, 'relayCompleted': True}, projected['turns'][0]['items'][0])
        self.assertEqual(final, self.items()[0])
        self.assertEqual(final, self.events[-1]['params']['item'])
        self.assertNotIn('relayCompleted', before[0]['items'][0])
        # Completion is scoped to both thread and turn, not a bare item ID.
        foreign = [{'id': 'r', 'items': [dict(final)]}]
        self.service._mark_completed_items('other-thread', foreign)
        self.assertNotIn('relayCompleted', foreign[0]['items'][0])
        self.event('item/started', turnId='other-turn', item={**final, 'text': ''})
        other = next(t for t in self.service._merge_live('t', []) if t['id'] == 'other-turn')
        self.service._mark_completed_items('t', [other])
        self.assertNotIn('relayCompleted', other['items'][0])

    def test_reasoning_plan_and_file_output_project_without_dropping_parts(self):
        self.event('item/reasoning/summaryPartAdded', itemId='why', summaryIndex=0)
        self.event('item/reasoning/summaryTextDelta', itemId='why', summaryIndex=0, delta='one')
        self.event('item/reasoning/summaryPartAdded', itemId='why', summaryIndex=1)
        self.event('item/reasoning/summaryTextDelta', itemId='why', summaryIndex=1, delta='two')
        self.event('item/reasoning/textDelta', itemId='why', contentIndex=0, delta='detail')
        self.event('item/plan/delta', itemId='plan', delta='steps')
        self.event('item/fileChange/outputDelta', itemId='file', delta='patch output')
        by_id = {i['id']: i for i in self.items()}
        self.assertEqual(['one', 'two'], by_id['why']['summary'])
        self.assertEqual(['detail'], by_id['why']['content'])
        self.assertEqual('steps', by_id['plan']['text'])
        self.assertEqual('patch output', by_id['file']['output'])

    def test_observation_notifications_do_not_invent_active_turns(self):
        for method in ('thread/tokenUsage/updated', 'turn/plan/updated', 'turn/diff/updated',
                       'model/rerouted', 'item/mcpToolCall/progress', 'serverRequest/resolved'):
            self.event(method)
        self.assertFalse(self.service._live)

    def test_late_start_preserves_delta_and_unicode_offset(self):
        self.event('item/agentMessage/delta', itemId='a', delta='你好🙂')
        self.event('item/started', item={'id': 'a', 'type': 'agentMessage', 'text': ''})
        self.event('item/agentMessage/delta', itemId='a', delta='!')
        self.assertEqual('你好🙂!', self.items()[0]['text'])
        self.assertEqual(3, self.events[-1]['params']['relayOffset'])

    def test_final_snapshot_and_longer_command_output_never_regress(self):
        self.event('item/commandExecution/outputDelta', itemId='cmd', delta='short')
        persisted = [{'id': 'r', 'status': 'inProgress', 'items': [
            {'id': 'cmd', 'type': 'commandExecution', 'status': 'inProgress', 'aggregatedOutput': 'short and more'}]}]
        self.assertEqual('short and more', self.service._merge_live('t', copy.deepcopy(persisted))[0]['items'][0]['aggregatedOutput'])
        persisted[0]['items'][0].update(status='completed', exitCode=0)
        result = self.service._merge_live('t', copy.deepcopy(persisted))[0]['items'][0]
        self.assertEqual('completed', result['status'])
        self.assertEqual(0, result['exitCode'])

    def test_terminal_turn_error_is_visible_and_late_start_cannot_revive(self):
        self.event('turn/started', turn={'id': 'r', 'status': 'inProgress', 'items': []})
        self.event('item/agentMessage/delta', itemId='a', delta='partial')
        self.event('turn/completed', turn={'id': 'r', 'status': 'failed', 'items': [],
                                        'error': {'message': 'native failure'}, 'completedAt': 10})
        self.event('item/agentMessage/delta', itemId='a', delta='late')
        self.event('turn/started', turn={'id': 'r', 'status': 'inProgress', 'items': []})
        turn = self.service._merge_live('t', [{'id': 'r', 'status': 'inProgress', 'items': []}])[0]
        self.assertEqual('failed', turn['status'])
        self.assertEqual({'message': 'native failure'}, turn['error'])
        self.assertEqual('partial', turn['items'][0]['text'])
        self.assertNotIn('t', self.service._active)

    def test_invalid_envelopes_and_indices_do_not_create_fake_turns(self):
        for event in (None, {}, {'method': 'item/started', 'params': []}):
            self.service._event(event)
        self.event('item/agentMessage/delta', itemId='a', delta={'text': 'not text'})
        self.event('item/reasoning/summaryTextDelta', itemId='a', summaryIndex=10**12, delta='bad')
        self.event('item/reasoning/summaryTextDelta', itemId='a', summaryIndex=3, delta='sparse')
        self.event('item/started', item={'id': 'missing-type'})
        self.assertFalse(self.service._live)

    def test_completion_retains_observed_items_when_turn_start_was_missed(self):
        self.event('item/agentMessage/delta', itemId='a', delta='answer')
        self.event('turn/completed', turn={'id': 'r', 'status': 'completed', 'items': []})
        turns = self.service._merge_live('t', [])
        self.assertEqual('completed', turns[0]['status'])
        self.assertEqual('answer', turns[0]['items'][0]['text'])


class LiveOffsetTests(unittest.TestCase):
    def test_live_offsets_are_monotonic_and_native_event_is_unchanged(self):
        events = []
        service = CodexService(connection=FakeNative(), on_event=events.append)
        event = {'method': 'item/agentMessage/delta', 'params': {
            'threadId': 't', 'turnId': 'turn', 'itemId': 'i', 'delta': 'hi'}}
        service._event(event)
        service._event(event)
        self.assertEqual([0, 2], [e['params']['relayOffset'] for e in events])
        self.assertNotIn('relayOffset', event['params'])
