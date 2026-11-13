package dev.rivo.relay

import dev.rivo.relay.codex.*
import dev.rivo.relay.data.RelayApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NodeActivitySyncTest {
    @Test fun scopedEventsDoNotClearAnotherRunningConversation() = runBlocking {
        for (event in listOf(
            """{"method":"relay/writerDisconnected","params":{"threadIds":[]}}""",
            """{"method":"relay/writerDisconnected","params":{"threadIds":["other"]}}""",
            """{"method":"turn/completed","params":{"threadId":"other","turn":{"id":"turn"}}}""",
            """{"method":"project/updated","params":{"projectId":"p"}}"""
        )) verifyEvent(event, targetUnknown = false)
    }

    @Test fun affectedConversationStillRequiresFreshState() = runBlocking {
        verifyEvent("""{"method":"relay/writerDisconnected","params":{"threadIds":["t"]}}""", targetUnknown = true)
        verifyEvent("""{"method":"relay/nativeDisconnected","params":{}}""", targetUnknown = true)
    }

    @Test fun activityNoticeWakesIdleFallbackButCoveredNoticeDoesNotReadAgain() = runBlocking {
        val newer = """{"method":"relay/activityChanged","params":{"threadIds":["t"],"generation":"g","revision":2}}"""
        assertFalse(catalogLifecycleEvent(dev.rivo.relay.data.RelayEvent("native", org.json.JSONObject(newer))))
        verifyEvent(newer, targetUnknown = true, initialIdle = true)
        verifyEvent(newer.replace("revision\":2", "revision\":1"), targetUnknown = false, noRefresh = true)
    }

    private suspend fun verifyEvent(event: String, targetUnknown: Boolean, noRefresh: Boolean = false, initialIdle: Boolean = false) = coroutineScope {
        val server = MockWebServer()
        val refreshing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl?.encodedPath == "/v1/events") {
                    return MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBodyDelay(1, TimeUnit.SECONDS)
                        .setBody("event: native\ndata: $event\n\n" + ":".repeat(65536))
                        .throttleBody(1024, 1, TimeUnit.SECONDS)
                }
                val version = reads.incrementAndGet()
                if (version > 1) { refreshing.countDown(); release.await(5, TimeUnit.SECONDS) }
                val tasks = if (initialIdle && version == 1) "[]" else
                    """[{"threadId":"t","projectId":"p","status":"running","nativeExecution":"running"}]"""
                return MockResponse().setBody("""{"complete":true,"generation":"g","version":$version,"revision":$version,
                    "tasks":$tasks,
                    "knownThreadIds":["t","other"],"unknownThreadIds":[]}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val api = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
            val sync = NodeSessionSync(api, scope)
            val connected = CompletableDeferred<Unit>()
            val delivered = CompletableDeferred<Unit>()
            val observing = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                sync.events().collect {
                    if (it.type == "connected") connected.complete(Unit)
                    if (it.type == "native") delivered.complete(Unit)
                }
            }
            withTimeout(5000) { connected.await() }
            val collecting = scope.launch { sync.activity.collect {} }
            withTimeout(5000) { sync.activity.first { it.status("t") == if (initialIdle) "idle" else "running" } }
            val ignored = noRefresh || event.contains("project/updated") || event.contains("\"threadIds\":[]")
            if (ignored) {
                withTimeout(5000) { delivered.await() }; delay(100)
                assertEquals(1, reads.get())
            } else withContext(Dispatchers.IO) { assertTrue(refreshing.await(5, TimeUnit.SECONDS)) }
            val state = sync.activity.value
            assertEquals(event, if (targetUnknown) "unknown" else "running", state.execution("t").status)
            if (!targetUnknown) {
                assertEquals(setOf("p"), state.runningProjectIds)
                assertEquals("running", CatalogState(threads = listOf(state.tasks.getValue("t")))
                    .withNodeActivity(state).threads.single().status)
            }
            release.countDown()
            if (!ignored) withTimeout(5000) { sync.activity.first { it.version >= 2 && it.status("t") == "running" } }
            collecting.cancelAndJoin()
            observing.cancelAndJoin()
        } finally { release.countDown(); scope.cancel(); server.shutdown() }
    }
}
