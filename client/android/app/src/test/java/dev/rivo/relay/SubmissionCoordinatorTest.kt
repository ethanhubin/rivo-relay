package dev.rivo.relay

import dev.rivo.relay.codex.SubmissionCoordinator
import dev.rivo.relay.connection.CredentialStorage
import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SubmissionCoordinatorTest {
    @Test fun disconnectedSendUsesPendingThenDefinitiveNoticeWithoutGlobalError() = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val errors = mutableListOf<Throwable>()
        try {
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody("""{"status":"failed","errorCode":"submission_not_received"}"""))
            val coordinator = SubmissionCoordinator(api(server), scope, MemoryVault(), "key", failure = { errors += it })
            coordinator.submit("/v1/threads", JSONObject(), "create") {}
            idle(coordinator)
            assertNotNull(coordinator.state.value.pending)
            assertTrue(errors.isEmpty())
            coordinator.reconcile(); idle(coordinator)
            assertNull(coordinator.state.value.pending)
            assertTrue(coordinator.state.value.notice.orEmpty().contains("节点未收到"))
            assertTrue(errors.isEmpty())
            assertEquals("POST", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
        } finally { scope.cancel(); server.shutdown() }
    }

    private class MemoryVault : CredentialStorage {
        var value: String? = null
        var failRemove = false
        override fun read(name: String) = value
        override fun save(name: String, plaintext: String) { value = plaintext }
        override fun remove(name: String) {
            if (failRemove) throw IOException("disk full")
            value = null
        }
    }
    private fun accepted() = MockResponse().setBody("""{"status":"accepted","receipt":{"threadId":"native","turnId":"turn"}}""")
    private fun api(server: MockWebServer) = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
    private suspend fun idle(coordinator: SubmissionCoordinator) = withTimeout(5000) { coordinator.state.first { !it.busy } }

    @Test fun acceptedReceiptIsNeverDowngradedWhenLocalPersistenceFails() = runBlocking {
        for (failOutcome in listOf(true, false)) {
            val server = MockWebServer(); server.start()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val vault = MemoryVault()
            val statuses = mutableListOf<String>()
            val errors = mutableListOf<Throwable>()
            var callback = 0
            var rejectOutcome = failOutcome
            vault.failRemove = !failOutcome
            try {
                server.enqueue(accepted()); server.enqueue(accepted())
                val coordinator = SubmissionCoordinator(api(server), scope, vault, "key", onResolved = { _, response ->
                    statuses += response.string("status")
                    if (rejectOutcome) throw IOException("cannot persist outcome")
                }, failure = { errors += it })
                coordinator.submit("/v1/threads", JSONObject(), "create") { callback++ }
                idle(coordinator)
                assertEquals(listOf("accepted"), statuses)
                assertNotNull(coordinator.state.value.pending)
                assertNotNull(vault.value)
                assertEquals(0, callback)
                assertEquals(1, errors.size)
                rejectOutcome = false; vault.failRemove = false
                coordinator.reconcile(); idle(coordinator)
                assertEquals(listOf("accepted", "accepted"), statuses)
                assertNull(coordinator.state.value.pending)
                assertNull(vault.value)
                assertEquals(1, callback)
                assertEquals("POST", server.takeRequest().method)
                assertEquals("GET", server.takeRequest().method)
            } finally { scope.cancel(); server.shutdown() }
        }
    }

    @Test fun conflictPreservesOriginalClaimUntilReceiptIsKnown() = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"submission_conflict","message":"claimed"}}"""))
            server.enqueue(accepted())
            val statuses = mutableListOf<String>()
            var callbacks = 0
            val coordinator = SubmissionCoordinator(api(server), scope, MemoryVault(), "key",
                onResolved = { _, response -> statuses += response.string("status") }, failure = {})
            coordinator.submit("/v1/threads", JSONObject(), "create") { callbacks++ }
            idle(coordinator)
            assertEquals(listOf("unknown"), statuses)
            assertNotNull(coordinator.state.value.pending)
            coordinator.reconcile(); idle(coordinator)
            assertEquals(listOf("unknown", "accepted"), statuses)
            assertNull(coordinator.state.value.pending)
            assertEquals(2, server.requestCount)
            assertEquals(0, callbacks)
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun continuationErrorsAreReportedWithoutUndoingAcceptanceOrNextOwner() = runBlocking {
        for (recover in listOf(false, true)) {
            val server = MockWebServer(); server.start()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val vault = MemoryVault()
            val errors = mutableListOf<Throwable>()
            val outcomes = mutableListOf<Pair<String, String>>()
            try {
                if (recover) server.enqueue(MockResponse().setBody("""{"status":"unknown"}"""))
                server.enqueue(accepted())
                server.enqueue(MockResponse().setBody("""{"status":"unknown"}"""))
                val coordinator = SubmissionCoordinator(api(server), scope, vault, "key",
                    onResolved = { pending, response -> outcomes += pending.id to response.string("status") }, failure = { errors += it })
                coordinator.submit("/v1/threads", JSONObject(), "create", submissionId = "first") {
                    coordinator.submit("/v1/threads/native/turns", JSONObject(), "send", submissionId = "second")
                    throw IOException("continuation failed after starting next operation")
                }
                if (recover) { idle(coordinator); coordinator.reconcile() }
                withTimeout(5000) { coordinator.state.first { !it.busy && it.pending?.id == "second" } }
                assertEquals("second", JSONObject(vault.value!!).getString("id"))
                assertEquals(1, errors.size)
                assertEquals(listOf("accepted"), outcomes.filter { it.first == "first" }.map { it.second }.filter { it != "unknown" })
                assertEquals("second" to "unknown", outcomes.last())
                assertEquals(if (recover) 3 else 2, server.requestCount)
            } finally { scope.cancel(); server.shutdown() }
        }
    }

    @Test fun reconciliationStorageErrorsAreVisibleAndKeepTheWaitRecoverable() = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val vault = MemoryVault()
        val errors = mutableListOf<Throwable>()
        try {
            server.enqueue(MockResponse().setBody("""{"status":"unknown"}"""))
            server.enqueue(accepted()); server.enqueue(accepted())
            var rejectReceipt = true
            var completed = 0
            val coordinator = SubmissionCoordinator(api(server), scope, vault, "key", onResolved = { _, response ->
                if (response.string("status") == "accepted" && rejectReceipt) throw IOException("cannot save receipt")
            }, failure = { errors += it })
            coordinator.submit("/v1/threads", JSONObject(), "create") { completed++ }
            idle(coordinator)
            coordinator.reconcile(); idle(coordinator)
            assertEquals(1, errors.size)
            assertNotNull(coordinator.state.value.pending)
            assertNotNull(vault.value)
            assertEquals(0, completed)
            rejectReceipt = false
            coordinator.reconcile(); idle(coordinator)
            assertNull(vault.value)
            assertEquals(1, completed)
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun missingSubmissionReconciliationUnlocksWithoutReplayingCreate() = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val vault = MemoryVault()
        try {
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"unavailable","message":"lost"}}"""))
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"submission_not_found","message":"missing"}}"""))
            server.enqueue(MockResponse().setBody("""{"status":"failed","errorCode":"submission_not_received"}"""))
            val statuses = mutableListOf<String>()
            var callbacks = 0
            val coordinator = SubmissionCoordinator(api(server), scope, vault, "key",
                onResolved = { _, response -> statuses += response.string("status") }, failure = {})
            coordinator.submit("/v1/threads", JSONObject(), "create", submissionId = "missing") { callbacks++ }
            idle(coordinator)
            assertNotNull(coordinator.state.value.pending)
            coordinator.reconcile(); idle(coordinator)
            assertEquals(listOf("unknown", "failed"), statuses)
            assertNull(coordinator.state.value.pending)
            assertNull(vault.value)
            assertEquals(0, callbacks)
            assertTrue(coordinator.state.value.notice.orEmpty().contains("草稿已保留"))
            assertEquals(listOf("/v1/threads", "/v1/submissions/missing", "/v1/submissions/missing/reconcile"),
                (1..3).map { server.takeRequest().path })
            assertEquals(3, server.requestCount)
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun cancellationBeforeNetworkStillRetiresDurablePreparation() = runBlocking {
        val server = MockWebServer(); server.start()
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val vault = MemoryVault()
        val prepared = CompletableDeferred<Unit>()
        val statuses = mutableListOf<String>()
        try {
            val coordinator = SubmissionCoordinator(api(server), scope, vault, "key", onResolved = { _, response ->
                // Real recording yields to IO; it must survive cancellation of the node scope.
                withContext(Dispatchers.IO) { delay(10) }
                statuses += response.string("status")
            }, failure = { fail(it.message) })
            coordinator.submit("/v1/threads", JSONObject(), "create", beforeSend = {
                prepared.complete(Unit); awaitCancellation()
            })
            withTimeout(5000) { prepared.await() }
            job.cancelAndJoin()
            assertEquals(listOf("failed"), statuses)
            assertNull(vault.value)
            assertNull(coordinator.state.value.pending)
            assertFalse(coordinator.state.value.busy)
            assertEquals(0, server.requestCount)
        } finally { scope.cancel(); server.shutdown() }
    }
}
