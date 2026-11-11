package dev.rivo.relay.ui.chat

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.MotionDurationScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.rivo.relay.data.NativeMessage
import dev.rivo.relay.data.SessionSnapshot
import dev.rivo.relay.data.string
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max

/** Presentation only: persisted text, copy/quote sources and completion state stay authoritative. */
internal class StreamingTextBuffer(initialText: String, initiallyStreaming: Boolean) {
    private var source = initialText
    private var streaming = initiallyStreaming
    private var length = initialText.length
    private var arrivalAt: Long? = null
    private var drainedAt: Long? = null
    private var rate = 24.0
    private var carry = 0.0

    val text: String get() = source.substring(0, length)
    val pending: Boolean get() = length < source.length

    fun update(value: String, active: Boolean, nowMs: Long) {
        val reset = !value.startsWith(source) || (!streaming && active)
        val added = value.length - source.length
        source = value
        streaming = active
        if (!active || reset) {
            length = value.length
            arrivalAt = null
            drainedAt = null
            rate = 24.0
            carry = 0.0
        } else if (added > 0) {
            val elapsed = arrivalAt?.let { max(0.01, (nowMs - it) / 1000.0) }
            rate = if (elapsed == null) max(24.0, added.toDouble())
                else max(24.0, rate * 0.9 + added / elapsed * 0.1)
            arrivalAt = nowMs
            if (drainedAt == null) drainedAt = nowMs
        }
    }

    fun advance(nowMs: Long): String {
        if (!pending) return text
        val elapsed = ((nowMs - (drainedAt ?: nowMs)) / 1000.0).coerceIn(0.0, 0.1)
        drainedAt = nowMs
        // Catch up with bursty transport without replaying a large response for seconds.
        val budget = elapsed * max(rate, (source.length - length) / 0.35) + carry
        val step = budget.toInt()
        carry = budget - step
        if (step > 0) {
            length = (length + step).coerceAtMost(source.length)
            // Kotlin indexes UTF-16; never publish half an emoji/supplementary character.
            if (length in 1 until source.length && source[length - 1].isHighSurrogate() && source[length].isLowSurrogate()) length++
        }
        if (!pending) { drainedAt = null; carry = 0.0 }
        return text
    }
}

internal fun shouldBufferMessage(message: NativeMessage?, snapshot: SessionSnapshot?): Boolean =
    message != null && snapshot != null && snapshot.isOngoing &&
        message.turnId == snapshot.activeTurnId &&
        message.type in setOf("agentMessage", "agent_message") &&
        message.raw.string("phase") != "commentary" &&
        !message.raw.optBoolean("relayCompleted") &&
        message.turnStatus !in setOf("completed", "interrupted", "failed", "canceled", "cancelled") &&
        message.raw.string("status") !in setOf("completed", "interrupted", "failed", "canceled", "cancelled")

/** One buffer above the lazy list, so text and its scroll request commit together. */
@Composable
internal fun rememberStreamingMessageText(identity: String?, source: String, streaming: Boolean): String {
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val enabled = streaming && lifecycle.isAtLeast(Lifecycle.State.STARTED) && motionScale > 0f
    val buffer = remember(identity) { StreamingTextBuffer(source, enabled) }
    var displayed by remember(buffer) { mutableStateOf(source) }
    // A completion, replacement, or background transition must not wait for the next tick.
    val result = if (!enabled || !source.startsWith(displayed)) source else displayed
    SideEffect {
        buffer.update(source, enabled, SystemClock.uptimeMillis())
        displayed = buffer.text
    }
    LaunchedEffect(buffer, enabled) {
        if (enabled) while (isActive) {
            delay(50)
            if (buffer.pending) withFrameNanos {
                displayed = buffer.advance(SystemClock.uptimeMillis())
            }
        }
    }
    return result
}
