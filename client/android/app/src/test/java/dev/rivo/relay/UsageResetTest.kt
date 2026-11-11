package dev.rivo.relay

import dev.rivo.relay.codex.CatalogController
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

class UsageResetTest {
    private class Vault : CredentialStorage {
        var value: String? = null
        override fun read(name: String) = value
        override fun save(name: String, plaintext: String) { value = plaintext }
        override fun remove(name: String) { value = null }
    }
    private fun api(server: MockWebServer) = RelayApi(server.url("/").toString())
    private suspend fun idle(c: SubmissionCoordinator) = withTimeout(5000) { c.state.first { !it.busy } }

    @Test fun countsAreAuthoritativeAndUnknownIsNotZero() {
        fun parse(resets: String) = catalogUsage(JSONObject("""{"usage":{"resetSupported":true,"rateLimitResetCredits":$resets}}"""))
        assertNull(parse("null").resetCredits)
        assertNull(parse("{}").resetCredits!!.availableCount)
        assertEquals(4, parse("""{"availableCount":4,"credits":[]}""").resetCredits!!.availableCount)
        assertNull(parse("""{"availableCount":4,"credits":null}""").resetCredits!!.credits)
        assertTrue(parse("""{"availableCount":0,"credits":[]}""").resetCredits!!.credits!!.isEmpty())
        for (value in listOf("-1", "1.5", "\"3\"")) assertNull(parse("""{"availableCount":$value}""").resetCredits!!.availableCount)
        assertFalse(catalogUsage(JSONObject("""{"usage":{"rateLimits":null}}""")).resetSupported)
        assertEquals(123L, parse("""{"availableCount":1,"credits":[{"id":"opaque","status":"available","expiresAt":123}]}""").resetCredits!!.credits!!.single().expiresAt)
    }

    @Test fun duplicateClickAndProcessRecoveryKeepTheOriginalRedemptionId() = runBlocking {
        val server = MockWebServer(); server.start()
        var scope = CoroutineScope(coroutineContext + SupervisorJob())
        val vault = Vault()
        try {
            server.enqueue(MockResponse().setBody("""{"status":"unknown"}"""))
            val first = SubmissionCoordinator(api(server), scope, vault, "node", recoveryDelayMillis = 60_000, failure = { throw it })
            first.submit(USAGE_RESET_PATH, JSONObject(), "重置额度", submissionId = "original")
            first.submit(USAGE_RESET_PATH, JSONObject(), "重置额度", submissionId = "double-click")
            idle(first)
            assertEquals(1, server.requestCount)
            assertEquals("original", JSONObject(server.takeRequest().body.readUtf8()).getString("submissionId"))
            assertNotNull(vault.value)
            scope.cancel()
            scope = CoroutineScope(coroutineContext + SupervisorJob())
            server.enqueue(MockResponse().setBody("""{"submissionId":"original","status":"unknown"}"""))
            server.enqueue(MockResponse().setBody("""{"submissionId":"original","status":"accepted","receipt":{"requestId":"original","resetOutcome":"alreadyRedeemed"}}"""))
            var resolved: String? = null
            val recovered = SubmissionCoordinator(api(server), scope, vault, "node", recoveryDelayMillis = 60_000,
                onResolved = { _, response -> resolved = response.optJSONObject("receipt")?.string("resetOutcome") }, failure = { throw it })
            recovered.reconcile(); idle(recovered)
            assertEquals("alreadyRedeemed", resolved)
            assertNull(vault.value)
            assertNull(recovered.state.value.pending)
            assertEquals("/v1/submissions/original", server.takeRequest().path)
            val reconciliation = server.takeRequest()
            assertEquals("$USAGE_RESET_PATH/reconcile", reconciliation.path)
            assertEquals("original", JSONObject(reconciliation.body.readUtf8()).getString("submissionId"))
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun malformedOrMismatchedOutcomeRetainsTheDurableId() = runBlocking {
        for (receipt in listOf("""{"requestId":"other","resetOutcome":"reset"}""", """{"requestId":"original","resetOutcome":"new-outcome"}""")) {
            val server = MockWebServer(); server.start()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val vault = Vault()
            try {
                server.enqueue(MockResponse().setBody("""{"status":"accepted","receipt":$receipt}"""))
                val c = SubmissionCoordinator(api(server), scope, vault, "node", recoveryDelayMillis = 60_000, failure = {})
                c.submit(USAGE_RESET_PATH, JSONObject(), "重置额度", submissionId = "original")
                idle(c)
                assertEquals("original", c.state.value.pending?.id)
                assertNotNull(vault.value)
            } finally { scope.cancel(); server.shutdown() }
        }
    }

    @Test fun refreshedQuotaIsAuthoritativeAndRefreshFailureDoesNotUndoSuccess() = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val catalog = CatalogController(api(server), scope, {})
            server.enqueue(MockResponse().setBody("""{"usage":{"rateLimits":{"secondary":{"usedPercent":100,"windowDurationMins":10080}},"resetSupported":true,"rateLimitResetCredits":{"availableCount":2}}}"""))
            catalog.refreshUsage(true)!!.join()
            server.enqueue(MockResponse().setResponseCode(503))
            catalog.usageResetResolved(JSONObject("""{"status":"accepted","receipt":{"resetOutcome":"reset"}}"""))
            assertEquals("额度重置成功", catalog.state.value.usageResetNotice)
            assertEquals(0, catalog.state.value.usage!!.weeklyWindow!!.remainingPercent)
            assertNotNull(catalog.state.value.usageError)
            server.enqueue(MockResponse().setBody("""{"usage":{"rateLimits":{"secondary":{"usedPercent":7,"windowDurationMins":10080}},"resetSupported":true,"rateLimitResetCredits":{"availableCount":1}}}"""))
            catalog.refreshUsage(true)!!.join()
            assertEquals(93, catalog.state.value.usage!!.weeklyWindow!!.remainingPercent)
            assertEquals(1, catalog.state.value.usage!!.resetCredits!!.availableCount)
            assertNull(catalog.state.value.usageError)
        } finally { scope.cancel(); server.shutdown() }
    }
}
