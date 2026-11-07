package dev.rivo.relay

import dev.rivo.relay.data.sessionSnapshot
import dev.rivo.relay.ui.chat.goalStatusLabel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeGoalTest {
    @Test fun goalSurvivesSnapshotCacheAndClearsAuthoritatively() {
        val json = JSONObject("""{"thread":{"id":"t","turns":[]},"goalSupported":true,"goal":{"objective":"Finish","status":"budgetLimited","tokensUsed":3000000000,"tokenBudget":3000000000,"timeUsedSeconds":120}}""")
        val restored = sessionSnapshot(JSONObject(json.toString()))
        assertTrue(restored.goalSupported)
        assertEquals("Finish", restored.goal?.getString("objective"))
        assertEquals(3000000000L, restored.goal?.getLong("tokensUsed"))
        json.put("goal", JSONObject.NULL)
        assertNull(sessionSnapshot(json).goal)
        assertTrue(sessionSnapshot(json).goalSupported)
    }

    @Test fun oldServerDoesNotAdvertiseUnsupportedControls() {
        val snapshot = sessionSnapshot(JSONObject("""{"thread":{"id":"t","turns":[]}}"""))
        assertFalse(snapshot.goalSupported)
        assertNull(snapshot.goal)
    }

    @Test fun allNativeStopReasonsRemainDistinguishable() {
        val statuses = listOf("active", "paused", "blocked", "usageLimited", "budgetLimited", "complete")
        assertEquals(statuses.size, statuses.map(::goalStatusLabel).distinct().size)
        assertTrue(goalStatusLabel("futureStatus").contains("futureStatus"))
    }
}
