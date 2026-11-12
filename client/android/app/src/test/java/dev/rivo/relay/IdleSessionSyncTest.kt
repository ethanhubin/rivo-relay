package dev.rivo.relay

import dev.rivo.relay.codex.*
import dev.rivo.relay.data.RelayApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IdleSessionSyncTest {
    private fun snapshot(name: String) = MockResponse().setBody(
        """{"thread":{"id":"t","name":"$name","turns":[]},"status":"idle"}""")
    private fun api(server: MockWebServer) = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())

    @Test fun confirmedIdleSessionDoesNotPollAndExplicitRefreshStillWorks(): Unit = runBlocking {
        val reads = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/v1/events")) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                else snapshot("read-${reads.incrementAndGet()}")
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope, pollIntervalMillis = 60) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(3000) { controller.state.first { it.snapshot != null && !it.stale } }
            delay(600) // Several fallback intervals, including the post-read debounce.
            assertEquals(1, reads.get())
            assertTrue(controller.state.value.control().canOperate)
            controller.refresh()
            withTimeout(3000) { controller.state.first { it.snapshot?.name == "read-2" } }
            delay(600)
            assertEquals(2, reads.get())
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun onlyRelevantEventsWakeConfirmedIdleSession(): Unit = runBlocking {
        for ((payload, relevant) in listOf(
            """{"method":"thread/name/updated","params":{"threadId":"t"}}""" to true,
            """{"method":"thread/started","params":{"thread":{"id":"t"}}}""" to true,
            """{"method":"thread/started","params":{"thread":{"id":"other"}}}""" to false,
            """{"method":"relay/todos","params":{}}""" to false,
            """{"method":"project/updated","params":{"projectId":"p"}}""" to false,
            """{"method":"serverRequest/resolved","params":{"requestId":1}}""" to true,
        )) {
            val reads = AtomicInteger()
            val server = MockWebServer()
            val event = "event: native\ndata: $payload\n\n"
            val chunkSize = event.toByteArray().size
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path!!.startsWith("/v1/events")) MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(":" + " ".repeat(chunkSize - 3) + "\n\n" + event + ":".repeat(4096))
                        .throttleBody(chunkSize.toLong(), 1, TimeUnit.SECONDS)
                    else snapshot("read-${reads.incrementAndGet()}")
            }
            server.start()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val controller = SessionController(api(server), scope, pollIntervalMillis = 60) { fail(it.message) }
            try {
                controller.open("t")
                withTimeout(3000) { controller.state.first { it.snapshot != null && !it.stale } }
                delay(450) // Let the initial connected event settle before the delayed notification.
                val before = reads.get()
                delay(1100)
                assertEquals(payload, before + if (relevant) 1 else 0, reads.get())
                assertTrue(controller.state.value.control().canOperate)
            } finally { controller.close(); scope.cancel(); server.shutdown() }
        }
    }

    @Test fun unsettledIdleSnapshotRetriesThenStopsWhenReady(): Unit = runBlocking {
        for (extra in listOf(
            """"nativeSync":{"state":"converging","historyReady":true}""",
            """"control":{"state":"ownership_unverified","canOperate":false}""",
            """"control":{"state":"release_pending","canOperate":false}""",
        )) {
            val reads = AtomicInteger()
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    return if (reads.incrementAndGet() == 1) MockResponse().setBody(
                        """{"thread":{"id":"t","name":"pending","turns":[]},"status":"idle",$extra}""")
                    else snapshot("ready")
                }
            }
            server.start()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val controller = SessionController(api(server), scope, pollIntervalMillis = 60) { fail(it.message) }
            try {
                controller.open("t")
                withTimeout(3000) { controller.state.first { it.snapshot?.name == "ready" && !it.stale } }
                delay(600)
                assertEquals(extra, 2, reads.get())
            } finally { controller.close(); scope.cancel(); server.shutdown() }
        }
    }

    @Test fun globalInvalidationLocksUntilFreshSnapshotAndCancelsOldRead(): Unit = runBlocking {
        for (method in listOf("relay/ownershipChanged", "relay/historyInvalidated", "thread/deleted")) {
            val reads = AtomicInteger()
            val releaseFresh = CountDownLatch(1)
            val server = MockWebServer()
            val event = "event: native\ndata: {\"method\":\"$method\",\"params\":{}}\n\n"
            val chunk = event.toByteArray().size
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.startsWith("/v1/events")) return MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(":" + " ".repeat(chunk - 3) + "\n\n" + event + ":".repeat(4096))
                        .throttleBody(chunk.toLong(), 1, TimeUnit.SECONDS)
                    val count = reads.incrementAndGet()
                    if (count == 1) return snapshot("initial")
                    if (count == 2) return snapshot("obsolete").setBodyDelay(4, TimeUnit.SECONDS)
                    releaseFresh.await(5, TimeUnit.SECONDS)
                    return snapshot("fresh")
                }
            }
            server.start()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val controller = SessionController(api(server), scope, slowReadMillis = 50, pollIntervalMillis = 60) { fail(it.message) }
            try {
                controller.open("t")
                withTimeout(3000) { controller.state.first { it.snapshot?.name == "initial" && !it.stale } }
                // Whether connected already queued it or not, ensure a read predates invalidation.
                controller.refresh()
                val invalid = withTimeout(3000) { controller.state.first { it.stale } }
                assertFalse(invalid.control().canOperate)
                withTimeout(1500) { while (reads.get() < 3) delay(10) }
                releaseFresh.countDown()
                withTimeout(3000) { controller.state.first { it.snapshot?.name == "fresh" && !it.stale } }
                delay(600)
                assertEquals(3, reads.get())
            } finally { releaseFresh.countDown(); controller.close(); scope.cancel(); server.shutdown() }
        }
    }

    @Test fun acceptedReceiptForVisibleIdleThreadTriggersReconciliation(): Unit = runBlocking {
        val reads = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                return if (reads.incrementAndGet() == 1) snapshot("initial") else MockResponse().setBody(
                    """{"thread":{"id":"t","name":"accepted","turns":[{"id":"turn","items":[{"id":"message","clientId":"s","type":"userMessage","text":"hello"}]}]},"status":"idle"}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope, pollIntervalMillis = 60) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(3000) { controller.state.first { it.snapshot != null && !it.stale } }
            delay(300)
            controller.beginSubmission("t", "s", JSONArray())
            controller.resolveSubmission("s", JSONObject().put("status", "accepted"))
            withTimeout(3000) { controller.state.first { it.snapshot?.name == "accepted" && it.pendingMessages.isEmpty() } }
            delay(400)
            assertEquals(2, reads.get())
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun interruptedStreamKeepsFallbackUntilItReconnects(): Unit = runBlocking {
        val streams = AtomicInteger()
        val reads = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) {
                    if (streams.incrementAndGet() == 1) return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("")
                    return MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setHeadersDelay(500, TimeUnit.MILLISECONDS)
                        .setBody(":".repeat(4096)).throttleBody(1, 1, TimeUnit.SECONDS)
                }
                if (request.path == "/v1/activity") return MockResponse().setBody(
                    """{"complete":true,"generation":"test","version":1,"tasks":[],"knownThreadIds":["t"],"unknownThreadIds":[]}""")
                return snapshot("read-${reads.incrementAndGet()}")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val transport = api(server)
        val sync = NodeSessionSync(transport, scope)
        val reconnected = CompletableDeferred<Int>()
        var connections = 0
        val existingSubscriber = scope.launch {
            sync.events(onInterrupted = {}).collect {
                if (it.type == "connected" && ++connections == 2) reconnected.complete(reads.get())
            }
        }
        withTimeout(1500) { while (!sync.streamInterrupted) delay(10) }
        // The chat joins after the shared stream's interruption, without replayed error events.
        val controller = SessionController(transport, scope, pollIntervalMillis = 60, sync = sync) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(1500) { while (reads.get() < 3) delay(10) }
            val beforeReconnectRead = withTimeout(5000) { reconnected.await() }
            withTimeout(3000) { controller.state.first {
                !it.stale && (it.snapshot?.name?.removePrefix("read-")?.toIntOrNull() ?: 0) > beforeReconnectRead
            } }
            // Wait for the actual connected event and its successful read, not
            // a wall-clock guess based on when MockWebServer accepted the request.
            delay(250)
            val settled = reads.get()
            delay(700)
            assertEquals(settled, reads.get())
            withTimeout(1500) { controller.state.first { it.control().canOperate } }
        } finally { controller.close(); existingSubscriber.cancel(); scope.cancel(); server.shutdown() }
    }

    @Test fun pageAccessDenialInvalidatesControlBeforeFreshHead(): Unit = runBlocking {
        val reads = AtomicInteger()
        val releaseHead = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                if (request.path!!.contains("cursor=")) return MockResponse().setResponseCode(403)
                    .setBody("""{"error":{"code":"forbidden","message":"denied"}}""")
                if (reads.incrementAndGet() > 1) releaseHead.await(5, TimeUnit.SECONDS)
                return MockResponse().setBody("""{"thread":{"id":"t","turns":[]},"status":"idle","nextCursor":"older"}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope, pollIntervalMillis = 60) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(3000) { controller.state.first { it.snapshot != null && !it.stale } }
            controller.loadEarlier()
            val invalid = withTimeout(3000) { controller.state.first { it.stale } }
            assertFalse(invalid.control().canOperate)
            releaseHead.countDown()
            withTimeout(3000) { controller.state.first { !it.stale } }
            delay(500)
            assertEquals(2, reads.get())
        } finally { releaseHead.countDown(); controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun stalePageDenialCannotRevokeNewControlOrBlockPageRetry(): Unit = runBlocking {
        val heads = AtomicInteger()
        val pages = AtomicInteger()
        val releasePage = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                if (request.path!!.contains("cursor=")) {
                    if (pages.incrementAndGet() == 1) {
                        releasePage.await(5, TimeUnit.SECONDS)
                        return MockResponse().setResponseCode(403).setBody("""{"error":{"code":"forbidden","message":"old denial"}}""")
                    }
                    return snapshot("page")
                }
                return MockResponse().setBody("""{"thread":{"id":"t","name":"head-${heads.incrementAndGet()}","turns":[]},"status":"idle","nextCursor":"older"}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope, pollIntervalMillis = 60) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(3000) { controller.state.first { it.snapshot != null && !it.stale } }
            controller.loadEarlier()
            withTimeout(1500) { while (pages.get() < 1) delay(10) }
            controller.invalidateControl()
            withTimeout(3000) { controller.state.first { it.snapshot?.name == "head-2" && !it.stale } }
            releasePage.countDown()
            withTimeout(3000) { controller.state.first { !it.loadingEarlier } }
            assertTrue(controller.state.value.control().canOperate)
            controller.loadEarlier()
            withTimeout(3000) { controller.state.first { it.earlierLoaded && !it.loadingEarlier } }
            assertEquals(2, heads.get())
            assertEquals(2, pages.get())
        } finally { releasePage.countDown(); controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun revokedEventImmediatelyInvalidatesVisiblePermission(): Unit = runBlocking {
        val reads = AtomicInteger()
        val failures = AtomicInteger()
        val server = MockWebServer()
        val event = "event: revoked\ndata: {}\n\n"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody(":" + " ".repeat(event.length - 3) + "\n\n" + event + ":".repeat(4096))
                    .throttleBody(event.length.toLong(), 1, TimeUnit.SECONDS)
                return if (reads.incrementAndGet() == 1) snapshot("initial") else snapshot("obsolete").setBodyDelay(4, TimeUnit.SECONDS)
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope) {
            assertEquals(401, (it as dev.rivo.relay.data.ApiException).status)
            failures.incrementAndGet()
        }
        try {
            controller.open("t")
            withTimeout(3000) { controller.state.first { it.snapshot != null && !it.stale } }
            withTimeout(3000) { while (failures.get() == 0) delay(10) }
            assertTrue(controller.state.value.stale)
            assertFalse(controller.state.value.control().canOperate)
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun forbiddenOrDeletedBackgroundReadStillRevokesPermission(): Unit = runBlocking {
        for (status in listOf(403, 404, 410)) {
            val reads = AtomicInteger()
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    return if (reads.incrementAndGet() == 1) snapshot("initial") else MockResponse().setResponseCode(status)
                        .setBody("""{"error":{"code":"forbidden","message":"denied"}}""")
                }
            }
            server.start()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val controller = SessionController(api(server), scope) { fail(it.message) }
            try {
                controller.open("t")
                withTimeout(3000) { controller.state.first { it.snapshot != null && !it.stale } }
                controller.refresh()
                val failed = withTimeout(3000) { controller.state.first { it.readError != null } }
                assertTrue(failed.stale)
                assertFalse(failed.control().canOperate)
            } finally { controller.close(); scope.cancel(); server.shutdown() }
        }
    }
}
