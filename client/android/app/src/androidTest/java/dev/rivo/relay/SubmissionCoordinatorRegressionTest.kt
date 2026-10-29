package dev.rivo.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.codex.BatchSubmission
import dev.rivo.relay.codex.SubmissionCoordinator
import dev.rivo.relay.connection.CredentialStore
import dev.rivo.relay.data.RelayApi
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Keystore + loopback fixtures; no saved node credentials or native operations are used. */
@RunWith(AndroidJUnit4::class)
class SubmissionCoordinatorRegressionTest {
    @Test fun batchContinuesAfterExplicitReconciliationAndKeepsTheNextOperationOwner() = runBlocking {
        val server = MockWebServer()
        val vault = CredentialStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val key = "instrumentation-batch-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val changed = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val releaseSecond = CountDownLatch(1)
        val lookups = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" -> MockResponse().setBody(JSONObject().put("status", "accepted")
                    .put("receipt", JSONObject().put("threadId", if (lookups.incrementAndGet() == 1) "a" else "b")).toString())
                request.path == "/v1/threads/b/archive" -> {
                    releaseSecond.await(10, TimeUnit.SECONDS)
                    MockResponse().setBody("""{"status":"unknown","receipt":{"threadId":"b"}}""")
                }
                else -> MockResponse().setBody("""{"status":"unknown"}""")
            }
        }
        server.start()
        try {
            val api = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
            val coordinator = SubmissionCoordinator(api, scope, vault, key) { failure.set(it) }
            withContext(Dispatchers.Main) {
                coordinator.submitBatch(listOf(
                    BatchSubmission("/v1/threads/a/archive", JSONObject().put("archived", true), "归档 a"),
                    BatchSubmission("/v1/threads/b/archive", JSONObject().put("archived", true), "归档 b")
                )) { changed.incrementAndGet() }
            }
            withTimeout(10_000) { while (coordinator.state.value.busy) delay(10) }
            val first = requireNotNull(coordinator.state.value.pending)
            assertEquals(0, changed.get())
            withContext(Dispatchers.Main) { coordinator.reconcile() }
            withTimeout(10_000) { while (server.requestCount < 3) delay(10) }
            assertTrue("The first reconciliation must not clear the next write's busy flag", coordinator.state.value.busy)
            releaseSecond.countDown()
            withTimeout(10_000) { while (coordinator.state.value.busy || coordinator.state.value.pending?.id == first.id) delay(10) }
            val second = requireNotNull(coordinator.state.value.pending)
            assertNotEquals(first.id, second.id)
            assertEquals("b", second.threadId)
            assertEquals(1, changed.get())
            assertEquals(second.id, JSONObject(requireNotNull(vault.read(key))).getString("id"))
            withContext(Dispatchers.Main) { coordinator.reconcile() }
            withTimeout(10_000) { while (coordinator.state.value.busy || changed.get() < 2) delay(10) }
            withContext(Dispatchers.Main) { coordinator.reconcile() }
            assertNull(coordinator.state.value.pending)
            assertNull(vault.read(key))
            assertNull(failure.get())
            assertEquals(2, changed.get())
            assertEquals(4, server.requestCount)
            val requests = (0 until 4).map { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
            assertEquals(listOf("POST", "GET", "POST", "GET"), requests.map { it.method })
            assertEquals(listOf("/v1/threads/a/archive", "/v1/submissions/${first.id}", "/v1/threads/b/archive", "/v1/submissions/${second.id}"), requests.map { it.path })
            assertEquals(first.id, JSONObject(requests[0].body.readUtf8()).getString("submissionId"))
            assertEquals(second.id, JSONObject(requests[2].body.readUtf8()).getString("submissionId"))
        } finally { releaseSecond.countDown(); scope.cancel(); vault.remove(key); server.shutdown() }
    }

    @Test fun failedReconciliationNeverStartsTheRemainingBatch() = runBlocking {
        val server = MockWebServer()
        val vault = CredentialStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val key = "instrumentation-batch-failed-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val changed = AtomicInteger()
        server.start()
        try {
            val api = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
            val coordinator = SubmissionCoordinator(api, scope, vault, key) {}
            server.enqueue(MockResponse().setBody("""{"status":"unknown"}"""))
            withContext(Dispatchers.Main) {
                coordinator.submitBatch(listOf(
                    BatchSubmission("/v1/threads/a/pin", JSONObject().put("pinned", true), "置顶 a"),
                    BatchSubmission("/v1/threads/b/pin", JSONObject().put("pinned", true), "置顶 b")
                )) { changed.incrementAndGet() }
            }
            withTimeout(10_000) { while (coordinator.state.value.busy) delay(10) }
            server.enqueue(MockResponse().setBody("""{"status":"failed"}"""))
            withContext(Dispatchers.Main) { coordinator.reconcile() }
            withTimeout(10_000) { while (coordinator.state.value.busy) delay(10) }
            withContext(Dispatchers.Main) { coordinator.reconcile() }
            assertNull(coordinator.state.value.pending)
            assertEquals(0, changed.get())
            assertEquals(2, server.requestCount)
        } finally { scope.cancel(); vault.remove(key); server.shutdown() }
    }
}
