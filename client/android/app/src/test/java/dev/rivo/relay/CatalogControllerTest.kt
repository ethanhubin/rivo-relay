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
    private fun api(server: MockWebServer) = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
    private fun projects(count: Int) = MockResponse().setBody(
        """{"projects":[{"id":"p","name":"Isolated fixture","path":"/fixture","threadCount":8,"outstandingWorkCount":$count}]}""")
    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue(latch.await(5, TimeUnit.SECONDS))
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
            {"id":"active","name":"Active","path":"/active","threadCount":8,"outstandingWorkCount":2},
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
            assertEquals(8, result.first().threadCount)
            assertNull(failure.get())
            assertEquals("/v1/projects", server.takeRequest().path)
        } finally { scope.cancel(); server.shutdown() }
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

    @Test fun readVersionRemainsReadUntilANewerCompletedUpdate() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"threads":[{"id":"t","name":"Thread","status":"idle","updatedAt":100}]}"""))
        server.enqueue(MockResponse().setBody("""{"threads":[{"id":"t","name":"Thread","status":"running","updatedAt":200}]}"""))
        server.enqueue(MockResponse().setBody("""{"threads":[{"id":"t","name":"Thread","status":"idle","updatedAt":201}]}"""))
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = CatalogController(api(server), scope, { throw it })
        try {
            controller.project(ProjectSummary("p", "Project", "/project"))
            withTimeout(5_000) { controller.state.first { !it.loading && it.threads.isNotEmpty() } }
            assertEquals(setOf("t"), controller.state.value.unreadThreadIds)
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
