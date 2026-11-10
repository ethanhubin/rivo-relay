package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeTurnFooterTest {
    private val turn = NativeTurnSettings("t", status = "inProgress", startedAt = 100)
    private val live = SessionSnapshot("s", "", "running", "t", emptyList(), emptyList(), JSONObject(), turnSettings = listOf(turn))

    @Test fun settledFooterStaysBeforeNextPendingOrFailedSendAndAfterLateTools() {
        val footer = NativeTurnFooter(turn.copy(status = "completed"), "已完成", observedAt = 120)
        val answer = NativeTimelineRow.Message(NativeMessage("answer", "t", "agentMessage", "done", JSONObject()))
        val tool = NativeTimelineRow.Activities(listOf(NativeMessage("tool", "t", "commandExecution", "", JSONObject())))
        for (delivery in listOf("submitting", "accepted", "failed")) {
            val pending = NativeTimelineRow.Message(NativeMessage("pending", "pending:new", "userMessage", "next",
                JSONObject().put("relayDelivery", delivery)))
            assertEquals(1, nativeTurnFooterBoundary(listOf(answer, pending), footer))
            assertEquals(2, nativeTurnFooterBoundary(listOf(answer, tool, pending), footer))
            assertEquals(0, nativeTurnFooterBoundary(listOf(pending), footer))
        }
        assertEquals(2, nativeTurnFooterBoundary(listOf(answer, tool), footer))
        assertEquals(0, nativeTurnFooterBoundary(emptyList(), footer))
    }

    @Test fun stoppedTurnDurationSurvivesControlStateConvergence() {
        for (status in listOf("failed", "interrupted", "canceled", "cancelled")) {
            val cache = NativeTurnFooterCache()
            val stopping = live.copy(turnSettings = listOf(turn.copy(status = status, durationMs = 12000)))
            val before = cache.project(stopping, false, 120)!!
            val after = cache.project(stopping.copy(status = "idle", activeTurnId = ""), false, 121)!!
            assertEquals(before.label, after.label)
            assertTrue(after.label!!.endsWith("耗时 12s"))
        }
    }

    @Test fun revalidationAndDisconnectRetainTheClientTimer() {
        val cache = NativeTurnFooterCache()
        val first = cache.project(live, false, 105, 1000)!!
        val syncing = cache.project(live, true, 110, 6000)!!
        assertEquals(first.turn, syncing.turn)
        assertTrue(syncing.synchronizing)
        assertEquals(1000L, syncing.clientStartedAtMillis)
        assertEquals(5L, clientTurnElapsedSeconds(syncing, 6000))
        assertEquals(syncing, cache.project(null, true, 120, 16000))
        assertEquals(15L, clientTurnElapsedSeconds(syncing, 16000))
        val restored = cache.project(live, false, 125)!!
        assertFalse(restored.synchronizing)
        assertEquals(25L, nativeTurnElapsedSeconds(restored.turn!!, restored.observedAt))
    }

    @Test fun missingActivityIdOrTimestampDoesNotRemoveKnownTiming() {
        val cache = NativeTurnFooterCache()
        cache.project(live, false, 105)
        val partial = live.copy(activeTurnId = "", status = "waiting", turnSettings = listOf(turn.copy(startedAt = null)))
        assertEquals(100L, cache.project(partial, false, 110)!!.turn!!.startedAt)
        assertNotNull(cache.project(live.copy(turnSettings = emptyList()), false, 112))
        assertTrue(cache.project(live.copy(nativeSyncState = "syncing"), false, 115)!!.synchronizing)
    }

    @Test fun noTimestampStillKeepsAVisibleFooterAndAcceptsLaterTiming() {
        val cache = NativeTurnFooterCache()
        assertNotNull(cache.project(live.copy(turnSettings = listOf(turn.copy(startedAt = null))), false, 105))
        assertEquals(100L, cache.project(live, false, 110)!!.turn!!.startedAt)
    }

    @Test fun completionIsStableDuringRefreshAndNeverRestartsFromLateRunningData() {
        val cache = NativeTurnFooterCache()
        cache.project(live, false, 105)
        val done = live.copy(status = "idle", activeTurnId = "", turnSettings = listOf(
            turn.copy(status = "completed", startedAt = null, completedAt = 123)))
        val result = cache.project(done, false, 123)!!
        assertTrue(result.label!!.startsWith("已完成 "))
        assertTrue(result.label!!.endsWith("耗时 23s"))
        assertEquals(result.label, cache.project(done, true, 125)!!.label)
        assertEquals(result.label, cache.project(live, false, 130)!!.label)
    }

    @Test fun newTurnDoesNotInheritOldTimingAndCachesAreConversationScoped() {
        val cache = NativeTurnFooterCache()
        cache.project(live, false, 105)
        val next = live.copy(activeTurnId = "next", turnSettings = listOf(turn, turn.copy(turnId = "next", startedAt = null)))
        val result = cache.project(next, false, 120)!!
        assertEquals("next", result.turn!!.turnId)
        assertNull(result.turn!!.startedAt)
        assertNull(NativeTurnFooterCache().project(null, true, 120))
    }

    @Test fun terminalEvidenceStopsCountingBeforeControlFieldsConverge() {
        val cache = NativeTurnFooterCache()
        cache.project(live, false, 105)
        val result = cache.project(live.copy(turnSettings = listOf(turn.copy(status = "completed", durationMs = 12000))), false, 120)!!
        assertEquals("本轮已结束 耗时 12s", result.label)
        val failed = cache.project(live.copy(status = "idle", activeTurnId = "", turnSettings = listOf(
            turn.copy(status = "failed", durationMs = 12000))), false, 121)!!
        assertEquals("执行失败 耗时 12s", failed.label)
    }
    @Test fun continuationUsesNewestTurnEvenWhenActivityStillPointsToInterruptedTurn() {
        val interrupted = turn.copy(status = "interrupted", completedAt = 211, durationMs = 111000)
        val resumed = NativeTurnSettings("resumed", status = "inProgress", startedAt = 500)
        for (syncing in listOf(false, true)) {
            val cache = NativeTurnFooterCache()
            cache.project(live.copy(status = "idle", activeTurnId = "", turnSettings = listOf(interrupted)), false, 211)
            val continuing = live.copy(turnSettings = listOf(interrupted, resumed))
            val running = cache.project(continuing, syncing, 505)!!
            assertEquals("resumed", running.turn!!.turnId)
            assertNull(running.label)
            assertNull(running.turn!!.durationMs)
            assertNull(running.turn!!.completedAt)
            assertEquals(5L, nativeTurnElapsedSeconds(running.turn!!, 505))
            val completed = continuing.copy(status = "idle", activeTurnId = "", turnSettings = listOf(
                interrupted, resumed.copy(status = "completed", completedAt = 521, durationMs = 21000)))
            val footer = cache.project(completed, false, 521)!!
            assertTrue(footer.label!!.startsWith("已完成 "))
            assertTrue(footer.label!!.endsWith("耗时 21s"))
            assertFalse(footer.label!!.contains("1m 51s"))
        }
    }

    @Test fun newlyCompletedContinuationReplacesOldFooterDuringSyncWithoutLiveObservation() {
        val cache = NativeTurnFooterCache()
        val interrupted = turn.copy(status = "interrupted", completedAt = 211, durationMs = 111000)
        cache.project(live.copy(status = "idle", activeTurnId = "", turnSettings = listOf(interrupted)), false, 211)
        val completed = NativeTurnSettings("resumed", status = "completed", startedAt = 500, completedAt = 521, durationMs = 21000)
        val snapshot = live.copy(status = "idle", activeTurnId = "t", turnSettings = listOf(interrupted, completed))
        val footer = cache.project(snapshot, true, 522)!!
        assertEquals("resumed", footer.turn!!.turnId)
        assertTrue(footer.label!!.endsWith("耗时 21s"))
        assertFalse(footer.label!!.contains("已中断"))
        val history = nativeHistoricalTimingRows(emptyList(), snapshot.turnSettings, footer.turn!!.turnId).values.flatten()
        assertEquals(listOf("t"), history.map { it.turnId })
        assertTrue(history.single().label.endsWith("耗时 1m 51s"))
    }

    @Test fun clientClockIgnoresServerTimeMissingFieldsAndWallClockChanges() {
        val cache = NativeTurnFooterCache()
        val initial = cache.project(live.copy(turnSettings = listOf(turn.copy(startedAt = null))), false, 500, 1000)!!
        assertEquals(0L, clientTurnElapsedSeconds(initial, 1000))
        assertEquals(60L, clientTurnElapsedSeconds(initial, 61000))
        val refreshed = cache.project(live.copy(turnSettings = listOf(turn.copy(startedAt = 1))), false, 5, 61000)!!
        assertEquals(60L, clientTurnElapsedSeconds(refreshed, 61000))
        val offline = cache.project(null, true, 999999, 121000)!!
        assertEquals(120L, clientTurnElapsedSeconds(offline, 121000))
        val next = cache.project(live.copy(activeTurnId = "next", turnSettings = listOf(turn.copy(turnId = "next"))), false, 600, 125000)!!
        assertEquals(0L, clientTurnElapsedSeconds(next, 125000))
    }

    @Test fun explicitEndStopsTheLocalTimerEvenWhileTransportIsSyncing() {
        val cache = NativeTurnFooterCache()
        cache.project(live, false, 105, 1000)
        val done = cache.project(live.copy(turnSettings = listOf(turn.copy(status = "completed", completedAt = 120, durationMs = 20000))), true, 120, 16000)!!
        assertNotNull(done.label)
        assertTrue(done.label!!.endsWith("耗时 20s"))
    }

}
