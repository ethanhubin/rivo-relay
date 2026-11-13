package dev.rivo.relay

import dev.rivo.relay.codex.*
import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ActivityDetailSyncTest {
    @Test fun unrelatedCompletionMustNotDiscardRunningDetailForList() = runBlocking {
        val server = MockWebServer()
        val pollRelease = CountDownLatch(1)
        val detailRelease = CountDownLatch(1)
        val detailStarted = CountDownLatch(1)
        val polls = AtomicInteger()
        fun activity(version: Int, running: Boolean) = """{"complete":true,"generation":"g","version":$version,
            "tasks":${if (running) """[{"threadId":"t","projectId":"p","status":"running","nativeExecution":"running"}]""" else "[]"},
            "knownThreadIds":["t","other"],"unknownThreadIds":[]}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/v1/events" -> MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBodyDelay(1, TimeUnit.SECONDS)
                    .setBody("event: native\ndata: {\"method\":\"turn/completed\",\"params\":{\"threadId\":\"other\"}}\n\n" + ":".repeat(65536))
                    .throttleBody(1024, 1, TimeUnit.SECONDS)
                "/v1/activity" -> {
                    val n = polls.incrementAndGet()
                    if (n > 1) pollRelease.await(10, TimeUnit.SECONDS)
                    MockResponse().setBody(activity(if (n == 1) 1 else 3, n > 1))
                }
                else -> {
                    detailStarted.countDown(); detailRelease.await(10, TimeUnit.SECONDS)
                    MockResponse().setBody("""{"status":"running","nativeExecution":"running","activity":${activity(2, true)}}""")
                }
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val sync = NodeSessionSync(RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString()), scope)
            val connected = CompletableDeferred<Unit>(); val event = CompletableDeferred<Unit>()
            scope.launch { sync.events().collect {
                if (it.type == "connected") connected.complete(Unit)
                if (it.type == "native") event.complete(Unit)
            } }
            withTimeout(5000) { connected.await() }
            scope.launch { sync.activity.collect {} }
            withTimeout(5000) { sync.activity.first { it.status("t") == "idle" } }
            val detail = async { sync.read("t") }
            withContext(Dispatchers.IO) { assertTrue(detailStarted.await(5, TimeUnit.SECONDS)) }
            withTimeout(5000) { event.await() }
            detailRelease.countDown()
            assertEquals("running", detail.await().getString("status"))
            val shared = withTimeout(1500) { sync.activity.first { it.status("t") == "running" } }
            val catalog = CatalogState(threads = listOf(threadSummary(org.json.JSONObject("""{"id":"t","status":"idle"}"""))))
                .withNodeActivity(shared)
            assertEquals("running", shared.status("t"))
            assertNull(shared.status("other"))
            assertEquals("running", catalog.threads.single().status)
        } finally { detailRelease.countDown(); pollRelease.countDown(); scope.cancel(); server.shutdown() }
    }
}
