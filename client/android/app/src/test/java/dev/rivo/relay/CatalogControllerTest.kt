package dev.rivo.relay

import dev.rivo.relay.codex.CatalogController
import dev.rivo.relay.codex.CatalogState
import dev.rivo.relay.codex.pinnedThreadsToObserve
import dev.rivo.relay.data.ConversationStore
import dev.rivo.relay.data.ThreadSummary
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import dev.rivo.relay.data.RelayApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import dev.rivo.relay.ui.projects.ProjectSummary
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CatalogControllerTest {
    @Test fun desktopCreationAppearsWhileFullScanIsBlockedWithEventsOrMetadataPolling() = runBlocking {
        for (withEvents in listOf(true, false)) {
            val server = MockWebServer()
            val fullStarted = CountDownLatch(1)
            val releaseFull = CountDownLatch(1)
            val summaries = AtomicInteger()
            val fullReads = AtomicInteger()
            val eventReads = AtomicInteger()
            val initial = """{"threads":[{"id":"old","name":"Old","status":"unknown"}],"catalogComplete":false}"""
            val added = """{"threads":[{"id":"old","name":"Old","status":"unknown"},{"id":"desktop-new","name":"Desktop new","status":"unknown","pinned":true}],"catalogComplete":false}"""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl?.encodedPath == "/v1/activity")
                        return MockResponse().setBody("""{"complete":true,"generation":"test","tasks":[],"knownThreadIds":[],"unknownThreadIds":[]}""")
                    if (request.requestUrl?.encodedPath == "/v1/events") {
                        eventReads.incrementAndGet(); fullStarted.await(5, TimeUnit.SECONDS)
                        return MockResponse().setHeader("Content-Type", "text/event-stream")
                            .setBody("event: native\ndata: {\"method\":\"thread/started\",\"params\":{\"threadId\":\"desktop-new\"}}\n\n" + ":".repeat(4096))
                    }
                    if (request.requestUrl?.queryParameter("view") == "summary")
                        return MockResponse().setBody(if (summaries.incrementAndGet() == 1) initial else added)
                    if (fullReads.incrementAndGet() == 1) {
                        fullStarted.countDown(); releaseFull.await(5, TimeUnit.SECONDS)
                        return MockResponse().setBody(initial.replace("false", "true"))
                    }
                    return MockResponse().setBody(added.replace("false", "true"))
                }
            }
            server.start()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val api = api(server)
            val sync = if (withEvents) dev.rivo.relay.codex.NodeSessionSync(api, scope) else null
            val controller = CatalogController(api, scope, { throw it }, sync = sync)
            controller.project(ProjectSummary("p", "P", "/p"))
            val observer = scope.launch { controller.observeThreads(activeIntervalMillis = 10_000, idleIntervalMillis = 10_000,
                summaryIntervalMillis = if (withEvents) 10_000 else 40) }
            try {
                await(fullStarted)
                withTimeout(3000) { controller.state.first { it.threads.any { row -> row.id == "desktop-new" } } }
                assertEquals(1L, releaseFull.count)
                assertFalse(controller.state.value.threadsFresh)
                assertTrue(controller.state.value.threadsSummaryFresh)
                assertEquals(setOf("desktop-new"), pinnedThreadsToObserve(controller.state.value))
                assertEquals(if (withEvents) 1 else 0, eventReads.get())
                releaseFull.countDown()
                withTimeout(3000) { controller.state.first { it.threadsFresh && it.threadsComplete } }
                assertEquals(setOf("old", "desktop-new"), controller.state.value.threads.map { it.id }.toSet())
                assertEquals(2, fullReads.get())
            } finally { releaseFull.countDown(); observer.cancel(); scope.cancel(); server.shutdown() }
        }
    }

    @Test fun runningCompletionRefreshesWithoutAnyNativeEvents() = runBlocking {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setBody("""{"threads":[{"id":"t","status":"running","nativeExecution":"running"}]}""")) }
        server.enqueue(MockResponse().setBody("""{"threads":[{"id":"t","status":"idle","nativeExecution":"idle"}]}"""))
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, { throw it })
        controller.project(ProjectSummary("p", "P", "/p"))
        try {
            withTimeout(3000) { controller.state.first { it.threadsFresh } }
            assertEquals("running", controller.state.value.threads.single().nativeExecution)
            val observer = scope.launch { controller.observeThreads(activeIntervalMillis = 40, idleIntervalMillis = 10_000) }
            try {
                withTimeout(3000) { controller.state.first { it.threads.singleOrNull()?.nativeExecution == "idle" } }
                assertEquals(3, server.requestCount)
            } finally { observer.cancel() }
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun onlyCatalogLifecycleEventsRequestRefresh() {
        fun native(method: String) = dev.rivo.relay.data.RelayEvent("native", JSONObject().put("method", method))
        for (method in listOf("thread/started", "thread/name/updated", "thread/goal/updated", "project/created", "turn/started", "turn/completed", "relay/queueChanged", "item/tool/requestUserInput"))
            assertTrue(method, dev.rivo.relay.codex.catalogLifecycleEvent(native(method)))
        for (method in listOf("thread/tokenUsage/updated", "item/agentMessage/delta", "item/reasoning/textDelta", "thread/realtime/outputAudio/delta"))
            assertFalse(method, dev.rivo.relay.codex.catalogLifecycleEvent(native(method)))
    }

    @Test fun summaryPreservesExecutionAndLateFullCompletesOtherRowsWithoutUndoingDetails() = runBlocking {
        val server = MockWebServer()
        val fullStarted = CountDownLatch(1)
        val releaseFull = CountDownLatch(1)
        val reads = AtomicInteger()
        val initial = """{"threads":[{"id":"r","name":"Running","status":"running","nativeExecution":"running","updatedAt":10},{"id":"q","name":"Waiting","status":"waiting","nativeExecution":"running","queueDepth":2,"deliveryState":"queued","displayState":"waiting","hasOutstandingWork":true,"updatedAt":10},{"id":"other","name":"Old metadata","updatedAt":1}],"catalogComplete":true}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (reads.incrementAndGet()) {
                1 -> MockResponse().setBody(initial)
                2 -> MockResponse().setBody("""{"threads":[{"id":"r","name":"Renamed","status":"unknown","updatedAt":11},{"id":"q","name":"Queue renamed","status":"unknown","updatedAt":11},{"id":"other","name":"Recent metadata","updatedAt":20}],"catalogComplete":false}""")
                3 -> {
                    fullStarted.countDown(); releaseFull.await(10, TimeUnit.SECONDS)
                    MockResponse().setBody("""{"threads":[{"id":"r","name":"Old full","status":"running","nativeExecution":"running","updatedAt":11},{"id":"q","name":"Old queue","status":"idle","nativeExecution":"idle","updatedAt":11},{"id":"other","name":"Discovered","status":"idle","updatedAt":10}],"catalogComplete":true}""")
                }
                else -> MockResponse().setBody("""{"threads":[{"id":"r","name":"Running again","status":"running","nativeExecution":"running","updatedAt":11},{"id":"q","name":"Finished","status":"idle","nativeExecution":"idle","updatedAt":11},{"id":"other","name":"Discovered","status":"idle","updatedAt":10}],"catalogComplete":true}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, { throw it })
        try {
            controller.project(ProjectSummary("p", "P", "/p"))
            withTimeout(3000) { controller.state.first { it.threadsFresh } }
            val refresh = controller.refreshThreads()!!
            await(fullStarted)
            val partial = controller.state.value.threads.associateBy { it.id }
            assertEquals("Renamed", partial.getValue("r").name)
            assertEquals("running", partial.getValue("r").nativeExecution)
            assertEquals("waiting", partial.getValue("q").status)
            assertEquals(2, partial.getValue("q").queueDepth)
            assertEquals("queued", partial.getValue("q").deliveryState)
            val completed = dev.rivo.relay.data.SessionSnapshot("r", "Metadata older", "idle", "",
                emptyList(), emptyList(), JSONObject(), updatedAt = 9, nativeExecution = "idle")
            controller.reconcileSession(completed)
            val queued = dev.rivo.relay.data.QueueTask("queue", "pending", "waiting", 1, "", JSONObject())
            controller.reconcileSession(dev.rivo.relay.data.SessionSnapshot("q", "Queue renamed", "waiting", "",
                emptyList(), emptyList(), JSONObject(), updatedAt = 11, nativeExecution = "running", queueDepth = 2,
                queue = listOf(queued), deliveryState = "queued", displayState = "waiting"))
            assertTrue(refresh.isActive)
            assertTrue(controller.state.value.threadsLoading)
            releaseFull.countDown(); withTimeout(3000) { refresh.join() }
            val full = controller.state.value.threads.associateBy { it.id }
            assertEquals(setOf("r", "q", "other"), full.keys)
            assertEquals("Recent metadata", full.getValue("other").name)
            assertEquals(20L, full.getValue("other").updatedAt)
            assertEquals("idle", full.getValue("r").nativeExecution)
            assertEquals("Renamed", full.getValue("r").name)
            assertEquals("waiting", full.getValue("q").status)
            assertEquals(2, full.getValue("q").queueDepth)
            assertTrue(controller.state.value.threadsFresh)
            assertTrue(controller.state.value.threadsComplete)
            controller.refreshThreads()?.join()
            assertEquals("running", controller.state.value.threads.first { it.id == "r" }.nativeExecution)
            assertEquals("idle", controller.state.value.threads.first { it.id == "q" }.nativeExecution)
            assertEquals(0, controller.state.value.threads.first { it.id == "q" }.queueDepth)
        } finally { releaseFull.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun incompleteUnknownCatalogRetriesAtActiveIntervalAfterFullFailure() = runBlocking {
        val server = MockWebServer()
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (reads.incrementAndGet()) {
                1 -> MockResponse().setBody("""{"threads":[{"id":"t","name":"T","status":"unknown"}],"catalogComplete":false}""")
                2 -> MockResponse().setResponseCode(503).setBody("""{"error":{"code":"native_unavailable","message":"retry"}}""")
                else -> MockResponse().setBody("""{"threads":[{"id":"t","name":"T","status":"idle","nativeExecution":"idle"}],"catalogComplete":true}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val errors = mutableListOf<Throwable>()
        val controller = CatalogController(api(server), scope, errors::add)
        controller.project(ProjectSummary("p", "P", "/p"))
        val observer = scope.launch { controller.observeThreads(activeIntervalMillis = 40, idleIntervalMillis = 10_000) }
        try {
            withTimeout(3000) { controller.state.first { it.threadsFresh && it.threadsComplete } }
            assertEquals(3, reads.get())
            assertEquals(1, errors.size)
            assertEquals("idle", controller.state.value.threads.single().nativeExecution)
            assertFalse(dev.rivo.relay.codex.threadCatalogNeedsFastRefresh(controller.state.value))
            assertTrue(dev.rivo.relay.codex.threadCatalogNeedsFastRefresh(controller.state.value.copy(threadsFresh = false)))
            assertTrue(dev.rivo.relay.codex.threadCatalogNeedsFastRefresh(controller.state.value.copy(threadsComplete = false)))
            assertTrue(dev.rivo.relay.codex.threadCatalogNeedsFastRefresh(controller.state.value.copy(
                threads = listOf(controller.state.value.threads.single().copy(status = "unknown", nativeExecution = "unknown")))))
        } finally { observer.cancel(); scope.cancel(); server.shutdown() }
    }

    @Test fun projectSummaryDoesNotRequestStatistics() = runBlocking {
        val server = MockWebServer()
        val statsStarted = CountDownLatch(1)
        val releaseStats = CountDownLatch(1)
        val name = AtomicReference("Initial")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/v1/projects?view=summary" -> MockResponse().setBody("""{"projects":[{"id":"p","name":"${name.get()}","path":"/p","statsReady":false}]}""")
                request.path == "/v1/projects" -> {
                    statsStarted.countDown(); releaseStats.await(10, TimeUnit.SECONDS)
                    MockResponse().setBody("""{"projects":[{"id":"p","name":"Initial","path":"/p","threadCount":99}]}""")
                }
                else -> MockResponse().setBody("""{"threads":[{"id":"t","name":"T"}]}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val errors = mutableListOf<Throwable>()
        val controller = CatalogController(api(server), scope, errors::add)
        try {
            withTimeout(3000) { controller.refreshProjects().join() }
            assertEquals(1L, statsStarted.count)
            assertTrue(controller.state.value.projectsFresh)
            assertFalse(controller.state.value.projectsLoading)
            assertFalse(controller.state.value.projects.single().isRunning)
            controller.project(controller.state.value.projects.single())
            withTimeout(3000) { controller.state.first { it.threadsFresh } }
            assertEquals("t", controller.state.value.threads.single().id)
            name.set("Renamed")
            controller.refreshProjects().join()
            releaseStats.countDown()
            delay(100)
            assertEquals("Renamed", controller.state.value.projects.single().name)
            assertEquals("Renamed", controller.state.value.selectedProject?.name)
            assertFalse(controller.state.value.projects.single().isRunning)
            assertTrue(errors.isEmpty())
        } finally { releaseStats.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun partialThreadsAppearBeforeFullAndCannotOverwriteNewSelection() = runBlocking {
        val server = MockWebServer()
        val fullStarted = CountDownLatch(1)
        val releaseFull = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl?.queryParameter("projectId") == "q")
                    return MockResponse().setBody("""{"threads":[{"id":"q-thread","name":"Q"}],"catalogComplete":true}""")
                if (request.requestUrl?.queryParameter("view") == "summary")
                    return MockResponse().setBody("""{"threads":[{"id":"early","name":"Early","status":"unknown"}],"catalogComplete":false}""")
                fullStarted.countDown(); releaseFull.await(10, TimeUnit.SECONDS)
                return MockResponse().setBody("""{"threads":[{"id":"late","name":"Late"}],"catalogComplete":true}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, { throw it })
        try {
            controller.project(ProjectSummary("p", "P", "/p"))
            await(fullStarted)
            assertEquals("early", controller.state.value.threads.single().id)
            assertFalse(controller.state.value.threadsFresh)
            assertFalse(controller.state.value.threadsComplete)
            val partial = controller.state.value.copy(threadsLoading = false)
            assertFalse(dev.rivo.relay.codex.catalogCacheNoticeVisible("sessions", partial, online = true))
            assertTrue(dev.rivo.relay.codex.catalogCacheNoticeVisible("sessions", partial, online = false))
            assertTrue(pinnedThreadsToObserve(controller.state.value).isEmpty())
            controller.project(ProjectSummary("q", "Q", "/q"))
            withTimeout(3000) { controller.state.first { it.threadsFresh } }
            releaseFull.countDown(); delay(100)
            assertEquals("q-thread", controller.state.value.threads.single().id)
            assertTrue(controller.state.value.threadsComplete)
        } finally { releaseFull.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun freshDetailUpdatesProvisionalTitleAndKeepsItDuringIndexing() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"threads":[]}""")
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, { throw it })
        try {
            controller.project(ProjectSummary("p", "P", "/p"))
            controller.refreshThreads()?.join()
            controller.recordCreatedThread("p", "new")
            controller.refreshThreads()?.join()
            val snapshot = dev.rivo.relay.data.SessionSnapshot("new", "修复列表同步", "idle", "",
                emptyList(), emptyList(), JSONObject(), updatedAt = 10, nativeExecution = "idle")
            controller.reconcileSession(snapshot.copy(nativeSyncState = "converging"))
            assertEquals("新会话", controller.state.value.threads.single().name)
            controller.reconcileSession(snapshot)
            assertEquals("修复列表同步", controller.state.value.threads.single().name)
            controller.refreshThreads()?.join()
            assertEquals("修复列表同步", controller.state.value.threads.single().name)
            controller.reconcileSession(snapshot.copy(name = "旧标题", updatedAt = 9))
            controller.reconcileSession(snapshot.copy(name = ""))
            assertEquals("修复列表同步", controller.state.value.threads.single().name)
            controller.reconcileSession(snapshot.copy(name = "更新后的标题", updatedAt = 11))
            assertEquals("更新后的标题", controller.state.value.threads.single().name)
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun successfulSyncReplacesListAndNavigationRetainsLastResult() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"threads":[{"id":"a","name":"A"},{"id":"b","name":"B"}]}"""))
        server.enqueue(MockResponse().setBody("""{"threads":[{"id":"a","name":"Updated"}]}"""))
        server.enqueue(MockResponse().setBody("""{"threads":[]}"""))
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, { throw it })
        try {
            controller.project(ProjectSummary("p", "P", "/p"))
            controller.refreshThreads()?.join()
            controller.refreshThreads()?.join()
            assertEquals(setOf("a"), controller.state.value.threads.map { it.id }.toSet())
            assertEquals("Updated", controller.state.value.threads.first { it.id == "a" }.name)
            controller.project(ProjectSummary("q", "Q", "/q"))
            controller.project(ProjectSummary("p", "P", "/p"))
            assertEquals(setOf("a"), controller.state.value.threads.map { it.id }.toSet())
            controller.refreshThreads()?.join()
            assertTrue(controller.state.value.threads.isEmpty())
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun missingProjectAndMalformedListDoNotEraseExistingThreads() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"threads":[{"id":"old","name":"Old"}]}"""))
        server.enqueue(MockResponse().setBody("""{"projects":[]}"""))
        server.enqueue(MockResponse().setBody("""{}"""))
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val failures = mutableListOf<Throwable>()
        val controller = CatalogController(api(server), scope, { failures.add(it) })
        try {
            controller.project(ProjectSummary("p", "P", "/p"))
            controller.refreshThreads()?.join()
            controller.refreshProjects().join()
            assertEquals("p", controller.state.value.selectedProject?.id)
            assertEquals(listOf("old"), controller.state.value.threads.map { it.id })
            controller.refreshThreads()?.join()
            assertEquals(listOf("old"), controller.state.value.threads.map { it.id })
            assertFalse(controller.state.value.threadsFresh)
            assertEquals(1, failures.size)
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun partialArchiveOnlyRetiresConfirmedProvisionalThread() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"threads":[]}""")
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, { throw it })
        try {
            controller.project(ProjectSummary("p", "P", "/p"))
            withTimeout(3000) { controller.state.first { it.threadsFresh } }
            controller.recordCreatedThread("p", "a")
            controller.recordCreatedThread("p", "b")
            controller.forgetCreatedThreads(listOf("a"))
            assertEquals(listOf("b"), controller.state.value.threads.map { it.id })
            controller.refreshThreads()?.join()
            assertEquals(listOf("b"), controller.state.value.threads.map { it.id })
            controller.setArchived(true)
            controller.refreshThreads()?.join()
            assertTrue(controller.state.value.threads.isEmpty())
            controller.setArchived(false)
            controller.refreshThreads()?.join()
            assertEquals(listOf("b"), controller.state.value.threads.map { it.id })
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun rapidNavigationCoalescesAndSameSelectionSharesRequest() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"threads":[{"id":"t","status":"idle"}]}"""))
        server.enqueue(MockResponse().setBody("""{"threads":[{"id":"t","status":"idle"}]}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, { throw it })
        try {
            repeat(20) { controller.project(ProjectSummary("p$it", "P", "/p")); yield() }
            withTimeout(3000) { controller.state.first { it.threadsFresh } }
            assertEquals(1, server.requestCount)
            assertTrue(server.takeRequest().path!!.contains("p19"))
            val read = controller.refreshThreads()
            repeat(20) { assertSame(read, controller.refreshThreads()) }
            controller.project(ProjectSummary("p19", "P", "/p"))
            assertEquals("t", controller.state.value.threads.single().id)
            read?.join()
            assertEquals(2, server.requestCount)
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun receiptIsScopedExpiresAndRetainsFreshDetailDuringIndexing() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"threads":[]}""")
        }
        server.start()
        var now = 0L
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, { throw it }, monotonicMillis = { now })
        suspend fun settled() { yield(); withTimeout(3000) { controller.state.first { !it.threadsLoading } } }
        try {
            controller.project(ProjectSummary("p", "P", "/p")); settled()
            val requests = server.requestCount
            controller.recordCreatedThread("q", "new")
            assertTrue(controller.state.value.threads.isEmpty())
            assertEquals(requests, server.requestCount)
            controller.project(ProjectSummary("q", "Q", "/q"))
            assertEquals("new", controller.state.value.threads.single().id)
            settled()
            controller.reconcileSession(dev.rivo.relay.data.SessionSnapshot("new", "N", "idle", "",
                emptyList(), emptyList(), JSONObject(), updatedAt = 10, nativeExecution = "idle"))
            controller.refreshThreads()?.join()
            assertEquals("idle", controller.state.value.threads.single().nativeExecution)
            controller.setArchived(true); settled()
            assertTrue(controller.state.value.threads.isEmpty())
            controller.setArchived(false); settled()
            assertEquals("new", controller.state.value.threads.single().id)
            now = 120_001
            controller.refreshThreads()?.join()
            assertTrue(controller.state.value.threads.isEmpty())
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun detailCompletionFencesOlderListButAllowsLaterRunningState() = runBlocking {
        val server = MockWebServer()
        val running = """{"threads":[{"id":"t","status":"running","nativeExecution":"running","updatedAt":10}]}"""
        server.enqueue(MockResponse().setBody(running))
        server.enqueue(MockResponse().setBody(running).setBodyDelay(300, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody(running))
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, { throw it })
        try {
            controller.project(ProjectSummary("p", "P", "/p"))
            withTimeout(3000) { controller.state.first { it.threadsFresh } }
            val oldRead = controller.refreshThreads()
            withTimeout(3000) { while (server.requestCount < 2) delay(10) }
            val completed = dev.rivo.relay.data.SessionSnapshot("t", "T", "idle", "",
                emptyList(), emptyList(), JSONObject(), updatedAt = 10, nativeExecution = "idle")
            controller.reconcileSession(completed.copy(updatedAt = 9))
            // Metadata updatedAt does not order live execution observations.
            assertFalse(dev.rivo.relay.codex.threadIsExecuting(controller.state.value.threads.single()))
            controller.reconcileSession(completed)
            oldRead?.join()
            assertFalse(dev.rivo.relay.codex.threadIsExecuting(controller.state.value.threads.single()))
            controller.refreshThreads()?.join()
            assertTrue(dev.rivo.relay.codex.threadIsExecuting(controller.state.value.threads.single()))
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun createdReceiptBridgesListIndexingDelayWithoutDuplicates() = runBlocking {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setBody("""{"threads":[]}""")) }
        server.enqueue(MockResponse().setBody("""{"threads":[{"id":"new","name":"Server title","status":"idle"}]}"""))
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, { throw it })
        try {
            controller.project(ProjectSummary("p", "Project", "/p"))
            withTimeout(3000) { controller.state.first { it.threadsFresh } }
            controller.recordCreatedThread("p", "new")
            assertEquals("new", controller.state.value.threads.single().id)
            yield()
            withTimeout(3000) { controller.state.first { !it.threadsLoading } }
            assertEquals("new", controller.state.value.threads.single().id)
            controller.refreshThreads()?.join()
            assertEquals("Server title", controller.state.value.threads.single().name)
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun completedDetailClearsListSpinner() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"threads":[{"id":"t","status":"running","nativeExecution":"running","hasOutstandingWork":true,"updatedAt":10}]}"""))
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, { throw it })
        try {
            controller.project(ProjectSummary("p", "Project", "/p"))
            withTimeout(3000) { controller.state.first { it.threadsFresh } }
            val completed = dev.rivo.relay.data.SessionSnapshot("t", "Thread", "idle", "",
                emptyList(), emptyList(), JSONObject(), updatedAt = 10, nativeExecution = "idle")
            controller.reconcileSession(completed.copy(nativeSyncState = "converging"))
            assertTrue(dev.rivo.relay.codex.threadIsExecuting(controller.state.value.threads.single()))
            controller.reconcileSession(completed)
            assertFalse(dev.rivo.relay.codex.threadIsExecuting(controller.state.value.threads.single()))
            assertFalse(controller.state.value.threads.single().hasOutstandingWork)
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun switchingNodesDoesNotDescribeLoadingCacheAsOffline() {
        val cached = CatalogState(projects = listOf(ProjectSummary("p", "Project", "/p")), projectsLoading = true)
        assertFalse(dev.rivo.relay.codex.catalogCacheNoticeVisible("projects", cached, true))
        assertTrue(dev.rivo.relay.codex.catalogCacheNoticeVisible("projects", cached.copy(projectsLoading = false), true))
        assertTrue(dev.rivo.relay.codex.catalogCacheNoticeVisible("projects", cached, false))
        assertFalse(dev.rivo.relay.codex.catalogCacheNoticeVisible("projects", cached.copy(projectsFresh = true), true))
        assertFalse(dev.rivo.relay.codex.catalogCacheNoticeVisible("settings", cached, false))
        assertFalse(dev.rivo.relay.codex.catalogCacheNoticeVisible("projects", CatalogState(), false))
    }

    private fun api(server: MockWebServer) = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
    private fun projects(count: Int) = MockResponse().setBody(
        """{"projects":[{"id":"p","name":"Fixture $count","path":"/fixture","threadCount":8,"outstandingWorkCount":$count}]}""")
    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    @Test fun reconnectReplacesStalledCatalogReadImmediately() = runBlocking {
        val server = MockWebServer()
        val started = CountDownLatch(1)
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (requests.incrementAndGet() == 1) {
                    started.countDown()
                    MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                } else projects(0)
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val transport = api(server)
        val controller = CatalogController(transport, scope, {})
        try {
            controller.refreshProjects()
            await(started)
            transport.resetTransport()
            controller.reconnect()
            withTimeout(2000) { controller.state.first { it.projectsFresh } }
            assertEquals(2, requests.get())
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun cachedCatalogsRemainUnwritableUntilTheirOwnNetworkReadsSucceed() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("relay-catalog-cache").toFile()
        val store = ConversationStore(directory)
        store.putMetadata("selectedProjectId", "p")
        store.saveCatalog("projects", JSONObject("""{"projects":[{"id":"p","name":"Cached project","path":"/fixture"}]}"""))
        store.saveCatalog("threads:p:false", JSONObject("""{"threads":[{"id":"t","name":"Cached thread","pinned":true}]}"""))
        val healthy = AtomicBoolean(false)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                !healthy.get() -> MockResponse().setResponseCode(503).setBody("""{"error":{"message":"offline"}}""")
                request.path == "/v1/projects?view=summary" -> projects(0)
                else -> MockResponse().setBody("""{"threads":[{"id":"t","name":"Fresh thread","pinned":true}]}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, {}, store)
        try {
            controller.refreshProjects().join()
            assertEquals("Cached project", controller.state.value.projects.single().name)
            assertFalse(controller.state.value.projectsFresh)
            controller.refreshThreads()
            withTimeout(5_000) { controller.state.first { !it.threadsLoading && it.threads.isNotEmpty() } }
            assertEquals("Cached thread", controller.state.value.threads.single().name)
            assertFalse(controller.state.value.threadsFresh)
            assertTrue(pinnedThreadsToObserve(controller.state.value).isEmpty())
            healthy.set(true)
            controller.refreshProjects().join()
            assertTrue(controller.state.value.projectsFresh)
            assertFalse(controller.state.value.threadsFresh)
            controller.refreshThreads()
            withTimeout(5_000) { controller.state.first { it.threadsFresh } }
            assertEquals(setOf("t"), pinnedThreadsToObserve(controller.state.value))
            healthy.set(false)
            controller.refreshThreads()
            withTimeout(5_000) { controller.state.first { !it.threadsLoading && !it.threadsFresh } }
            controller.refreshProjects().join()
            assertFalse(controller.state.value.projectsFresh)
            assertTrue(pinnedThreadsToObserve(controller.state.value).isEmpty())
        } finally { scope.cancel(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun restoredPinObservationKeysIgnoreRefreshTimestampsAndExcludeArchivedOrCachedLists() {
        val thread = ThreadSummary("pinned", "Pinned", "", "idle", "/fixture", "p", pinned = true, updatedAt = 10)
        val state = CatalogState(threads = listOf(thread), threadsFresh = true)
        assertEquals(setOf("pinned"), pinnedThreadsToObserve(state))
        assertEquals(pinnedThreadsToObserve(state), pinnedThreadsToObserve(state.copy(threads = listOf(thread.copy(updatedAt = 20)))))
        assertTrue(pinnedThreadsToObserve(state.copy(threadsFresh = false)).isEmpty())
        assertTrue(pinnedThreadsToObserve(state.copy(archived = true)).isEmpty())
        assertEquals(12, pinnedThreadsToObserve(state.copy(threads = (1..20).map { thread.copy(id = "$it") })).size)
    }

    @Test fun legacyProjectStatisticsCannotOverrideNodeActivity() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"projects":[
            {"id":"active","name":"Active","path":"/active","threadCount":8,"outstandingWorkCount":2,"runningTurnCount":1,"activeGoalCount":1},
            {"id":"old","name":"Missing count","path":"/old","threadCount":3},
            {"id":"invalid","name":"Negative count","path":"/invalid","outstandingWorkCount":-1}
        ]}"""))
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val failure = AtomicReference<Throwable?>()
        val controller = CatalogController(api(server), scope, failure::set)
        try {
            withTimeout(5_000) { controller.refreshProjects().join() }
            val result = controller.state.value.projects
            assertEquals(listOf("active", "old", "invalid"), result.map { it.id })
            assertTrue(result.none { it.isRunning })
            assertNull(failure.get())
            assertEquals("/v1/projects?view=summary", server.takeRequest().path)
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun projectPinsSurviveCatalogRefreshAndReopeningTheNodeStore() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("relay-project-pins").toFile()
        val server = MockWebServer()
        val catalog = """{"projects":[{"id":"a","name":"A","path":"/a"},{"id":"b","name":"B","path":"/b"}]}"""
        server.enqueue(MockResponse().setBody(catalog))
        server.enqueue(MockResponse().setBody(catalog))
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val transport = api(server)
        val store = ConversationStore(ConversationStore.nodeDirectory(directory, "primary"))
        val controller = CatalogController(transport, scope, { throw AssertionError(it) }, store)
        try {
            withTimeout(5_000) { controller.refreshProjects().join() }
            controller.setProjectPinned("b", true)
            controller.setProjectPinned("a", true)
            controller.setProjectPinned("b", true)
            assertEquals(listOf("a", "b"), controller.state.value.pinnedProjectIds)
            assertEquals(1, server.requestCount)
            withTimeout(5_000) { controller.refreshProjects().join() }
            assertEquals(listOf("a", "b"), controller.state.value.pinnedProjectIds)
            controller.setProjectPinned("a", false)
            store.flushMetadata()

            val reopened = ConversationStore(ConversationStore.nodeDirectory(directory, "primary"))
            assertEquals(listOf("b"), CatalogController(transport, scope, {}, reopened).state.value.pinnedProjectIds)
            val other = ConversationStore(ConversationStore.nodeDirectory(directory, "other"))
            assertTrue(CatalogController(transport, scope, {}, other).state.value.pinnedProjectIds.isEmpty())
        } finally { scope.cancel(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun concurrentRefreshCallsShareTheInFlightReadAndCanRefreshAfterCompletion() = runBlocking {
        val server = MockWebServer()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val count = reads.incrementAndGet()
                if (count == 1) { started.countDown(); release.await(10, TimeUnit.SECONDS) }
                return projects(if (count == 1) 2 else 0)
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val failure = AtomicReference<Throwable?>()
        val controller = CatalogController(api(server), scope, failure::set)
        try {
            val first = controller.refreshProjects()
            await(started)
            repeat(4) { assertSame(first, controller.refreshProjects()) }
            assertEquals(1, server.requestCount)
            release.countDown()
            withTimeout(5_000) { first.join() }
            assertEquals("Fixture 2", controller.state.value.projects.single().name)
            val next = controller.refreshProjects()
            assertNotSame(first, next)
            withTimeout(5_000) { next.join() }
            assertEquals("Fixture 0", controller.state.value.projects.single().name)
            assertEquals(2, server.requestCount)
            assertNull(failure.get())
        } finally { release.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun cancellingHomeObservationStopsFurtherPollingWithoutCancellingSharedRead() = runBlocking {
        val server = MockWebServer()
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val count = reads.incrementAndGet()
                if (count == 2) { secondStarted.countDown(); releaseSecond.await(10, TimeUnit.SECONDS) }
                return projects(if (count == 1) 1 else 0)
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val failure = AtomicReference<Throwable?>()
        val controller = CatalogController(api(server), scope, failure::set)
        val observer = scope.launch { controller.observeProjects(pollIntervalMillis = 40) }
        try {
            await(secondStarted)
            val sharedRead = controller.refreshProjects()
            observer.cancelAndJoin()
            assertTrue(sharedRead.isActive)
            releaseSecond.countDown()
            withTimeout(5_000) { sharedRead.join() }
            assertEquals("Fixture 0", controller.state.value.projects.single().name)
            delay(160) // Four test polling periods after the observer has ended.
            assertEquals(2, server.requestCount)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/v1/projects?view=summary", request.path)
            }
            assertNull(failure.get())
        } finally { releaseSecond.countDown(); observer.cancel(); scope.cancel(); server.shutdown() }
    }

    @Test fun archivedFilterUsesSeparateReadAndOldProjectReplyCannotReplaceIt() = runBlocking {
        val server = MockWebServer()
        val recentStarted = CountDownLatch(1)
        val releaseRecent = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val archived = request.requestUrl?.queryParameter("archived") == "true"
                if (!archived) { recentStarted.countDown(); releaseRecent.await(5, TimeUnit.SECONDS) }
                val id = if (archived) "archived" else "recent"
                return MockResponse().setBody("""{"threads":[{"id":"$id","name":"$id","updatedAt":100}]}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val failure = AtomicReference<Throwable?>()
        val controller = CatalogController(api(server), scope, failure::set)
        try {
            controller.project(ProjectSummary("p", "Project", "/project"))
            await(recentStarted)
            controller.setArchived(true)
            withTimeout(5_000) { controller.state.first { !it.loading && it.threads.singleOrNull()?.id == "archived" } }
            releaseRecent.countDown()
            delay(80)
            assertTrue(controller.state.value.archived)
            assertEquals("archived", controller.state.value.threads.single().id)
            assertNull(failure.get())
            assertEquals("/v1/threads?projectId=p&archived=false&view=summary", server.takeRequest().path)
            assertEquals("/v1/threads?projectId=p&archived=true&view=summary", server.takeRequest().path)
        } finally { releaseRecent.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun historicalBaselineSurvivesRestartAndKeepsLaterUpdatesUnread() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("relay-read-baseline").toFile()
        val server = MockWebServer()
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            for (version in listOf(100L, 100L, 200L)) {
                server.enqueue(MockResponse().setBody("""{"threads":[{"id":"t","status":"idle","updatedAt":$version}]}"""))
                val store = ConversationStore(directory)
                val controller = CatalogController(api(server), scope, { throw it }, store)
                controller.project(ProjectSummary("p", "Project", "/project"))
                withTimeout(5_000) { controller.state.first { it.threadsFresh && !it.loading } }
                assertEquals(if (version == 100L) emptySet<String>() else setOf("t"), controller.state.value.unreadThreadIds)
                store.flushMetadata()
                assertEquals("100", ConversationStore(directory).getMetadata("read:t"))
            }
        } finally { scope.cancel(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun readVersionRemainsReadUntilANewerCompletedUpdate() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"threads":[{"id":"t","name":"Thread","status":"idle","updatedAt":100}]}"""))
        server.enqueue(MockResponse().setBody("""{"threads":[{"id":"t","name":"Thread","status":"running","updatedAt":200}]}"""))
        server.enqueue(MockResponse().setBody("""{"threads":[{"id":"t","name":"Thread","status":"idle","updatedAt":201,"recoveryRequired":true,"hasOutstandingWork":true}]}"""))
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, { throw it })
        try {
            controller.project(ProjectSummary("p", "Project", "/project"))
            withTimeout(5_000) { controller.state.first { !it.loading && it.threads.isNotEmpty() } }
            assertTrue(controller.state.value.unreadThreadIds.isEmpty())
            controller.markRead("t", 100)
            assertTrue(controller.state.value.unreadThreadIds.isEmpty())
            controller.refreshThreads()
            withTimeout(5_000) { controller.state.first { it.threads.firstOrNull()?.updatedAt == 200L } }
            assertTrue(controller.state.value.unreadThreadIds.isEmpty())
            controller.refreshThreads()
            withTimeout(5_000) { controller.state.first { it.threads.firstOrNull()?.updatedAt == 201L } }
            assertEquals(setOf("t"), controller.state.value.unreadThreadIds)
            controller.markRead("t", 201)
            controller.markRead("t", 100)
            assertTrue(controller.state.value.unreadThreadIds.isEmpty())
        } finally { scope.cancel(); server.shutdown() }
    }
    @Test fun executionEventsDoNotInvalidateListMembership() {
        fun event(method: String) = dev.rivo.relay.data.RelayEvent("native", JSONObject().put("method", method))
        for (method in listOf("turn/started", "turn/completed", "thread/status/changed", "thread/name/updated", "thread/goal/updated"))
            assertFalse(method, dev.rivo.relay.codex.catalogMembershipEvent(event(method)))
        for (method in listOf("thread/started", "thread/deleted", "thread/archived", "thread/unarchived"))
            assertTrue(method, dev.rivo.relay.codex.catalogMembershipEvent(event(method)))
    }

    @Test fun completedListStaysCompleteDuringMetadataRefresh() = runBlocking {
        val server = MockWebServer()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val index = reads.incrementAndGet()
                if (index == 3) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                return MockResponse().setBody("""{"threads":[{"id":"t","name":"${if (index == 1) "Old" else "Renamed"}","updatedAt":$index}],"catalogComplete":${index != 2}}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, { throw it })
        try {
            controller.project(ProjectSummary("p", "P", "/p"))
            withTimeout(3000) { controller.state.first { it.threadsFresh && it.threadsComplete } }
            val refresh = controller.refreshThreads(force = true)
            await(started)
            assertTrue(controller.state.value.threadsComplete)
            assertEquals("Renamed", controller.state.value.threads.single().name)
            release.countDown()
            refresh?.join()
            assertTrue(controller.state.value.threadsComplete)
        } finally { release.countDown(); scope.cancel(); server.shutdown() }
    }

}
