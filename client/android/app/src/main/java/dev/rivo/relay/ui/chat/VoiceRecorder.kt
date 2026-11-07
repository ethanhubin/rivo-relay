package dev.rivo.relay.ui.chat

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Pure PCM framing is separate so stop/cancel tail semantics are testable. */
internal class Pcm16Chunks(private val samplesPerChunk: Int = 8_000, private val emit: (ByteArray) -> Unit) {
    private val bytes = ByteArray(samplesPerChunk * 2)
    private var size = 0
    fun append(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        for (index in 0 until count) {
            val value = samples[index].toInt()
            bytes[size++] = (value and 0xff).toByte()
            bytes[size++] = ((value ushr 8) and 0xff).toByte()
            if (size == bytes.size) { emit(bytes.copyOf()); size = 0 }
        }
    }
    fun finish(flush: Boolean) {
        if (flush && size > 0) emit(bytes.copyOf(size))
        size = 0
    }
}

internal fun pcm16RmsAmplitude(samples: ShortArray, count: Int): Float {
    if (count <= 0) return 0f
    var sum = 0.0
    for (i in 0 until count.coerceAtMost(samples.size)) { val sample = samples[i].toDouble() / 32768.0; sum += sample * sample }
    return (sqrt(sum / count) * 4).toFloat().coerceIn(0f, 1f)
}

internal class VoiceRecorder {
    private var recording = AtomicBoolean()
    private var flushOnStop = AtomicBoolean()
    private var recorder: AudioRecord? = null
    private var reader: Thread? = null

    fun start(onChunk: (ByteArray) -> Unit, onAmplitude: (Float) -> Unit,
              onStopped: (String?) -> Unit): Boolean {
        cancel()
        return runCatching {
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0) { "设备不支持 16 kHz 录音" }
            @Suppress("MissingPermission")
            val active = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 32_000))
            recorder = active
            check(active.state == AudioRecord.STATE_INITIALIZED) { "录音设备初始化失败" }
            active.startRecording()
            check(active.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法启动录音" }
            val running = AtomicBoolean(true)
            val flush = AtomicBoolean(false)
            recording = running; flushOnStop = flush
            reader = thread(name = "rivo-streaming-recorder", isDaemon = true) {
                val frames = Pcm16Chunks(emit = onChunk)
                val samples = ShortArray(800)
                var captured = 0
                var failure: String? = null
                try {
                    while (running.get() && captured < 16_000 * 120) {
                        val count = active.read(samples, 0, minOf(samples.size, 16_000 * 120 - captured), AudioRecord.READ_BLOCKING)
                        if (count < 0) {
                            if (running.get()) failure = "录音设备中断，请重试"
                            break
                        }
                        if (count == 0) continue
                        // Samples already captured before stop belong to the final tail.
                        if (running.get() || flush.get()) {
                            captured += count
                            onAmplitude(pcm16RmsAmplitude(samples, count))
                            frames.append(samples, count)
                        }
                    }
                    if (captured >= 16_000 * 120) flush.set(true)
                } catch (_: Exception) {
                    if (running.get()) failure = "录音已中断，请重试"
                } finally {
                    running.set(false)
                    frames.finish(flush.get() && failure == null)
                    onAmplitude(0f)
                    onStopped(failure)
                }
            }
        }.onFailure { release(false) }.isSuccess
    }
    fun stop() = release(true)
    fun cancel() = release(false)
    private fun release(flush: Boolean) {
        flushOnStop.set(flush)
        recording.set(false)
        val active = recorder
        recorder = null
        runCatching { active?.stop() }
        reader?.let { if (it !== Thread.currentThread()) runCatching { it.join(1_000) } }
        reader = null
        runCatching { active?.release() }
    }
}
