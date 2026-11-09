"""Bound native full-history pages without dropping or synthesizing cursors."""
import copy
import unittest

from rivo_relay.codex.history import NativeHistoryAdapter
from rivo_relay.codex.native_journal import NativeJournal
from rivo_relay.read_budget import ReadStopped, read_budget
from unittest.mock import patch


class PagedNative:
    generation = 'one'

    def __init__(self):
        self.turns = [{'id': f'turn-{index}', 'status': 'completed', 'items': [
            {'id': f'item-{index}', 'type': 'agentMessage', 'text': f'body-{index}'}]}
            for index in range(27)]
        self.calls = []
        self.offsets = {None: 0}

    def request(self, method, params, **kwargs):
        self.calls.append(copy.deepcopy(params))
        assert method == 'thread/turns/list'
        assert params['itemsView'] == 'full'
        assert params['sortDirection'] == 'desc'
        offset = self.offsets[params['cursor']]
        newest = list(reversed(self.turns))
        data = newest[offset:offset + params['limit']]
        next_offset = offset + len(data)
        cursor = f'native:opaque/{next_offset}' if next_offset < len(newest) else None
        if cursor:
            self.offsets[cursor] = next_offset
        return {'data': copy.deepcopy(data), 'nextCursor': cursor,
                'backwardsCursor': f'native:back/{offset}'}


class BoundedHistoryPagesTests(unittest.TestCase):
    def test_large_requested_page_preserves_native_cursors_and_all_turns(self):
        native, adapter = PagedNative(), NativeHistoryAdapter()
        cursor, chronological, widths = None, [], []
        while True:
            page = adapter.read_page(native, 'thread', limit=100, cursor=cursor)
            widths.append(len(page.turns))
            chronological = list(page.turns) + chronological
            self.assertEqual(f'native:back/{sum(widths[:-1])}', page.backwards_cursor)
            cursor = page.next_cursor
            if cursor is None:
                break
            self.assertEqual(f'native:opaque/{sum(widths)}', cursor)
        self.assertEqual([10, 10, 7], widths)
        self.assertEqual(native.turns, chronological)
        self.assertEqual([10, 10, 10], [p['limit'] for p in native.calls])

    def test_head_uses_same_bounded_page_but_small_limits_remain_distinct(self):
        native, adapter = PagedNative(), NativeHistoryAdapter()
        with adapter.read_scope(head_limit=30):
            head = adapter.read_head(native, 'thread')
            self.assertEqual(10, len(head.turns))
            self.assertIs(head, adapter.read_page(native, 'thread', limit=100))
            self.assertEqual(1, len(native.calls))
            page = adapter.read_page(native, 'thread', limit=2)
            self.assertEqual(2, len(page.turns))
            self.assertEqual([10, 2], [p['limit'] for p in native.calls])

    def test_contains_preserves_search_allowance_beyond_one_hundred_small_pages(self):
        native, adapter = PagedNative(), NativeHistoryAdapter()
        native.turns = [{'id': f'turn-{index}', 'status': 'completed', 'items': []}
                        for index in range(1101)]
        journal = NativeJournal(native, adapter)
        journal._local.projection_ids = set()
        self.assertTrue(journal._contains('thread', 'turn-0'))
        self.assertEqual(111, len(native.calls))
        native.calls.clear()
        with patch('rivo_relay.read_budget.time.monotonic', return_value=0) as clock:
            with read_budget(seconds=1):
                clock.return_value = 2
                with self.assertRaises(ReadStopped):
                    journal._contains('thread', 'absent')
                clock.return_value = 0
        self.assertEqual([], native.calls)
