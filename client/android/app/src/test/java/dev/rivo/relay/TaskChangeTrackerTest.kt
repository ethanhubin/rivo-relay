package dev.rivo.relay

import dev.rivo.relay.codex.TaskChangeTracker
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskChangeTrackerTest {
    private fun snapshot(vararg turns: Pair<String, String>, request: String? = null) = JSONObject()
        .put("thread", JSONObject().put("id", "thread").put("turns", JSONArray(turns.map { (id, status) -> JSONObject().put("id", id).put("status", status) })))
        .put("requests", JSONArray(listOfNotNull(request?.let { JSONObject().put("key", it) })))
    @Test fun initialHistoryAndPendingRequestsNeverNotify() {
        val tracker = TaskChangeTracker()
        assertTrue(tracker.observe(snapshot("old" to "completed", request = "approval")).isEmpty())
        assertTrue(tracker.observe(snapshot("old" to "completed", request = "approval")).isEmpty())
    }
    @Test fun completionFailureAndNewRequestsNotifyOnlyOnce() {
        val tracker = TaskChangeTracker()
        tracker.observe(snapshot("old" to "completed", "live" to "inProgress"))
        assertEquals("waiting", tracker.observe(snapshot("old" to "completed", "live" to "inProgress", request = "approval")).single().kind)
        assertTrue(tracker.observe(snapshot("old" to "completed", "live" to "inProgress", request = "approval")).isEmpty())
        assertEquals("failed", tracker.observe(snapshot("old" to "completed", "live" to "failed")).single().kind)
        assertTrue(tracker.observe(snapshot("old" to "completed", "live" to "failed")).isEmpty())
        assertEquals("completed", tracker.observe(snapshot("old" to "completed", "live" to "failed", "fast" to "completed")).single().kind)
    }
    @Test fun newlyAcceptedFastTaskCanFinishBeforeFirstSnapshot() {
        val tracker = TaskChangeTracker()
        tracker.expectTurn("new")
        assertEquals("completed", tracker.observe(snapshot("old" to "completed", "new" to "completed")).single().kind)
        assertTrue(tracker.observe(snapshot("old" to "completed", "new" to "completed")).isEmpty())
    }
    @Test fun interruptedAndUnrelatedReplacementHistoryDoNotMasqueradeAsCompletion() {
        val tracker = TaskChangeTracker()
        tracker.observe(snapshot("old" to "inProgress"))
        assertTrue(tracker.observe(snapshot("old" to "interrupted")).isEmpty())
        assertTrue(tracker.observe(snapshot("unrelated" to "completed")).isEmpty())
    }
}
