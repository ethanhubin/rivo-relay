package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMessageTimingTest {
    private val message = NativeMessage("a", "t", "agentMessage", "answer", JSONObject())
    private val turn = NativeTurnSettings("t", status = "completed", startedAt = 100, completedAt = 116, durationMs = 16500)

    @Test fun finalReplyUsesNativeTurnTiming() {
        assertEquals(NativeMessageTiming(116, 16), nativeMessageTiming(message, turn, true))
        assertEquals("16 秒", formatNativeThinkingDuration(16))
        assertEquals("1 分1 秒", formatNativeThinkingDuration(61))
        assertEquals("不足 1 秒", formatNativeThinkingDuration(0))
    }

    @Test fun intermediateAndLiveRepliesDoNotBorrowCompletionTime() {
        assertEquals(NativeMessageTiming(null, null), nativeMessageTiming(message, turn, false))
        assertEquals(NativeMessageTiming(null, null), nativeMessageTiming(message, turn.copy(status = "inProgress"), true))
        assertEquals(NativeMessageTiming(null, null), nativeMessageTiming(message, null, true))
    }

    @Test fun itemTimestampTakesPrecedenceAndMissingDurationUsesBounds() {
        val dated = message.copy(raw = JSONObject().put("timestamp", "1970-01-01T00:01:45Z"))
        assertEquals(NativeMessageTiming(105, 5), nativeMessageTiming(dated, turn, false))
        assertEquals(NativeMessageTiming(116, 16), nativeMessageTiming(message, turn.copy(durationMs = null), true))
        assertNull(nativeMessageTiming(dated, turn.copy(startedAt = 200), false).elapsedSeconds)
    }

    @Test fun snapshotRetainsNativeTimingWithoutInventingMissingValues() {
        val snapshot = sessionSnapshot(JSONObject("""{"thread":{"id":"s","turns":[
            {"id":"t","status":"completed","startedAt":100,"completedAt":116,"durationMs":16500,"items":[]},
            {"id":"old","status":"completed","items":[]}]}}"""))
        assertEquals(turn, snapshot.turnSettings.first())
        assertNull(snapshot.turnSettings.last().startedAt)
        assertNull(snapshot.turnSettings.last().completedAt)
        assertNull(snapshot.turnSettings.last().durationMs)
        assertNull(nativeTimestamp(JSONObject.NULL))
        assertNull(nativeTimestamp("invalid"))
    }
    @Test fun wholeTurnUsesDurationBeforeBoundsEvenWithoutCompletionTimestamp() {
        assertEquals(23L, nativeTurnElapsedSeconds(turn.copy(durationMs = 23999)))
        assertEquals(23L, nativeTurnElapsedSeconds(turn.copy(durationMs = 23999, completedAt = null)))
        assertEquals(16L, nativeTurnElapsedSeconds(turn.copy(durationMs = null)))
        assertEquals(16L, nativeTurnElapsedSeconds(turn.copy(durationMs = -1)))
        assertEquals(0L, nativeTurnElapsedSeconds(turn.copy(durationMs = 0)))
    }

    @Test fun stoppedTurnsFreezeAndNeverBorrowCurrentTime() {
        for (status in listOf("completed", "failed", "interrupted", "canceled", "cancelled")) {
            assertEquals(16L, nativeTurnElapsedSeconds(turn.copy(status = status), 999))
            assertNull(nativeTurnElapsedSeconds(turn.copy(status = status, durationMs = null, completedAt = null), 999))
        }
        assertNull(nativeTurnElapsedSeconds(turn.copy(durationMs = null, startedAt = 200)))
        assertNull(nativeTurnElapsedSeconds(turn.copy(durationMs = null, startedAt = null)))
    }

    @Test fun liveTimingRequiresExplicitRunningStateAndReliableStart() {
        val live = turn.copy(status = "inProgress", completedAt = null, durationMs = null)
        assertEquals(5L, nativeTurnElapsedSeconds(live, 105))
        assertEquals(6L, nativeTurnElapsedSeconds(live, 106))
        assertNull(nativeTurnElapsedSeconds(live))
        assertNull(nativeTurnElapsedSeconds(live, 99))
        assertNull(nativeTurnElapsedSeconds(live.copy(startedAt = null), 106))
        assertNull(nativeTurnElapsedSeconds(live.copy(status = "unknown"), 106))
        assertNull(nativeTurnElapsedSeconds(live.copy(completedAt = 104), 106))
    }

    @Test fun completionFooterTracksLatestStoppedTurn() {
        val snapshot = SessionSnapshot("s", "", "idle", "", emptyList(), emptyList(), JSONObject(), turnSettings = listOf(turn))
        assertTrue(sessionCompletionLabel(snapshot)!!.matches(Regex("已完成 [0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}")))
        for ((status, label) in mapOf("failed" to "执行失败", "interrupted" to "已中断", "canceled" to "已取消", "cancelled" to "已取消"))
            assertEquals(label, sessionCompletionLabel(snapshot.copy(turnSettings = listOf(turn.copy(status = status)))))
        assertNull(sessionCompletionLabel(snapshot.copy(activeTurnId = "next")))
        assertNull(sessionCompletionLabel(snapshot.copy(status = "running")))
        assertNull(sessionCompletionLabel(snapshot.copy(queueDepth = 1)))
        assertNull(sessionCompletionLabel(snapshot.copy(turnSettings = listOf(turn, turn.copy(status = "inProgress")))))
        assertNull(sessionCompletionLabel(snapshot.copy(turnSettings = emptyList())))
    }
    @Test fun goalFooterUsesExplicitStateAndDisappearsOnResume() {
        val snapshot = SessionSnapshot("s", "", "idle", "", emptyList(), emptyList(), JSONObject(), turnSettings = listOf(turn))
        for ((status, label) in mapOf("paused" to "已暂停", "blocked" to "受阻", "usageLimited" to "额度受限", "budgetLimited" to "预算已用尽")) {
            val stopped = snapshot.copy(goal = JSONObject().put("status", status).put("updatedAt", 120))
            assertEquals(label, sessionCompletionLabel(stopped))
            assertNull(sessionCompletionLabel(stopped.copy(activeTurnId = "next")))
            assertTrue(sessionCompletionLabel(stopped.copy(turnSettings = listOf(turn.copy(startedAt = 130, completedAt = 140))))!!.startsWith("已完成 "))
            assertEquals(label, sessionCompletionLabel(stopped.copy(turnSettings = emptyList())))
        }
        assertNull(sessionCompletionLabel(snapshot.copy(goal = JSONObject().put("status", "active"))))
        assertTrue(sessionCompletionLabel(snapshot.copy(goal = JSONObject().put("status", "complete").put("updatedAt", 120)))!!.startsWith("已完成 "))
    }
    @Test fun uncertainOrConflictingStateNeverKeepsTerminalFooter() {
        val snapshot = SessionSnapshot("s", "", "idle", "", emptyList(), emptyList(), JSONObject(), turnSettings = listOf(turn))
        assertNull(sessionCompletionLabel(snapshot.copy(nativeExecution = "running")))
        assertNull(sessionCompletionLabel(snapshot.copy(nativeSyncState = "syncing")))
        assertNull(sessionCompletionLabel(snapshot.copy(recoveryRequired = true)))
        assertNull(sessionCompletionLabel(snapshot.copy(workspaceUnavailable = true)))
        assertNull(sessionCompletionLabel(snapshot.copy(messages = listOf(message.copy(turnId = "next")))))
        assertNull(sessionCompletionLabel(snapshot.copy(turnSettings = listOf(turn.copy(status = "unknown")))))
        assertNull(sessionCompletionLabel(snapshot.copy(goal = JSONObject().put("status", "blocked"))))
        val oldGoal = JSONObject().put("status", "blocked").put("updatedAt", 110)
        assertTrue(sessionCompletionLabel(snapshot.copy(goal = oldGoal))!!.startsWith("已完成 "))
        assertEquals("已完成", sessionCompletionLabel(snapshot.copy(turnSettings = listOf(turn.copy(completedAt = Long.MAX_VALUE)))))
    }
}
