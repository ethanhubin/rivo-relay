"""Native history compatibility must preserve content without inventing execution state."""
import copy
import unittest

from rivo_relay.codex.history import NativeHistoryAdapter, NativeHistoryProtocolError


class NativePage:
    def __init__(self, turns, **envelope):
        self.response = {'data': turns, 'nextCursor': None, 'backwardsCursor': None, **envelope}

    def request(self, method, params, **kwargs):
        assert method == 'thread/turns/list'
        assert params['itemsView'] == 'full'
        return self.response


def turn(identity='turn', items=None, **extra):
    return {'id': identity, 'status': 'completed', 'items': [] if items is None else items, **extra}


class HistoryCompatibilityTests(unittest.TestCase):
    def read(self, turns, *, cursor=None, **envelope):
        return NativeHistoryAdapter().read_page(NativePage(turns, **envelope), 'thread', limit=30, cursor=cursor)

    def test_unknown_display_item_preserves_identity_fields_and_neighboring_messages(self):
        unknown = {'id': 'future-item', 'type': 'futureNativeTool', 'status': 'futureState',
                   'payload': {'content': [{'type': 'newContent', 'value': [1, None, {'a': True}]}]},
                   'futureField': 'exact native bytes represented by JSON'}
        native = [turn(items=[{'id': 'user', 'type': 'userMessage', 'content': []}, unknown,
                             {'id': 'agent', 'type': 'agentMessage', 'text': 'known answer'}])]
        before = copy.deepcopy(native)
        page = self.read(native)
        self.assertEqual(before, list(page.turns))
        self.assertEqual(before, native)
        self.assertEqual('futureNativeTool', page.turns[0]['items'][1]['type'])
        self.assertNotIn('text', page.turns[0]['items'][1])
        self.assertEqual('futureState', page.turns[0]['items'][1]['status'])

    def test_unknown_turn_status_never_becomes_idle_or_complete(self):
        for status in ('futureStatus', '', None, {}, True, ' completed '):
            with self.subTest(status=status), self.assertRaises(NativeHistoryProtocolError):
                self.read([turn(status=status)])

    def test_summary_and_unloaded_items_are_not_accepted_as_complete_history(self):
        for view in ('summary', 'notLoaded', None, 'futureView'):
            with self.subTest(itemsView=view), self.assertRaisesRegex(NativeHistoryProtocolError, 'full items'):
                self.read([turn(itemsView=view)])
        self.assertEqual((), self.read([]).turns)
        self.assertEqual([], self.read([turn(itemsView='full')]).turns[0]['items'])
        self.assertEqual([], self.read([turn()]).turns[0]['items'])  # Older native omits the default.

    def test_unknown_items_still_require_canonical_id_and_type(self):
        for field in ('id', 'type'):
            for invalid in ('', ' ', ' item ', 1, None, [], {}):
                item = {'id': 'future-item', 'type': 'futureType', field: invalid}
                with self.subTest(field=field, value=invalid), self.assertRaises(NativeHistoryProtocolError):
                    self.read([turn(items=[item])])
        with self.assertRaises(NativeHistoryProtocolError):
            self.read([turn(' turn ')])

    def test_unknown_items_do_not_bypass_duplicate_identity_checks(self):
        item = {'id': 'same-item', 'type': 'futureType'}
        for native in ([turn(items=[item, item])],
                       [turn('a', [item]), turn('b', [item])],
                       [turn('same-turn'), turn('same-turn')]):
            with self.subTest(native=native), self.assertRaisesRegex(NativeHistoryProtocolError, 'duplicate'):
                self.read(native)

    def test_malformed_envelopes_and_cursor_progress_still_fail(self):
        for turns in (None, {}, ['not-a-turn'], [turn(items={})], [turn(items=[None])]):
            with self.subTest(turns=turns), self.assertRaises(NativeHistoryProtocolError):
                self.read(turns)
        with self.assertRaisesRegex(NativeHistoryProtocolError, 'non-advancing'):
            self.read([turn()], cursor='cursor', nextCursor='cursor')
        for field in ('nextCursor', 'backwardsCursor'):
            with self.subTest(field=field), self.assertRaises(NativeHistoryProtocolError):
                self.read([turn()], **{field: {}})

    def test_chronology_and_all_native_turn_fields_survive_compatibility(self):
        older = turn('older', [{'id': 'u1', 'type': 'futureType'}], startedAt=1,
                     error={'message': 'original error'}, futureTurnField={'value': 1})
        newer = turn('newer', [{'id': 'u2', 'type': 'agentMessage', 'text': 'new'}], startedAt=2)
        page = self.read([newer, older], nextCursor='older-page', backwardsCursor='newer-page')
        self.assertEqual([older, newer], list(page.turns))
        self.assertEqual('older-page', page.next_cursor)
        self.assertEqual('newer-page', page.backwards_cursor)


if __name__ == '__main__':
    unittest.main()
