package dev.rivo.relay.data

/** Display-only state: never changes execution, permissions or stored history. */
data class NativeTurnFooter(
    val turn: NativeTurnSettings?,
    val label: String? = null,
    val synchronizing: Boolean = false,
    val observedAt: Long,
)

/** Owned by one open conversation. Missing transport data must not erase its footer. */
class NativeTurnFooterCache {
    private var retained: NativeTurnFooter? = null

    fun project(snapshot: SessionSnapshot?, synchronizing: Boolean, nowSeconds: Long): NativeTurnFooter? {
        val previous = retained
        fun pending(): NativeTurnFooter? {
            val frozen = previous?.copy(synchronizing = true,
                observedAt = if (previous.synchronizing) previous.observedAt else nowSeconds)
            retained = frozen
            return frozen
        }
        if (snapshot == null) return pending()
        val uncertain = synchronizing || !snapshot.nativeReady || snapshot.recoveryRequired || snapshot.workspaceUnavailable
        if (uncertain && previous != null) return pending()
        val candidate = snapshot.turnSettings.firstOrNull { it.turnId == snapshot.activeTurnId }
            ?: snapshot.turnSettings.lastOrNull()
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
        return NativeTurnFooter(turn, label, uncertain, nowSeconds).also { retained = it }
    }
}
