package dev.rivo.relay

import dev.rivo.relay.codex.*
import dev.rivo.relay.data.sessionSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionControlTest {
    private fun session(state: String, writable: Boolean = false) = SessionState(id = "thread", snapshot = sessionSnapshot(
        JSONObject().put("thread", JSONObject().put("id", "thread")).put("readOnly", !writable)
            .put("control", JSONObject().put("state", state).put("canOperate", writable).put("action", "resume"))))

    @Test fun continueRequiresFreshIdleInterruptedSnapshot() {
        val base = session("writable", true)
        val ready = base.copy(snapshot = base.snapshot!!.copy(status = "idle", resumeTurnId = "old"))
        assertTrue(ready.canContinueTask())
        assertFalse(base.canContinueTask())
        assertFalse(ready.copy(synchronization = SessionSynchronization.pending()).canContinueTask())
        assertFalse(ready.copy(snapshot = ready.snapshot!!.copy(activeTurnId = "new")).canContinueTask())
        assertFalse(ready.copy(snapshot = ready.snapshot!!.copy(status = "running")).canContinueTask())
        assertFalse(ready.copy(id = "another").canContinueTask())
    }

    @Test fun unavailableStatesNeverEnableInput() {
        for (state in listOf("external", "released", "release_pending", "workspace_unavailable", "future_state"))
            assertFalse(session(state).control().canOperate)
        assertEquals(ControlState.RELEASED, session("released").control().state)
        assertEquals("resume", session("released").control().action)
    }
    @Test fun staleAndLoadingOverrideWritableSnapshot() {
        val ready = session("writable", true)
        assertTrue(ready.control().canOperate)
        assertFalse(ready.copy(synchronization = SessionSynchronization.pending()).control().canOperate)
        assertFalse(ready.copy(synchronization = SessionSynchronization.opening()).control().canOperate)
        assertNull(ready.copy(synchronization = SessionSynchronization.pending()).control().action)
        assertFalse(SessionState().control().canOperate)
    }
    @Test fun legacyReleasedIsNotIdleOrWritable() {
        val snapshot = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "thread"))
            .put("readOnly", true).put("writerOwner", "none"))
        assertEquals(ControlState.RELEASED, SessionState(snapshot = snapshot).control().state)
        assertFalse(SessionState(snapshot = snapshot).control().canOperate)
    }
    @Test fun staleHistoryCanPreviewTakeoverButCannotEnableInput() {
        val stale = session("external").copy(synchronization = SessionSynchronization.pending())
        assertFalse(stale.control().canOperate)
        assertTrue(stale.takeoverControl().enabled)
        assertFalse(stale.takeoverControl("网络不可用").enabled)
        assertEquals("网络不可用", stale.takeoverControl("网络不可用").reason)
    }
    @Test fun missingHostCacheAllowsFreshVerificationAndExplicitLimitsExplainWhy() {
        val base = session("external")
        val pending = base.copy(snapshot = base.snapshot!!.copy(nativeRuntime = JSONObject()
            .put("handoffAvailable", false).put("handoffCheckAvailable", true)))
        assertTrue(pending.takeoverControl().enabled)
        val unavailable = base.copy(snapshot = base.snapshot!!.copy(nativeRuntime = JSONObject()
            .put("handoffAvailable", false).put("handoffUnavailableReason", "实际主机不可操作")))
        assertFalse(unavailable.takeoverControl().enabled)
        assertEquals("实际主机不可操作", unavailable.takeoverControl().reason)
        val desktop = base.copy(snapshot = base.snapshot!!.copy(nativeRuntime = JSONObject().put("desktopShared", true)))
        assertFalse(desktop.takeoverControl().enabled)
        assertTrue(desktop.takeoverControl().reason.contains("Desktop"))
    }
    @Test fun initialLoadingAndCachedHistoryDoNotSuggestTakeover() {
        assertFalse(SessionState(id = "t", synchronization = SessionSynchronization.opening()).showTakeover())
        val cached = session("external").copy(synchronization = SessionSynchronization.opening())
        assertFalse(cached.showTakeover())
        assertFalse(cached.copy(synchronization = SessionSynchronization.pending()).showTakeover())
        assertEquals(ControlState.UNKNOWN, cached.displayControl().state)
    }
    @Test fun refreshKeepsConfirmedOwnershipWithoutUnlockingMutations() {
        val writable = session("writable", true)
        val refreshing = writable.copy(synchronization = SessionSynchronization.pending(), lastConfirmedControl = writable.control())
        assertEquals(ControlState.WRITABLE, refreshing.displayControl().state)
        assertFalse(refreshing.showTakeover())
        assertFalse(refreshing.control().canOperate)
        val external = session("external")
        assertTrue(external.showTakeover())
        assertFalse(external.copy(synchronization = SessionSynchronization.pending(), lastConfirmedControl = external.control()).showTakeover())
        assertFalse(writable.copy(lastConfirmedControl = external.control()).showTakeover())
    }
    @Test fun pendingReleaseIsNotReportedAsReleased() {
        val pending = session("release_pending")
        assertEquals(ControlState.RELEASE_PENDING, pending.control().state)
        assertFalse(pending.control().canOperate)
        assertFalse(pending.showTakeover())
    }

    @Test fun unverifiedOwnershipDoesNotReuseOldWritableOrSuggestTakeover() {
        val current = session("ownership_unverified").copy(lastConfirmedControl = session("writable", true).control())
        assertEquals(ControlState.UNVERIFIED, current.control().state)
        assertEquals(ControlState.UNVERIFIED, current.displayControl().state)
        assertFalse(current.control().canOperate)
        assertNull(current.control().action)
        assertFalse(current.showTakeover())
        val legacy = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "thread"))
            .put("readOnly", true).put("writerOwner", "unknown"))
        assertEquals(ControlState.UNVERIFIED, SessionState(snapshot = legacy).control().state)
    }
    @Test fun onlyConfirmedOwnersHaveButtons() {
        for (name in listOf("released", "release_pending", "workspace_unavailable", "ownership_unverified", "future_state")) {
            assertFalse(session(name).showTakeover())
            assertFalse(session(name).showRelease())
        }
        val own = session("writable", true).let { it.copy(snapshot = it.snapshot!!.copy(
            control = JSONObject().put("state", "writable").put("canOperate", true).put("action", "release"))) }
        assertTrue(own.showRelease())
        assertFalse(own.copy(synchronization = SessionSynchronization.pending(), lastConfirmedControl = own.control()).showRelease())
        assertFalse(own.copy(synchronization = SessionSynchronization.opening()).showRelease())
    }

    @Test fun releasedCanComposeWithoutClaimingOwnership() {
        val released = session("released")
        assertTrue(released.canCompose())
        assertFalse(released.control().canOperate)
        assertFalse(released.copy(synchronization = SessionSynchronization.pending()).canCompose())
        assertFalse(released.copy(synchronization = SessionSynchronization.opening()).canCompose())
        for (name in listOf("external", "release_pending", "workspace_unavailable", "ownership_unverified"))
            assertFalse(session(name).canCompose())
    }

    @Test fun queueBookkeepingDoesNotRequireNativeWriter() {
        for (name in listOf("released", "external", "release_pending", "ownership_unverified", "workspace_unavailable")) {
            val current = session(name)
            for (operation in listOf("remove", "edit", "reorder", "dismiss")) {
                assertTrue(current.canQueueOperation(operation))
                assertFalse(current.copy(synchronization = SessionSynchronization.pending()).canQueueOperation(operation))
                assertFalse(current.copy(synchronization = SessionSynchronization.opening()).canQueueOperation(operation))
                assertFalse(current.copy(id = "other").canQueueOperation(operation))
            }
            for (operation in listOf("steer", "recover", "enqueue"))
                assertFalse(current.canQueueOperation(operation))
        }
        assertFalse(SessionState().canManageQueue())
    }

    @Test fun waitingQueueCanResumeWithoutSendingMessage() {
        val base = session("released")
        val waiting = sessionSnapshot(JSONObject()
            .put("thread", JSONObject().put("id", "thread"))
            .put("queue", org.json.JSONArray().put(JSONObject().put("id", "q").put("status", "waiting"))))
        val released = base.copy(snapshot = base.snapshot!!.copy(queue = waiting.queue))
        assertTrue(released.canResumeQueue())
        assertFalse(released.copy(synchronization = SessionSynchronization.pending()).canResumeQueue())
        assertFalse(released.copy(synchronization = SessionSynchronization.opening()).canResumeQueue())
        assertFalse(base.canResumeQueue())
        assertFalse(session("external").let { it.copy(snapshot = it.snapshot!!.copy(queue = waiting.queue)) }.canResumeQueue())
    }

}
