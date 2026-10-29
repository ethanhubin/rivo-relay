package dev.rivo.relay

import dev.rivo.relay.codex.SessionController
import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SessionControllerTest {
    // MockWebServer binds IPv4 here. Pin the fixture address: canceled localhost
    // routes must not send the next non-retrying call to an unbound IPv6 socket.
    private fun api(server: MockWebServer) = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
    private fun snapshot(id: String, name: String, cursor: String? = null, item: String = "current"): MockResponse {
        val body = JSONObject("""{"thread":{"id":"$id","name":"$name","turns":[{"id":"turn-$item","items":[{"id":"$item","type":"agentMessage","text":"$name"}]}]},"requests":[],"settings":{},"status":"idle"}""")
        body.put("nextCursor", cursor ?: JSONObject.NULL)
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }
    private fun error(status: Int) = MockResponse().setResponseCode(status)
        .setBody("""{"error":{"code":"native_rejected","message":"读取暂时失败"}}""")
    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) { assertTrue(latch.await(5, TimeUnit.SECONDS)) }

    @Test fun recoveredSnapshotClearsReadErrorWithoutClearingUnrelatedWriteFailure() = runBlocking {
        val server = MockWebServer()
        val healthy = AtomicBoolean(false)
        val methods = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                methods += request.method.orEmpty()
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                return if (healthy.get()) snapshot("t", "recovered") else error(409)
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var globalWriteError: String? = "An earlier write was rejected"
        val forwarded = mutableListOf<Throwable>()
        val controller = SessionController(api(server), scope) { forwarded += it; globalWriteError = it.message }
        try {
            controller.open("t")
            withTimeout(5_000) { controller.state.first { it.readError != null } }
            assertTrue(controller.state.value.stale)
            assertTrue(forwarded.isEmpty())
            healthy.set(true); controller.refresh()
            val recovered = withTimeout(5_000) { controller.state.first { it.snapshot?.name == "recovered" && !it.stale } }
            assertNull(recovered.readError)
            assertEquals("An earlier write was rejected", globalWriteError)
            assertTrue(methods.all { it == "GET" })
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun currentAuthorizationFailureStillReachesNodeOwner() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path!!.startsWith("/v1/events")) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else error(401)
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val unauthorized = CompletableDeferred<Throwable>()
        val controller = SessionController(api(server), scope) { unauthorized.complete(it) }
        try {
            controller.open("t")
            assertEquals(401, (withTimeout(5_000) { unauthorized.await() } as ApiException).status)
            assertNull(controller.state.value.readError)
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun lateReadFromSameThreadBeforeReopenCannotReplaceNewGeneration(): Unit = runBlocking {
        val server = MockWebServer()
        val firstRead = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val oldReturned = CountDownLatch(1)
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                val count = reads.incrementAndGet()
                if (count == 1) {
                    firstRead.countDown(); releaseOld.await(15, TimeUnit.SECONDS); oldReturned.countDown()
                    return snapshot("t", "obsolete")
                }
                return snapshot("t", if (count == 2) "current" else "current-confirmed")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope) { fail(it.message) }
        try {
            controller.open("t"); await(firstRead)
            controller.open("t")
            withTimeout(5_000) { controller.state.first { it.snapshot?.name == "current" } }
            releaseOld.countDown(); await(oldReturned)
            controller.refresh()
            withTimeout(5_000) { controller.state.first { it.snapshot?.name == "current-confirmed" } }
            assertEquals("current-confirmed", controller.state.value.snapshot?.name)
            assertNull(controller.state.value.readError)
            // Returning from a system picker keeps the snapshot while showing a normal sync state.
            controller.pause(); controller.open("t")
            assertEquals("current-confirmed", controller.state.value.snapshot?.name)
            assertTrue(controller.state.value.loading)
            withTimeout(5_000) { controller.state.first { !it.loading && !it.stale } }
        } finally { releaseOld.countDown(); controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun streamResetCancelsOldPaginationAndCannotRestoreStaleCursor() = runBlocking {
        val server = MockWebServer()
        val allowReset = CountDownLatch(1)
        val pageStarted = CountDownLatch(1)
        val releasePage = CountDownLatch(1)
        val pageReturned = CountDownLatch(1)
        val reset = AtomicBoolean(false)
        val streams = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) {
                    if (streams.incrementAndGet() > 1) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    allowReset.await(5, TimeUnit.SECONDS); reset.set(true)
                    return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: reset\ndata: {}\n\n")
                }
                if (request.path!!.contains("cursor=")) {
                    pageStarted.countDown(); releasePage.await(5, TimeUnit.SECONDS); pageReturned.countDown()
                    return snapshot("t", "obsolete page", "stale-next", "older")
                }
                return if (reset.get()) snapshot("t", "after-reset") else snapshot("t", "before-reset", "older-page")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(5_000) { controller.state.first { it.historyCursor == "older-page" } }
            controller.loadEarlier(); await(pageStarted)
            allowReset.countDown()
            withTimeout(5_000) { controller.state.first { it.snapshot?.name == "after-reset" && !it.loadingEarlier } }
            releasePage.countDown(); await(pageReturned)
            controller.refresh()
            val state = withTimeout(5_000) { controller.state.first { it.snapshot?.name == "after-reset" && !it.stale } }
            assertNull(state.historyCursor)
            assertFalse(state.earlierLoaded)
            assertEquals(listOf("current"), state.snapshot!!.messages.map { it.id })
        } finally { allowReset.countDown(); releasePage.countDown(); controller.close(); scope.cancel(); server.shutdown() }
    }
}
