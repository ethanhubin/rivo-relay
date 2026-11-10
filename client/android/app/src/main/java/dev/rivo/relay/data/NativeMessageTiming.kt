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

fun formatNativeElapsedDuration(seconds: Long): String = buildList {
    val value = seconds.coerceAtLeast(0)
    if (value >= 3600) add("${value / 3600}h")
    if (value >= 60) add("${value / 60 % 60}m")
    add("${value % 60}s")
}.joinToString(" ")

/** Whole-turn wall time, independent of individual assistant message timestamps. */
fun nativeTurnElapsedSeconds(turn: NativeTurnSettings, nowSeconds: Long? = null): Long? {
    if (turn.status in setOf("completed", "failed", "interrupted", "canceled", "cancelled")) {
        turn.durationMs?.takeIf { it >= 0 }?.let { return it / 1000 }
        val start = turn.startedAt ?: return null
        val end = turn.completedAt ?: return null
        return (end - start).takeIf { start > 0 && end >= start }
    }
    if (turn.status != "inProgress" || turn.completedAt != null) return null
    val start = turn.startedAt ?: return null
    val now = nowSeconds ?: return null
    return (now - start).takeIf { start > 0 && now >= start }
}

/** Transient footer for the latest stopped turn; never stored as a message. */
fun sessionCompletionLabel(snapshot: SessionSnapshot): String? {
    if (!snapshot.nativeReady || snapshot.recoveryRequired || snapshot.workspaceUnavailable ||
        snapshot.nativeExecution in setOf("running", "waiting", "active") || snapshot.isOngoing || snapshot.requests.isNotEmpty() || snapshot.queue.isNotEmpty() || snapshot.queueDepth > 0) return null
    val turn = snapshot.turnSettings.lastOrNull()
    val goal = snapshot.goal
    if (goal?.string("status") in setOf("active", "pending")) return null
    val terminalTurns = setOf("completed", "failed", "interrupted", "canceled", "cancelled")
    if (turn != null && turn.status !in terminalTurns) return null
    if (snapshot.messages.lastOrNull()?.turnId?.let { it != turn?.turnId } == true) return null
    val goalTime = nativeTimestamp(goal?.opt("updatedAt"))
    // A later independent turn supersedes a stopped goal retained on the thread.
    val turnTime = turn?.completedAt ?: turn?.startedAt
    val goalIsLatest = goal != null && (turn == null || (turnTime != null && goalTime != null && goalTime >= turnTime))
    // Without ordering evidence, do not reuse an old goal or claim its task completed.
    if (goal != null && !goalIsLatest && (turnTime == null || goalTime == null)) return null
    val status = if (goalIsLatest && goal?.string("status") in setOf("complete", "paused", "blocked", "usageLimited", "budgetLimited"))
        goal?.string("status") else turn?.status
    return when (status) {
        "completed", "complete" -> {
            val timestamp = (if (status == "complete") goalTime else turn?.completedAt)
                ?: snapshot.updatedAt.takeIf { it > 0 }
            val time = timestamp?.let { runCatching {
                java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            }.getOrNull() }
            val elapsed = turn?.let { nativeTurnElapsedSeconds(it) }
                ?.let { "耗时 ${formatNativeElapsedDuration(it)}" }
            listOfNotNull("已完成", time, elapsed).joinToString(" ")
        }
        "paused" -> "已暂停"
        "blocked" -> "受阻"
        "usageLimited" -> "额度受限"
        "budgetLimited" -> "预算已用尽"
        "failed" -> "执行失败"
        "interrupted" -> "已中断"
        "canceled", "cancelled" -> "已取消"
        else -> null
    }
}
