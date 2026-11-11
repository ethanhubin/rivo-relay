package dev.rivo.relay

import dev.rivo.relay.codex.*
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.todos.TodosController
import dev.rivo.relay.ui.projects.ProjectSummary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RefreshConcurrencyTest {
    private fun api(server: MockWebServer) = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) { assertTrue(latch.await(5, TimeUnit.SECONDS)) }

    @Test fun todoReadersCoalesceWithoutBlockingDispatchOrRewritingUnchangedState() = runBlocking {
        val server = MockWebServer()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val reads = AtomicInteger(); val changed = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (reads.incrementAndGet() == 2) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                return MockResponse().setBody("""{"todos":[{"id":"t","title":"Task"}]}""")
            }
        }
        server.start(); val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val controller = TodosController(api(server), scope, { throw it }, onChanged = { changed.incrementAndGet() })
            controller.refresh().join()
            val job = controller.refresh(); await(started)
            repeat(10) { assertSame(job, controller.refresh()) }
            assertTrue(controller.state.value.refreshing)
            assertFalse(controller.state.value.syncing)
            assertTrue(controller.state.value.online)
            release.countDown(); job.join()
            assertEquals(2, reads.get()); assertEquals(1, changed.get())
        } finally { release.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun localEditDuringReadSurvivesAndGetsOneFollowupWrite() = runBlocking {
        val server = MockWebServer()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val reads = AtomicInteger(); val writes = AtomicInteger()
        var title = "old"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "PATCH") { writes.incrementAndGet(); title = JSONObject(request.body.readUtf8()).getString("title"); return MockResponse().setBody("{}") }
                val snapshot = title
                if (reads.incrementAndGet() == 2) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                return MockResponse().setBody("""{"todos":[{"id":"t","title":"$snapshot"}]}""")
            }
        }
        server.start(); val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val controller = TodosController(api(server), scope, { throw it })
            controller.refresh().join()
            val job = controller.refresh(); await(started)
            val edited = CompletableDeferred<Unit>()
            controller.save(controller.state.value.items.single(), "new", "") { edited.complete(Unit) }
            withTimeout(5000) { edited.await() }
            assertEquals("new", controller.state.value.items.single().title)
            release.countDown(); job.join()
            assertEquals("new", controller.state.value.items.single().title)
            assertEquals(0, controller.state.value.pendingCount)
            assertEquals(1, writes.get()); assertEquals(3, reads.get())
        } finally { release.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun dispatchPreparationCannotDuplicateOrDispatchRemotelyCompletedTodo() = runBlocking {
        for (completed in listOf(false, true)) {
            val server = MockWebServer(); val release = CountDownLatch(1); val started = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    started.countDown(); release.await(5, TimeUnit.SECONDS)
                    return MockResponse().setBody("""{"todos":[{"id":"t","title":"Task","completed":$completed}]}""")
                }
            }
            server.start(); val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val controller = TodosController(api(server), scope, { throw it }); val called = AtomicInteger()
                controller.prepareDispatch("t") { called.incrementAndGet() }; await(started)
                controller.prepareDispatch("t") { called.incrementAndGet() }
                release.countDown(); controller.refresh().join(); yield()
                assertEquals(if (completed) 0 else 1, called.get()); assertEquals(1, server.requestCount)
            } finally { release.countDown(); scope.cancel(); server.shutdown() }
        }
    }

    @Test fun catalogMutationDuringReadGetsFollowupWithoutCancellingOriginal() = runBlocking {
        val server = MockWebServer(); val started = CountDownLatch(1); val release = CountDownLatch(1)
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val count = reads.incrementAndGet()
                if (count == 1) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                return MockResponse().setBody("""{"threads":[{"id":"t","name":"version$count","nativeExecution":"idle","status":"idle"}]}""")
            }
        }
        server.start(); val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val controller = CatalogController(api(server), scope, { throw it })
            controller.project(ProjectSummary("p", "P", "/p")); await(started)
            repeat(10) { controller.refreshChangedThreads() }
            assertEquals(1, reads.get()); release.countDown()
            withTimeout(5000) { controller.state.first { it.threadsFresh && it.threads.singleOrNull()?.name == "version2" } }
            assertEquals(2, reads.get())
        } finally { release.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun catalogAuthorizationFailureRevokesPreviouslyConfirmedPermissions() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"projects":[{"id":"p","name":"P","path":"/p"}]}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"forbidden"}}"""))
        server.start(); val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val controller = CatalogController(api(server), scope, {})
            controller.refreshProjects().join(); assertTrue(controller.state.value.projectsFresh)
            controller.refreshProjects().join(); assertFalse(controller.state.value.projectsFresh)
            assertEquals("p", controller.state.value.projects.single().id)
        } finally { scope.cancel(); server.shutdown() }
    }
    @Test fun activityIdlePollingSlowsDownAndTransientFailureKeepsConfirmedState() = runBlocking {
        val server = MockWebServer(); val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse()
                    .setHeader("Content-Type", "text/event-stream").setBody(":".repeat(65536)).throttleBody(1024, 1, TimeUnit.SECONDS)
                return when (reads.incrementAndGet()) {
                    2 -> MockResponse().setResponseCode(503).setBody("""{"error":{"message":"temporary"}}""")
                    3 -> MockResponse().setResponseCode(403).setBody("""{"error":{"message":"forbidden"}}""")
                    else -> MockResponse().setBody("""{"complete":true,"generation":"g","version":1,"tasks":[],"knownThreadIds":["t"],"unknownThreadIds":[]}""")
                }
            }
        }
        server.start(); val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val api = api(server); val sync = NodeSessionSync(api, scope)
            val connected = CompletableDeferred<Unit>()
            scope.launch { sync.events().collect { if (it.type == "connected") connected.complete(Unit) } }
            withTimeout(5000) { connected.await() }
            val activity = nodeActivity(api, sync, scope, activeIntervalMillis = 200, idleIntervalMillis = 800)
            scope.launch { activity.collect {} }
            withTimeout(5000) { activity.first { it.status("t") == "idle" } }
            delay(300); assertEquals(1, reads.get())
            withTimeout(5000) { while (reads.get() < 2) delay(10) }
            delay(50); assertEquals("idle", activity.value.status("t"))
            withTimeout(5000) { activity.first { !it.confirmed } }
            assertEquals(3, reads.get())
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun idleCatalogUsesSummaryWithoutRepeatedFullScansAndStopsWithScreen() = runBlocking {
        val server = MockWebServer(); val summaries = AtomicInteger(); val full = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val summary = request.path!!.contains("view=summary")
                if (summary) summaries.incrementAndGet() else full.incrementAndGet()
                return MockResponse().setBody("""{"threads":[{"id":"t","name":"T","status":"idle","nativeExecution":"idle"}],"catalogComplete":${!summary}}""")
            }
        }
        server.start(); val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val controller = CatalogController(api(server), scope, { throw it })
            controller.project(ProjectSummary("p", "P", "/p")); controller.refreshThreads()?.join()
            assertEquals(1, full.get()); assertEquals(1, summaries.get())
            val observer = scope.launch { controller.observeThreads(activeIntervalMillis = 20, idleIntervalMillis = 100,
                summaryIntervalMillis = 50, fullIntervalMillis = 10000) }
            withTimeout(5000) { while (summaries.get() < 3) delay(10) }
            observer.cancelAndJoin()
            val count = server.requestCount; delay(180)
            assertEquals(count, server.requestCount); assertEquals(1, full.get())
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun projectCreationDuringReadRequiresOneFreshFollowup() = runBlocking {
        val server = MockWebServer(); val started = CountDownLatch(1); val release = CountDownLatch(1)
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val count = reads.incrementAndGet()
                if (count == 1) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                return MockResponse().setBody(if (count == 1) """{"projects":[]}"""
                    else """{"projects":[{"id":"new","name":"New","path":"/new"}]}""")
            }
        }
        server.start(); val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val controller = CatalogController(api(server), scope, { throw it })
            val reading = controller.refreshProjects(); await(started)
            repeat(10) { assertSame(reading, controller.refreshProjects(changed = true)) }
            assertEquals(1, reads.get()); release.countDown(); reading.join()
            assertEquals("new", controller.state.value.projects.single().id)
            assertEquals(2, reads.get())
        } finally { release.countDown(); scope.cancel(); server.shutdown() }
    }

}
