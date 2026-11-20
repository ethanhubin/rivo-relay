package dev.rivo.relay

import dev.rivo.relay.data.*
import dev.rivo.relay.ui.projects.validProjectFolderName
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogUsageTest {
    @Test fun nativeLimitsChooseCodexWeeklyWindowAndPreserveEachLimit() {
        val usage = catalogUsage(JSONObject("""{"usage":{"rateLimitsByLimitId":{
            "codex":{"limitId":"codex","primary":{"usedPercent":20,"windowDurationMins":300,"resetsAt":2000},"secondary":{"usedPercent":62,"windowDurationMins":10080,"resetsAt":9000}},
            "review":{"limitName":"Code review","primary":{"usedPercent":13,"windowDurationMins":10080}}
        }}}"""))
        assertEquals(2, usage.limits.size)
        assertEquals(38, usage.weeklyWindow?.remainingPercent)
        assertEquals(10080L, usage.weeklyWindow?.windowMinutes)
        assertEquals("周额度", usageWindowLabel(10080))
        assertEquals("5 小时额度", usageWindowLabel(300))
    }

    @Test fun missingUsageDoesNotLookLikeFullQuotaAndBoundsMalformedValues() {
        assertNull(catalogUsage(JSONObject("""{"usage":{"rateLimits":null}}""")).weeklyWindow)
        assertNull(catalogUsage(JSONObject("""{"usage":{"rateLimits":{"primary":{"resetsAt":200}}}}""")).weeklyWindow)
        val window = catalogUsage(JSONObject("""{"usage":{"rateLimits":{"primary":{"usedPercent":120,"windowDurationMins":0}}}}""")).weeklyWindow!!
        assertEquals(0, window.remainingPercent)
        assertNull(window.windowMinutes)
        assertNull(quotaTimeRemaining(window, 100))
    }

    @Test fun paceUsesTimeRemainingWithinTheResetWindow() {
        val window = CodexUsageWindow(40, 60, 4600)
        assertEquals(0.5f, quotaTimeRemaining(window, 2800)!!, 0.001f)
        assertEquals(0f, quotaTimeRemaining(window, 5000)!!, 0.001f)
        assertEquals(1f, quotaTimeRemaining(window, 0)!!, 0.001f)
    }

    @Test fun resetCountdownHandlesDayHourBoundariesAndMissingOrExpiredData() {
        val now = 1000L
        assertEquals("2 天 2 小时后重置", quotaResetCountdown(now + 50 * 3600, now))
        assertEquals("1 天 0 小时后重置", quotaResetCountdown(now + 24 * 3600, now))
        assertEquals("23 小时后重置", quotaResetCountdown(now + 24 * 3600 - 1, now))
        assertEquals("不足 1 小时后重置", quotaResetCountdown(now + 3599, now))
        assertEquals("等待重置更新", quotaResetCountdown(now, now))
        assertEquals("等待重置更新", quotaResetCountdown(now - 1, now))
        assertNull(quotaResetCountdown(null, now))
    }

    @Test fun projectFolderCreationAcceptsOneNameAndRejectsTraversal() {
        assertTrue(validProjectFolderName("我的项目"))
        assertTrue(validProjectFolderName("Relay Test"))
        listOf("", " ", ".", "..", "../escape", "folder/name", "folder\\name", "name\n", " padded ").forEach {
            assertFalse(it, validProjectFolderName(it))
        }
    }

    @Test fun creditExpiryUsesNearestAvailableExpiryAndHandlesMissingData() {
        val now = 1000L
        fun usage(vararg credits: CodexResetCredit) = CodexUsage(emptyList(), CodexResetCredits(2, credits.toList()))
        val near = CodexResetCredit("near", "available", now + 26 * 3600)
        assertEquals("最近一次重置机会将在 1 天 2 小时后过期", resetCreditExpiryCountdown(usage(
            CodexResetCredit("far", "available", now + 72 * 3600), near,
            CodexResetCredit("used", "redeemed", now + 3600),
            CodexResetCredit("expired", "available", now)), now))
        assertEquals("最近一次重置机会将在不足 1 小时后过期",
            resetCreditExpiryCountdown(usage(near.copy(expiresAt = now + 3599)), now))
        assertEquals("重置次数到期时间未知", resetCreditExpiryCountdown(usage(near.copy(expiresAt = null)), now))
        assertEquals("重置次数到期时间未知", resetCreditExpiryCountdown(null, now))
        assertEquals("暂无可用重置次数", resetCreditExpiryCountdown(
            CodexUsage(emptyList(), CodexResetCredits(0, emptyList())), now))
    }
}
