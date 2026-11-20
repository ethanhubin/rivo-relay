"""Compact latest-turn async-question projection, guarded by ActivityRegistry.lock.

No answer text or duplicate conversation history is retained. Persisted heads and
live items use the same native question identities as the conversation panel.
"""
import json

from .questions import pending_questions

FAILED = {'failed', 'interrupted', 'canceled', 'cancelled'}


class AsyncQuestionState:
    def __init__(self, turn_id, status='inProgress', *, live=False):
        self.turn_id, self.status, self.live = turn_id, status, live
        self.by_item = {}
        self.answered = set()
        self.submissions = {}

    def item(self, item):
        item_id = item.get('id')
        if not isinstance(item_id, str) or not item_id:
            return
        if item.get('type') == 'agentMessage' and 'questions' in item:
            self.by_item[item_id] = set(pending_questions({'items': [item]}))
        elif item.get('type') in ('userMessage', 'steeringUserMessage'):
            # Match the conversation parser: only accepted steering is an answer.
            parts = item.get('content' if item['type'] == 'userMessage' else 'input', [])
            from .questions import OPEN, CLOSE
            text = '\n'.join(p.get('text', '') for p in parts if p.get('type') == 'text').strip()
            accepted = item['type'] == 'userMessage' or item.get('status') == 'accepted' or item.get('steerAcknowledged') is True
            if accepted and text.startswith(OPEN) and text.endswith(CLOSE):
                try:
                    values = json.loads(text[len(OPEN):-len(CLOSE)])
                    for row in values if isinstance(values, list) else [values]:
                        if isinstance(row, dict) and isinstance(row.get('questionItemId'), str) and isinstance(row.get('answer'), str):
                            self.answered.add(row['questionItemId'])
                except (ValueError, TypeError):
                    pass

    def observe(self, turn, answer_states=None):
        self.status = turn.get('status', self.status)
        for item in turn.get('items', []):
            self.item(item)
        if answer_states is not None:
            self.submissions = dict(answer_states)
            self.answered.update(key for key, state in answer_states.items() if state == 'accepted')
        self.live = False

    @property
    def pending(self):
        if self.status in FAILED:
            return set()
        return (set().union(*self.by_item.values()) - self.answered -
                {key for key, state in self.submissions.items() if state in ('pending', 'unknown', 'accepted')}) if self.by_item else set()
