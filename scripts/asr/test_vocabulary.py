import unittest
from types import SimpleNamespace
from unittest.mock import Mock
from mac_asr import PROJECT_VOCABULARY_CONTEXT, SpeechTranscriptionService as Service

class VocabularyTest(unittest.TestCase):
    def test_labeled_partial_and_complete_echo(self):
        for text in ['固定词表：Merge、MergeDemo', PROJECT_VOCABULARY_CONTEXT]:
            self.assertEqual(Service._clean_transcript(text), '')

    def test_unlabeled_echo_with_different_punctuation_and_case(self):
        text = PROJECT_VOCABULARY_CONTEXT.split('：', 1)[1].replace('、', ', ').upper()
        self.assertEqual(Service._clean_transcript(text), '')

    def test_real_vocabulary_speech_is_preserved(self):
        for text in ['固定词表需要修改一下', '固定词表：棋子和订单', 'Merge', '棋子', '打开 MergeDemo 的棋子生成器。',
                     'Gossip Harbor 和 Tasty Travels 的订单等级是多少？', '']:
            self.assertEqual(Service._clean_transcript(text), text)

    def test_stream_and_file_outputs_filter_echo(self):
        state = SimpleNamespace(text=PROJECT_VOCABULARY_CONTEXT, stable_text='固定词表：Merge')
        result = Service._stream_payload('test', state, False)
        self.assertEqual((result['text'], result['stable_text']), ('', ''))
        service = Service.__new__(Service)
        service._request = Mock(return_value=SimpleNamespace(text=PROJECT_VOCABULARY_CONTEXT))
        self.assertEqual(service.transcribe(b'audio', 'test.wav')['text'], '')

if __name__ == '__main__':
    unittest.main()
