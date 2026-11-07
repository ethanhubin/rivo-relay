package dev.rivo.relay

import dev.rivo.relay.codex.*
import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class QiGuangStateParityTest {
    private fun api(server: MockWebServer) = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
    private fun snapshot(revision: String, cursor: String? = null, turn: String = "head", sync: String = "ready") = JSONObject("""
        {"thread":{"id":"t","name":"test","turns":[{"id":"$turn","status":"completed","items":[{"id":"item-$turn","type":"agentMessage","text":"$turn"}]}]},
        "status":"idle","settings":{},"nativeRevision":"$revision","nativeSync":{"state":"$sync"}}
    """).put("nextCursor", cursor ?: JSONObject.NULL)
    private fun response(json: JSONObject) = MockResponse().setBody(json.toString())

    @Test fun stableActiveOrderingUsesServerOwnershipAndCompletedTimestamps() {
        fun thread(id: String, time: Long, active: Boolean = false, pin: Boolean = false) =
            ThreadSummary(id, id, "", "idle", "", "p", pin, time, hasOutstandingWork = active)
        val list = listOf(thread("b", 90, true), thread("older", 1), thread("a", 10, true), thread("latest", 100), thread("pin", 0, pin = true))
        assertEquals(listOf("pin", "a", "b", "latest", "older"), stableThreadOrder(list, listOf("a", "b")).map { it.id })
        assertTrue(threadIsActive(thread("recover", 0).copy(recoveryRequired = true)))
    }

    @Test fun interruptedQueueRemainsVisibleWithoutExecutionSpinner() {
        val idle = ThreadSummary("t", "test", "", "idle", "", "p", false, 0,
            nativeExecution = "idle", deliveryState = "recovery", recoveryRequired = true, hasOutstandingWork = true)
        assertTrue(threadIsActive(idle))
        assertFalse(threadIsExecuting(idle))
        assertEquals("待恢复", threadPendingLabel(idle))
        assertTrue(threadIsExecuting(idle.copy(nativeExecution = "running", status = "running")))
        val waiting = idle.copy(status = "waiting", nativeExecution = "running")
        assertFalse(threadIsExecuting(waiting))
        assertEquals("待回答", threadPendingLabel(waiting))
        assertEquals("状态待确认", threadPendingLabel(idle.copy(status = "unknown", nativeExecution = "unknown",
            recoveryRequired = false, hasOutstandingWork = false, deliveryState = "idle")))
        val queued = idle.copy(recoveryRequired = false, deliveryState = "queued", queueDepth = 1)
        assertFalse(threadIsExecuting(queued))
        assertEquals("排队中", threadPendingLabel(queued))
        assertEquals("失败", threadPendingLabel(queued.copy(deliveryState = "failed", queueDepth = 0)))
    }

    @Test fun newRunningTurnQueuesInputWhileOldRecoveryRemains() {
        val idle = sessionSnapshot(snapshot("r").put("recoveryRequired", true))
        assertFalse(idle.shouldQueueInput)
        assertTrue(idle.copy(status = "running", activeTurnId = "new").shouldQueueInput)
        val externalWaiting = idle.copy(status = "waiting")
        assertTrue(externalWaiting.isOngoing)
        assertTrue(externalWaiting.shouldQueueInput)
        assertTrue(idle.copy(goal = JSONObject().put("status", "active")).shouldQueueInput)
        assertFalse(idle.copy(readOnly = true).shouldQueueInput)
    }

    @Test fun convergenceKeepsLastReadyMessagesAndTransientControlsNeverEnterCache() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("rivo-parity-cache").toFile()
        val store = ConversationStore(directory)
        val current = AtomicReference(snapshot("one", "old-cursor"))
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/v1/events")) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                else response(current.get())
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope, store) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(5000) { controller.state.first { !it.stale && it.snapshot?.nativeRevision == "one" } }
            current.set(snapshot("two", turn = "partial", sync = "converging").put("queue", org.json.JSONArray()
                .put(JSONObject("""{"id":"q","text":"queued","status":"unknown","recoveryRequired":true}"""))))
            controller.refresh()
            val converging = withTimeout(5000) { controller.state.first { it.snapshot?.nativeSyncState == "converging" } }
            assertFalse(converging.stale)
            assertEquals(listOf("head", "partial"), converging.snapshot!!.messages.map { it.text })
            assertEquals("one", converging.snapshot.nativeRevision)
            assertEquals("q", converging.snapshot.queue.single().id)
            val persisted = withTimeout(5000) {
                var cached = store.loadSnapshot("t")
                while (cached == null) { delay(20); cached = store.loadSnapshot("t") }
                cached
            }
            assertEquals("head", sessionSnapshot(persisted).messages.single().text)
            assertNull(store.loadSnapshot("t")!!.optString("nextCursor", null))
            current.set(snapshot("two", turn = "new-head"))
            controller.refresh()
            val ready = withTimeout(5000) { controller.state.first { !it.stale && it.snapshot?.nativeRevision == "two" } }
            assertEquals("new-head", ready.snapshot!!.messages.single().text)
        } finally { controller.close(); scope.cancel(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun autofillCanLoadMoreThanThreePagesAndStopsRepeatedCursors() = runBlocking {
        val pages = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                val cursor = request.requestUrl?.queryParameter("cursor")
                if (cursor == null) return response(snapshot("r", "1"))
                assertEquals("r", request.requestUrl?.queryParameter("nativeRevision"))
                pages.incrementAndGet()
                val number = cursor.toInt()
                return response(snapshot("r", if (number == 5) "3" else "${number + 1}", "older-$number"))
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(5000) { controller.state.first { it.historyCursor == "1" && !it.stale } }
            for (number in 1..5) {
                controller.ensureVisibleHistory()
                withTimeout(5000) { controller.state.first { !it.loadingEarlier && it.snapshot?.messages?.size == number + 1 } }
            }
            assertEquals(5, pages.get())
            assertNull(controller.state.value.historyCursor)
            controller.ensureVisibleHistory()
            delay(100)
            assertEquals(5, pages.get())
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun changedRevisionDoesNotSpliceOlderPageIntoCurrentHistory() = runBlocking {
        val requests = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                if (request.requestUrl?.queryParameter("cursor") != null) return response(snapshot("new", turn = "wrong-page"))
                return response(if (requests.incrementAndGet() == 1) snapshot("old", "page") else snapshot("new", turn = "new-head"))
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(5000) { controller.state.first { it.historyCursor == "page" } }
            controller.loadEarlier()
            val ready = withTimeout(5000) { controller.state.first { !it.stale && it.snapshot?.nativeRevision == "new" } }
            assertEquals(listOf("new-head"), ready.snapshot!!.messages.map { it.text })
            assertNull(ready.historyError)
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun streamCursorIsCapturedAndResumedWithoutBeingStoredAsAuthority() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("id: epoch:42\nevent: native\ndata: {\"method\":\"turn/completed\"}\n\n"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("id: epoch:43\nevent: reset\ndata: {}\n\n"))
        server.start()
        try {
            val api = api(server)
            val event = withTimeout(5000) { api.events("t").first { it.type != "connected" } }
            assertEquals("epoch:42", event.cursor)
            val reset = withTimeout(5000) { api.events("t", event.cursor).first { it.type != "connected" } }
            assertEquals("reset", reset.type)
            assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Last-Event-ID"))
            assertEquals("epoch:42", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Last-Event-ID"))
        } finally { server.shutdown() }
    }

    @Test fun serviceTierDraftAndCacheRemainBoundToSavedContent() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("rivo-parity-draft").toFile()
        try {
            val store = ConversationStore(directory)
            store.saveDraft("t", ConversationDraft(text = "draft", serviceTier = "priority"))
            assertEquals("priority", ConversationStore(directory).loadDraft("t").serviceTier)
            val json = snapshot("r", "cursor").put("requests", org.json.JSONArray().put(JSONObject().put("key", "approval")))
                .put("queue", org.json.JSONArray().put(JSONObject().put("id", "q")))
                .put("recoveryTask", JSONObject().put("id", "q")).put("recoveryRequired", true)
            store.saveSnapshot("t", json)
            val cached = sessionSnapshot(store.loadSnapshot("t")!!)
            assertTrue(cached.requests.isEmpty()); assertTrue(cached.queue.isEmpty())
            assertNull(cached.historyCursor); assertNull(cached.nativeRevision); assertNull(cached.recoveryTask)
            assertFalse(cached.recoveryRequired)
        } finally { directory.deleteRecursively() }
    }
    @Test fun pendingCreationAndFirstSendReconcileAcrossProcessRestartWithoutResurrectingInput() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("rivo-pending-checkpoint").toFile()
        try {
            val store = ConversationStore(directory)
            store.beginPending("project")
            store.saveDraft("pending:project", ConversationDraft("first message", model = "model", effort = "high", serviceTier = "priority"))
            store.setPendingWorktreeRef("project", "refs/heads/main")
            store.checkpointPendingSubmission("project", "create-request", "create")
            val restarted = ConversationStore(directory)
            restarted.resolvePendingSubmission("create-request", JSONObject("""{"status":"accepted","receipt":{"threadId":"native-thread"}}"""))
            assertEquals("native-thread", restarted.pendingThreadId("project"))
            assertEquals("first message", restarted.loadDraft("pending:project").text)
            assertEquals("refs/heads/main", restarted.pendingWorktreeRef("project"))
            restarted.checkpointPendingSubmission("project", "send-request", "send")
            val afterSend = ConversationStore(directory)
            afterSend.resolvePendingSubmission("send-request", JSONObject("""{"status":"accepted","receipt":{"threadId":"native-thread","turnId":"turn"}}"""))
            assertTrue(afterSend.pendingCompleted("project"))
            assertEquals("native-thread", afterSend.completedPendingThreadId("project"))
            // A delayed old writer cannot revive a first message that was already accepted.
            afterSend.saveDraft("pending:project", ConversationDraft("stale old text"))
            assertTrue(afterSend.loadDraft("pending:project").empty)
            assertNull(afterSend.pendingThreadId("project"))
            afterSend.beginPending("project")
            assertFalse(afterSend.pendingCompleted("project"))
            assertTrue(afterSend.loadDraft("pending:project").empty)
        } finally { directory.deleteRecursively() }
    }

    @Test fun optimisticBubbleUsesExactNativeClientIdentityAndNeverSimilarText() {
        val pending = PendingNativeMessage("submission", "client", "t", listOf(JSONObject().put("type", "text").put("text", "same text")))
        val different = sessionSnapshot(JSONObject("""{"thread":{"id":"t","turns":[{"id":"turn","items":[{"id":"native","type":"userMessage","clientId":"different","content":[{"type":"text","text":"same text"}]}]}]}}"""))
        assertEquals(2, projectedSessionMessages(different, listOf(pending)).size)
        assertEquals("submitting", projectedSessionMessages(different, listOf(pending)).last().raw.string("relayDelivery"))
        val matching = different.copy(messages = different.messages.map { it.copy(raw = JSONObject(it.raw.toString()).put("clientId", "client")) })
        assertEquals(listOf("native"), projectedSessionMessages(matching, listOf(pending)).map { it.id })
        assertEquals(1, different.messages.size) // Display overlays never enter the canonical snapshot.
    }

    @Test fun orderedDraftAppendSurvivesReopenAndDefaultsStayNodeLocal() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("rivo-ordered-draft").toFile()
        try {
            val store = ConversationStore(directory)
            store.enqueueDraft("t", ConversationDraft("old"))
            store.saveDraftOrdered("t", ConversationDraft("old\n\nforwarded link",
                images = (1..10).map { StoredDraftImage("$it", byteArrayOf(1, 2)) }))
            assertEquals("old\n\nforwarded link", ConversationStore(directory).loadDraft("t").text)
            assertEquals(10, ConversationStore(directory).loadDraft("t").images.size)
            store.saveDefaultSettings(JSONObject().put("model", "selected").put("effort", "high").put("serviceTier", "priority"))
            assertEquals("priority", ConversationStore(directory).defaultSettings()!!.string("serviceTier"))
            assertNull(ConversationStore(java.io.File(directory, "different-node")).defaultSettings())
        } finally { directory.deleteRecursively() }
    }

    @Test fun acceptedDraftClearsOriginalThreadAfterSwitchAndKeepsNewerDraft() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("rivo-receipt-draft").toFile()
        try {
            val store = ConversationStore(directory)
            store.saveDraftOrdered("original", ConversationDraft("sent", model = "m", serviceTier = "priority"))
            store.checkpointSentDraft("submission", "original")
            store.saveDraftOrdered("other", ConversationDraft("other chat draft"))
            assertTrue(store.markSentDraftResolved("submission", JSONObject().put("status", "accepted")))
            // An accepted checkpoint survives a restart before queued cleanup finishes.
            val restarted = ConversationStore(directory)
            assertEquals("", restarted.loadDraft("original").text)
            restarted.clearAcceptedSentDraft("submission")
            assertEquals("priority", restarted.loadDraft("original").serviceTier)
            assertEquals("other chat draft", restarted.loadDraft("other").text)
            restarted.saveDraftOrdered("original", ConversationDraft("second send"))
            restarted.checkpointSentDraft("second", "original")
            restarted.enqueueDraft("original", ConversationDraft("newer typed text"))
            restarted.markSentDraftResolved("second", JSONObject().put("status", "accepted"))
            restarted.clearAcceptedSentDraft("second")
            assertEquals("newer typed text", restarted.loadDraft("original").text)
            assertTrue(restarted.sentDraftCheckpoints().isEmpty())
            restarted.saveDraftOrdered("original", restarted.loadDraft("original"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun convergingLiveTurnAcceptsDeltasAndCanonicalHistoryTakesOverWithoutDuplicates() = runBlocking {
        val current = AtomicReference(snapshot("ready", turn = "old"))
        val server = MockWebServer()
        val event = "event: native\ndata: {\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":\"t\",\"turnId\":\"live\",\"itemId\":\"answer\",\"relayOffset\":2,\"delta\":\" there\"}}\n\n"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path!!.startsWith("/v1/events"))
                MockResponse().setHeader("Content-Type", "text/event-stream").setBodyDelay(1, TimeUnit.SECONDS)
                    .setBody(event + " ".repeat(10000)).throttleBody(300, 100, TimeUnit.MILLISECONDS)
                else response(current.get())
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(5000) { controller.state.first { it.snapshot?.nativeRevision == "ready" && !it.stale } }
            controller.beginSubmission("t", "client", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "hello")))
            val live = JSONObject("""{"thread":{"id":"t","turns":[{"id":"live","status":"inProgress","items":[{"id":"user","clientId":"client","type":"userMessage","content":[{"type":"text","text":"hello"}]},{"id":"answer","type":"agentMessage","text":"Hi"}]}]},"activeTurnId":"live","status":"running","nativeRevision":"ready","nativeSync":{"state":"converging"}}""")
            current.set(live); controller.refresh()
            val streaming = withTimeout(5000) { controller.state.first { it.snapshot?.messages?.any { message -> message.text == "Hi there" } == true } }
            assertFalse(streaming.stale)
            assertEquals("live", streaming.snapshot!!.activeTurnId)
            assertEquals(1, streaming.pendingMessages.size)
            assertEquals(1, projectedSessionMessages(streaming.snapshot, streaming.pendingMessages).count { it.raw.string("clientId") == "client" })
            assertEquals(listOf("old", "hello", "Hi there"), streaming.snapshot.messages.map { it.text })
            val terminal = JSONObject(live.toString()).put("activeTurnId", "").put("status", "idle")
                .put("nativeRevision", "done").put("nativeSync", JSONObject().put("state", "ready"))
            terminal.getJSONObject("thread").getJSONArray("turns").getJSONObject(0).put("status", "completed")
                .getJSONArray("items").getJSONObject(1).put("text", "Hi there")
            current.set(terminal); controller.refresh()
            val ready = withTimeout(5000) { controller.state.first { it.snapshot?.nativeRevision == "done" } }
            assertEquals(listOf("user", "answer"), ready.snapshot!!.messages.map { it.id })
            assertTrue(ready.snapshot.nativeReady)
            assertFalse(ready.stale)
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun frozenSubmissionBytesSurviveWithoutOverwritingANewerQueuedEdit() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("rivo-frozen-input").toFile()
        try {
            val store = ConversationStore(directory)
            store.saveDraftOrdered("t", ConversationDraft("older saved text"))
            val frozen = ConversationDraft("the submitted input")
            store.enqueueDraft("t", ConversationDraft("newer user edit"))
            store.checkpointSentDraft("request", "t", frozen)
            assertEquals("newer user edit", ConversationStore(directory).loadDraft("t").text)
            val frozenFile = directory.listFiles()!!.single { it.name.startsWith("sent-input-") }
            assertEquals("the submitted input", JSONObject(frozenFile.readText()).getString("text"))
            store.markSentDraftResolved("request", JSONObject().put("status", "accepted"))
            store.clearAcceptedSentDraft("request")
            assertEquals("newer user edit", store.loadDraft("t").text)
            assertFalse(frozenFile.exists())
        } finally { directory.deleteRecursively() }
    }

}
