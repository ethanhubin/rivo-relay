package dev.rivo.relay.data

/** Display-only state: never changes execution, permissions or stored history. */
data class NativeTurnFooter(
    val turn: NativeTurnSettings?,
    val label: String? = null,
    val synchronizing: Boolean = false,
    val observedAt: Long,
    val clientStartedAtMillis: Long? = null,
)

/** Owned by one open conversation. Missing transport data must not erase its footer. */
class NativeTurnFooterCache {
    private var retained: NativeTurnFooter? = null

    fun project(snapshot: SessionSnapshot?, synchronizing: Boolean, nowSeconds: Long,
        nowElapsedMillis: Long = System.nanoTime() / 1_000_000): NativeTurnFooter? {
        val previous = retained
        fun pending(): NativeTurnFooter? {
            val pending = previous?.copy(synchronizing = true)
            retained = pending
            return pending
        }
        if (snapshot == null) return pending()
        val uncertain = synchronizing || !snapshot.nativeReady || snapshot.recoveryRequired || snapshot.workspaceUnavailable
        // Native history is chronological. An interrupted turn can remain in the
        // activity projection while its continuation already has a newer turn.
        val candidate = snapshot.turnSettings.lastOrNull()
        // Retain missing/same-turn observations, but never pin an older footer
        // below a newly observed continuation (including during revalidation).
        val candidateEnded = candidate?.status in setOf("completed", "failed", "interrupted", "canceled", "cancelled")
        if (uncertain && previous != null && (candidate == null ||
            candidate.turnId == previous.turn?.turnId && (!candidateEnded || previous.label != null))) return pending()
        if (candidate == null) {
            val label = sessionCompletionLabel(snapshot) ?: return pending()
            return NativeTurnFooter(null, label, uncertain, nowSeconds).also { retained = it }
        }
        val same = previous?.turn?.takeIf { it.turnId == candidate.turnId }
        val turn = candidate.copy(startedAt = candidate.startedAt ?: same?.startedAt,
            completedAt = candidate.completedAt ?: same?.completedAt,
            durationMs = candidate.durationMs ?: same?.durationMs)
        val terminal = turn.status in setOf("completed", "failed", "interrupted", "canceled", "cancelled")
        if (!terminal && turn.status != "inProgress") return pending()
        // A late incomplete read of the same turn must not restart a settled timer.
        if (!terminal && same?.status in setOf("completed", "failed", "interrupted", "canceled", "cancelled")) return pending()
        val resolved = snapshot.copy(turnSettings = snapshot.turnSettings.map { if (it.turnId == turn.turnId) turn else it })
        val label = if (terminal) sessionCompletionLabel(resolved) ?: listOfNotNull(
            when (turn.status) {
                "failed" -> "执行失败"
                "interrupted" -> "已中断"
                "canceled", "cancelled" -> "已取消"
                else -> "本轮已结束"
            },
            nativeTurnElapsedSeconds(turn)?.let { "耗时 ${formatNativeElapsedDuration(it)}" }
        ).joinToString(" ") else null
        val clientStart = previous?.takeIf { it.turn?.turnId == turn.turnId }?.clientStartedAtMillis
            ?: nowElapsedMillis.takeIf { !terminal }
        return NativeTurnFooter(turn, label, uncertain, nowSeconds, clientStart).also { retained = it }
    }
}

/** Live elapsed time belongs to the client, not native timestamps or transport state. */
fun clientTurnElapsedSeconds(footer: NativeTurnFooter, nowElapsedMillis: Long): Long? =
    footer.clientStartedAtMillis?.let { ((nowElapsedMillis - it).coerceAtLeast(0)) / 1000 }

/** A settled footer belongs to its turn, before any next-turn local send receipt. */
fun nativeTurnFooterBoundary(rows: List<NativeTimelineRow>, footer: NativeTurnFooter): Int {
    if (footer.label == null) return rows.size
    val last = rows.indexOfLast { row ->
        when (row) {
            is NativeTimelineRow.Message -> row.message.turnId
            is NativeTimelineRow.Activities -> row.messages.first().turnId
            is NativeTimelineRow.Notice -> row.turn.turnId
        } == footer.turn?.turnId
    }
    if (last >= 0) return last + 1
    return rows.indexOfFirst { it is NativeTimelineRow.Message && it.message.raw.has("relayDelivery") }
        .takeIf { it >= 0 } ?: rows.size
}
