package dev.rivo.relay

import dev.rivo.relay.data.SpeechTransport
import dev.rivo.relay.ui.chat.Pcm16Chunks
import dev.rivo.relay.ui.chat.VoiceTranscriptionController
import dev.rivo.relay.ui.chat.pcm16RmsAmplitude
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTranscriptionTest {
    private class Speech : SpeechTransport {
        val contexts = mutableListOf<String?>()
        var partial = "一段"
        var final = "一段语音"
        var cancelCount = 0
        var startGate: CompletableDeferred<Unit>? = null
        var chunkGate: CompletableDeferred<Unit>? = null
        var finishGate: CompletableDeferred<Unit>? = null
        override suspend fun start(context: String?): String { contexts += context; startGate?.await(); return "stream" }
        override suspend fun chunk(streamId: String, pcm: ByteArray): String { chunkGate?.await(); return partial }
        override suspend fun finish(streamId: String): String { finishGate?.await(); return final }
        override suspend fun cancel(streamId: String) { cancelCount++ }
    }

    @Test fun eachRecordingReadsCurrentVocabularyAndKeepsItsOwnContext() = runTest {
        val transport = Speech()
        var vocabulary = "ComfyUI、LoRA"
        var draft = "正在编辑生成器"
        val controller = VoiceTranscriptionController(transport, backgroundScope, { "thread" }, { draft },
            { value, _ -> draft = value }, currentVocabulary = { vocabulary })
        controller.start(0)
        vocabulary = "Gossip Harbor、棋子"
        runCurrent()
        assertEquals("固定词表：ComfyUI、LoRA\n正在编辑生成器", transport.contexts.single())
        controller.finish(); runCurrent()
        draft = ""
        controller.start(0); runCurrent()
        assertEquals("固定词表：Gossip Harbor、棋子", transport.contexts.last())
        controller.finish(); runCurrent()
        vocabulary = ""
        draft = "普通草稿"
        controller.start(0); runCurrent()
        assertEquals("普通草稿", transport.contexts.last())
        controller.cancel(); runCurrent()
    }

    @Test fun partialAndFinalReplaceOnlyTheOriginalInsertionSpan() = runTest {
        val transport = Speech()
        var draft = "前后"
        var cursor = -1
        val controller = VoiceTranscriptionController(transport, backgroundScope, { "thread" }, { draft }, { value, position -> draft = value; cursor = position })
        assertTrue(controller.start(1))
        runCurrent()
        controller.acceptAudio(byteArrayOf(1, 0)); runCurrent()
        assertEquals("前一段后", draft)
        controller.finish(); runCurrent()
        assertEquals("前一段语音后", draft)
        assertEquals("前一段语音".length, cursor)
        assertFalse(controller.state.value.transcribing)
        assertEquals(0, transport.cancelCount)
    }

    @Test fun endInsertionUsesSourceNewlineAndEmptyFinalPreservesRecognizedPartial() = runTest {
        val transport = Speech().apply { final = "" }
        var draft = "现有草稿"
        val controller = VoiceTranscriptionController(transport, backgroundScope, { "thread" }, { draft }, { value, _ -> draft = value })
        controller.start(draft.length); runCurrent()
        controller.acceptAudio(byteArrayOf(0, 0)); runCurrent()
        controller.markSendPending(); controller.finish(); runCurrent()
        assertEquals("现有草稿\n一段", draft)
        assertEquals(0, controller.state.value.sendReady)
        assertNotNull(controller.state.value.error)
    }

    @Test fun manualDraftEditFencesLateFinalWithoutOverwritingText() = runTest {
        val transport = Speech().apply { finishGate = CompletableDeferred() }
        var draft = ""
        val controller = VoiceTranscriptionController(transport, backgroundScope, { "thread" }, { draft }, { value, _ -> draft = value })
        controller.start(0); runCurrent(); controller.finish(); runCurrent()
        draft = "用户手动输入"
        transport.finishGate!!.complete(Unit); runCurrent()
        assertEquals("用户手动输入", draft)
        assertFalse(controller.state.value.transcribing)
        assertEquals(0, controller.state.value.sendReady)
    }

    @Test fun switchingTargetCannotPublishOrSendAnOldTranscript() = runTest {
        val transport = Speech().apply { finishGate = CompletableDeferred() }
        var target = "one"
        var draft = ""
        val controller = VoiceTranscriptionController(transport, backgroundScope, { target }, { draft }, { value, _ -> draft = value })
        controller.start(0); runCurrent(); controller.markSendPending(); controller.finish(); runCurrent()
        target = "two"; controller.cancel(); draft = "second draft"
        transport.finishGate!!.complete(Unit); runCurrent()
        assertEquals("second draft", draft)
        assertFalse(controller.state.value.transcribing)
        assertEquals(0, controller.state.value.sendReady)
        assertEquals(1, transport.cancelCount)
    }

    @Test fun sendDuringTranscriptionIsDeferredUntilFinalAndConsumedOnce() = runTest {
        val transport = Speech()
        var draft = ""
        val controller = VoiceTranscriptionController(transport, backgroundScope, { "thread" }, { draft }, { value, _ -> draft = value })
        controller.start(0); runCurrent()
        assertTrue(controller.markSendPending()); assertFalse(controller.markSendPending())
        assertEquals(0, controller.state.value.sendReady)
        controller.finish(); runCurrent()
        assertEquals(transport.final, draft)
        val ready = controller.state.value.sendReady
        assertTrue(ready > 0)
        assertTrue(controller.consumeSendReady(ready)); assertFalse(controller.consumeSendReady(ready))
    }

    @Test fun boundedAudioQueueStopsRatherThanDroppingSpeechOrGrowingForever() = runTest {
        val transport = Speech().apply { startGate = CompletableDeferred() }
        val controller = VoiceTranscriptionController(transport, backgroundScope, { "thread" }, { "" }, { _, _ -> })
        controller.start(0); runCurrent()
        repeat(9) { controller.acceptAudio(ByteArray(16_000)) }
        runCurrent()
        assertFalse(controller.state.value.transcribing)
        assertTrue(controller.state.value.error!!.contains("上传跟不上"))
    }

    @Test fun timedOutChunkCancelsStreamOnceAndNeverReplaysChunk() = runTest {
        val transport = Speech().apply { chunkGate = CompletableDeferred() }
        val controller = VoiceTranscriptionController(transport, backgroundScope, { "thread" }, { "" }, { _, _ -> }, requestTimeout = 100)
        controller.start(0); runCurrent(); controller.acceptAudio(byteArrayOf(0, 0)); runCurrent()
        advanceTimeBy(101); runCurrent()
        assertFalse(controller.state.value.transcribing)
        assertTrue(controller.state.value.error!!.contains("超时"))
        assertEquals(1, transport.cancelCount)
    }

    @Test fun stoppingFlushesEvenARecordingShorterThanOneChunkInLittleEndian() {
        val emitted = mutableListOf<ByteArray>()
        val pcm = Pcm16Chunks(4, emitted::add)
        pcm.append(shortArrayOf(0x1234, -1, 1), 3)
        assertTrue(emitted.isEmpty())
        pcm.finish(true); pcm.finish(true)
        assertEquals(1, emitted.size)
        assertArrayEquals(byteArrayOf(0x34, 0x12, -1, -1, 1, 0), emitted.single())
    }

    @Test fun cancellingDropsOnlyUnsubmittedTailAndFullChunksStayBounded() {
        val emitted = mutableListOf<ByteArray>()
        val pcm = Pcm16Chunks(2, emitted::add)
        pcm.append(shortArrayOf(1, 2, 3), 3); pcm.finish(false)
        assertEquals(1, emitted.size); assertEquals(4, emitted.single().size)
        assertEquals(0f, pcm16RmsAmplitude(shortArrayOf(0, 0), 2))
        assertEquals(1f, pcm16RmsAmplitude(shortArrayOf(Short.MAX_VALUE), 1))
    }
}
