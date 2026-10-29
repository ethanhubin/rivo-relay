package dev.rivo.relay

import dev.rivo.relay.codex.CatalogController
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
