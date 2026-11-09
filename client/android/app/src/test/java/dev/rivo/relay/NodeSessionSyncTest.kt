package dev.rivo.relay

import dev.rivo.relay.codex.NodeSessionSync
import dev.rivo.relay.data.RelayApi
import dev.rivo.relay.data.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NodeSessionSyncTest {
    private fun api(server: MockWebServer) = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) { assertTrue(latch.await(5, TimeUnit.SECONDS)) }

    @Test fun handledInterruptionKeepsSubscriberAndReconnectCursor() = runBlocking {
        val server = MockWebServer()
        val requests = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val cursor = if (requests.size == 1) "before-disconnect" else "after-reconnect"
                return MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("event: native\nid: $cursor\ndata: {}\n\n")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val sync = NodeSessionSync(api(server), scope)
            var interruptions = 0
            val event = withTimeout(6000) {
                sync.events(onInterrupted = { interruptions++ }).first { it.cursor == "after-reconnect" }
            }
            assertEquals("after-reconnect", event.cursor)
            assertEquals(1, interruptions)
            assertEquals(2, requests.size)
            assertEquals("before-disconnect", requests[1].getHeader("Last-Event-ID"))
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun concurrentReadersShareOnlyInFlightResponseAndOneCancellationDoesNotCancelAnother() = runBlocking {
        val server = MockWebServer()
        val count = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val current = count.incrementAndGet()
                if (current == 1) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                return MockResponse().setBody("""{"value":"$current"}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val sync = NodeSessionSync(api(server), scope)
            val first = async { sync.read("t") }
            await(started)
            val second = async(start = CoroutineStart.UNDISPATCHED) { sync.read("t") }
            first.cancelAndJoin(); release.countDown()
            assertEquals("1", second.await().string("value"))
            assertEquals(1, count.get())
            assertEquals("2", sync.read("t").string("value")) // No TTL or completed-response reuse.
        } finally { release.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun invalidationSeparatesAnOldReadFromNewPermissionObservation() = runBlocking {
        val server = MockWebServer()
        val count = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val current = count.incrementAndGet()
                if (current == 1) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                return MockResponse().setBody("""{"value":"$current"}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val sync = NodeSessionSync(api(server), scope)
            val old = async { sync.read("t") }; await(started)
            sync.invalidate()
            assertEquals("2", sync.read("t").string("value"))
            release.countDown(); assertEquals("1", old.await().string("value"))
        } finally { release.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun controlFreshnessCannotReuseAnOlderNotificationReadButNotificationsCanJoinNewControlRead() = runBlocking {
        val server = MockWebServer()
        val count = AtomicInteger()
        val oldStarted = CountDownLatch(1)
        val freshStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val releaseFresh = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val current = count.incrementAndGet()
                if (current == 1) { oldStarted.countDown(); releaseOld.await(5, TimeUnit.SECONDS) }
                if (current == 2) { freshStarted.countDown(); releaseFresh.await(5, TimeUnit.SECONDS) }
                return MockResponse().setBody("""{"value":"$current"}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val sync = NodeSessionSync(api(server), scope)
            val oldNotification = async { sync.read("t") }; await(oldStarted)
            val controlStarted = System.nanoTime()
            val control = async(start = CoroutineStart.UNDISPATCHED) { sync.read("t", freshAfterNanos = controlStarted) }
            await(freshStarted)
            val joinedNotification = async(start = CoroutineStart.UNDISPATCHED) { sync.read("t") }
            releaseFresh.countDown()
            assertEquals("2", control.await().string("value"))
            assertEquals("2", joinedNotification.await().string("value"))
            assertEquals(2, count.get())
            assertFalse(oldNotification.isCompleted)
            releaseOld.countDown()
            assertEquals("1", oldNotification.await().string("value"))
        } finally { releaseOld.countDown(); releaseFresh.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun anotherThreadsEventDoesNotDuplicateAnInFlightRead() = runBlocking {
        val server = MockWebServer()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("event: native\ndata: {\"method\":\"turn/completed\",\"params\":{\"threadId\":\"other\"}}\n\n" + ":".repeat(4096))
                    .throttleBody(150, 1, TimeUnit.SECONDS)
                count.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS)
                return MockResponse().setBody("{}")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val sync = NodeSessionSync(api(server), scope)
            val connected = CompletableDeferred<Unit>()
            val notice = CompletableDeferred<Unit>()
            lateinit var first: Deferred<org.json.JSONObject>
            val observer = launch {
                sync.events().collect { event ->
                    if (event.type == "connected") {
                        first = async(start = CoroutineStart.UNDISPATCHED) { sync.read("t") }
                        connected.complete(Unit)
                    }
                    if (event.type == "native") notice.complete(Unit)
                }
            }
            withTimeout(5000) { connected.await(); notice.await() }; await(started)
            val second = async(start = CoroutineStart.UNDISPATCHED) { sync.read("t") }
            release.countDown(); first.await(); second.await()
            assertEquals(1, count.get())
            observer.cancelAndJoin()
        } finally { release.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun threadInvalidationSeparatesOnlyItsReadAndDoesNotCancelExistingConsumers() = runBlocking {
        val server = MockWebServer()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                count.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS)
                return MockResponse().setBody("{}")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val sync = NodeSessionSync(api(server), scope)
            val a = async { sync.read("a") }; val b = async { sync.read("b") }; await(started)
            sync.invalidate("a")
            val newA = async(start = CoroutineStart.UNDISPATCHED) { sync.read("a") }
            val sameB = async(start = CoroutineStart.UNDISPATCHED) { sync.read("b") }
            release.countDown(); awaitAll(a, b, newA, sameB)
            assertEquals(3, count.get())
        } finally { release.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun differentPageSizesDoNotShareReadsAndInvalidationFencesBoth() = runBlocking {
        val server = MockWebServer()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val number = count.incrementAndGet()
                if (number <= 2) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                return MockResponse().setBody("""{"limit":${request.requestUrl!!.queryParameter("limit")},"number":$number}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val sync = NodeSessionSync(api(server), scope)
            val small = async { sync.read("t", limit = 2) }
            val large = async { sync.read("t", limit = 30) }
            await(started)
            sync.invalidate("t")
            assertEquals(3, sync.read("t", limit = 2).getInt("number"))
            assertEquals(4, sync.read("t", limit = 30).getInt("number"))
            release.countDown()
            assertEquals(2, small.await().getInt("limit"))
            assertEquals(30, large.await().getInt("limit"))
        } finally { release.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun conditionalReadCannotSupplyAnUnacknowledgedConsumer() = runBlocking {
        val server = MockWebServer()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val known = request.requestUrl!!.queryParameter("knownHeadRevision")
                if (known != null) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                return MockResponse().setBody("""{"historyUnchanged":${known != null}}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val sync = NodeSessionSync(api(server), scope)
            val conditional = async { sync.read("t", limit = 2, knownHeadRevision = "head") }
            await(started)
            assertFalse(withTimeout(2000) { sync.read("t", limit = 2) }.getBoolean("historyUnchanged"))
            release.countDown()
            assertTrue(conditional.await().getBoolean("historyUnchanged"))
            assertEquals(2, server.requestCount)
        } finally { release.countDown(); scope.cancel(); server.shutdown() }
    }

    @Test fun twoObserversUseOneNodeStreamAndDisposalCancelsIt() = runBlocking {
        val server = MockWebServer()
        val streams = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                streams.incrementAndGet()
                return MockResponse().setHeader("Content-Type", "text/event-stream").setBodyDelay(100, TimeUnit.MILLISECONDS)
                    .setBody("event: native\nid: cursor-1\ndata: {\"method\":\"turn/completed\",\"params\":{\"threadId\":\"t\"}}\n\n" + ":".repeat(4096))
                    .throttleBody(150, 1, TimeUnit.SECONDS)
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val sync = NodeSessionSync(api(server), scope)
            val first = async { sync.events().first { it.type == "native" } }
            val second = async { sync.events().first { it.type == "native" } }
            withTimeout(5000) { assertEquals("cursor-1", first.await().cursor); assertEquals("cursor-1", second.await().cursor) }
            assertEquals(1, streams.get())
        } finally { scope.cancel(); server.shutdown() }
    }
}
