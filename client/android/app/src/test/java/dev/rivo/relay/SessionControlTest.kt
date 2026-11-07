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

    @Test fun unavailableStatesNeverEnableInput() {
        for (state in listOf("external", "released", "workspace_unavailable", "future_state"))
            assertFalse(session(state).control().canOperate)
        assertEquals(ControlState.RELEASED, session("released").control().state)
        assertEquals("resume", session("released").control().action)
    }
    @Test fun staleAndLoadingOverrideWritableSnapshot() {
        val ready = session("writable", true)
        assertTrue(ready.control().canOperate)
        assertFalse(ready.copy(stale = true).control().canOperate)
        assertFalse(ready.copy(loading = true).control().canOperate)
        assertNull(ready.copy(stale = true).control().action)
        assertFalse(SessionState().control().canOperate)
    }
    @Test fun legacyReleasedIsNotIdleOrWritable() {
        val snapshot = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "thread"))
            .put("readOnly", true).put("writerOwner", "none"))
        assertEquals(ControlState.RELEASED, SessionState(snapshot = snapshot).control().state)
        assertFalse(SessionState(snapshot = snapshot).control().canOperate)
    }
    @Test fun staleHistoryCanPreviewTakeoverButCannotEnableInput() {
        val stale = session("external").copy(stale = true)
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
        assertFalse(SessionState(id = "t", loading = true).showTakeover())
        val cached = session("external").copy(stale = true, loading = true)
        assertFalse(cached.showTakeover())
        assertFalse(cached.copy(loading = false).showTakeover())
        assertEquals(ControlState.UNKNOWN, cached.displayControl().state)
    }
    @Test fun refreshKeepsConfirmedOwnershipWithoutUnlockingMutations() {
        val writable = session("writable", true)
        val refreshing = writable.copy(stale = true, lastConfirmedControl = writable.control())
        assertEquals(ControlState.WRITABLE, refreshing.displayControl().state)
        assertFalse(refreshing.showTakeover())
        assertFalse(refreshing.control().canOperate)
        val external = session("external")
        assertTrue(external.showTakeover())
        assertTrue(external.copy(stale = true, lastConfirmedControl = external.control()).showTakeover())
        assertFalse(writable.copy(lastConfirmedControl = external.control()).showTakeover())
    }
}
