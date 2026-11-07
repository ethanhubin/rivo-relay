package dev.rivo.relay.data

/** Native turn timestamps are Unix seconds; item timestamps may also be ISO instants. */
fun nativeTimestamp(value: Any?): Long? = when (value) {
    is Number -> value.toLong().takeIf { it > 0 }
    is String -> value.toLongOrNull()?.takeIf { it > 0 }
        ?: runCatching { java.time.Instant.parse(value).epochSecond }.getOrNull()?.takeIf { it > 0 }
    else -> null
}

data class NativeMessageTiming(val outputAt: Long?, val elapsedSeconds: Long?)

fun nativeMessageTiming(message: NativeMessage, turn: NativeTurnSettings?, isLastReply: Boolean): NativeMessageTiming {
    val ownTime = nativeTimestamp(message.raw.opt("createdAt"))
        ?: nativeTimestamp(message.raw.opt("timestamp"))
    val finalTime = turn?.completedAt?.takeIf { isLastReply && turn.status in setOf("completed", "failed", "interrupted") }
    val outputAt = ownTime ?: finalTime
    val elapsed = if (ownTime != null && turn?.startedAt != null) {
        (ownTime - turn.startedAt).takeIf { it >= 0 }
    } else if (finalTime != null) {
        turn?.durationMs?.div(1000) ?: turn?.startedAt?.let { (finalTime - it).takeIf { seconds -> seconds >= 0 } }
    } else null
    return NativeMessageTiming(outputAt, elapsed)
}

fun formatNativeThinkingDuration(seconds: Long): String = when {
    seconds < 1 -> "不足 1 秒"
    seconds < 60 -> "${seconds} 秒"
    else -> "${seconds / 60} 分${seconds % 60} 秒"
}
