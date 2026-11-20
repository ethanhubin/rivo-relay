"""Native diagnostics and reasoning remain visible without copying raw requests."""
import json
import unittest

from rivo_relay.dsh.capabilities import capabilities
from rivo_relay.dsh.projection import project, stream_text
from rivo_relay.dsh.media import source_url
from rivo_relay.codex.connection import CodexError


def event(seq, kind, data):
    return {'seq': seq, 'type': kind, 'data': data}


class DshProjectionTests(unittest.TestCase):
    def test_reference_images_render_inline_with_one_shared_media_locator(self):
        text = '🙂 ![首图][pic] / [再看][pic]\n[pic]: /tmp/image.png'
        first, second = '![首图][pic]', '[再看][pic]'
        destination = '/tmp/image.png'
        media = {'eventSeq': 1, 'blockIndex': 0, 'imageIndex': 0, 'kind': 'markdown',
                 'name': 'image.png', 'start': text.index(destination), 'end': len(text),
                 'referenceUses': [
                     {'start': text.index(first), 'end': text.index(first) + len(first), 'label': '首图', 'image': True},
                     {'start': text.index(second), 'end': text.index(second) + len(second), 'label': '再看', 'image': False}]}
        history = [event(0, 'turn/start', {'turn': 1}), event(1, 'assistant/message', {'turn': 1,
            'message': {'content': [{'type': 'text', 'text': text}]}})]
        url = source_url('fixture', 1, 0, 0, name='image.png')
        result = project(history, session_id='fixture', media=[media])[0]['items'][0]['text']
        self.assertEqual(f'🙂 ![首图]({url}) / [再看]({url})\n[pic]: {url}', result)
        media['referenceUses'][1]['start'] = 0
        with self.assertRaises(CodexError):
            project(history, session_id='fixture', media=[media])

    def test_turn_failure_keeps_native_message_but_not_request_metadata(self):
        result = project([event(0, 'turn/start', {'turn': 1}),
            event(1, 'turn/end', {'turn': 1, 'reason': {'kind': 'error', 'error': {
                'code': 'INVALID_REQUEST', 'message': 'Invalid model input',
                'request': {'authorization': 'private-fixture'}}}})])
        self.assertEqual('failed', result[0]['status'])
        self.assertEqual('[INVALID_REQUEST] Invalid model input', result[0]['error']['message'])
        self.assertNotIn('private-fixture', json.dumps(result))

    def test_retry_and_empty_failed_attempt_have_native_turn_diagnostics(self):
        result = project([event(0, 'turn/start', {'turn': 1}),
            event(1, 'llm/retry', {'turn': 1, 'retry': 1, 'maxRetries': 3,
                'failure': {'code': 'RATE_LIMITED', 'message': 'Please wait'}}),
            event(2, 'assistant/attempt', {'turn': 1, 'stream': [{'type': 'chunk', 'chunk': {
                'type': 'finish', 'reason': {'kind': 'error', 'failure': {
                    'code': 'INVALID_REQUEST', 'message': 'Rejected input'}}}}]}),
            event(3, 'turn/end', {'turn': 1, 'reason': {'kind': 'completed'}})])
        self.assertEqual('completed', result[0]['status'])
        self.assertEqual([], result[0]['items'])
        self.assertIn('模型请求重试（1/3）：[RATE_LIMITED] Please wait', result[0]['error']['additionalDetails'])
        self.assertIn('[INVALID_REQUEST] Rejected input', result[0]['error']['additionalDetails'])

    def test_reasoning_only_attempt_is_preserved(self):
        stream = [{'type': 'reasoning-chunks', 'texts': ['先', '检查']},
            {'type': 'chunk', 'chunk': {'type': 'reasoning-delta', 'index': 0, 'text': '🙂'}}]
        self.assertEqual('先检查🙂', stream_text(stream, kind='reasoning'))
        self.assertEqual('', stream_text(stream))
        result = project([event(0, 'turn/start', {'turn': 1}),
                          event(1, 'assistant/attempt', {'turn': 1, 'stream': stream})])
        self.assertEqual('reasoning', result[0]['items'][0]['type'])
        self.assertEqual(['先检查🙂'], result[0]['items'][0]['content'])

    def test_live_reasoning_and_text_have_distinct_stable_ids(self):
        frames = [{'type': 'start', 'attemptId': 'attempt', 'turn': 1},
            {'type': 'chunk', 'attemptId': 'attempt', 'index': 0,
             'chunk': {'type': 'reasoning-delta', 'index': 0, 'text': 'Thinking'}},
            {'type': 'chunk', 'attemptId': 'attempt', 'index': 1,
             'chunk': {'type': 'text-delta', 'index': 0, 'text': 'Answer'}}]
        history = [event(0, 'turn/start', {'turn': 1})]
        items = project(history, frames)[0]['items']
        self.assertEqual(['live-reasoning-attempt', 'live-attempt'], [item['id'] for item in items])
        self.assertEqual(['Thinking'], items[0]['content'])
        self.assertEqual([], project(history, frames + [{'type': 'end'}])[0]['items'])
        frames[2]['index'] = 4
        self.assertEqual([], project(history, frames)[0]['items'])

    def test_dsh_advertises_attachment_limit_even_when_disconnected(self):
        for connected in (True, False):
            declaration = capabilities(connected=connected, features=['files.upload'])
            self.assertEqual(20 * 1024 * 1024, declaration['limits']['maxAttachmentBytes'])
            self.assertEqual(connected, declaration['operations']['files.upload']['available'])


if __name__ == '__main__':
    unittest.main()
