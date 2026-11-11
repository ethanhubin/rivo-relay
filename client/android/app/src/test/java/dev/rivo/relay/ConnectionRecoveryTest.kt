package dev.rivo.relay

import dev.rivo.relay.data.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRecoveryTest {
    @Test fun networkSwitchCancelsStalledProbeWithoutWaitingForTimeout() = runTest {
        var probes = 0
        var restored = 0
        var cancelled = false
        val recovery = ConnectionRecovery(this, {
            if (++probes == 1) try { awaitCancellation() } finally { cancelled = true }
        }, {}, { restored++ }, { throw it })
        recovery.failed(IOException("old route"))
        runCurrent()
        recovery.networkAvailable()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(2, probes)
        assertEquals(1, restored)
        assertEquals(RecoveryState.CONNECTED, recovery.state.value)
    }

    @Test fun networkSwitchInterruptsBackoffAndRecoversExhaustedRounds() = runTest {
        var offline = true
        var probes = 0
        var restored = 0
        val recovery = ConnectionRecovery(this, { probes++; if (offline) throw IOException("offline") },
            {}, { restored++ }, { throw it })
        recovery.reconnect(manual = true)
        runCurrent()
        offline = false
        recovery.networkAvailable()
        runCurrent()
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(2, probes)
        assertEquals(RecoveryState.CONNECTED, recovery.state.value)
        offline = true
        recovery.reconnect(manual = true)
        advanceUntilIdle()
        assertEquals(RecoveryState.DISCONNECTED, recovery.state.value)
        offline = false
        recovery.networkAvailable()
        runCurrent()
        assertEquals(RecoveryState.CONNECTED, recovery.state.value)
        assertEquals(2, restored)
    }

    @Test fun directNetworkSwitchSynchronizesEvenWhenConnectionStillLooksHealthy() = runTest {
        var resets = 0
        var restored = 0
        val recovery = ConnectionRecovery(this, {}, { resets++ }, { restored++ }, { throw it })
        recovery.networkAvailable()
        runCurrent()
        assertEquals(1, resets)
        assertEquals(1, restored)
        assertEquals(RecoveryState.CONNECTED, recovery.state.value)
    }

    @Test fun foregroundSynchronizesHealthyNodeWithoutResettingTransport() = runTest {
        var probes = 0
        var resets = 0
        var refreshes = 0
        val recovery = ConnectionRecovery(this, { probes++ }, { resets++ }, { refreshes++ }, { throw it })
        recovery.foreground()
        recovery.foreground()
        advanceUntilIdle()
        assertEquals(1, probes)
        assertEquals(0, resets)
        assertEquals(1, refreshes)
        assertEquals(RecoveryState.CONNECTED, recovery.state.value)
    }

    @Test fun explicitRefreshKeepsHealthyTransportAndWaitsForSynchronization() = runTest {
        var resets = 0
        var refreshed = false
        val recovery = ConnectionRecovery(this, {}, { resets++ }, {
            delay(100)
            refreshed = true
        }, { throw it })
        recovery.refresh()
        assertTrue(refreshed)
        assertEquals(0, resets)
        assertEquals(RecoveryState.CONNECTED, recovery.state.value)
    }

    @Test fun explicitRefreshRecoversDisconnectedNode() = runTest {
        var offline = true
        var resets = 0
        val recovery = ConnectionRecovery(this, { if (offline) throw IOException("offline") },
            { resets++ }, {}, { throw it })
        recovery.refresh()
        assertEquals(RecoveryState.DISCONNECTED, recovery.state.value)
        offline = false
        recovery.refresh()
        assertEquals(RecoveryState.CONNECTED, recovery.state.value)
        assertEquals(2, resets)
    }

    @Test fun slowSessionReadsDoNotQueueCatalogReads(): Unit = runBlocking {
        val server = MockWebServer()
        val started = java.util.concurrent.CountDownLatch(5)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/threads/")) {
                    started.countDown()
                    return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                }
                return MockResponse().setBody("{}")
            }
        }
        server.start()
        val jobs = mutableListOf<Job>()
        try {
            val api = RelayApi(server.url("/").toString())
            repeat(5) { index -> jobs += launch(Dispatchers.Default) { api.call("/v1/threads/$index") } }
            assertTrue(withContext(Dispatchers.IO) { started.await(3, java.util.concurrent.TimeUnit.SECONDS) })
            withTimeout(2000) {
                api.call("/v1/projects")
                api.call("/v1/threads?projectId=test")
            }
        } finally {
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            server.shutdown()
        }
    }

    @Test fun truncatedHistoryKeepsConcurrentRequestsAlive() = runBlocking {
        val server = MockWebServer()
        val probeStarted = CompletableDeferred<Unit>()
        val releaseProbe = CompletableDeferred<Unit>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                "/v1/threads/old" -> MockResponse().setBody("x".repeat(100000))
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                else -> MockResponse().setBody("{}")
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val api = RelayApi(server.url("/").toString())
            val recovery = ConnectionRecovery(scope, {
                probeStarted.complete(Unit); releaseProbe.await()
                api.call("/v1/node", reconnectProbe = true)
            }, { error("Must not reset shared transport") }, { error("Must not reload failed history") }, {})
            api.recovery = recovery
            try { api.call("/v1/threads/old"); fail("Expected truncated body") }
            catch (_: IOException) { }
            withTimeout(3000) { probeStarted.await() }
            assertEquals(RecoveryState.CONNECTED, recovery.state.value)
            for (path in listOf("/v1/projects", "/v1/todos", "/v1/threads/other")) api.call(path)
            releaseProbe.complete(Unit)
            withTimeout(3000) { while (server.requestCount < 5) delay(10) }
            assertEquals(RecoveryState.CONNECTED, recovery.state.value)
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun unavailableHistoryDoesNotDisconnectNode() = runTest {
        var probes = 0
        val recovery = ConnectionRecovery(this, { probes++ }, {}, {}, {})
        val error = ApiException(503, "native_history_unavailable", "历史暂不可用")
        assertFalse(isConnectionFailure(error))
        recovery.failed(error)
        advanceUntilIdle()
        assertEquals(RecoveryState.CONNECTED, recovery.state.value)
        assertEquals(0, probes)
        assertTrue(isConnectionFailure(ApiException(503, "node_offline", "节点离线")))
        assertTrue(isConnectionFailure(IOException("connection reset")))
    }

    @Test fun savingWaitsForProbeThenDownloadsExactlyOnce() = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseProbe = CompletableDeferred<Unit>()
        try {
            val api = RelayApi(server.url("/").toString())
            val recovery = ConnectionRecovery(scope,
                { releaseProbe.await(); api.call("/v1/node", reconnectProbe = true) },
                { api.resetTransport() }, {}, {})
            api.recovery = recovery
            recovery.reconnect(manual = true)
            val output = java.io.ByteArrayOutputStream()
            val save = async { api.downloadFile("/fixture/qr.png", output) { _, _ -> } }
            delay(50)
            assertFalse(save.isCompleted)
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("original-image"))
            releaseProbe.complete(Unit)
            withTimeout(3000) { save.await() }
            assertEquals("/v1/node", server.takeRequest().path)
            assertTrue(server.takeRequest().path!!.startsWith("/v1/files/download?"))
            assertEquals(2, server.requestCount)
            assertEquals("original-image", output.toString("UTF-8"))
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun downloadWaitsForForegroundRecoveryAndExplicitRetryCanRecover() = runTest {
        var available = true
        var attempts = 0
        val recovery = ConnectionRecovery(this, { attempts++; delay(20); if (!available) throw IOException("offline") }, {}, {}, {}, backoffMillis = 1)
        recovery.reconnect(manual = true)
        val download = async { recovery.awaitDownloadConnection() }
        runCurrent()
        assertFalse(download.isCompleted)
        advanceUntilIdle()
        download.await()
        assertEquals(1, attempts)
        available = false
        recovery.reconnect(manual = true)
        advanceUntilIdle()
        assertEquals(RecoveryState.DISCONNECTED, recovery.state.value)
        available = true
        recovery.awaitDownloadConnection()
        assertEquals(RecoveryState.CONNECTED, recovery.state.value)
        assertEquals(5, attempts)
    }

    @Test fun retriesAreBoundedAndForegroundOrManualCanStartAnotherRound() = runTest {
        var attempts = 0
        var available = false
        var restored = 0
        val recovery = ConnectionRecovery(this, { attempts++; if (!available) throw IOException("offline") }, {},
            { restored++ }, { error("Unexpected error") })
        recovery.failed(IOException("offline"))
        recovery.reconnect() // Concurrent callers share one attempt sequence.
        assertEquals(RecoveryState.CONNECTED, recovery.state.value) // Diagnostic probe is non-disruptive.
        advanceUntilIdle()
        assertEquals(3, attempts)
        assertEquals(RecoveryState.DISCONNECTED, recovery.state.value)
        recovery.failed(IOException("still offline")); recovery.reconnect()
        advanceUntilIdle()
        assertEquals(3, attempts)
        available = true
        recovery.reconnect(manual = true) // Foreground and the settings button use this path.
        advanceUntilIdle()
        assertEquals(4, attempts)
        assertEquals(1, restored)
        assertEquals(RecoveryState.CONNECTED, recovery.state.value)
    }

    @Test fun stalledAttemptsExpireAndAuthorizationErrorsAreNotHidden() = runTest {
        var attempts = 0
        val stalled = ConnectionRecovery(this, { attempts++; awaitCancellation() }, {}, {}, {}, timeoutMillis = 10, backoffMillis = 1)
        stalled.reconnect(manual = true); advanceUntilIdle()
        assertEquals(3, attempts)
        assertEquals(RecoveryState.DISCONNECTED, stalled.state.value)
        var rejection: Throwable? = null
        val denied = ApiException(401, "unauthorized", "expired")
        val auth = ConnectionRecovery(this, { throw denied }, {}, {}, { rejection = it })
        auth.reconnect(manual = true); advanceUntilIdle()
        assertSame(denied, rejection)
        assertEquals(RecoveryState.DISCONNECTED, auth.state.value)
    }

    @Test fun disconnectedTransportBlocksReadsAndWritesButAllowsOnlyExplicitProbe() = runBlocking {
        val server = MockWebServer()
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val api = RelayApi(server.url("/").toString())
            val recovery = ConnectionRecovery(scope, { throw IOException("offline") }, {}, {}, {}, backoffMillis = 1)
            api.recovery = recovery
            recovery.reconnect()
            withTimeout(2000) { while (recovery.state.value != RecoveryState.DISCONNECTED) delay(5) }
            for (method in listOf("GET", "POST")) {
                try { api.call("/v1/projects", method); fail("Must not send while disconnected") }
                catch (_: ConnectionPausedException) { }
            }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("{}"))
            api.call("/v1/node", reconnectProbe = true)
            assertEquals("GET", server.takeRequest().method)
            assertEquals(1, server.requestCount)
            assertEquals(RecoveryState.DISCONNECTED, recovery.state.value)
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun failedMutationStartsReadOnlyRecoveryAndIsNeverReplayed() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val api = RelayApi(server.url("/").toString())
            val probed = CompletableDeferred<Unit>()
            val recovery = ConnectionRecovery(scope, { api.call("/v1/node", reconnectProbe = true); probed.complete(Unit) },
                { error("Healthy node must not reset") }, { error("Failed mutation must not refresh") }, { probed.completeExceptionally(it) }, backoffMillis = 1)
            api.recovery = recovery
            try { api.call("/v1/submissions", "POST"); fail("Expected original operation failure") }
            catch (e: ApiException) { assertEquals(503, e.status) }
            withTimeout(2000) { probed.await() }
            assertEquals("POST", server.takeRequest().method)
            val probe = server.takeRequest()
            assertEquals("GET", probe.method)
            assertEquals("/v1/node", probe.path)
            assertEquals(2, server.requestCount)
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun successfulProbeRestoresOnceWithoutRetryingBusinessOperations() = runTest {
        var probes = 0
        var restores = 0
        var resets = 0
        val recovery = ConnectionRecovery(this, { if (++probes == 1) throw IOException("temporary") }, { resets++ }, { restores++ }, {})
        recovery.failed(IOException("timeout")); advanceUntilIdle()
        assertEquals(2, probes)
        assertEquals(1, resets)
        assertEquals(1, restores)
        assertEquals(RecoveryState.CONNECTED, recovery.state.value)
    }
}
