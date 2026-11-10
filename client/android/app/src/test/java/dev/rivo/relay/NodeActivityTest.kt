package dev.rivo.relay
import dev.rivo.relay.codex.*
import org.junit.Assert.*
import org.junit.Test

class NodeActivityTest {
    @Test fun staleRunningCacheDoesNotKeepCompletedConversationProcessing() {
        val snapshot = dev.rivo.relay.data.sessionSnapshot(org.json.JSONObject("""{"thread":{"id":"t","turns":[{"id":"turn","status":"completed","items":[]}]},"status":"idle","nativeExecution":"idle"}"""))
        val task = dev.rivo.relay.data.threadSummary(org.json.JSONObject("""{"id":"t","status":"running"}"""))
        val state = SessionState(id = "t", snapshot = snapshot)
        val running = NodeActivity(tasks = mapOf("t" to task), confirmed = true, known = setOf("t"))
        // Even a confirmed aggregate can lag behind a newer conversation read.
        assertFalse(state.withNodeActivity(running).executionActive)
        assertNull(state.withNodeActivity(running).syncStatusLabel())
        assertTrue(state.copy(snapshot = null).withNodeActivity(running).executionActive)
        // Aggregate idle must likewise not hide a newly started conversation turn.
        assertTrue(state.copy(snapshot = snapshot.copy(status = "running", activeTurnId = "next"))
            .withNodeActivity(running.copy(tasks = emptyMap())).executionActive)
        assertFalse(state.withNodeActivity(running.copy(confirmed = false)).executionActive)
        assertFalse(state.withNodeActivity(running.copy(unknown = setOf("t"))).executionActive)
        assertFalse(state.withNodeActivity(running.copy(tasks = emptyMap())).executionActive)
        assertTrue(state.copy(snapshot = snapshot.copy(status = "running", activeTurnId = "next"))
            .withNodeActivity(running.copy(confirmed = false)).executionActive)
    }

    @Test fun absenceMeansIdleOnlyWhenConfirmedAndKnown() {
        assertNull(NodeActivity().status("t"))
        assertEquals("idle", NodeActivity(confirmed = true, known = setOf("t")).status("t"))
        assertNull(NodeActivity(unknown = setOf("t"), confirmed = true).status("t"))
    }
}
