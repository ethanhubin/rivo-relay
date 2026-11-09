package dev.rivo.relay

import dev.rivo.relay.codex.TaskChangeTracker
import dev.rivo.relay.codex.mergePinnedObservations
import dev.rivo.relay.codex.TaskNotice
import dev.rivo.relay.codex.fillObservedTurns
import dev.rivo.relay.data.ApiException
import kotlinx.coroutines.runBlocking
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

    @Test fun terminalEvidenceResolvesExpectedTurnOutsideHeadOnlyAfterFreshRead() {
        val tracker = TaskChangeTracker()
        tracker.expectTurn("a")
        tracker.observe(snapshot("a" to "inProgress"))
        val newer = snapshot("b" to "completed", "c" to "completed")
        assertTrue(tracker.observe(newer).isEmpty()) // Disappearance alone is never completion.
        assertTrue(tracker.hasUnresolvedTurns)
        assertTrue(tracker.observeTurnEvent("turn/completed", JSONObject().put("id", "a").put("status", "completed"), 20))
        assertTrue(tracker.observe(newer).isEmpty()) // Visible controller may publish an older in-flight read.
        assertTrue(tracker.observe(newer, freshAfterNanos = 10).isEmpty())
        assertTrue(tracker.hasPendingTerminals)
        assertEquals(TaskNotice("turn:a:completed", "completed"), tracker.observe(newer, freshAfterNanos = 30).single())
        assertFalse(tracker.hasPendingTerminals)
        assertFalse(tracker.hasUnresolvedTurns)
        assertFalse(tracker.observeTurnEvent("turn/completed", JSONObject().put("id", "a").put("status", "completed"), 40))
        assertTrue(tracker.observe(newer, freshAfterNanos = 50).isEmpty())
    }

    @Test fun observedStartAndTerminalPreserveGoalOwnershipAndFailureSemantics() {
        for (terminal in listOf("completed", "failed", "interrupted")) {
            val tracker = TaskChangeTracker()
            val active = JSONObject().put("createdAt", 1).put("status", "active")
            tracker.observe(snapshot("baseline" to "completed").put("goal", active))
            assertTrue(tracker.observeTurnEvent("turn/started", JSONObject().put("id", "a").put("status", "inProgress"), 10))
            assertTrue(tracker.observeTurnEvent("turn/completed", JSONObject().put("id", "a").put("status", terminal), 20))
            val notices = tracker.observe(snapshot("b" to "inProgress").put("goal", active), freshAfterNanos = 30)
            if (terminal == "failed") assertEquals("failed", notices.single().kind) else assertTrue(notices.isEmpty())
            val finished = active.put("status", "complete")
            assertEquals("completed", tracker.observe(snapshot("b" to "completed").put("goal", finished)).single().kind)
        }
        assertFalse(TaskChangeTracker().observeTurnEvent("turn/completed", JSONObject().put("id", "historical").put("status", "completed"), 10))
    }

    @Test fun partialPinnedDiscoveryAddsWithoutRemovingUntilFullRead() {
        val partial = mergePinnedObservations(setOf("existing"), setOf("new"), complete = false)
        assertEquals(setOf("existing", "new"), partial)
        assertEquals(partial, mergePinnedObservations(partial, emptySet(), complete = false))
        assertEquals(setOf("new"), mergePinnedObservations(partial, setOf("new"), complete = true))
        assertTrue(mergePinnedObservations(partial, emptySet(), complete = true).isEmpty())
    }

    @Test fun limitedHistoryCheckUsesRealTerminalEvidenceAndKeepsFreshHeadMetadata() = runBlocking {
        val tracker = TaskChangeTracker().also { it.expectTurn("a") }
        val head = snapshot("b" to "completed", "c" to "completed")
            .put("nativeRevision", "r1").put("nextCursor", "older")
            .put("goal", JSONObject().put("status", "paused")).put("recoveryRequired", true)
        assertTrue(tracker.observe(head).isEmpty())
        assertTrue(tracker.missingFromHead(head))
        var reads = 0
        val filled = fillObservedTurns(head, tracker.unresolvedTurns) { cursor, revision ->
            reads++
            assertEquals("older", cursor); assertEquals("r1", revision)
            JSONObject().put("nativeRevision", "r1").put("turns", JSONArray().put(JSONObject().put("id", "a").put("status", "completed")))
                .put("nextCursor", JSONObject.NULL).put("goal", JSONObject().put("status", "active"))
        }
        assertEquals(1, reads)
        assertEquals("paused", filled.getJSONObject("goal").getString("status"))
        assertTrue(filled.getBoolean("recoveryRequired"))
        assertEquals(2, head.getJSONObject("thread").getJSONArray("turns").length())
        assertEquals("completed", tracker.observe(filled).single().kind)
        assertFalse(tracker.hasUnresolvedTurns)
    }

    @Test fun missingOrChangedHistoryRemainsUncertainAndNeverScansUnbounded() = runBlocking {
        val tracker = TaskChangeTracker().also { it.expectTurn("missing") }
        val head = snapshot("head" to "completed").put("nativeRevision", "r1").put("nextCursor", "older")
        var pages = 0
        val filled = fillObservedTurns(head, tracker.unresolvedTurns) { _, _ ->
            pages++
            JSONObject().put("nativeRevision", "r1").put("nextCursor", "page-$pages")
                .put("turns", JSONArray().put(JSONObject().put("id", "old-$pages").put("status", "completed")))
        }
        assertEquals(3, pages)
        assertTrue(tracker.observe(filled).isEmpty())
        assertTrue(tracker.hasUnresolvedTurns)
        val failure = runCatching { fillObservedTurns(head, tracker.unresolvedTurns) { _, _ -> JSONObject().put("nativeRevision", "changed") } }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertTrue(tracker.hasUnresolvedTurns)
        val settled = TaskChangeTracker()
        settled.observe(snapshot("old" to "completed"))
        assertFalse(settled.observeTurnEvent("turn/started", JSONObject().put("id", "old").put("status", "inProgress"), 10))
        assertFalse(settled.observeTurnEvent("turn/completed", JSONObject().put("id", "old").put("status", "completed"), 20))
    }
}
