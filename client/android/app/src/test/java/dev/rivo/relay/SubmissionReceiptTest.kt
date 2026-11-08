package dev.rivo.relay

import dev.rivo.relay.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SubmissionReceiptTest {
    private fun api(server: MockWebServer) = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
    private fun missing(code: String = "submission_not_found") = MockResponse().setResponseCode(404)
        .setBody("""{"error":{"code":"$code","message":"missing"}}""")

    @Test fun missingReceiptIsFencedBeforeItBecomesDefinitive() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(missing())
            server.enqueue(MockResponse().setBody("""{"status":"failed","errorCode":"submission_not_received"}"""))
            assertEquals("failed", api(server).reconcileSubmission("create-id").string("status"))
            val read = server.takeRequest(); val fence = server.takeRequest()
            assertEquals("GET", read.method)
            assertEquals("/v1/submissions/create-id", read.path)
            assertEquals("POST", fence.method)
            assertEquals("/v1/submissions/create-id/reconcile", fence.path)
        } finally { server.shutdown() }
    }

    @Test fun acceptedOrUnknownReceiptNeverIssuesMutation() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            for (status in listOf("accepted", "unknown", "pending")) {
                server.enqueue(MockResponse().setBody("""{"status":"$status"}"""))
                assertEquals(status, api(server).reconcileSubmission("id").string("status"))
                assertEquals("GET", server.takeRequest().method)
            }
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun unsupportedFenceAndUnrelated404RemainUncertain() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(missing())
            server.enqueue(missing("not_found"))
            try { api(server).reconcileSubmission("id"); fail("Must retain unresolved submission") }
            catch (e: ApiException) { assertEquals(404, e.status) }
            assertEquals(2, server.requestCount)
            server.enqueue(missing("not_found"))
            try { api(server).reconcileSubmission("other"); fail("Must propagate unrelated 404") }
            catch (e: ApiException) { assertEquals("not_found", e.code) }
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
}
