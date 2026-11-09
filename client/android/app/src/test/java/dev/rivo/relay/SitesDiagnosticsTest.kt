package dev.rivo.relay
import dev.rivo.relay.data.*
import org.junit.Test
import org.junit.Assert.*
class SitesDiagnosticsTest {
    @Test fun identifiesFailuresWithoutLeakingPayload() {
        val trace = "a".repeat(32)
        assertTrue(sitesDiagnostic(trace, "failed", 15, ConnectionPausedException()).contains("connection_paused"))
        assertTrue(sitesDiagnostic(trace, "failed", 15, java.net.SocketTimeoutException("token")).contains("transport_timeout"))
        val record = sitesDiagnostic(trace, "failed", 15, ApiException(503,"sites_unavailable","private-token"))
        assertTrue(record.contains("request_id=$trace"))
        assertTrue(record.contains("sites_unavailable"))
        assertFalse(record.contains("private-token"))
        assertFalse(sitesDiagnostic("secret", "secret", 0, ApiException(503,"secret","secret")).contains("secret"))
    }
    @Test fun attachesCorrelationHeaderToSitesRequest() = kotlinx.coroutines.runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{}"))
        server.start()
        try {
            RelayApi(server.url("/").toString().trimEnd('/')).call("/v1/sites", diagnosticRequestId = "a".repeat(32))
            assertEquals("a".repeat(32), server.takeRequest().getHeader("X-Rivo-Request-Id"))
        } finally { server.shutdown() }
    }
}
