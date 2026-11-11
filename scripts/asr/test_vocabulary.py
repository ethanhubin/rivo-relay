import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock, patch
from mac_asr import AsrInvalidResponse, SpeechTranscriptionService as Service


class VocabularyTest(unittest.TestCase):
    def test_request_vocabulary_filters_labeled_and_complete_echo(self):
        for context in ['固定词表：ComfyUI、LoRA\n编辑图片', '固定词表：Merge、棋子、订单']:
            hint = context.partition('\n')[0]
            for text in [hint, hint.split('、')[0], hint.split('：')[1].replace('、', ', ').upper()]:
                self.assertEqual(Service._clean_transcript(text, context), '')

    def test_real_speech_and_single_word_vocabulary_are_preserved(self):
        context = '固定词表：Merge、棋子、订单'
        for text in ['固定词表需要修改一下', '固定词表：棋子和订单', 'Merge', '棋子', '打开 Merge 的棋子生成器。', '']:
            self.assertEqual(Service._clean_transcript(text, context), text)
        self.assertEqual(Service._clean_transcript('Merge', '固定词表：Merge'), 'Merge')

    def test_empty_and_other_request_context_do_not_filter_someone_elses_words(self):
        text = '固定词表：Merge、棋子'
        for context in ['', '普通草稿', '固定词表：ComfyUI、LoRA']:
            self.assertEqual(Service._clean_transcript(text, context), text)

    def test_file_and_stream_requests_do_not_inject_server_vocabulary(self):
        service = Service.__new__(Service)
        service._request = Mock(return_value=SimpleNamespace(text='固定词表：ComfyUI、LoRA'))
        context = '固定词表：ComfyUI、LoRA\n编辑图片'
        self.assertEqual(service.transcribe(b'audio', 'test.wav', context)['text'], '')
        service._request.assert_called_with('transcribe', b'audio', 'test.wav', context, timeout=120)
        self.assertEqual(service.transcribe(b'audio', 'test.wav')['text'], '固定词表：ComfyUI、LoRA')
        service._request.assert_called_with('transcribe', b'audio', 'test.wav', '', timeout=120)
        for context in ['', '草稿', '固定词表：词条', '文' * 400]:
            service.start_stream(context)
            service._request.assert_called_with('start_stream', context, timeout=120)
        for context in ['文' * 401, None, 123]:
            with self.assertRaises(AsrInvalidResponse):
                service.start_stream(context)

    def test_interleaved_streams_keep_request_hints_for_partial_and_final_recognition(self):
        session = Mock()
        # Model states deliberately have no context field: the worker owns the hint.
        session.init_streaming.side_effect = lambda **kw: SimpleNamespace(text='', stable_text='', hint=kw['context'])
        session.feed_audio.side_effect = lambda pcm, state: SimpleNamespace(
            text=state.hint.partition('\n')[0], stable_text=state.hint.partition('\n')[0], hint=state.hint)
        session.transcribe.side_effect = lambda pcm, **kw: SimpleNamespace(text=kw['context'].partition('\n')[0])
        with tempfile.TemporaryDirectory() as folder, patch.dict('sys.modules', {'numpy': MagicMock()}):
            service = Service(SimpleNamespace(local_asr_model=folder, local_asr_temp=folder), session_factory=lambda _: session)
            try:
                first_context = '固定词表：ComfyUI、LoRA\n编辑图片'
                second_context = '固定词表：Merge、棋子'
                first = service.start_stream(first_context)['stream_id']
                second = service.start_stream(second_context)['stream_id']
                for stream in [second, first]:
                    partial = service.feed_stream(stream, b'\x00\x00')
                    self.assertEqual((partial['text'], partial['stable_text']), ('', ''))
                    self.assertEqual(service.finish_stream(stream)['text'], '')
                self.assertEqual([call.kwargs['context'] for call in session.transcribe.call_args_list],
                                 [second_context, first_context])
                plain = service.start_stream('普通草稿')['stream_id']
                self.assertEqual(service.finish_stream(plain)['text'], '普通草稿')
            finally:
                service.requests.put(None)
                service.worker.join(timeout=5)
                self.assertFalse(service.worker.is_alive())


if __name__ == '__main__':
    unittest.main()
