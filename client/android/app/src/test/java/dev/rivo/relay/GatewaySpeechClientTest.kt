package dev.rivo.relay

import dev.rivo.relay.connection.*
import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class GatewaySpeechClientTest {
    private fun setup(server: MockWebServer): NodeRoutes {
        val store = java.security.KeyStore.getInstance("PKCS12").apply {
            load(GatewaySpeechClientTest::class.java.getResourceAsStream("/gateway-test.p12"), "test-only".toCharArray())
        }
        val manager = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "test-only".toCharArray())
        }
        val tls = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(manager.keyManagers, null, null) }
        server.useHttps(tls.socketFactory, false)
        val cert = store.getCertificate(store.aliases().nextElement())
        val pin = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
        return NodeRoutes(direct = "http://127.0.0.1:1", relay = server.url("/relay/" + "a".repeat(32)).toString(),
            pin = pin, mode = ConnectionMode.DIRECT)
    }
    private fun grant(token: String = "speech1.test.signature", now: Long = System.currentTimeMillis() / 1000) =
        JSONObject().put("token", token).put("expiresAt", now + 180)

    @Test fun audioUsesVpsEvenWhenChatIsDirectWithoutNodeProbesOrPhoneToken() = runBlocking {
        MockWebServer().use { server ->
            val routes = setup(server)
            server.enqueue(MockResponse().setBody("""{"speech":{"streaming":true}}"""))
            server.enqueue(MockResponse().setBody("""{"stream_id":"one"}"""))
            server.enqueue(MockResponse().setBody("""{"text":"语音"}"""))
            server.enqueue(MockResponse().setBody("""{"text":"完整语音"}"""))
            var issues = 0
            val client = GatewaySpeechClient(routes, Dns.SYSTEM, { issues++; grant() })
            assertTrue(client.capability().getJSONObject("speech").getBoolean("streaming"))
            val context = speechContext("Merge、棋子", "编辑生成器")
            client.start(context)
            val pcm = byteArrayOf(0x34, 0x12, -1, -1)
            assertEquals("语音", client.chunk("one", pcm).getString("text"))
            client.finish("one")
            val requests = (1..4).map { server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals(context, JSONObject(requests[1].body.readUtf8()).getString("context"))
            assertEquals(1, issues)
            assertEquals(4, server.requestCount)
            assertTrue(requests.all { it.path!!.startsWith("/relay/" + "a".repeat(32) + "/v1/speech-direct/") })
            assertTrue(requests.all { it.getHeader("Authorization") == "Bearer speech1.test.signature" })
            assertArrayEquals(pcm, requests[2].body.readByteArray())
            assertEquals("application/octet-stream", requests[2].getHeader("Content-Type"))
        }
    }

    @Test fun relayApiOnlyUsesChatNodeToIssueGrantThenAudioSurvivesNodeDisconnect() = runBlocking {
        MockWebServer().use { node -> MockWebServer().use { gateway ->
            val routes = setup(gateway).copy(direct = node.url("/").toString().trimEnd('/'))
            node.enqueue(MockResponse().setBody("{}")) // authenticated route probe
            node.enqueue(MockResponse().setBody(grant().toString()))
            for (body in listOf("""{"speech":{"streaming":true,"sampleRate":16000,"encoding":"pcm_s16le"}}""",
                """{"stream_id":"one"}""", """{"text":"语音"}""", """{"text":"完整语音"}"""))
                gateway.enqueue(MockResponse().setBody(body))
            val repository = SpeechRepository(RelayApi(routes.direct, "phone-pairing-token", Dns.SYSTEM, routes))
            assertTrue(repository.capability().available)
            repository.start(null)
            assertEquals("/v1/node", node.takeRequest().path)
            val issuance = node.takeRequest()
            assertEquals("/v1/speech/access", issuance.path)
            assertEquals("Bearer phone-pairing-token", issuance.getHeader("Authorization"))
            node.shutdown()
            assertEquals("语音", repository.chunk("one", byteArrayOf(0, 0)))
            assertEquals("完整语音", repository.finish("one"))
            assertEquals(2, node.requestCount)
            assertEquals(4, gateway.requestCount)
            repeat(4) { assertEquals("Bearer speech1.test.signature", gateway.takeRequest().getHeader("Authorization")) }
        } }
    }

    @Test fun freshGrantForNewRecordingDoesNotReplaceExistingStreamGrant() = runBlocking {
        MockWebServer().use { server ->
            val routes = setup(server)
            for (body in listOf("""{"stream_id":"one"}""", """{"stream_id":"two"}""", "{}", "{}", "{}"))
                server.enqueue(MockResponse().setBody(body))
            var now = 1000L; var issues = 0
            val client = GatewaySpeechClient(routes, Dns.SYSTEM, { issues++; grant("speech1.test.$issues", now) }, { now })
            client.start(null)
            now += 31
            client.start(null)
            client.chunk("one", byteArrayOf(0, 0))
            client.finish("one")
            client.finish("two", cancel = true)
            val tokens = (1..5).map { server.takeRequest().getHeader("Authorization") }
            assertEquals(listOf("Bearer speech1.test.1", "Bearer speech1.test.2", "Bearer speech1.test.1",
                "Bearer speech1.test.1", "Bearer speech1.test.2"), tokens)
        }
    }

    @Test fun failedAudioWriteIsNeverReplayedOrRoutedThroughChat() = runBlocking {
        MockWebServer().use { server ->
            val routes = setup(server)
            server.enqueue(MockResponse().setBody("""{"stream_id":"one"}"""))
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"unavailable","message":"offline"}}"""))
            val client = GatewaySpeechClient(routes, Dns.SYSTEM, { grant() })
            client.start(null)
            try { client.chunk("one", byteArrayOf(0, 0)); fail("expected failure") } catch (_: ApiException) { }
            try { client.chunk("one", byteArrayOf(0, 0)); fail("stream should be retired") } catch (_: IOException) { }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun wrongCertificatePinAndRedirectNeverLeakSpeechGrant() = runBlocking {
        MockWebServer().use { server ->
            val routes = setup(server)
            val invalid = GatewaySpeechClient(routes.copy(pin = "0".repeat(64)), Dns.SYSTEM, { grant() })
            try { invalid.capability(); fail("wrong pin accepted") } catch (_: IOException) { }
            assertEquals(0, server.requestCount)
            MockWebServer().use { other ->
                server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/stolen")).setBody("{}"))
                val client = GatewaySpeechClient(routes, Dns.SYSTEM, { grant() })
                try { client.capability(); fail("redirect accepted") } catch (_: ApiException) { }
                assertEquals(0, other.requestCount)
            }
        }
    }
}
