package dev.rivo.relay.data

/** Rebuilt from native history when a conversation is reopened or older turns are paged in. */
fun nativeHistoricalTimingLabel(turn: NativeTurnSettings): String? {
    if (turn.status !in setOf("completed", "failed", "interrupted", "canceled", "cancelled")) return null
    val time = turn.completedAt?.let { runCatching {
        java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrNull() }
    val elapsed = nativeTurnElapsedSeconds(turn)?.let { "耗时 ${formatNativeElapsedDuration(it)}" }
    return listOfNotNull(time, elapsed).joinToString(" ").takeIf { it.isNotBlank() }
}

data class NativeHistoricalTiming(val turnId: String, val label: String)

/** Boundaries are before row N (rows.size means after the final row); no extra lazy indices. */
fun nativeHistoricalTimingRows(rows: List<NativeTimelineRow>, turns: List<NativeTurnSettings>,
    tailTurnId: String?): Map<Int, List<NativeHistoricalTiming>> {
    val first = mutableMapOf<String, Int>()
    val last = mutableMapOf<String, Int>()
    rows.forEachIndexed { index, row ->
        val id = when (row) {
            is NativeTimelineRow.Message -> row.message.turnId
            is NativeTimelineRow.Activities -> row.messages.first().turnId
            is NativeTimelineRow.Notice -> row.turn.turnId
        }
        first.putIfAbsent(id, index)
        last[id] = index
    }
    val ordered = turns.distinctBy { it.turnId }
    val following = arrayOfNulls<Int>(ordered.size)
    var next: Int? = null
    for (index in ordered.indices.reversed()) {
        following[index] = next
        first[ordered[index].turnId]?.let { next = it }
    }
    val result = mutableMapOf<Int, MutableList<NativeHistoricalTiming>>()
    var precedingEnd: Int? = null
    ordered.forEachIndexed { index, turn ->
        if (turn.turnId != tailTurnId) nativeHistoricalTimingLabel(turn)?.let { label ->
            val boundary = last[turn.turnId]?.plus(1) ?: following[index] ?: precedingEnd ?: rows.size
            result.getOrPut(boundary) { mutableListOf() }.add(NativeHistoricalTiming(turn.turnId, label))
        }
        last[turn.turnId]?.let { precedingEnd = it + 1 }
    }
    return result
}
