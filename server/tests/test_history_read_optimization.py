"""Read optimization contracts use only disposable homes and in-memory RPCs."""
import copy
import gc
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.history import NativeHistoryAdapter
from rivo_relay.codex.host_probe import local_native_host_info, read_identity_scope
from rivo_relay.codex import native_history_host
from rivo_relay.codex.native_history_host import inspect_journal, dispatch
from rivo_relay.codex.native_history_support import sqlite_connection
from rivo_relay.codex.native_journal import NativeJournal
from rivo_relay.codex.native_reprojection import NativeHistoryReprojectionError
from rivo_relay.codex.sessions import CodexService
from relay_test_support.native import FakeNative


THREAD = '01a04609-7917-7c73-ad6d-cae86ce70da8'
TURN = '019fc374-aaaa-7ac2-a9c9-edeee37051ea'


class IncrementalJournalTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.home = Path(temporary.name)
        self.path = self.home / 'sessions' / f'rollout-{THREAD}.jsonl'
        self.path.parent.mkdir()
        self.records = [
            {'type': 'session_meta', 'payload': {'id': THREAD}},
            {'type': 'event_msg', 'payload': {'type': 'task_started', 'turn_id': TURN}},
            *[{'type': 'response_item', 'payload': {'text': 'private body ' * 20, 'index': i}} for i in range(200)],
        ]
        self.write()
        with sqlite_connection(self.home / 'state_5.sqlite') as database:
            database.execute('CREATE TABLE threads(id TEXT, rollout_path TEXT, history_mode TEXT, cwd TEXT)')
            database.execute('INSERT INTO threads VALUES(?,?,?,?)', (THREAD, str(self.path), 'paginated', str(self.home)))

    def write(self):
        self.path.write_text(''.join(json.dumps(record) + '\n' for record in self.records))

    def append(self, record=None):
        record = record or {'type': 'event_msg', 'payload': {'type': 'task_complete', 'turn_id': TURN}}
        with self.path.open('ab') as stream:
            stream.write((json.dumps(record) + '\n').encode())

    def test_append_hashes_old_bytes_but_only_decodes_new_records(self):
        known = inspect_journal(self.home, THREAD)
        self.append()
        with patch('rivo_relay.codex.native_history_host.json.loads', wraps=json.loads) as decode, patch(
                'rivo_relay.codex.native_history_host._prefix_digest', wraps=native_history_host._prefix_digest) as prefix:
            result = inspect_journal(self.home, THREAD, known)
        self.assertEqual(1, decode.call_count)
        self.assertEqual(known['scan']['offset'], prefix.call_args.args[1])
        self.assertEqual('completed', result['journal']['status'])
        self.assertEqual(known['historyEpoch'], result['historyEpoch'])
        self.assertNotIn('private body', json.dumps(result))
        self.assertEqual(len(self.records) + 1, result['scan']['records'])

    def test_middle_rewrite_plus_append_cannot_reuse_stale_watermark(self):
        known = inspect_journal(self.home, THREAD)
        # Same inode, intact first/last bytes and larger size: a tail sample
        # would miss this old middle mutation. The complete prefix digest cannot.
        original = self.path.read_bytes()
        position = original.index(b'private body', len(original) // 2)
        with self.path.open('r+b') as stream:
            stream.seek(position)
            stream.write(b'changed body')
        self.append()
        with patch('rivo_relay.codex.native_history_host.json.loads', wraps=json.loads) as decode:
            result = inspect_journal(self.home, THREAD, known)
        self.assertEqual(len(self.records) + 1, decode.call_count)
        self.assertNotEqual(known['historyEpoch'], result['historyEpoch'])

    def test_truncation_replacement_and_same_size_rewrite_invalidate_epoch(self):
        for mutation in ('truncate', 'replace', 'same_size'):
            with self.subTest(mutation=mutation):
                self.write()
                known = inspect_journal(self.home, THREAD)
                if mutation == 'truncate':
                    self.path.write_text(json.dumps(self.records[0]) + '\n')
                elif mutation == 'replace':
                    replacement = self.path.with_suffix('.new')
                    replacement.write_bytes(self.path.read_bytes())
                    os.replace(replacement, self.path)
                else:
                    before = self.path.stat()
                    self.path.write_bytes(self.path.read_bytes().replace(b'private body', b'changed body', 1))
                    os.utime(self.path, ns=(before.st_atime_ns, before.st_mtime_ns))
                result = inspect_journal(self.home, THREAD, known)
                self.assertNotEqual(known['historyEpoch'], result['historyEpoch'])

    def test_partial_append_drops_resume_state_and_complete_corruption_fails(self):
        known = inspect_journal(self.home, THREAD)
        with self.path.open('ab') as stream:
            stream.write(b'{"type":"event_msg"')
        partial = inspect_journal(self.home, THREAD, known)
        self.assertTrue(partial['changing'])
        with self.path.open('ab') as stream:
            stream.write(b',"payload":{"type":"task_complete","turn_id":"' + TURN.encode() + b'"}}\n')
        with patch('rivo_relay.codex.native_history_host.json.loads', wraps=json.loads) as decode:
            complete = inspect_journal(self.home, THREAD, partial)
        self.assertEqual(len(self.records) + 1, decode.call_count)
        self.assertFalse(complete['changing'])
        self.assertNotEqual(known['historyEpoch'], complete['historyEpoch'])
        with self.path.open('ab') as stream:
            stream.write(b'{broken}\n')
        with self.assertRaises(NativeHistoryReprojectionError):
            inspect_journal(self.home, THREAD, complete)

    def test_identity_change_and_cached_record_limit_are_checked(self):
        known = inspect_journal(self.home, THREAD)
        self.records[0]['payload']['id'] = TURN
        self.write()
        self.append()
        with self.assertRaisesRegex(NativeHistoryReprojectionError, 'ID'):
            inspect_journal(self.home, THREAD, known)
        self.records[0]['payload']['id'] = THREAD
        self.write()
        known = inspect_journal(self.home, THREAD)
        self.append()
        with patch('rivo_relay.codex.native_history_host.MAX_RECORDS', len(self.records)):
            with self.assertRaisesRegex(NativeHistoryReprojectionError, '大小限制'):
                inspect_journal(self.home, THREAD, known)

    def test_rollback_record_invalidates_epoch_and_repair_never_uses_cached_scan(self):
        known = inspect_journal(self.home, THREAD)
        self.append({'type': 'event_msg', 'payload': {'type': 'thread_rolled_back', 'num_turns': 1}})
        result = inspect_journal(self.home, THREAD, known)
        self.assertNotEqual(known['historyEpoch'], result['historyEpoch'])
        with patch('rivo_relay.codex.native_history_host.verify_runtime', return_value=self.home), patch(
                'rivo_relay.codex.native_history_host.json.loads', wraps=json.loads) as decode, patch(
                'rivo_relay.codex.native_history_host.writer_busy', return_value=True):
            repaired = dispatch({'runtime': {}, 'threadId': THREAD, 'operation': 'repair',
                                 'known': result, 'fingerprint': result['fingerprint']})
        self.assertFalse(repaired['reprojected'])
        self.assertEqual(len(self.records) + 1, decode.call_count)

    def test_activity_round_trips_cache_but_always_checks_live_lock(self):
        request = {'runtime': {}, 'operation': 'activity', 'threadIds': [THREAD]}
        with patch('rivo_relay.codex.native_history_host.verify_runtime', return_value=self.home), patch(
                'rivo_relay.codex.native_history_host.writer_busy', return_value=True) as busy:
            first = dispatch(request)
            self.assertEqual(1, busy.call_count)
            request['known'] = first['inspections']
            with patch('rivo_relay.codex.native_history_host._rollout_reader', side_effect=AssertionError('unchanged body reread')):
                second = dispatch(request)
            self.assertEqual({THREAD: 'running'}, second['states'])
            self.assertEqual(2, busy.call_count)
            busy.return_value = False
            self.assertEqual({}, dispatch(request)['states'])
            self.assertEqual(3, busy.call_count)


class ReadScopeTests(unittest.TestCase):
    def service(self, *, pinned=False):
        native = FakeNative()
        native.native_host_info = lambda: {'generation': native.generation}
        native.turn = {'id': TURN, 'status': 'inProgress', 'items': [
            {'id': 'answer', 'type': 'agentMessage', 'text': 'hello'}]}
        original = native.request
        def request(method, params, **kwargs):
            if method == 'thread/turns/list':
                native.calls.append((method, params))
                if params['cursor']:
                    return {'data': [{'id': 'old', 'status': 'completed', 'items': []}], 'nextCursor': None}
                return {'data': [copy.deepcopy(native.turn)], 'nextCursor': 'older'}
            return original(method, params, **kwargs)
        native.request = request
        if pinned:
            wrapper = FakeNative()
            wrapper.history_host_connection = lambda _thread: native
            service = CodexService(connection=wrapper)
        else:
            service = CodexService(connection=native)
        service.native_journal._call = Mock(return_value={'available': True, 'journal': {
            'turnId': TURN, 'status': 'inProgress'}, 'writerBusy': True, 'historyEpoch': 'epoch'})
        return native, service

    def test_convergence_uses_returned_head_page_once_on_pinned_host(self):
        native, service = self.service(pinned=True)
        first = service.history('thread')
        self.assertEqual([10], [params['limit'] for method, params in native.calls if method == 'thread/turns/list'])
        native.calls.clear()
        service.history('thread', cursor='older', native_revision=first['nativeRevision'])
        self.assertEqual([2, 10], [params['limit'] for method, params in native.calls if method == 'thread/turns/list'])
        service.history('thread')
        self.assertEqual(3, len([1 for method, _ in native.calls if method == 'thread/turns/list']))

    def test_item_completion_changes_head_without_invalidating_convergence_or_cursor(self):
        native, service = self.service()
        first = service.history('thread', limit=2)
        service._event({'method': 'item/completed', 'params': {
            'threadId': 'thread', 'turnId': TURN, 'item': copy.deepcopy(native.turn['items'][0])}})
        second = service.history('thread', limit=2)
        self.assertEqual('ready', second['nativeSync']['state'])
        self.assertTrue(second['turns'][0]['items'][0]['relayCompleted'])
        self.assertNotIn('status', second['turns'][0]['items'][0])
        self.assertEqual(first['nativeRevision'], second['nativeRevision'])
        self.assertNotEqual(first['nativeHeadRevision'], second['nativeHeadRevision'])
        self.assertNotIn('relayCompleted', native.turn['items'][0])
        self.assertEqual('old', service.history('thread', cursor='older',
            native_revision=first['nativeRevision'])['turns'][0]['id'])

    def test_stream_body_revision_changes_without_invalidating_older_page(self):
        native, service = self.service()
        first = service.history('thread')
        native.turn['items'][0]['text'] += ' world'
        second = service.history('thread')
        self.assertEqual(first['nativeRevision'], second['nativeRevision'])
        self.assertNotEqual(first['nativeHeadRevision'], second['nativeHeadRevision'])
        self.assertEqual('old', service.history('thread', cursor='older', native_revision=first['nativeRevision'])['turns'][0]['id'])

    def test_live_stream_keeps_older_cursor_and_native_control_convergence(self):
        native, service = self.service(pinned=True)
        first = service.history('thread', limit=2)
        for index in range(4):
            service._event({'method': 'item/agentMessage/delta', 'params': {
                'threadId': 'thread', 'turnId': TURN, 'itemId': 'answer', 'delta': f' live {index}'}})
            head = service.history('thread', limit=2)
            self.assertEqual('converging', head['nativeSync']['state'])
            self.assertTrue(head['nativeSync']['historyReady'])
            self.assertEqual('older', head['nextCursor'])
            self.assertEqual(first['nativeRevision'], head['nativeRevision'])
            page = service.history('thread', cursor='older', native_revision=first['nativeRevision'])
            self.assertEqual('old', page['turns'][0]['id'])
            self.assertTrue(page['nativeSync']['historyReady'])

    def test_running_tool_items_do_not_restart_older_pagination(self):
        native, service = self.service()
        first = service.history('thread', limit=2)
        for index in range(4):
            native.turn['items'].append({'id': f'tool-{index}', 'type': 'commandExecution',
                'status': 'inProgress', 'command': 'test', 'aggregatedOutput': f'output {index}'})
            head = service.history('thread', limit=2)
            self.assertEqual(first['nativeRevision'], head['nativeRevision'])
            self.assertNotEqual(first['nativeHeadRevision'], head['nativeHeadRevision'])
            page = service.history('thread', cursor='older', native_revision=first['nativeRevision'])
            self.assertEqual('old', page['turns'][0]['id'])

    def test_external_running_turn_mislabel_does_not_churn_page_revision(self):
        native, service = self.service()
        native.turn['status'] = 'interrupted'
        first = service.history('thread', limit=2)
        self.assertEqual('converging', first['nativeSync']['state'])
        self.assertTrue(first['nativeSync']['historyReady'])
        native.turn['items'][0]['text'] += ' more output'
        native.turn['items'].append({'id': 'new-tool', 'type': 'commandExecution', 'status': 'inProgress'})
        second = service.history('thread', limit=2)
        self.assertEqual(first['nativeRevision'], second['nativeRevision'])
        self.assertNotEqual(first['nativeHeadRevision'], second['nativeHeadRevision'])
        self.assertEqual('old', service.history('thread', cursor='older',
            native_revision=first['nativeRevision'])['turns'][0]['id'])
        native.turn['status'] = 'inProgress'
        self.assertEqual(first['nativeRevision'], service.history('thread')['nativeRevision'])

    def test_live_body_changes_head_token_and_concurrent_repair_rejects_cached_page(self):
        native, service = self.service()
        first = service.history('thread')
        service._event({'method': 'item/agentMessage/delta', 'params': {
            'threadId': 'thread', 'turnId': TURN, 'itemId': 'answer', 'delta': 'hello live'}})
        second = service.history('thread')
        self.assertEqual(first['nativeRevision'], second['nativeRevision'])
        self.assertNotEqual(first['nativeHeadRevision'], second['nativeHeadRevision'])
        original = service._merge_live
        def concurrent_repair(*args):
            service.native_journal._converger._generations['thread'] = 1
            return original(*args)
        with patch.object(service, '_merge_live', side_effect=concurrent_repair), self.assertRaises(CodexError) as raised:
            service.history('thread')
        self.assertEqual('history_changed', raised.exception.code)

    def test_structure_terminal_body_repair_epoch_and_generation_invalidate(self):
        for change in ('terminal_structure', 'terminal_body', 'epoch', 'generation', 'repair', 'completion'):
            with self.subTest(change=change):
                native, service = self.service()
                if change in ('terminal_body', 'terminal_structure'):
                    native.turn['status'] = 'completed'
                    service.native_journal._call.return_value['journal']['status'] = 'completed'
                first = service.history('thread')
                if change == 'terminal_structure':
                    native.turn['items'].append({'id': 'new', 'type': 'reasoning', 'summary': []})
                elif change == 'completion':
                    native.turn['status'] = 'completed'
                    service.native_journal._call.return_value['journal']['status'] = 'completed'
                elif change == 'terminal_body':
                    native.turn['items'][0]['text'] = 'correction'
                elif change == 'epoch':
                    service.native_journal._call.return_value['historyEpoch'] = 'rewritten'
                elif change == 'generation':
                    native.generation = 'replacement'
                else:
                    service.native_journal.generation = lambda _: 1
                with self.assertRaises(CodexError) as raised:
                    service.history('thread', cursor='older', native_revision=first['nativeRevision'])
                self.assertEqual('history_changed', raised.exception.code)

    def test_limit_one_keeps_requested_width_and_stable_two_turn_revision(self):
        native, service = self.service()
        original = native.request
        def request(method, params, **kwargs):
            result = original(method, params, **kwargs)
            if method == 'thread/turns/list' and not params['cursor'] and params['limit'] > 1:
                result['data'].append({'id': 'second', 'status': 'completed', 'items': []})
            return result
        native.request = request
        first = service.history('thread', limit=1)
        self.assertEqual(1, len(first['turns']))
        service.history('thread', cursor='older', native_revision=first['nativeRevision'])

    def test_page_cache_discarded_on_repair_and_generation_change(self):
        native, service = self.service()
        adapter = service.history_adapter
        with adapter.read_scope(head_limit=30):
            adapter.read_page(native, 'thread', limit=30)
            adapter.read_page(native, 'thread', limit=30)
            adapter.invalidate('thread')
            adapter.read_page(native, 'thread', limit=30)
            native.generation = 'another'
            adapter.read_page(native, 'thread', limit=30)
        self.assertEqual(3, len(native.calls))
        with adapter.read_scope(head_limit=30):
            for index in range(20):
                adapter.read_page(native, 'thread', limit=30, cursor=str(index))
            self.assertFalse(adapter._reads.scope['pages'])

    def test_local_proof_scoped_by_request_generation_and_repair_refresh(self):
        native = FakeNative()
        with patch('rivo_relay.codex.host_probe._local_native_host_info', return_value={'pid': 10}) as probe:
            with read_identity_scope():
                local_native_host_info(native)
                local_native_host_info(native)
                self.assertEqual(1, probe.call_count)
                local_native_host_info(native, refresh=True)
                self.assertEqual(2, probe.call_count)
            with self.assertRaises(CodexError), read_identity_scope():
                local_native_host_info(native)
                native.generation = 'next'
                local_native_host_info(native)
            local_native_host_info(native)
            self.assertEqual(5, probe.call_count)

    def test_activity_cache_and_payload_batches_remain_bounded(self):
        native, service = self.service()
        journal = service.native_journal
        calls = []
        def activity(thread_id, operation, **params):
            calls.append(params)
            return {'states': {}, 'inspections': {tid: {'available': True, 'journal': None,
                'historyEpoch': tid, 'fingerprint': [1, 2, 3, 4, 5]} for tid in params['threadIds']}}
        journal._call = activity
        journal.execution_states([str(i) for i in range(160)])
        self.assertEqual(128, len(journal._inspections))
        self.assertEqual([32] * 5, [len(call['threadIds']) for call in calls])
        journal.execution_states(['159'])
        self.assertIn('159', calls[-1]['known'])
        # Idle convergence locks do not retain every visited thread forever.
        for index in range(200):
            journal._converger._lock_for_thread(str(index))
        gc.collect()
        self.assertFalse(journal._converger._thread_locks)


if __name__ == '__main__':
    unittest.main()
