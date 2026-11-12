package dev.rivo.relay.data

import org.json.JSONObject

data class CodexUsageWindow(val usedPercent: Int, val windowMinutes: Long?, val resetsAt: Long?) {
    val remainingPercent: Int get() = 100 - usedPercent
}
data class CodexUsageLimit(val id: String, val name: String, val primary: CodexUsageWindow?, val secondary: CodexUsageWindow?)
data class CodexResetCredit(val id: String, val status: String, val expiresAt: Long?)
data class CodexResetCredits(val availableCount: Int?, val credits: List<CodexResetCredit>?)
data class CodexUsage(val limits: List<CodexUsageLimit>, val resetCredits: CodexResetCredits? = null,
    val resetSupported: Boolean = false) {
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
    val resets = usage.optJSONObject("rateLimitResetCredits")?.let { value ->
        val count = (value.opt("availableCount") as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
        val details = value.optJSONArray("credits")?.let { rows -> (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.let { row ->
                row.string("id").takeIf { it.isNotBlank() }?.let { id ->
                    CodexResetCredit(id, row.string("status"), row.optLong("expiresAt").takeIf { it > 0 })
                }
            }
        } }
        CodexResetCredits(count, details)
    }
    return CodexUsage(snapshots.map { (key, snapshot) ->
        CodexUsageLimit(snapshot.string("limitId").ifBlank { key }, snapshot.string("limitName").ifBlank { "Codex" },
            usageWindow(snapshot.optJSONObject("primary")), usageWindow(snapshot.optJSONObject("secondary")))
    }.filter { it.primary != null || it.secondary != null }, resets, usage.optBoolean("resetSupported", false))
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

/** Reset timestamps are Unix seconds; expired data must not promise a fresh quota. */
fun quotaResetCountdown(resetsAt: Long?, nowSeconds: Long): String? {
    val remaining = (resetsAt ?: return null) - nowSeconds
    if (remaining <= 0) return "等待重置更新"
    val days = remaining / 86400
    val hours = remaining % 86400 / 3600
    return when {
        days > 0 -> "$days 天 $hours 小时后重置"
        hours > 0 -> "$hours 小时后重置"
        else -> "不足 1 小时后重置"
    }
}

const val USAGE_RESET_PATH = "/v1/usage/reset"
val usageResetOutcomes = setOf("reset", "alreadyRedeemed", "nothingToReset", "noCredit")
fun usageResetMessage(outcome: String): String = when (outcome) {
    "reset" -> "额度重置成功"
    "alreadyRedeemed" -> "此前已完成额度重置"
    "nothingToReset" -> "当前没有可重置的额度"
    "noCredit" -> "没有可用的重置次数"
    else -> "额度重置结果待核对"
}
