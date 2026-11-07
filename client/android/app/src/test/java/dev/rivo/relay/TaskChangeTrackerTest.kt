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
    @Test fun goalsNotifyOnOutcomeInsteadOfEveryAutomaticTurn() {
        val tracker = TaskChangeTracker()
        fun state(turn: String, goalStatus: String, updated: Long = 1) = snapshot("live" to turn)
            .put("goal", JSONObject().put("createdAt", 1).put("updatedAt", updated).put("status", goalStatus))
        assertTrue(tracker.observe(state("inProgress", "active")).isEmpty())
        assertTrue(tracker.observe(state("completed", "active")).isEmpty())
        assertEquals("goalStopped", tracker.observe(state("completed", "budgetLimited", 2)).single().kind)
        assertTrue(tracker.observe(state("completed", "budgetLimited", 3)).isEmpty())
        assertTrue(tracker.observe(state("inProgress", "active", 4)).isEmpty())
        assertEquals("completed", tracker.observe(state("completed", "complete", 5)).single().kind)
        assertTrue(tracker.observe(state("completed", "complete", 5)).isEmpty())
        assertTrue(TaskChangeTracker().observe(state("completed", "complete", 5)).isEmpty())
        tracker.observe(snapshot("other" to "inProgress").put("goal", state("completed", "complete", 5).getJSONObject("goal")))
        assertEquals("completed", tracker.observe(snapshot("other" to "completed").put("goal", state("completed", "complete", 5).getJSONObject("goal"))).single().kind)
    }

    @Test fun goalOutcomeAndLaterFinalTurnCompletionDoNotNotifyTwice() {
        for (terminal in listOf("complete", "blocked", "budgetLimited", "usageLimited")) {
            val tracker = TaskChangeTracker()
            fun state(turn: String, goal: String) = snapshot("last" to turn)
                .put("goal", JSONObject().put("createdAt", 1).put("status", goal))
            tracker.observe(state("inProgress", "active"))
            assertEquals(1, tracker.observe(state("inProgress", terminal)).size)
            assertTrue(tracker.observe(state("completed", terminal)).isEmpty())
            tracker.observe(snapshot("next" to "inProgress").put("goal", JSONObject().put("createdAt", 1).put("status", terminal)))
            assertEquals("completed", tracker.observe(snapshot("next" to "completed").put("goal", JSONObject().put("createdAt", 1).put("status", terminal))).single().kind)
        }
    }

    @Test fun failedGoalOwnedTurnStillReportsFailureAfterGoalStops() {
        val tracker = TaskChangeTracker()
        tracker.observe(snapshot("last" to "inProgress").put("goal", JSONObject().put("status", "active")))
        tracker.observe(snapshot("last" to "inProgress").put("goal", JSONObject().put("status", "blocked")))
        assertEquals("failed", tracker.observe(snapshot("last" to "failed").put("goal", JSONObject().put("status", "blocked"))).single().kind)
    }

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
