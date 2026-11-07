package dev.rivo.relay

import dev.rivo.relay.codex.SessionController
import dev.rivo.relay.codex.control
import dev.rivo.relay.codex.displayControl
import dev.rivo.relay.codex.showTakeover
import dev.rivo.relay.codex.ControlState
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

    private fun revisionSnapshot(name: String, revision: String, cursor: String?, item: String, readOnly: Boolean = false): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject("""{"thread":{"id":"t","name":"$name","turns":[{"id":"turn-$item","items":[{"id":"$item","type":"agentMessage","text":"$name"}]}]},"requests":[],"settings":{},"status":"idle"}""")
            .put("nativeRevision", revision).put("nextCursor", cursor ?: JSONObject.NULL).put("readOnly", readOnly).toString())

    @Test fun historyRestoreKeepsOlderPagesWhenLatestTurnHasNoItems() = runBlocking {
        val server = MockWebServer()
        val revision = AtomicInteger(1)
        val refreshed = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                val version = revision.get()
                return when (request.requestUrl!!.queryParameter("cursor")) {
                    "older" -> revisionSnapshot("older $version", "r$version", "oldest", "older")
                    "oldest" -> revisionSnapshot("oldest $version", "r$version", null, "oldest")
                    else -> MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject("""{"thread":{"id":"t","name":"head $version${if (refreshed.get()) " refreshed" else ""}","turns":[{"id":"empty-failure","status":"failed","error":{"message":"failure $version"},"items":[]}]},"requests":[],"settings":{},"status":"idle"}""")
                        .put("nativeRevision", "r$version").put("nextCursor", "older").toString())
                }
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(5000) { controller.state.first { it.snapshot?.name == "head 1" && !it.stale } }
            controller.loadEarlier()
            withTimeout(5000) { controller.state.first { it.snapshot?.messages?.size == 1 && !it.loadingEarlier } }
            controller.loadEarlier()
            withTimeout(5000) { controller.state.first { it.snapshot?.messages?.size == 2 && !it.loadingEarlier } }
            revision.set(2); controller.refresh()
            val restored = withTimeout(5000) { controller.state.first { it.snapshot?.name == "head 2" && !it.loadingEarlier } }
            val snapshot = restored.snapshot!!
            assertEquals(listOf("oldest", "older"), snapshot.messages.map { it.id })
            assertEquals(listOf("oldest 2", "older 2"), snapshot.messages.map { it.text })
            assertEquals(listOf("turn-oldest", "turn-older", "empty-failure"), snapshot.turnSettings.map { it.turnId })
            assertEquals("failed", snapshot.turnSettings.last().status)
            assertEquals("failure 2", snapshot.turnSettings.last().error?.string("message"))
            assertEquals("notice:empty-failure", nativeTimelineRows(snapshot.messages, turnSettings = snapshot.turnSettings).last().key)
            assertEquals("r2", snapshot.nativeRevision)
            assertTrue(restored.earlierLoaded)
            assertNull(restored.historyCursor)
            assertFalse(restored.stale)
            // A subsequent unchanged-revision head must retain the restored pages too.
            refreshed.set(true); controller.refresh()
            val repeated = withTimeout(5000) { controller.state.first { it.snapshot?.name == "head 2 refreshed" && !it.loadingEarlier } }
            assertEquals(listOf("oldest", "older"), repeated.snapshot!!.messages.map { it.id })
            assertEquals(listOf("turn-oldest", "turn-older", "empty-failure"), repeated.snapshot.turnSettings.map { it.turnId })
            assertEquals("failure 2", repeated.snapshot.turnSettings.last().error?.string("message"))
            assertTrue(repeated.earlierLoaded)
            assertNull(repeated.historyCursor)
            assertFalse(repeated.stale)
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun longHistoryRestorePublishesHeadFirstAndDoesNotOverwriteNewControl() = runBlocking {
        val server = MockWebServer()
        val revision = AtomicInteger(1)
        val readOnly = AtomicBoolean(false)
        val pageStarted = CountDownLatch(1)
        val releasePage = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                val version = revision.get()
                if (request.path!!.contains("cursor=")) {
                    if (version == 2) { pageStarted.countDown(); releasePage.await(5, TimeUnit.SECONDS) }
                    return revisionSnapshot("old page", "r$version", null, "older")
                }
                return revisionSnapshot(if (readOnly.get()) "external head" else "head $version", "r$version", "page-$version", "current", readOnly.get())
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope, slowReadMillis = 100, readTimeoutMillis = 3000) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(5000) { controller.state.first { it.snapshot?.name == "head 1" && !it.stale } }
            controller.loadEarlier()
            withTimeout(5000) { controller.state.first { it.earlierLoaded && !it.loadingEarlier } }
            revision.set(2); controller.refresh(); await(pageStarted)
            val fresh = controller.state.value
            assertEquals("head 2", fresh.snapshot?.name)
            assertFalse(fresh.stale)
            assertTrue(fresh.control().canOperate)
            assertTrue(fresh.loadingEarlier)
            // Pagination remains stalled while a later head changes ownership.
            readOnly.set(true); controller.refresh()
            withTimeout(5000) { controller.state.first { it.snapshot?.name == "external head" && !it.stale } }
            releasePage.countDown()
            val restored = withTimeout(5000) { controller.state.first { !it.loadingEarlier && it.snapshot?.messages?.size == 2 } }
            assertEquals("external head", restored.snapshot?.name)
            assertFalse(restored.control().canOperate)
            assertFalse(restored.stale)
            assertEquals(listOf("older", "current"), restored.snapshot!!.messages.map { it.id })
            assertEquals("external head", restored.snapshot.messages.last().text)
        } finally { releasePage.countDown(); controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun lateBackgroundPageCannotCrossNativeRevision() = runBlocking {
        val server = MockWebServer()
        val revision = AtomicInteger(1)
        val obsoleteStarted = CountDownLatch(1)
        val releaseObsolete = CountDownLatch(1)
        val obsoleteReturned = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                val version = revision.get()
                if (request.path!!.contains("cursor=")) {
                    if (version == 2) {
                        obsoleteStarted.countDown(); releaseObsolete.await(5, TimeUnit.SECONDS); obsoleteReturned.countDown()
                        return revisionSnapshot("obsolete", "r2", "obsolete-cursor", "obsolete")
                    }
                    return revisionSnapshot("older $version", "r$version", null, "older")
                }
                return revisionSnapshot("head $version", "r$version", "page-$version", "current")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(5000) { controller.state.first { it.snapshot?.name == "head 1" && !it.stale } }
            controller.loadEarlier()
            withTimeout(5000) { controller.state.first { it.earlierLoaded && !it.loadingEarlier } }
            revision.set(2); controller.refresh(); await(obsoleteStarted)
            revision.set(3); controller.refresh()
            withTimeout(5000) { controller.state.first { it.snapshot?.name == "head 3" && !it.loadingEarlier && it.snapshot.messages.size == 2 } }
            releaseObsolete.countDown(); await(obsoleteReturned)
            delay(100)
            assertEquals("r3", controller.state.value.snapshot?.nativeRevision)
            assertEquals(listOf("older", "current"), controller.state.value.snapshot!!.messages.map { it.id })
            assertNull(controller.state.value.historyCursor)
            assertFalse(controller.state.value.stale)
        } finally { releaseObsolete.countDown(); controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun historyRestoreTimeoutKeepsFreshControlAndAllowsPageRetry() = runBlocking {
        val server = MockWebServer()
        val revision = AtomicInteger(1)
        val recovered = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                val version = revision.get()
                if (request.path!!.contains("cursor=")) {
                    if (version == 2 && !recovered.get()) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    return revisionSnapshot("older", "r$version", null, "older")
                }
                return revisionSnapshot("head $version", "r$version", "page-$version", "current")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope, slowReadMillis = 100, readTimeoutMillis = 400) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(5000) { controller.state.first { it.snapshot?.name == "head 1" && !it.stale } }
            controller.loadEarlier()
            withTimeout(5000) { controller.state.first { it.earlierLoaded && !it.loadingEarlier } }
            revision.set(2); controller.refresh()
            val failedPage = withTimeout(5000) { controller.state.first { it.historyError != null && !it.loadingEarlier } }
            assertEquals("head 2", failedPage.snapshot?.name)
            assertFalse(failedPage.stale)
            assertTrue(failedPage.control().canOperate)
            recovered.set(true); controller.loadEarlier()
            withTimeout(5000) { controller.state.first { it.snapshot?.messages?.size == 2 && !it.loadingEarlier } }
            assertNull(controller.state.value.historyError)
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun writerDisconnectInvalidatesControlAndApprovalsBeforeRefresh() = runBlocking {
        val server = MockWebServer()
        val releaseFresh = CountDownLatch(1)
        val reads = AtomicInteger()
        val event = "event: native\ndata: {\"method\":\"relay/writerDisconnected\",\"params\":{\"threadIds\":[\"t\"],\"requestIds\":[\"old\"]}}\n\n"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBodyDelay(600, TimeUnit.MILLISECONDS).setBody(event + ":".repeat(4096))
                    .throttleBody(event.toByteArray().size.toLong(), 3, TimeUnit.SECONDS)
                if (reads.incrementAndGet() > 1) releaseFresh.await(5, TimeUnit.SECONDS)
                return MockResponse().setBody("""{"thread":{"id":"t","name":"head","turns":[]},"requests":[{"key":"old","method":"approval"}],"settings":{},"status":"idle"}""")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(5000) { controller.state.first { it.snapshot?.requests?.isNotEmpty() == true && !it.stale } }
            val disconnected = withTimeout(5000) { controller.state.first { it.controlNotice?.contains("writer 已断开") == true } }
            assertFalse(disconnected.control().canOperate)
            assertTrue(disconnected.snapshot!!.requests.isEmpty())
        } finally { releaseFresh.countDown(); controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun otherWriterAndMetadataDisconnectDoNotInvalidateOrRefreshCurrentThread() = runBlocking {
        for (targets in listOf("[\"other\"]", "[]")) {
            val server = MockWebServer()
            val reads = AtomicInteger()
            val event = "event: native\ndata: {\"method\":\"relay/writerDisconnected\",\"params\":{\"threadIds\":$targets}}\n\n"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.startsWith("/v1/events")) return MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBodyDelay(600, TimeUnit.MILLISECONDS).setBody(event + ":".repeat(4096))
                        .throttleBody(event.toByteArray().size.toLong(), 3, TimeUnit.SECONDS)
                    reads.incrementAndGet()
                    return snapshot("t", "head")
                }
            }
            server.start()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val controller = SessionController(api(server), scope) { fail(it.message) }
            try {
                controller.open("t")
                withTimeout(5000) { controller.state.first { it.snapshot != null && !it.stale } }
                delay(350)
                val before = reads.get()
                delay(650)
                assertEquals(before, reads.get())
                assertTrue(controller.state.value.control().canOperate)
                assertNull(controller.state.value.controlNotice)
            } finally { controller.close(); scope.cancel(); server.shutdown() }
        }
    }

    @Test fun slowReadLocksInputAndTimeoutOnlyRecoversFromFreshRead() = runBlocking {
        val server = MockWebServer()
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                return when (reads.incrementAndGet()) {
                    1 -> snapshot("t", "initial")
                    2 -> snapshot("t", "late").setBodyDelay(500, TimeUnit.MILLISECONDS)
                    else -> snapshot("t", "fresh")
                }
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope, slowReadMillis = 50, readTimeoutMillis = 250) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(5000) { controller.state.first { it.snapshot?.name == "initial" && !it.stale } }
            controller.refresh()
            val slow = withTimeout(5000) { controller.state.first { it.controlNotice?.contains("响应较慢") == true } }
            assertFalse(slow.control().canOperate)
            assertEquals("initial", slow.snapshot?.name)
            assertEquals(ControlState.WRITABLE, slow.displayControl().state)
            assertFalse(slow.showTakeover())
            controller.refresh()
            val fresh = withTimeout(5000) { controller.state.first { it.snapshot?.name == "fresh" && !it.stale } }
            assertTrue(fresh.control().canOperate)
            assertNull(fresh.controlNotice)
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun responseStartedBeforeInvalidationCannotRestorePermission() = runBlocking {
        val server = MockWebServer()
        val reads = AtomicInteger()
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val thirdStarted = CountDownLatch(1)
        val releaseThird = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                return when (reads.incrementAndGet()) {
                    1 -> snapshot("t", "initial")
                    2 -> { secondStarted.countDown(); releaseSecond.await(5, TimeUnit.SECONDS); snapshot("t", "obsolete") }
                    else -> { thirdStarted.countDown(); releaseThird.await(5, TimeUnit.SECONDS); snapshot("t", "fresh") }
                }
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(5000) { controller.state.first { it.snapshot?.name == "initial" && !it.stale } }
            controller.refresh(); await(secondStarted)
            controller.invalidateControl()
            releaseSecond.countDown(); await(thirdStarted)
            assertTrue(controller.state.value.stale)
            assertFalse(controller.state.value.control().canOperate)
            assertEquals("initial", controller.state.value.snapshot?.name)
            releaseThird.countDown()
            withTimeout(5000) { controller.state.first { it.snapshot?.name == "fresh" && !it.stale } }
            Unit
        } finally { releaseSecond.countDown(); releaseThird.countDown(); controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun transportResetDoesNotLeaveSessionLoadingForever() = runBlocking {
        val server = MockWebServer()
        val started = CountDownLatch(1)
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                if (reads.incrementAndGet() == 1) {
                    started.countDown()
                    return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                return snapshot("t", "reconnected")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val transport = api(server)
        val controller = SessionController(transport, scope) { fail(it.message) }
        try {
            controller.open("t")
            await(started)
            transport.resetTransport()
            controller.refresh()
            val recovered = withTimeout(5000) { controller.state.first { it.snapshot?.name == "reconnected" && !it.stale } }
            assertFalse(recovered.loading)
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun liveOutputUpdatesWithoutAnotherHistoryRequest() = runBlocking {
        val server = MockWebServer()
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBodyDelay(1, TimeUnit.SECONDS)
                    .setBody("event: native\ndata: {\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":\"t\",\"turnId\":\"turn-current\",\"itemId\":\"current\",\"relayOffset\":2,\"delta\":\" there\"}}\n\n")
                reads.incrementAndGet()
                return snapshot("t", "Hi")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(5_000) { controller.state.first { it.snapshot?.messages?.singleOrNull()?.text == "Hi there" } }
            // Initial read and stream-connected calibration only; the delta needs no GET.
            assertTrue("unexpected history reads: ${reads.get()}", reads.get() <= 2)
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun offlineRouteCancelsReadsAndReconnectKeepsSnapshotUntilFresh() = runBlocking {
        val server = MockWebServer()
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                reads.incrementAndGet()
                return snapshot("t", "saved")
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope) { fail(it.message) }
        try {
            controller.open("t")
            withTimeout(5000) { controller.state.first { it.snapshot?.name == "saved" && !it.stale } }
            controller.open("t", online = false)
            withTimeout(5000) { controller.state.first { it.stale && !it.loading } }
            assertEquals("saved", controller.state.value.snapshot?.name)
            val baseline = reads.get()
            controller.refresh()
            delay(250)
            assertEquals(baseline, reads.get())
            controller.open("t", online = true)
            withTimeout(5000) { controller.state.first { !it.stale } }
            assertTrue(reads.get() > baseline)
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun cachedHistoryStaysReadOnlyUntilAnAuthoritativeReadSucceeds() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("relay-history-test").toFile()
        val store = ConversationStore(directory)
        store.saveSnapshot("t", JSONObject("""{"thread":{"id":"t","name":"cached","turns":[]},"requests":[{"key":"old-approval","method":"approval"}],"nextCursor":"old-cursor","settings":{}}"""))
        val server = MockWebServer()
        val healthy = AtomicBoolean(false)
        val pages = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                if (request.path!!.contains("cursor=")) pages.incrementAndGet()
                return if (healthy.get()) snapshot("t", "fresh", "fresh-cursor") else error(503)
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = SessionController(api(server), scope, store = store) { fail(it.message) }
        try {
            controller.open("t")
            val cached = withTimeout(5_000) { controller.state.first { it.snapshot?.name == "cached" && it.readError != null } }
            assertTrue(cached.stale)
            assertTrue(cached.snapshot!!.requests.isEmpty())
            controller.loadEarlier()
            assertEquals(0, pages.get())
            healthy.set(true); controller.refresh()
            val fresh = withTimeout(5_000) { controller.state.first { it.snapshot?.name == "fresh" && !it.stale } }
            assertEquals("fresh-cursor", fresh.historyCursor)
        } finally { controller.close(); scope.cancel(); server.shutdown(); directory.deleteRecursively() }
    }

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
            assertFalse(controller.state.value.loading)
            assertTrue(controller.state.value.stale)
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

    @Test fun streamResetCancelsOldPaginationAndCannotRestoreStaleCursor() =
        assertHistoryInvalidationCancelsPagination("event: reset\ndata: {}\n\n")

    @Test fun nativeReprojectionCancelsOldPaginationAndCannotRestoreStaleCursor() =
        assertHistoryInvalidationCancelsPagination("event: native\ndata: {\"method\":\"relay/historyInvalidated\",\"params\":{\"threadId\":\"t\",\"reason\":\"native_reprojection\"}}\n\n")

    private fun assertHistoryInvalidationCancelsPagination(invalidationEvent: String) = runBlocking {
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
                    return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(invalidationEvent)
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
