package dev.rivo.relay

import dev.rivo.relay.connection.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URLEncoder

class NodeTransportTest {
    private fun client(base: String, routes: NodeRoutes, observer: ConnectionStatusObserver? = null): OkHttpClient {
        RouteMemory.read = { null }; RouteMemory.write = { _, _ -> }
        val probe = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        return probe.newBuilder().apply { observer?.let { addInterceptor(it) } }.addInterceptor(NodeRouteInterceptor(base, routes, probe)).build()
    }
    @Test fun selfSignedGatewayRequiresExactPin() {
        val store = java.security.KeyStore.getInstance("PKCS12").apply {
            load(NodeTransportTest::class.java.getResourceAsStream("/gateway-test.p12"), "test-only".toCharArray())
        }
        val manager = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test-only".toCharArray()) }
        val tls = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(manager.keyManagers, null, null) }
        val certificate = store.getCertificate(store.aliases().nextElement())
        val pin = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        MockWebServer().use { gateway ->
            gateway.useHttps(tls.socketFactory, false)
            gateway.enqueue(MockResponse().setBody("trusted"))
            val routes = NodeRoutes(relay = gateway.url("/relay/" + "a".repeat(32)).toString(), pin = pin)
            OkHttpClient.Builder().gatewayTrust(routes).build().newCall(Request.Builder().url(gateway.url("/health")).build())
                .execute().use { assertEquals("trusted", it.body!!.string()) }
            assertThrows(IOException::class.java) {
                OkHttpClient.Builder().gatewayTrust(routes.copy(pin = "0".repeat(64))).build()
                    .newCall(Request.Builder().url(gateway.url("/health")).build()).execute()
            }
        }
    }
    @Test fun versionTwoQrRetainsBothRoutesAndPin() {
        val direct = NodeEndpoints.DIRECT
        val relay = "https://134.175.156.219:80/relay/" + "a".repeat(32)
        val fields = mapOf("v" to "2", "code" to "ABCD", "node" to "12345678-1234-1234-1234-123456789abc", "direct" to direct,
            "relay" to relay, "pin" to "b".repeat(64), "updates" to "1")
        val input = PairingParser.parse(direct + "/pair#" + fields.entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") })
        assertEquals(relay, input.routes!!.relay)
        assertTrue(input.updateSource)
        assertFalse(input.toString().contains("ABCD"))
    }
    @Test fun rejectsMissingGatewayPin() {
        assertThrows(IllegalArgumentException::class.java) { NodeRoutes(relay = "https://1.2.3.4/relay/" + "a".repeat(32)).validated() }
    }
    @Test fun fallsBackBeforeWriteAndPreservesBodyAndPath() {
        MockWebServer().use { relay ->
            relay.enqueue(MockResponse().setBody("{}")) // route probe
            relay.enqueue(MockResponse().setBody("done"))
            val base = "http://127.0.0.1:1"
            val routes = NodeRoutes(base, relay.url("/relay/node").toString(), "")
            val request = Request.Builder().url("$base/v1/threads?id=a%2Bb").post(RequestBody.create(null, "write-once")).build()
            client(base, routes).newCall(request).execute().use { assertEquals("done", it.body!!.string()) }
            assertEquals("/relay/node/health", relay.takeRequest().path)
            val write = relay.takeRequest()
            assertEquals("/relay/node/v1/threads?id=a%2Bb", write.path)
            assertEquals("write-once", write.body.readUtf8())
        }
    }
    @Test fun neverReplaysWriteAfterServerReceivedIt() {
        MockWebServer().use { direct -> MockWebServer().use { relay ->
            direct.enqueue(MockResponse().setBody("{}"))
            direct.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val base = direct.url("/").toString().trimEnd('/')
            val transport = client(base, NodeRoutes(base, relay.url("/").toString().trimEnd('/')))
            assertThrows(IOException::class.java) { transport.newCall(Request.Builder().url("$base/v1/write").post(RequestBody.create(null, "once")).build()).execute() }
            assertEquals(2, direct.requestCount)
            assertEquals(0, relay.requestCount)
        } }
    }
    @Test fun readFailureCanSwitchToOtherRoute() {
        MockWebServer().use { direct -> MockWebServer().use { relay ->
            direct.enqueue(MockResponse().setBody("{}"))
            direct.enqueue(MockResponse().setResponseCode(503))
            relay.enqueue(MockResponse().setBody("{}"))
            relay.enqueue(MockResponse().setBody("recovered"))
            val base = direct.url("/").toString().trimEnd('/')
            val routes = NodeRoutes(base, relay.url("/").toString().trimEnd('/'))
            val observer = ConnectionStatusObserver(routes, base)
            client(base, routes, observer).newCall(Request.Builder().url("$base/v1/read").build())
                .execute().use { assertEquals("recovered", it.body!!.string()) }
            assertEquals(routes.relay, observer.status.value.endpoint)
            assertEquals(200, observer.status.value.httpCode)
            assertTrue(observer.status.value.checkedAt > 0)
        } }
    }
    @Test fun cancelledReadKeepsVerifiedRouteForNextRequest() {
        MockWebServer().use { direct -> MockWebServer().use { relay ->
            direct.enqueue(MockResponse().setBody("{}"))
            direct.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            direct.enqueue(MockResponse().setBody("next"))
            // A regression consumes this extra response after an unnecessary probe.
            direct.enqueue(MockResponse().setBody("next"))
            val base = direct.url("/").toString().trimEnd('/')
            val transport = client(base, NodeRoutes(base, relay.url("/").toString().trimEnd('/')))
            val call = transport.newCall(Request.Builder().url("$base/v1/obsolete").build())
            val finished = java.util.concurrent.CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<IOException>()
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { failure.set(e); finished.countDown() }
                override fun onResponse(call: Call, response: Response) { response.close(); finished.countDown() }
            })
            assertEquals("/health", direct.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/v1/obsolete", direct.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            call.cancel()
            assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertNotNull(failure.get())
            transport.newCall(Request.Builder().url("$base/v1/next").build()).execute().use {
                assertEquals("next", it.body!!.string())
            }
            assertEquals("/v1/next", direct.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals(3, direct.requestCount)
            assertEquals(0, relay.requestCount)
        } }
    }
    @Test fun actualReadFailureStillInvalidatesVerifiedRoute() {
        MockWebServer().use { direct ->
            direct.enqueue(MockResponse().setBody("{}"))
            direct.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            direct.enqueue(MockResponse().setBody("{}"))
            direct.enqueue(MockResponse().setBody("recovered"))
            val base = direct.url("/").toString().trimEnd('/')
            val transport = client(base, NodeRoutes(direct = base, mode = ConnectionMode.DIRECT))
            assertThrows(IOException::class.java) {
                transport.newCall(Request.Builder().url("$base/v1/read").build()).execute().close()
            }
            transport.newCall(Request.Builder().url("$base/v1/next").build()).execute().use {
                assertEquals("recovered", it.body!!.string())
            }
            assertEquals(listOf("/health", "/v1/read", "/health", "/v1/next"),
                (1..4).map { direct.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path })
        }
    }
    @Test fun directModeDoesNotFallBack() {
        MockWebServer().use { relay ->
            assertThrows(IOException::class.java) {
                client("http://127.0.0.1:1", NodeRoutes("http://127.0.0.1:1", relay.url("/").toString(), mode=ConnectionMode.DIRECT))
                    .newCall(Request.Builder().url("http://127.0.0.1:1/v1/read").build()).execute()
            }
            assertEquals(0, relay.requestCount)
        }
    }
    @Test fun failedProbeDoesNotReportSavedRouteAsConnected() {
        val base = "http://127.0.0.1:1"
        val routes = NodeRoutes(direct = base, mode = ConnectionMode.DIRECT)
        val observer = ConnectionStatusObserver(routes, base)
        assertThrows(IOException::class.java) { client(base, routes, observer)
            .newCall(Request.Builder().url("$base/v1/node").build()).execute() }
        assertTrue(observer.status.value.failed)
        assertEquals("", observer.status.value.endpoint)
        assertNull(observer.status.value.requestDurationMs)
    }
    @Test fun businessDurationIncludesResponseWait() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val observer = ConnectionStatusObserver(null, base)
            server.enqueue(MockResponse().setHeadersDelay(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            OkHttpClient.Builder().addInterceptor(observer).build()
                .newCall(Request.Builder().url("$base/v1/node").build()).execute().close()
            assertTrue(observer.status.value.requestDurationMs!! >= 100)
        }
    }
    @Test fun legacyConnectionReportsAuthorizationFailure() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val observer = ConnectionStatusObserver(null, base)
            server.enqueue(MockResponse().setResponseCode(401))
            OkHttpClient.Builder().addInterceptor(observer).build()
                .newCall(Request.Builder().url("$base/v1/node").build()).execute().close()
            assertEquals(base, observer.status.value.endpoint)
            assertEquals(401, observer.status.value.httpCode)
            assertFalse(observer.status.value.failed)
        }
    }

}
