"""Native async question identities and explicit, bounded answer envelopes."""
import json

from .connection import CodexError

OPEN = '<send_user_message_question_reply>'
CLOSE = '</send_user_message_question_reply>'


def pending_questions(turn):
    pending = {}
    if turn.get('status') in {'failed', 'interrupted', 'canceled', 'cancelled'}:
        return pending
    for item in turn.get('items', []):
        if item.get('type') == 'agentMessage' and isinstance(item.get('id'), str) and item['id']:
            questions = item.get('questions')
            for index, question in enumerate(questions if isinstance(questions, list) else []):
                if isinstance(question, dict) and isinstance(question.get('title'), str) and question['title'].strip():
                    key = json.dumps(['request_user_input_async', item['id'], index], ensure_ascii=False, separators=(',', ':'))
                    pending[key] = question['title']
        elif item.get('type') == 'userMessage' or (item.get('type') == 'steeringUserMessage' and
                (item.get('status') == 'accepted' or item.get('steerAcknowledged') is True)):
            parts = item.get('content' if item['type'] == 'userMessage' else 'input', [])
            text = '\n'.join(part['text'] for part in parts if isinstance(part, dict) and part.get('type') == 'text' and isinstance(part.get('text'), str)).strip()
            if text.startswith(OPEN) and text.endswith(CLOSE):
                try:
                    rows = json.loads(text[len(OPEN):-len(CLOSE)])
                    for row in rows if isinstance(rows, list) else [rows]:
                        if isinstance(row, dict) and isinstance(row.get('questionItemId'), str) and isinstance(row.get('answer'), str):
                            pending.pop(row['questionItemId'], None)
                except (ValueError, TypeError):
                    pass
    return pending


def answer_input(turn, answers):
    pending = pending_questions(turn)
    if not isinstance(answers, list) or not 1 <= len(answers) <= 100:
        raise CodexError('请填写问题答案', code='invalid_response', http_status=400)
    rows, seen = [], set()
    for answer in answers:
        if not isinstance(answer, dict):
            raise CodexError('问题答案无效', code='invalid_response', http_status=400)
        key, text = answer.get('questionItemId'), answer.get('answer')
        if not isinstance(key, str) or key not in pending or key in seen:
            raise CodexError('问题已回答或已失效，请刷新会话', code='interaction_expired', http_status=409)
        if not isinstance(text, str) or not text.strip() or len(text) > 200000:
            raise CodexError('问题答案无效', code='invalid_response', http_status=400)
        seen.add(key)
        rows.append({'questionItemId': key, 'question': pending[key], 'answer': text.strip()})
    text = OPEN + '\n' + json.dumps(rows, ensure_ascii=False, separators=(',', ':')) + '\n' + CLOSE
    if len(text) > 200000:
        raise CodexError('问题答案过长', code='invalid_response', http_status=400)
    return [{'type': 'text', 'text': text}]


class QuestionClaims:
    """Durable per-question dispatch fence; stores identities/status, never answer text."""
    kind = 'questionAnswer'

    def __init__(self, db=None):
        import threading
        self.db, self.memory, self.lock = db, {}, threading.RLock()

    def states(self, thread_id):
        with self.lock:
            if self.db is None:
                return {key: value['state'] for (thread, key), value in self.memory.items() if thread == thread_id}
            with self.db.read() as c:
                return {row['turn_id']: json.loads(row['payload_json'])['state'] for row in c.execute(
                    'SELECT turn_id,payload_json FROM native_observations WHERE thread_id=? AND kind=?', (thread_id, self.kind))}

    def claim(self, thread_id, ids, submission_id):
        import time
        value = {'submissionId': submission_id, 'state': 'pending'}
        with self.lock:
            if self.db is None:
                if any((thread_id, key) in self.memory for key in ids):
                    raise CodexError('此问题已提交，等待核对结果；不会重复发送', code='question_already_submitted', http_status=409)
                for key in ids: self.memory[thread_id, key] = dict(value)
                return
            with self.db.transaction() as c:
                for key in ids:
                    if c.execute('SELECT 1 FROM native_observations WHERE thread_id=? AND kind=? AND turn_id=?',
                                 (thread_id, self.kind, key)).fetchone():
                        raise CodexError('此问题已提交，等待核对结果；不会重复发送', code='question_already_submitted', http_status=409)
                    c.execute('INSERT INTO native_observations VALUES(?,?,?,?,?)',
                              (thread_id, self.kind, key, json.dumps(value), time.time()))

    def finish(self, thread_id, ids, submission_id, state):
        import time
        with self.lock:
            if self.db is None:
                for key in ids:
                    old = self.memory.get((thread_id, key))
                    if old and old['submissionId'] == submission_id:
                        if state is None: self.memory.pop((thread_id, key))
                        else: old['state'] = state
                return
            with self.db.transaction() as c:
                for key in ids:
                    row = c.execute('SELECT payload_json FROM native_observations WHERE thread_id=? AND kind=? AND turn_id=?',
                                    (thread_id, self.kind, key)).fetchone()
                    if not row or json.loads(row['payload_json'])['submissionId'] != submission_id: continue
                    if state is None:
                        c.execute('DELETE FROM native_observations WHERE thread_id=? AND kind=? AND turn_id=?', (thread_id, self.kind, key))
                    else:
                        c.execute('UPDATE native_observations SET payload_json=?,updated_at=? WHERE thread_id=? AND kind=? AND turn_id=?',
                                  (json.dumps({'submissionId': submission_id, 'state': state}), time.time(), thread_id, self.kind, key))
