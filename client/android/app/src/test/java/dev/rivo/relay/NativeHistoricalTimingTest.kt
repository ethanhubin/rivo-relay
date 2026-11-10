package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeHistoricalTimingTest {
    private fun turn(id: String) = NativeTurnSettings(id, status = "completed", startedAt = 100, completedAt = 3823, durationMs = 3723000)
    private fun row(id: String, turnId: String) = NativeTimelineRow.Message(NativeMessage(id, turnId, "agentMessage", id, JSONObject()))

    @Test fun historyShowsNativeDateAndDurationWithoutCompletionStatus() {
        val label = nativeHistoricalTimingLabel(turn("t"))!!
        assertEquals("耗时 1h 2m 3s", label)
        assertFalse(label.contains("已完成"))
        assertEquals(label, nativeHistoricalTimingLabel(turn("t").copy(status = "interrupted")))
        assertNull(nativeHistoricalTimingLabel(turn("t").copy(status = "inProgress")))
        assertNull(nativeHistoricalTimingLabel(turn("old").copy(startedAt = null, completedAt = null, durationMs = null)))
    }

    @Test fun eachCompletedTurnAppearsOnceAfterItsLastMessageExceptLiveTail() {
        val rows = listOf(row("a", "t1"), row("b", "t1"), row("c", "t2"))
        val projected = nativeHistoricalTimingRows(rows, listOf(turn("t1"), turn("t2")), "t2")
        assertEquals(setOf(2), projected.keys)
        assertEquals(listOf("t1"), projected[2]!!.map { it.turnId })
        val next = nativeHistoricalTimingRows(rows + row("d", "t3"), listOf(turn("t1"), turn("t2"), turn("t3").copy(status = "inProgress")), "t3")
        assertEquals(listOf("t1", "t2"), next.values.flatten().map { it.turnId })
    }

    @Test fun lateToolCompletionMovesTimingAfterAllTurnRecords() {
        val replies = listOf(row("answer", "t1"))
        val turns = listOf(turn("t1"))
        assertEquals(setOf(1), nativeHistoricalTimingRows(replies, turns, null).keys)
        val lateTool = NativeTimelineRow.Activities(listOf(
            NativeMessage("emulator", "t1", "commandExecution", "", JSONObject().put("exitCode", 1))))
        val withTool = replies + lateTool
        assertEquals(setOf(2), nativeHistoricalTimingRows(withTool, turns, null).keys)
        val nextRound = withTool + row("next", "t2")
        val timings = nativeHistoricalTimingRows(nextRound, turns + turn("t2"), "t2")
        assertEquals(setOf(2), timings.keys)
        assertEquals(listOf("t1"), timings.values.flatten().map { it.turnId })
    }

    @Test fun emptyTurnsKeepChronologicalPositionAndPagingRebuildsFromHistory() {
        val turns = listOf(turn("empty"), turn("t1"), turn("t2"))
        val rows = listOf(row("a", "t1"), row("b", "t2"))
        val first = nativeHistoricalTimingRows(rows, turns, "t2")
        assertEquals("empty", first[0]!!.single().turnId)
        assertEquals("t1", first[1]!!.single().turnId)
        assertEquals(first, nativeHistoricalTimingRows(rows.toList(), turns.map { it.copy() }, "t2"))
        val paged = nativeHistoricalTimingRows(listOf(row("old", "older")) + rows, listOf(turn("older")) + turns, "t2")
        assertEquals(listOf("older", "empty"), paged[1]!!.map { it.turnId })
        assertEquals("t1", paged[2]!!.single().turnId)
    }
}
