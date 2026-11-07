package dev.rivo.relay

import dev.rivo.relay.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConversationStoreTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun draftsSurviveReopenWithoutCrossingNodeOrThread() = runBlocking {
        val root = directory.root
        val path = ConversationStore.nodeDirectory(root, "node/device/base")
        val store = ConversationStore(path)
        val reference = JSONObject().put("type", "mention").put("name", "report.pdf").put("path", "/workspace/report.pdf")
        store.saveDraft("one", ConversationDraft("unfinished", listOf(StoredDraftImage("image", byteArrayOf(1, 2, 3))), listOf(reference), "model", "high", "plan"))
        store.putMetadata("selectedProjectId", "project")
        store.flushMetadata()
        val restored = ConversationStore(path)
        val draft = restored.loadDraft("one")
        assertEquals("unfinished", draft.text)
        assertArrayEquals(byteArrayOf(1, 2, 3), draft.images.single().bytes)
        assertEquals("/workspace/report.pdf", draft.files.single().getString("path"))
        assertEquals("plan", draft.mode)
        assertEquals("model", draft.model)
        assertEquals("project", restored.getMetadata("selectedProjectId"))
        assertTrue(restored.loadDraft("two").empty)
        assertTrue(ConversationStore(ConversationStore.nodeDirectory(root, "different-node")).loadDraft("one").empty)
        restored.saveDraft("one", ConversationDraft())
        assertTrue(ConversationStore(path).loadDraft("one").empty)
    }
    @Test fun pendingGoalSurvivesReopenAndIsRemovedFromSentSettings() = runBlocking {
        val store = ConversationStore(directory.root)
        val goal = JSONObject().put("operation", "set").put("objective", "完成任务").put("tokenBudget", 12000).toString()
        val draft = ConversationDraft(pendingGoal = goal)
        assertFalse(draft.empty)
        store.saveDraft("pending:new", draft)
        val restored = ConversationStore(directory.root).loadDraft("pending:new")
        assertEquals(goal, restored.pendingGoal)
        store.checkpointSentDraft("send-goal", "pending:new", draft)
        val settings = store.sentDraftCheckpoints().getValue("send-goal").getJSONObject("settings")
        assertFalse(settings.has("pendingGoal"))
        assertEquals(goal, ConversationStore(directory.root).loadDraft("pending:new").pendingGoal)
        store.markSentDraftResolved("send-goal", JSONObject().put("status", "accepted"))
        store.clearAcceptedSentDraft("send-goal")
        assertTrue(ConversationStore(directory.root).loadDraft("pending:new").empty)
    }
    @Test fun goalOnlyPendingDraftIsListedAndGoalDeliverySurvivesRestart() = runBlocking {
        val store = ConversationStore(directory.root)
        val id = store.createPending("project")
        val goal = JSONObject().put("operation", "set").put("objective", "Finish").toString()
        val draft = ConversationDraft(pendingGoal = goal)
        assertTrue(draft.hasContent)
        store.saveDraftOrdered("pending:$id", draft)
        store.checkpointPendingSubmission(id, "goal-delivery", "goal", goal)
        val restarted = ConversationStore(directory.root)
        assertEquals(id, restarted.listPending("project").single().id)
        restarted.resolvePendingSubmission("goal-delivery", JSONObject().put("status", "unknown"))
        assertNull(restarted.pendingAppliedGoal(id))
        restarted.resolvePendingSubmission("goal-delivery", JSONObject().put("status", "accepted"))
        assertEquals(goal, ConversationStore(directory.root).pendingAppliedGoal(id))
        assertNull(pendingGoalAction(goal, restarted.pendingAppliedGoal(id)))
        val clear = pendingGoalAction(null, restarted.pendingAppliedGoal(id))!!
        assertEquals("clear", clear.string("operation"))
        restarted.checkpointPendingSubmission(id, "goal-clear", "goal", clear.toString())
        restarted.resolvePendingSubmission("goal-clear", JSONObject().put("status", "failed"))
        assertEquals(goal, restarted.pendingAppliedGoal(id))
        restarted.checkpointPendingSubmission(id, "goal-clear-retry", "goal", clear.toString())
        restarted.resolvePendingSubmission("goal-clear-retry", JSONObject().put("status", "accepted"))
        assertNull(pendingGoalAction(null, restarted.pendingAppliedGoal(id)))
        assertEquals(goal, pendingGoalAction(goal, restarted.pendingAppliedGoal(id)).toString())
        restarted.finishPending(id)
        assertNull(restarted.pendingAppliedGoal(id))
        assertTrue(restarted.listPending("project").isEmpty())
    }
    @Test fun firstTurnReceiptRecoversOnlyGoalAndFinishesAfterGoalAcceptance() = runBlocking {
        val store = ConversationStore(directory.root)
        val id = store.createPending("project")
        val goal = JSONObject().put("operation", "set").put("objective", "Finish").toString()
        store.saveDraftOrdered("pending:$id", ConversationDraft("Task", pendingGoal = goal))
        store.checkpointPendingSubmission(id, "create", "create")
        store.resolvePendingSubmission("create", JSONObject("""{"status":"accepted","receipt":{"threadId":"native"}}"""))
        store.checkpointPendingSubmission(id, "first", "sendWithGoal", goal)
        store.resolvePendingSubmission("first", JSONObject().put("status", "unknown"))
        assertNull(store.pendingGoalContinuation(id))
        val restarted = ConversationStore(directory.root)
        restarted.resolvePendingSubmission("first", JSONObject("""{"status":"accepted","receipt":{"threadId":"native","turnId":"turn"}}"""))
        assertFalse(restarted.pendingCompleted(id))
        val resumed = ConversationStore(directory.root)
        val continuation = resumed.pendingGoalContinuation(id)!!
        assertEquals("turn", continuation.getJSONObject("receipt").getString("turnId"))
        assertEquals(goal, continuation.getString("goalAction"))
        resumed.checkpointPendingSubmission(id, "enable", "finishGoal", goal)
        resumed.resolvePendingSubmission("enable", JSONObject().put("status", "failed"))
        assertNotNull(resumed.pendingGoalContinuation(id))
        assertFalse(resumed.pendingCompleted(id))
        resumed.checkpointPendingSubmission(id, "enable-retry", "finishGoal", goal)
        resumed.resolvePendingSubmission("enable-retry", JSONObject("""{"status":"accepted","receipt":{"threadId":"native"}}"""))
        val finished = ConversationStore(directory.root)
        assertTrue(finished.pendingCompleted(id))
        assertEquals("native", finished.completedPendingThreadId(id))
        assertNull(finished.pendingGoalContinuation(id))
        assertTrue(finished.listPending("project").isEmpty())
    }
    @Test fun lateReceiptsCannotResurrectDiscardedGoalWorkAcrossStoreInstances() = runBlocking {
        for (kind in listOf("create", "goal", "sendWithGoal", "finishGoal")) {
            val store = ConversationStore(directory.newFolder(kind))
            val id = store.createPending("project")
            val goal = JSONObject().put("operation", "set").put("objective", "Finish").toString()
            store.checkpointPendingSubmission(id, "late-$kind", kind, goal)
            assertTrue(store.pendingHasUnresolvedSubmission(id))
            val stale = ConversationStore(File(directory.root, kind))
            store.finishPending(id)
            val accepted = JSONObject("""{"status":"accepted","receipt":{"threadId":"native","turnId":"turn"}}""")
            stale.resolvePendingSubmission("late-$kind", accepted)
            val restarted = ConversationStore(File(directory.root, kind))
            assertTrue(restarted.pendingCompleted(id))
            assertFalse(restarted.pendingHasUnresolvedSubmission(id))
            assertNull(restarted.pendingThreadId(id))
            assertNull(restarted.pendingGoalContinuation(id))
            assertNull(restarted.pendingAppliedGoal(id))
            assertNull(restarted.completedPendingThreadId(id))
            assertTrue(restarted.pendingSubmissionCheckpoints().isEmpty())
        }
    }

    @Test fun goalOnlyReceiptIsRecoverableWithoutSentDraftAndCompletionIsIdempotent() = runBlocking {
        val store = ConversationStore(directory.root)
        val id = store.createPending("project")
        val action = JSONObject().put("operation", "set").put("objective", "Finish").toString()
        store.checkpointPendingSubmission(id, "goal-only", "finishGoal", action)
        assertTrue(store.sentDraftCheckpoints().isEmpty())
        assertEquals(setOf("goal-only"), ConversationStore(directory.root).pendingSubmissionCheckpoints().keys)
        store.resolvePendingSubmission("goal-only", JSONObject().put("status", "unknown"))
        assertTrue(store.pendingHasUnresolvedSubmission(id))
        val accepted = JSONObject("""{"status":"accepted","receipt":{"threadId":"native"}}""")
        store.resolvePendingSubmission("goal-only", accepted)
        val restarted = ConversationStore(directory.root)
        assertEquals("native", restarted.completedPendingThreadId(id))
        assertTrue(restarted.pendingSubmissionCheckpoints().isEmpty())
        assertNull(store.resolvePendingSubmission("goal-only", accepted))
        assertEquals("native", ConversationStore(directory.root).completedPendingThreadId(id))
    }

    @Test fun snapshotCacheIsBoundedAndRejectsWrongThread() = runBlocking {
        val store = ConversationStore(directory.root)
        repeat(14) { index ->
            val turns = JSONArray((0..39).map { JSONObject().put("id", "turn-$it").put("items", JSONArray()) })
            store.saveSnapshot("thread-$index", JSONObject().put("thread", JSONObject().put("id", "thread-$index").put("turns", turns)))
        }
        assertEquals(12, directory.root.listFiles()!!.count { it.name.startsWith("snapshot-") })
        assertEquals(30, store.loadSnapshot("thread-13")!!.getJSONObject("thread").getJSONArray("turns").length())
        store.saveSnapshot("wrong", JSONObject().put("thread", JSONObject().put("id", "elsewhere")))
        assertNull(store.loadSnapshot("wrong"))
    }
    @Test fun corruptDraftIsIgnoredButRecoveryMetadataFailsClosed() = runBlocking {
        val store = ConversationStore(directory.root)
        store.saveDraft("thread", ConversationDraft("text"))
        directory.root.listFiles()!!.first { it.name.startsWith("draft-") }.writeText("{broken")
        assertTrue(store.loadDraft("thread").empty)
        File(directory.root, "metadata.json").writeText("not json")
        try { ConversationStore(directory.root); fail("Corrupt recovery receipts must not be treated as an empty store") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("手机恢复记录")) }
        assertEquals("not json", File(directory.root, "metadata.json").readText())
    }
}
