package dev.rivo.relay.ui.chat

import dev.rivo.relay.data.SpeechTransport
import dev.rivo.relay.data.speechContext
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class VoiceUiState(
    val transcribing: Boolean = false,
    val sendPending: Boolean = false,
    val sendReady: Long = 0,
    val error: String? = null,
)

/** A transcript owns only the insertion span of one unchanged draft/target. */
internal class VoiceTranscriptionController(
    private val transport: SpeechTransport,
    private val scope: CoroutineScope,
    private val currentTarget: () -> String,
    private val currentDraft: () -> String,
    private val onDraftUpdated: (String, Int) -> Unit,
    private val requestTimeout: Long = 15_000,
    private val currentVocabulary: () -> String = { "" },
) {
    private val mutableState = MutableStateFlow(VoiceUiState())
    val state = mutableState.asStateFlow()
    private val generation = AtomicLong()
    @Volatile private var chunks: Channel<ByteArray>? = null
    private var job: Job? = null

    fun start(insertionOffset: Int): Boolean {
        if (state.value.transcribing) return false
        val epoch = generation.incrementAndGet()
        val target = currentTarget()
        val initial = currentDraft()
        val context = speechContext(currentVocabulary(), initial)
        val offset = insertionOffset.coerceIn(0, initial.length)
        val prefix = initial.substring(0, offset) + if (offset == initial.length && initial.isNotBlank()) "\n" else ""
        val suffix = initial.substring(offset)
        var expectedDraft = initial
        val channel = Channel<ByteArray>(8) // At most four seconds of half-second PCM chunks.
        chunks = channel
        mutableState.value = VoiceUiState(transcribing = true)
        fun owns() = generation.get() == epoch && currentTarget() == target
        fun apply(transcript: String) {
            if (!owns()) throw CancellationException("Conversation changed")
            if (currentDraft() != expectedDraft) throw CancellationException("Draft changed")
            val content = transcript.trim()
            if (content.isEmpty()) return
            expectedDraft = prefix + content + suffix
            onDraftUpdated(expectedDraft, prefix.length + content.length)
        }
        job = scope.launch {
            var streamId: String? = null
            var finished = false
            try {
                streamId = withTimeout(requestTimeout) { transport.start(context) }
                for (chunk in channel) {
                    if (!owns()) throw CancellationException("Conversation changed")
                    apply(withTimeout(requestTimeout) { transport.chunk(streamId, chunk) })
                }
                val final = withTimeout(requestTimeout) { transport.finish(streamId) }
                finished = true
                apply(final)
                if (owns()) {
                    val shouldSend = state.value.sendPending && final.isNotBlank()
                    mutableState.value = VoiceUiState(sendReady = if (shouldSend) epoch else 0,
                        error = if (final.isBlank()) "没有识别到语音，请重试" else null)
                }
            } catch (error: Exception) {
                if (owns()) {
                    mutableState.value = VoiceUiState(error = when (error) {
                        is TimeoutCancellationException -> "语音转写超时，请重试"
                        is CancellationException -> null
                        else -> error.message ?: "语音转写失败，请重试"
                    })
                }
            } finally {
                if (!finished && streamId != null) withContext(NonCancellable) {
                    runCatching { withTimeout(3_000) { transport.cancel(streamId) } }
                }
                channel.cancel()
                if (chunks === channel) chunks = null
                if (generation.get() == epoch) job = null
            }
        }
        return true
    }

    fun acceptAudio(pcm: ByteArray) {
        val channel = chunks ?: return
        if (pcm.isEmpty() || pcm.size > 16_000 || pcm.size % 2 != 0 || channel.trySend(pcm.copyOf()).isFailure)
            cancel("语音上传跟不上录音，已停止，请重试")
    }
    fun finish() { chunks?.close() }
    fun markSendPending(): Boolean {
        if (!state.value.transcribing || state.value.sendPending) return false
        mutableState.update { it.copy(sendPending = true) }
        return true
    }
    fun consumeSendReady(epoch: Long): Boolean {
        if (epoch == 0L || generation.get() != epoch || state.value.sendReady != epoch) return false
        mutableState.update { it.copy(sendReady = 0) }
        return true
    }
    fun cancel(error: String? = null) {
        generation.incrementAndGet()
        chunks?.cancel(); chunks = null
        job?.cancel(); job = null
        mutableState.value = VoiceUiState(error = error)
    }
}
