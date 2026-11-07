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
}
