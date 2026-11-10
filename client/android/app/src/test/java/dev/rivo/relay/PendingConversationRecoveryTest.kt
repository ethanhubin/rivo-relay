package dev.rivo.relay

import dev.rivo.relay.codex.SessionController
import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

class PendingConversationRecoveryTest {
    private fun input(text: String) = listOf(JSONObject().put("type", "text").put("text", text))
    private fun history(client: String = "different") = JSONObject("""{"thread":{"id":"t","turns":[
      {"id":"old","items":[{"id":"old-user","type":"userMessage","content":[{"type":"text","text":"old"}]}]},
      {"id":"turn","items":[{"id":"canonical","type":"userMessage","clientId":"$client","content":[{"type":"text","text":"same"}]},{"id":"reply","type":"agentMessage","text":"answer"}]},
      {"id":"newer","items":[{"id":"later","type":"agentMessage","text":"later answer"}]}]},"status":"idle","nativeSync":{"state":"ready"}}""")

    @Test fun orphanOverlayIsRecoveredAndReceiptIsDurableBeforeRetiringItsCheckpoint() = runBlocking {
        val directory = Files.createTempDirectory("rivo-orphan-overlay").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val store = ConversationStore(directory)
            store.savePendingMessage(PendingNativeMessage("orphan", "client", "native", input("unsent")))
            store.savePendingMessage(PendingNativeMessage("accepted", "accepted-client", "native", input("sent"), delivery = "accepted"))
            assertEquals(setOf("orphan"), ConversationStore(directory).unresolvedSubmissionIds())
            val controller = SessionController(RelayApi("http://127.0.0.1:1"), scope, store) { fail(it.message) }
            controller.resolveSubmissionDurably("orphan", JSONObject().put("status", "failed"))
            assertEquals(setOf("orphan", "accepted"), ConversationStore(directory).loadPendingMessages().map { it.submissionId }.toSet())
            assertEquals("failed", ConversationStore(directory).loadPendingMessages().first { it.submissionId == "orphan" }.delivery)
            assertTrue(store.unresolvedSubmissionIds().isEmpty())
            controller.close()
        } finally { scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun fencedMissingCreationUnlocksDraftWithoutDiscardingInput() = runBlocking {
        val directory = Files.createTempDirectory("rivo-missing-create").toFile()
        try {
            val store = ConversationStore(directory)
            val id = store.createPending("project")
            val key = "pending:$id"
            store.saveDraftOrdered(key, ConversationDraft("unsent input", model = "chosen"))
            store.checkpointPendingSubmission(id, "missing", "create")
            assertTrue(store.pendingHasUnresolvedSubmission(id))
            store.resolvePendingSubmission("missing", JSONObject("""{"status":"failed","errorCode":"submission_not_received"}"""))
            val restarted = ConversationStore(directory)
            assertFalse(restarted.pendingHasUnresolvedSubmission(id))
            assertFalse(restarted.pendingCompleted(id))
            assertNull(restarted.pendingThreadId(id))
            assertEquals("unsent input", restarted.loadDraft(key).text)
            assertEquals("chosen", restarted.loadDraft(key).model)
        } finally { directory.deleteRecursively() }
    }

    @Test fun severalPendingDraftsStayIndependentWithoutMigratingLegacyDraft() = runBlocking {
        val directory = Files.createTempDirectory("rivo-many-pending").toFile()
        try {
            val store = ConversationStore(directory)
            store.saveDraftOrdered("pending:project", ConversationDraft("legacy"))
            val first = store.createPending("project")
            val second = store.createPending("project")
            assertNotEquals(first, second)
            store.saveDraftOrdered("pending:$first", ConversationDraft("first", model = "m1", serviceTier = "priority"))
            store.saveDraftOrdered("pending:$second", ConversationDraft("second", model = "m2"))
            store.setPendingWorktreeRef(first, "refs/heads/main")
            store.checkpointPendingSubmission(first, "create-first", "create")
            val restarted = ConversationStore(directory)
            assertEquals(setOf("first", "second"), restarted.listPending("project").map { it.draft.text }.toSet())
            assertEquals("project", restarted.resolvePendingSubmission("create-first", JSONObject("""{"status":"accepted","receipt":{"threadId":"native-first"}}""")))
            assertEquals("native-first", restarted.pendingThreadId(first))
            assertNull(restarted.pendingThreadId(second))
            assertEquals("refs/heads/main", restarted.pendingWorktreeRef(first))
            restarted.finishPending(first)
            assertEquals(setOf("second"), restarted.listPending("project").map { it.draft.text }.toSet())
            assertEquals("m2", restarted.loadDraft("pending:$second").model)
            restarted.saveDraftOrdered("pending:$first", ConversationDraft())
        } finally { directory.deleteRecursively() }
    }

    @Test fun acceptedOverlaySurvivesDraftCleanupAndRestoresUntilExactNativeIdentity() = runBlocking {
        val directory = Files.createTempDirectory("rivo-durable-overlay").toFile()
        val server = MockWebServer()
        val payload = AtomicReference(history())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path!!.startsWith("/v1/events"))
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else MockResponse().setBody(payload.get().toString())
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        var controller: SessionController? = null
        try {
            val store = ConversationStore(directory)
            store.saveDraftOrdered("t", ConversationDraft("same"))
            store.checkpointSentDraft("submission", "t")
            val original = PendingNativeMessage("submission", "client", "t", input("same") + JSONObject().put("type", "image").put("url", "data:image/png;base64,AA=="))
            store.savePendingMessage(original)
            val accepted = JSONObject("""{"status":"accepted","receipt":{"threadId":"t","turnId":"turn"}}""")
            store.resolvePendingMessage("submission", accepted)
            store.markSentDraftResolved("submission", accepted)
            store.clearAcceptedSentDraft("submission")
            assertTrue(store.sentDraftCheckpoints().isEmpty())
            val restarted = ConversationStore(directory)
            assertEquals("accepted", restarted.loadPendingMessages().single().delivery)
            assertEquals(2, restarted.loadPendingMessages().single().input.size)
            val active = SessionController(api, scope, restarted) { fail(it.message) }
            controller = active
            active.open("t")
            val visible = withTimeout(5000) { active.state.first { !it.loading && it.pendingMessages.size == 1 } }
            assertEquals(listOf("old-user", "pending:client", "canonical", "reply", "later"), projectedSessionMessages(visible.snapshot, visible.pendingMessages).map { it.id })
            assertEquals("", restarted.loadDraft("t").text)
            payload.set(history("client").put("nativeSync", JSONObject().put("state", "converging"))); active.refresh()
            val live = withTimeout(5000) { active.state.first { it.snapshot?.nativeSyncState == "converging" } }
            assertEquals(1, live.pendingMessages.size)
            assertEquals(1, projectedSessionMessages(live.snapshot, live.pendingMessages).count { it.raw.string("clientId") == "client" })
            assertEquals("client", ConversationStore(directory).loadPendingMessages().single().clientId)
            assertFalse(live.stale)
            payload.set(history("client")); active.refresh()
            withTimeout(5000) { active.state.first { it.pendingMessages.isEmpty() && it.snapshot?.messages?.any { item -> item.raw.string("clientId") == "client" } == true } }
            assertTrue(restarted.loadPendingMessages().isEmpty())
        } finally { controller?.close(); scope.cancel(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun createdThreadPromotionKeepsLatestDraftWhileFrozenFirstInputIsAccepted() = runBlocking {
        val directory = Files.createTempDirectory("rivo-pending-promotion").toFile()
        try {
            val store = ConversationStore(directory)
            val id = store.createPending("project")
            val key = "pending:$id"
            val frozen = ConversationDraft("first input A")
            store.saveDraftOrdered(key, frozen)
            store.checkpointPendingSubmission(id, "create", "create")
            store.saveDraftOrdered(key, ConversationDraft("later draft B", serviceTier = "priority"))
            store.resolvePendingSubmission("create", JSONObject("""{"status":"accepted","receipt":{"threadId":"native"}}"""))
            // Promotion may have completed its durable save before the send coroutine begins.
            store.saveDraftOrdered("native", store.captureDraft(key))
            store.checkpointPendingSubmission(id, "send", "send")
            store.checkpointSentDraft("send", "native", frozen)
            assertEquals("later draft B", ConversationStore(directory).loadDraft("native").text)
            val frozenFile = directory.listFiles()!!.single { it.name.startsWith("sent-input-") }
            assertEquals("first input A", JSONObject(frozenFile.readText()).string("text"))
            val accepted = JSONObject("""{"status":"accepted","receipt":{"threadId":"native","turnId":"turn"}}""")
            store.markSentDraftResolved("send", accepted)
            store.resolvePendingSubmission("send", accepted)
            store.clearAcceptedSentDraft("send")
            val restarted = ConversationStore(directory)
            assertEquals("later draft B", restarted.loadDraft("native").text)
            assertEquals("priority", restarted.loadDraft("native").serviceTier)
            assertTrue(restarted.pendingCompleted(id))
            store.saveDraftOrdered(key, ConversationDraft())
        } finally { directory.deleteRecursively() }
    }

    @Test fun turnPlacementAndRepeatedGuidanceAnchorsPreserveOrderBeforeNewerTurn() {
        val snapshot = sessionSnapshot(history())
        val turn = PendingNativeMessage("s0", "c0", "t", input("start"), nativeTurnId = "turn", createdAt = 1)
        val first = PendingNativeMessage("s1", "c1", "t", input("first guidance"), kind = "guidance", nativeTurnId = "turn", anchorItemId = "canonical", createdAt = 2)
        val second = first.copy(submissionId = "s2", clientId = "c2", input = input("second guidance"), createdAt = 3)
        assertEquals(listOf("old-user", "pending:c0", "canonical", "pending:c1", "pending:c2", "reply", "later"),
            projectedSessionMessages(snapshot, listOf(second, first, turn)).map { it.id })
        val initial = first.copy(kind = "turn", nativeTurnId = null, anchorItemId = null, startsThread = true)
        assertEquals(listOf("pending:c1", "pending:c2", "old-user"), projectedSessionMessages(snapshot,
            listOf(initial, initial.copy(clientId = "c2", createdAt = 3))).take(3).map { it.id })
    }

    @Test fun acceptedOverlayOutsideLoadedPageWaitsForItsTurnWithoutMovingToLatestReply() = runBlocking {
        val directory = Files.createTempDirectory("rivo-older-overlay").toFile()
        try {
            val old = PendingNativeMessage("old-submission", "old-client", "t", input("older request"),
                delivery = "accepted", nativeTurnId = "not-loaded", anchorItemId = "older-anchor", createdAt = 1)
            val store = ConversationStore(directory)
            store.savePendingMessage(old)
            val snapshot = sessionSnapshot(history())
            assertEquals(snapshot.messages.map { it.id }, projectedSessionMessages(snapshot, listOf(old)).map { it.id })
            assertEquals("old-client", ConversationStore(directory).loadPendingMessages().single().clientId)
            // A newly accepted send still displays after its last visible canonical item.
            val fresh = old.copy(clientId = "new-client", anchorItemId = "later")
            assertEquals("pending:new-client", projectedSessionMessages(snapshot, listOf(fresh)).last().id)
            assertEquals("pending:old-client", projectedSessionMessages(null, listOf(old)).single().id)
        } finally { directory.deleteRecursively() }
    }

    @Test fun initialCreationOverlayPromotesByStableClientIdAndNeverSuppliesExecutionState() = runBlocking {
        val directory = Files.createTempDirectory("rivo-create-overlay").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(RelayApi("http://127.0.0.1:1"), scope, ConversationStore(directory)) { fail(it.message) }
        try {
            val items = JSONArray(input("first"))
            controller.beginSubmission("pending:local", "create", items, "create", "send-id")
            assertEquals("send-id", controller.pendingMessagesFor("pending:local").single().clientId)
            assertNull(controller.state.value.snapshot)
            controller.persistSubmission("create")
            controller.resolveSubmission("create", JSONObject().put("status", "accepted"))
            controller.beginSubmission("native", "send-id", items)
            controller.persistSubmission("send-id")
            assertTrue(controller.pendingMessagesFor("pending:local").isEmpty())
            assertEquals("send-id", ConversationStore(directory).loadPendingMessages().single().clientId)
            assertNull(controller.state.value.snapshot)
            controller.resolveSubmission("send-id", JSONObject().put("status", "failed"))
            assertEquals("failed", ConversationStore(directory).loadPendingMessages().single().delivery)
        } finally { scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun reviewDiagnosticsMergeOnlyByTurnIdAndLatestValueWins() {
        val before = JSONObject().put("turnId", "t1").put("status", "unavailable")
        val after = JSONObject().put("turnId", "t1").put("status", "changed")
        val other = JSONObject().put("turnId", "t2").put("status", "verified")
        val merged = mergeTurnDiagnostics(listOf(before, other), listOf(after, JSONObject()))
        assertEquals(listOf("t1", "t2"), merged.map { it.string("turnId") })
        assertEquals("changed", merged.first().string("status"))
    }
    @Test fun createReceiptWithoutThreadCannotRetireDurableCheckpoint() = runBlocking {
        val directory = Files.createTempDirectory("rivo-incomplete-create").toFile()
        try {
            val store = ConversationStore(directory)
            val pending = store.createPending("project")
            store.saveDraftOrdered("pending:$pending", ConversationDraft("keep this draft"))
            store.checkpointPendingSubmission(pending, "create-id", "create")
            assertThrows(IllegalArgumentException::class.java) {
                store.resolvePendingSubmission("create-id", JSONObject("""{"status":"accepted","receipt":{"projectId":"project"}}"""))
            }
            val restarted = ConversationStore(directory)
            assertTrue(restarted.pendingHasUnresolvedSubmission(pending))
            assertNull(restarted.pendingThreadId(pending))
            assertEquals("keep this draft", restarted.loadDraft("pending:$pending").text)
        } finally { directory.deleteRecursively() }
    }

    @Test fun wrongThreadReceiptCannotClearAnOrphanedSentDraft() = runBlocking {
        val directory = Files.createTempDirectory("rivo-wrong-receipt").toFile()
        try {
            val store = ConversationStore(directory)
            store.saveDraftOrdered("original", ConversationDraft("preserve input"))
            store.checkpointSentDraft("send", "original")
            assertThrows(IllegalArgumentException::class.java) {
                store.markSentDraftResolved("send", JSONObject("""{"status":"accepted","receipt":{"threadId":"other"}}"""))
            }
            val restarted = ConversationStore(directory)
            assertEquals("pending", restarted.sentDraftCheckpoints().getValue("send").string("status"))
            assertEquals("preserve input", restarted.loadDraft("original").text)
        } finally { directory.deleteRecursively() }
    }

    @Test fun orphanedFirstSendRequiresMatchingThreadAndTurnBeforeCompletingDraft() = runBlocking {
        val directory = Files.createTempDirectory("rivo-orphan-first-send").toFile()
        try {
            val store = ConversationStore(directory)
            val pending = store.createPending("project")
            store.saveDraftOrdered("pending:$pending", ConversationDraft("first input"))
            store.checkpointPendingSubmission(pending, "create", "create")
            store.resolvePendingSubmission("create", JSONObject("""{"status":"accepted","receipt":{"threadId":"native"}}"""))
            store.checkpointPendingSubmission(pending, "send", "send")
            for (receipt in listOf("""{"threadId":"native"}""", """{"threadId":"wrong","turnId":"turn"}""")) {
                assertThrows(IllegalArgumentException::class.java) {
                    store.resolvePendingSubmission("send", JSONObject().put("status", "accepted").put("receipt", JSONObject(receipt)))
                }
                val restarted = ConversationStore(directory)
                assertTrue(restarted.pendingHasUnresolvedSubmission(pending))
                assertFalse(restarted.pendingCompleted(pending))
                assertEquals("first input", restarted.loadDraft("pending:$pending").text)
            }
            store.resolvePendingSubmission("send", JSONObject("""{"status":"accepted","receipt":{"threadId":"native","turnId":"turn"}}"""))
            assertTrue(store.pendingCompleted(pending))
            assertFalse(store.pendingHasUnresolvedSubmission(pending))
        } finally { directory.deleteRecursively() }
    }

}
