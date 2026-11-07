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
}
