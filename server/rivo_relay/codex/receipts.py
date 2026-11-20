"""Codex-only acceptance evidence from native identities and paged history."""
from ..receipts import InputReceipt, execution_started, identity


def decode_legacy(receipt, submission_id):
    thread_id, turn_id = identity(receipt, 'thread'), identity(receipt, 'turn')
    if thread_id and turn_id:
        return InputReceipt('execution-started', submission_id, thread_id, turn_id)
    return None


def accepted_receipt(thread_id, turn_id, submission_id):
    # Direct/internal callers predating submissions may not have a client ID.
    if submission_id is None:
        return {'threadId': thread_id, 'turnId': turn_id}
    return execution_started(submission_id, thread_id, turn_id, turnId=turn_id)


class HistoryReceiptRecovery:
    def __init__(self):
        self.cursors = {}

    def recover(self, record, history):
        submission_id = record['id']
        if record['operation'] not in {'thread.turns', 'thread.continue', 'thread.answers', 'thread.queue.steer'}:
            return None
        target = record['target']
        client_id = (record.get('receipt') or {}).get('itemId') if record['operation'] == 'thread.queue.steer' else submission_id
        if not client_id:
            return None
        try:
            snapshot = history(target, limit=100)
            if (snapshot.get('nativeSync') or {}).get('state', 'ready') != 'ready' or snapshot.get('threadId', target) != target:
                return None
            revision = snapshot.get('nativeRevision')
            turns = snapshot.get('turns', [])
            saved = self.cursors.get(submission_id)
            cursor = saved[1] if saved and saved[0] == revision else snapshot.get('nextCursor')
            seen = set()
            for page_index in range(6):
                matches = [turn for turn in turns if turn.get('id') and any(
                    item.get('type') == 'userMessage' and item.get('clientId') == client_id
                    for item in turn.get('items', []))]
                if len(matches) == 1:
                    self.cursors.pop(submission_id, None)
                    return accepted_receipt(target, matches[0]['id'], submission_id)
                if matches or not cursor or cursor in seen:
                    self.cursors.pop(submission_id, None)
                    return None
                if page_index == 5:
                    self.cursors[submission_id] = (revision, cursor)
                    return None
                seen.add(cursor)
                self.cursors[submission_id] = (revision, cursor)
                page = history(target, cursor=cursor, limit=100, native_revision=revision)
                if ((page.get('nativeSync') or {}).get('state', 'ready') != 'ready'
                        or page.get('threadId', target) != target or page.get('nativeRevision', revision) != revision):
                    self.cursors.pop(submission_id, None)
                    return None
                turns, cursor = page.get('turns', []), page.get('nextCursor')
            return None
        except Exception as error:
            # Preserve progress on timeouts, restart only invalidated cursors.
            if isinstance(error, ValueError) or getattr(error, 'code', None) in {'history_changed', 'invalid_request'}:
                self.cursors.pop(submission_id, None)
            raise
