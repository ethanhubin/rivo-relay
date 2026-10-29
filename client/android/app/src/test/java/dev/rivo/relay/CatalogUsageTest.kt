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

    @Test fun projectFolderCreationAcceptsOneNameAndRejectsTraversal() {
        assertTrue(validProjectFolderName("我的项目"))
        assertTrue(validProjectFolderName("Relay Test"))
        listOf("", " ", ".", "..", "../escape", "folder/name", "folder\\name", "name\n", " padded ").forEach {
            assertFalse(it, validProjectFolderName(it))
        }
    }
}
