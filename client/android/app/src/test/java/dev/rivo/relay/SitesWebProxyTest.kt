package dev.rivo.relay

import dev.rivo.relay.ui.sites.SitesWebProxy
import org.junit.Assert.*
import org.junit.Test

class SitesWebProxyTest {
    @Test fun acceptsOnlyBoundedHttpsConnect() {
        assertEquals("site.example.com", SitesWebProxy.connectHost("CONNECT site.example.com:443 HTTP/1.1\r\nHost: site.example.com\r\n\r\n"))
        for (request in listOf("GET https://example.com/ HTTP/1.1\r\n\r\n", "CONNECT example.com:80 HTTP/1.1\r\n\r\n", "CONNECT user@example.com:443 HTTP/1.1\r\n\r\n", "CONNECT example.com:443 HTTP/1.1\r\n", "CONNECT example.com:443 HTTP/1.1\r\n\r\nextra")) {
            assertNull(request, SitesWebProxy.connectHost(request))
        }
    }
    @Test fun downstreamRemainsOpenWithoutBrowserWrites() {
        val store = java.security.KeyStore.getInstance("PKCS12").apply {
            load(SitesWebProxyTest::class.java.getResourceAsStream("/gateway-test.p12"), "test-only".toCharArray())
        }
        val manager = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "test-only".toCharArray())
        }
        val tls = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(manager.keyManagers, null, null) }
        val certificate = store.getCertificate(store.aliases().nextElement())
        val pin = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        val opened = java.util.concurrent.CompletableFuture<okhttp3.WebSocket>()
        okhttp3.mockwebserver.MockWebServer().use { gateway ->
            gateway.useHttps(tls.socketFactory, false)
            gateway.enqueue(okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) { opened.complete(webSocket) }
            }))
            val routes = dev.rivo.relay.connection.NodeRoutes(relay = gateway.url("/relay/" + "a".repeat(32)).toString(), pin = pin)
            SitesWebProxy(routes, "test-device-token").use { proxy ->
                java.net.Socket("127.0.0.1", proxy.port).use { browser ->
                    browser.soTimeout = 40_000
                    browser.getOutputStream().write("CONNECT example.com:443 HTTP/1.1\r\n\r\n".toByteArray())
                    val input = browser.getInputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val value = input.read(); assertTrue(value >= 0); headers.append(value.toChar())
                    }
                    assertTrue(headers.startsWith("HTTP/1.1 200"))
                    val tunnel = opened.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    Thread.sleep(31_000)
                    assertTrue(tunnel.send(okio.ByteString.of(42)))
                    assertEquals(42, input.read())
                    val request = gateway.takeRequest()
                    assertEquals("Bearer test-device-token", request.getHeader("Authorization"))
                    assertEquals("/relay/" + "a".repeat(32) + "/v1/sites/tunnel?host=example.com", request.path)
                }
            }
        }
    }
}
