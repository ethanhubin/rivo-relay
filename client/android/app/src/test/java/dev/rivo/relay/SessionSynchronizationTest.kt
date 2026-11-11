package dev.rivo.relay

import dev.rivo.relay.codex.*
import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionSynchronizationTest {
    private fun running() = SessionState(id = "t", activityStatus = "running", snapshot = SessionSnapshot(
        "t", "running", "running", "turn", emptyList(), emptyList(), JSONObject()))

    @Test fun slowAndFailedBackgroundRefreshPreserveConfirmedPermission() {
        val initial = running()
        val refreshing = initial.transition(SessionSyncEvent.ReadStarted)
        assertFalse(refreshing.stale)
        val slow = refreshing.transition(SessionSyncEvent.ReadSlow)
        assertFalse(slow.stale)
        assertFalse(slow.offline)
        assertEquals("执行中", slow.syncStatusLabel())
        assertSame(initial.snapshot, slow.snapshot)
        assertEquals("turn", slow.snapshot!!.activeTurnId)
        assertTrue(slow.control().canOperate)
        val failed = slow.transition(SessionSyncEvent.ReadFailed("timeout"))
        assertEquals("同步失败，正在重试", failed.syncStatusLabel())
        assertFalse(failed.transition(SessionSyncEvent.DismissError).stale)
        val retried = failed.transition(SessionSyncEvent.ReadStarted)
        assertFalse(retried.stale)
        val fresh = retried.transition(SessionSyncEvent.ReadSucceeded)
        assertFalse(fresh.stale)
        assertNull(fresh.readError)
        assertNull(fresh.controlNotice)
        assertEquals("执行中", fresh.syncStatusLabel())
    }

    @Test fun slowOrFailedVerificationNeverUnlocksInput() {
        for (event in listOf(SessionSyncEvent.Open(true), SessionSyncEvent.StreamInterrupted,
            SessionSyncEvent.Revalidate("ownership changed"), SessionSyncEvent.HistoryInvalidated)) {
            val pending = running().transition(event).transition(SessionSyncEvent.ReadStarted)
            val slow = pending.transition(SessionSyncEvent.ReadSlow)
            assertTrue(slow.stale)
            assertFalse(slow.control().canOperate)
            val failed = slow.transition(SessionSyncEvent.ReadFailed("timeout"))
                .transition(SessionSyncEvent.DismissError)
            assertTrue(failed.stale)
            assertFalse(failed.control().canOperate)
            assertTrue(failed.transition(SessionSyncEvent.ReadSucceeded).control().canOperate)
        }
    }

    @Test fun fallbackStopsOnlyWhenNativeStateAndControlAreSettled() {
        val idle = SessionState(id = "t", snapshot = running().snapshot!!.copy(status = "idle", activeTurnId = ""))
        assertFalse(idle.needsReadFallback())
        assertTrue(running().needsReadFallback())
        assertTrue(idle.transition(SessionSyncEvent.StreamInterrupted).needsReadFallback())
        assertTrue(idle.transition(SessionSyncEvent.ReadFailed(null)).transition(SessionSyncEvent.DismissError).needsReadFallback())
        assertFalse(idle.transition(SessionSyncEvent.ReadSlow).needsReadFallback())
        for (state in listOf("converging", "unavailable")) {
            val pending = idle.copy(snapshot = idle.snapshot!!.copy(nativeSyncState = state, persistedHistoryReady = true))
            assertTrue(pending.snapshot!!.historyReady)
            assertTrue(pending.needsReadFallback())
        }
        for (state in listOf("ownership_unverified", "release_pending", "unknown")) {
            assertTrue(idle.copy(snapshot = idle.snapshot!!.copy(control = JSONObject().put("state", state))).needsReadFallback())
        }
        for (state in listOf("released", "external", "workspace_unavailable")) {
            assertFalse(idle.copy(snapshot = idle.snapshot!!.copy(control = JSONObject().put("state", state))).needsReadFallback())
        }
        assertTrue(idle.copy(snapshot = idle.snapshot!!.copy(nativeExecution = "unknown")).needsReadFallback())
        assertTrue(idle.copy(snapshot = idle.snapshot!!.copy(queueDepth = 1)).needsReadFallback())
        assertTrue(idle.copy(snapshot = idle.snapshot!!.copy(deliveryState = "dispatching")).needsReadFallback())
        for (status in listOf("waiting", "dispatching", "failed")) {
            val queue = listOf(queueTask(JSONObject().put("id", "q").put("status", status)))
            assertEquals(status != "failed", idle.copy(snapshot = idle.snapshot!!.copy(queue = queue)).needsReadFallback())
        }
        assertFalse(idle.copy(snapshot = idle.snapshot!!.copy(queueDepth = 1, recoveryRequired = true)).needsReadFallback())
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
