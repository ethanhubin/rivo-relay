package dev.rivo.relay.data

import org.json.JSONObject

data class CodexUsageWindow(val usedPercent: Int, val windowMinutes: Long?, val resetsAt: Long?) {
    val remainingPercent: Int get() = 100 - usedPercent
}
data class CodexUsageLimit(val id: String, val name: String, val primary: CodexUsageWindow?, val secondary: CodexUsageWindow?)
data class CodexUsage(val limits: List<CodexUsageLimit>) {
    val weeklyWindow: CodexUsageWindow? get() =
        (limits.firstOrNull { it.id == "codex" } ?: limits.firstOrNull())?.let { limit ->
            listOfNotNull(limit.primary, limit.secondary).firstOrNull { it.windowMinutes == 7L * 24 * 60 }
                ?: listOfNotNull(limit.primary, limit.secondary).maxByOrNull { it.windowMinutes ?: 0 }
        }
}

/** Read native account/rateLimits/read without manufacturing a quota when the account has none. */
fun catalogUsage(response: JSONObject): CodexUsage {
    val usage = response.optJSONObject("usage") ?: response
    val byId = usage.optJSONObject("rateLimitsByLimitId")
    val snapshots = if (byId != null && byId.length() > 0) {
        byId.keys().asSequence().mapNotNull { key -> byId.optJSONObject(key)?.let { key to it } }.toList()
    } else listOfNotNull(usage.optJSONObject("rateLimits")?.let { "codex" to it })
    return CodexUsage(snapshots.map { (key, snapshot) ->
        CodexUsageLimit(snapshot.string("limitId").ifBlank { key }, snapshot.string("limitName").ifBlank { "Codex" },
            usageWindow(snapshot.optJSONObject("primary")), usageWindow(snapshot.optJSONObject("secondary")))
    }.filter { it.primary != null || it.secondary != null })
}

private fun usageWindow(json: JSONObject?): CodexUsageWindow? {
    if (json == null || json.isNull("usedPercent")) return null
    val used = json.optDouble("usedPercent", Double.NaN)
    if (!used.isFinite()) return null
    return CodexUsageWindow(used.toInt().coerceIn(0, 100),
        json.optLong("windowDurationMins", 0).takeIf { it > 0 },
        json.optLong("resetsAt", 0).takeIf { it > 0 })
}

/** Remaining time is a pace reference, not another amount of available quota. */
fun quotaTimeRemaining(window: CodexUsageWindow, nowSeconds: Long): Float? {
    val duration = window.windowMinutes?.takeIf { it > 0 } ?: return null
    val reset = window.resetsAt ?: return null
    return ((reset - nowSeconds).toDouble() / (duration * 60.0)).coerceIn(0.0, 1.0).toFloat()
}

fun usageWindowLabel(minutes: Long?): String = when {
    minutes == null -> "额度"
    minutes == 7L * 24 * 60 -> "周额度"
    minutes % (24 * 60) == 0L -> "${minutes / (24 * 60)} 天额度"
    minutes % 60 == 0L -> "${minutes / 60} 小时额度"
    else -> "$minutes 分钟额度"
}
