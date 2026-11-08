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
        assertEquals(RecoveryState.CONNECTING, recovery.state.value)
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
        stalled.reconnect(); advanceUntilIdle()
        assertEquals(3, attempts)
        assertEquals(RecoveryState.DISCONNECTED, stalled.state.value)
        var rejection: Throwable? = null
        val denied = ApiException(401, "unauthorized", "expired")
        val auth = ConnectionRecovery(this, { throw denied }, {}, {}, { rejection = it })
        auth.reconnect(); advanceUntilIdle()
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
            val restored = CompletableDeferred<Unit>()
            val recovery = ConnectionRecovery(scope, { api.call("/v1/node", reconnectProbe = true) },
                { api.resetTransport() }, { restored.complete(Unit) }, { restored.completeExceptionally(it) }, backoffMillis = 1)
            api.recovery = recovery
            try { api.call("/v1/submissions", "POST"); fail("Expected original operation failure") }
            catch (e: ApiException) { assertEquals(503, e.status) }
            withTimeout(2000) { restored.await() }
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
