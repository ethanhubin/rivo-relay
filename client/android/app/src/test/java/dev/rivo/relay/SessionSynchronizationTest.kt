package dev.rivo.relay

import dev.rivo.relay.codex.*
import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionSynchronizationTest {
    private fun running() = SessionState(id = "t", activityStatus = "running", snapshot = SessionSnapshot(
        "t", "running", "running", "turn", emptyList(), emptyList(), JSONObject()))

    @Test fun slowRefreshKeepsExecutionAndContentButRequiresFreshPermission() {
        val initial = running()
        val refreshing = initial.transition(SessionSyncEvent.ReadStarted)
        assertFalse(refreshing.stale)
        val slow = refreshing.transition(SessionSyncEvent.ReadSlow)
        assertTrue(slow.stale)
        assertFalse(slow.offline)
        assertEquals("同步中", slow.syncStatusLabel())
        assertSame(initial.snapshot, slow.snapshot)
        assertEquals("turn", slow.snapshot!!.activeTurnId)
        assertFalse(slow.control().canOperate)
        val failed = slow.transition(SessionSyncEvent.ReadFailed("timeout"))
        assertEquals("同步失败，正在重试", failed.syncStatusLabel())
        assertTrue(failed.transition(SessionSyncEvent.DismissError).stale)
        val retried = failed.transition(SessionSyncEvent.ReadStarted)
        assertTrue(retried.stale)
        val fresh = retried.transition(SessionSyncEvent.ReadSucceeded)
        assertFalse(fresh.stale)
        assertNull(fresh.readError)
        assertNull(fresh.controlNotice)
        assertEquals("执行中", fresh.syncStatusLabel())
    }

    @Test fun offlineAndReconnectCannotReuseCachedPermission() {
        val original = running()
        val offline = original.transition(SessionSyncEvent.Open(false))
        assertEquals("离线缓存", offline.syncStatusLabel())
        assertFalse(offline.control().canOperate)
        val reopened = offline.transition(SessionSyncEvent.Open(true))
        assertFalse(reopened.offline)
        assertTrue(reopened.loading)
        assertTrue(reopened.stale)
        assertSame(original.snapshot, reopened.snapshot)
        assertTrue(reopened.transition(SessionSyncEvent.ReadStarted).stale)
        assertFalse(reopened.transition(SessionSyncEvent.ReadSucceeded).stale)
    }

    @Test fun streamInterruptionIsNotOfflineAndOnlySnapshotConfirmsPermission() {
        val state = running().transition(SessionSyncEvent.StreamInterrupted)
        assertEquals(SessionConnection.RECONNECTING, state.synchronization.connection)
        assertEquals("同步中", state.syncStatusLabel())
        assertTrue(state.stale)
        assertSame(state.snapshot, state.transition(SessionSyncEvent.ReadStarted).snapshot)
        assertTrue(state.transition(SessionSyncEvent.ReadStarted).stale)
        assertEquals(SessionConnection.ONLINE, state.transition(SessionSyncEvent.ReadSucceeded).synchronization.connection)
    }

    @Test fun historyInvalidationAndPauseNeverChangeExecutionOrGrantPermission() {
        val initial = running()
        for (event in listOf(SessionSyncEvent.HistoryInvalidated, SessionSyncEvent.Pause,
            SessionSyncEvent.Revalidate("ownership changed"))) {
            val next = initial.transition(event)
            assertSame(initial.snapshot, next.snapshot)
            assertTrue(next.stale)
            assertFalse(next.control().canOperate)
        }
    }
}
