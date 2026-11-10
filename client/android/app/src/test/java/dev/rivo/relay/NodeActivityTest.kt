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
        assertTrue(state.withNodeActivity(running).executionActive)
        assertFalse(state.withNodeActivity(running.copy(confirmed = false)).executionActive)
        assertFalse(state.withNodeActivity(running.copy(unknown = setOf("t"))).executionActive)
        assertFalse(state.withNodeActivity(running.copy(tasks = emptyMap())).executionActive)
        assertTrue(state.copy(snapshot = snapshot.copy(status = "running", activeTurnId = "next"))
            .withNodeActivity(running.copy(confirmed = false)).executionActive)
    }

    @Test fun staleActivityCannotRestartCompletedListOrProjectSpinner() {
        val completed = dev.rivo.relay.data.threadSummary(org.json.JSONObject("""{"id":"t","projectId":"p","status":"idle","nativeExecution":"idle"}"""))
        val running = completed.copy(status = "running", nativeExecution = "running")
        val project = dev.rivo.relay.ui.projects.ProjectSummary("p", "P", "/p")
        val catalog = CatalogState(threads = listOf(completed), projects = listOf(project), selectedProject = project)
        val activity = NodeActivity(tasks = mapOf("t" to running), confirmed = true, known = setOf("t"))
        for (stale in listOf(activity.copy(confirmed = false), activity.copy(unknown = setOf("t")), activity.copy(known = emptySet()))) {
            val result = catalog.withNodeActivity(stale)
            assertEquals(completed, result.threads.single())
            assertFalse(result.projects.single().isRunning)
            assertFalse(result.selectedProject!!.isRunning)
        }
        val next = catalog.withNodeActivity(activity)
        assertTrue(threadIsExecuting(next.threads.single()))
        assertTrue(next.projects.single().isRunning)
        val finished = next.withNodeActivity(activity.copy(tasks = emptyMap()))
        assertFalse(threadIsExecuting(finished.threads.single()))
        assertFalse(finished.projects.single().isRunning)
        assertEquals(completed, catalog.copy(archived = true).withNodeActivity(activity).threads.single())
    }

    @Test fun absenceMeansIdleOnlyWhenConfirmedAndKnown() {
        assertNull(NodeActivity().status("t"))
        assertEquals("idle", NodeActivity(confirmed = true, known = setOf("t")).status("t"))
        assertNull(NodeActivity(unknown = setOf("t"), confirmed = true).status("t"))
    }
}
