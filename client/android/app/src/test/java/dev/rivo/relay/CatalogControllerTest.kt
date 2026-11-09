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
            controller.project(ProjectSummary("p", "P", "/p", 1))
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
            controller.project(ProjectSummary("p", "P", "/p", 0))
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
            repeat(20) { controller.project(ProjectSummary("p$it", "P", "/p", 1)); yield() }
            withTimeout(3000) { controller.state.first { it.threadsFresh } }
            assertEquals(1, server.requestCount)
            assertTrue(server.takeRequest().path!!.contains("p19"))
            val read = controller.refreshThreads()
            repeat(20) { assertSame(read, controller.refreshThreads()) }
            controller.project(ProjectSummary("p19", "P", "/p", 1))
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
            controller.project(ProjectSummary("p", "P", "/p", 0)); settled()
            val requests = server.requestCount
            controller.recordCreatedThread("q", "new")
            assertTrue(controller.state.value.threads.isEmpty())
            assertEquals(requests, server.requestCount)
            controller.project(ProjectSummary("q", "Q", "/q", 0))
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
            controller.project(ProjectSummary("p", "P", "/p", 1))
            withTimeout(3000) { controller.state.first { it.threadsFresh } }
            val oldRead = controller.refreshThreads()
            withTimeout(3000) { while (server.requestCount < 2) delay(10) }
            val completed = dev.rivo.relay.data.SessionSnapshot("t", "T", "idle", "",
                emptyList(), emptyList(), JSONObject(), updatedAt = 10, nativeExecution = "idle")
            controller.reconcileSession(completed.copy(updatedAt = 9))
            assertTrue(dev.rivo.relay.codex.threadIsExecuting(controller.state.value.threads.single()))
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
            controller.project(ProjectSummary("p", "Project", "/p", 0))
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
            controller.project(ProjectSummary("p", "Project", "/p", 1))
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
        val cached = CatalogState(projects = listOf(ProjectSummary("p", "Project", "/p", 1)), projectsLoading = true)
        assertFalse(dev.rivo.relay.codex.catalogCacheNoticeVisible("projects", cached, true))
        assertTrue(dev.rivo.relay.codex.catalogCacheNoticeVisible("projects", cached.copy(projectsLoading = false), true))
        assertTrue(dev.rivo.relay.codex.catalogCacheNoticeVisible("projects", cached, false))
        assertFalse(dev.rivo.relay.codex.catalogCacheNoticeVisible("projects", cached.copy(projectsFresh = true), true))
        assertFalse(dev.rivo.relay.codex.catalogCacheNoticeVisible("settings", cached, false))
        assertFalse(dev.rivo.relay.codex.catalogCacheNoticeVisible("projects", CatalogState(), false))
    }

    private fun api(server: MockWebServer) = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
    private fun projects(count: Int) = MockResponse().setBody(
        """{"projects":[{"id":"p","name":"Isolated fixture","path":"/fixture","threadCount":8,"outstandingWorkCount":$count}]}""")
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
                request.path == "/v1/projects" -> projects(0)
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

    @Test fun projectActivityCountIsProjectedAndMissingOrInvalidCountsAreIdle() = runBlocking {
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
            assertEquals(listOf(2, 0, 0), result.map { it.outstandingWorkCount })
            assertEquals(listOf(1, 0, 0), result.map { it.runningTurnCount })
            assertEquals(listOf(1, 0, 0), result.map { it.activeGoalCount })
            assertEquals(8, result.first().threadCount)
            assertNull(failure.get())
            assertEquals("/v1/projects", server.takeRequest().path)
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
            assertEquals(2, controller.state.value.projects.single().outstandingWorkCount)
            val next = controller.refreshProjects()
            assertNotSame(first, next)
            withTimeout(5_000) { next.join() }
            assertEquals(0, controller.state.value.projects.single().outstandingWorkCount)
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
            assertEquals(0, controller.state.value.projects.single().outstandingWorkCount)
            delay(160) // Four test polling periods after the observer has ended.
            assertEquals(2, server.requestCount)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/v1/projects", request.path)
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
            assertEquals("/v1/threads?projectId=p&archived=false", server.takeRequest().path)
            assertEquals("/v1/threads?projectId=p&archived=true", server.takeRequest().path)
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
}
