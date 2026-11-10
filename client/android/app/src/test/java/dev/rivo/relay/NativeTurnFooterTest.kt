package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeTurnFooterTest {
    private val turn = NativeTurnSettings("t", status = "inProgress", startedAt = 100)
    private val live = SessionSnapshot("s", "", "running", "t", emptyList(), emptyList(), JSONObject(), turnSettings = listOf(turn))

    @Test fun revalidationAndDisconnectRetainAndFreezeTheLastKnownTimer() {
        val cache = NativeTurnFooterCache()
        val first = cache.project(live, false, 105)!!
        val syncing = cache.project(live, true, 110)!!
        assertEquals(first.turn, syncing.turn)
        assertTrue(syncing.synchronizing)
        assertEquals(110L, syncing.observedAt)
        assertEquals(syncing, cache.project(null, true, 120))
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
        assertEquals("执行失败", failed.label)
    }
}
