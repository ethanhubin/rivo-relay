package dev.rivo.relay.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class NodeAppUpdatesTest {
    @Test fun managedGatewayUsesPublicHttpsWithoutChangingOtherDeployments() {
        fun routes(host: String, port: Int) = dev.rivo.relay.connection.NodeRoutes(
            relay = "https://$host:$port/relay/" + "a".repeat(32), pin = "b".repeat(64))
        assertEquals("https://134.175.156.219:8443", NodeAppUpdates.publicUpdateOrigin(routes("134.175.156.219", 80)))
        assertEquals("https://134.175.156.219:8443", NodeAppUpdates.publicUpdateOrigin(routes("134.175.156.219", 8443)))
        assertNull(NodeAppUpdates.publicUpdateOrigin(routes("134.175.156.219", 9443)))
        assertNull(NodeAppUpdates.publicUpdateOrigin(routes("other.example", 80)))
        assertNull(NodeAppUpdates.publicUpdateOrigin(dev.rivo.relay.connection.NodeRoutes(direct = "http://100.124.150.1:18767")))
    }
    private val bytes = "apk fixture".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun manifest() = JSONObject().put("version", "0.2.13").put("versionCode", 25).put("sha256", hash)
        .put("size", bytes.size).put("apkPath", "/v1/app-updates/apks/$hash.apk").put("notes", "node update")
    @Test fun candidateDeclarationIsReadButLegacyAndMalformedStayUnknown() = runBlocking {
        MockWebServer().use { server ->
            val updates = NodeAppUpdates(server.url("/").toString().trimEnd('/'))
            for ((declaration, expected) in listOf(
                JSONObject("{apiVersions:[1,3]}") to setOf(1,3),
                JSONObject("{apiVersions:['1']}") to null,
                JSONObject() to null)) {
                server.enqueue(MockResponse().setBody(manifest().put("compatibility", declaration).toString()))
                assertEquals(expected, updates.check("0.2.12")!!.apiVersions)
            }
            server.enqueue(MockResponse().setBody(manifest().toString()))
            assertNull(updates.check("0.2.12")!!.apiVersions)
        }
    }
    @Test fun gatewayUpdatesUseOriginWithoutNodeProbeOrCredentials() = runBlocking {
        val store = java.security.KeyStore.getInstance("PKCS12").apply {
            load(NodeAppUpdatesTest::class.java.getResourceAsStream("/gateway-test.p12"), "test-only".toCharArray())
        }
        val manager = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "test-only".toCharArray())
        }
        val tls = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(manager.keyManagers, null, null) }
        val pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate(store.aliases().nextElement()).encoded)
            .joinToString("") { "%02x".format(it) }
        MockWebServer().use { gateway ->
            gateway.useHttps(tls.socketFactory, false)
            val routes = dev.rivo.relay.connection.NodeRoutes(
                relay = gateway.url("/relay/" + "a".repeat(32)).toString(), pin = pin)
            gateway.enqueue(MockResponse().setBody(manifest().toString()))
            val updates = NodeAppUpdates.gateway(routes)
            val release = updates.check("0.2.12")!!
            assertEquals(gateway.url("/v1/app-updates/apks/$hash.apk").toString(), release.apkUrl)
            val request = gateway.takeRequest()
            assertEquals("/v1/app-updates/latest", request.path)
            assertNull(request.getHeader("Authorization"))
            assertEquals(1, gateway.requestCount)
            try {
                NodeAppUpdates.gateway(routes.copy(pin = "0".repeat(64))).check("0.2.12")
                fail("Wrong gateway certificate must fail")
            } catch (_: java.io.IOException) { }
        }
        Unit
    }
    @Test fun slowHealthyConnectionRemainsOpenUntilComplete() = runBlocking {
        val payload = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val ranges = mutableListOf<String?>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/v1/app-updates/latest") return MockResponse().setBody(manifest()
                        .put("sha256", sha).put("size", payload.size).put("apkPath", "/v1/app-updates/apks/$sha.apk").toString())
                    val range = request.getHeader("Range")
                    ranges.add(range)
                    if (range == null) return MockResponse().setBody(okio.Buffer().write(payload))
                        .throttleBody(16384, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                        .setBody(okio.Buffer().write(payload.copyOfRange(start, payload.size)))
                }
            }
            val dir = Files.createTempDirectory("slow-update").toFile()
            try {
                val updates = NodeAppUpdates(server.url("/").toString().trimEnd('/'))
                val progress = mutableListOf<Long>()
                val release = updates.check("0.2.12")!!
                assertArrayEquals(payload, updates.download(release, dir) { done, _ -> progress.add(done) }.readBytes())
                assertEquals(listOf<String?>(null), ranges)
                assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
            } finally { dir.deleteRecursively() }
        }
        Unit
    }
    @Test fun interruptedDownloadResumesFromReceivedBytes() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("updates").toFile()
        val ranges = mutableListOf<String?>()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/v1/app-updates/latest") return MockResponse().setBody(manifest().toString())
                    val range = request.getHeader("Range")
                    ranges.add(range)
                    if (range == null) return MockResponse().setBody(okio.Buffer().write(bytes))
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-${bytes.size - 1}/${bytes.size}")
                        .setBody(okio.Buffer().write(bytes.copyOfRange(start, bytes.size)))
                }
            }
            val updates = NodeAppUpdates(server.url("/").toString().trimEnd('/'))
            val release = updates.check("0.2.12")!!
            val progress = mutableListOf<Long>()
            assertArrayEquals(bytes, updates.download(release, dir) { done, _ -> progress.add(done) }.readBytes())
            assertEquals(2, ranges.size)
            assertTrue(ranges[1]!!.matches(Regex("bytes=[1-9][0-9]*-")))
            assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        } finally { server.shutdown(); dir.deleteRecursively() }
    }
    @Test fun rejectsIncorrectResumeRange() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("updates").toFile()
        try {
            val updates = NodeAppUpdates(server.url("/").toString().trimEnd('/'))
            server.enqueue(MockResponse().setBody(manifest().toString()))
            val release = updates.check("0.2.12")!!
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-1/${bytes.size}").setBody("xx"))
            try { updates.download(release, dir); fail() } catch (_: java.io.IOException) { }
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { server.shutdown(); dir.deleteRecursively() }
    }
    @Test fun interruptedDownloadRetriesAndVerifiesCompleteFile() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("updates").toFile()
        try {
            val updates = NodeAppUpdates(server.url("/").toString().trimEnd('/'))
            server.enqueue(MockResponse().setBody(manifest().toString()))
            val release = updates.check("0.2.12")!!
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            assertArrayEquals(bytes, updates.download(release, dir).readBytes())
            assertEquals(3, server.requestCount)
            assertEquals(1, dir.listFiles()!!.size)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }
    @Test fun nodeCheckAndVerifiedDownloadDoNotUseCredentialsOrGithub() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("updates").toFile()
        try {
            val updates = NodeAppUpdates(server.url("/").toString().trimEnd('/'))
            server.enqueue(MockResponse().setBody(manifest().toString()))
            val release = updates.check("0.2.12")!!
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val progress = mutableListOf<Pair<Long, Long>>()
            assertArrayEquals(bytes, updates.download(release, dir) { done, total -> progress.add(done to total) }.readBytes())
            assertEquals(0L to bytes.size.toLong(), progress.first())
            assertEquals(bytes.size.toLong() to bytes.size.toLong(), progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> a.first <= b.first })
            assertEquals("/v1/app-updates/latest", server.takeRequest().path)
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertEquals("/v1/app-updates/apks/$hash.apk", request.path)
            server.enqueue(MockResponse().setBody(manifest().toString()))
            assertNull(updates.check("0.2.13"))
        } finally { server.shutdown(); dir.deleteRecursively() }
        Unit
    }
    @Test fun rejectsForeignPathsRedirectsAndTamperedApk() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("updates").toFile()
        try {
            val updates = NodeAppUpdates(server.url("/").toString().trimEnd('/'))
            server.enqueue(MockResponse().setBody(manifest().put("apkPath", "https://foreign/app.apk").toString()))
            try { updates.check("0.2.12"); fail() } catch (_: java.io.IOException) { }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://github.com"))
            try { updates.check("0.2.12"); fail() } catch (_: java.io.IOException) { }
            server.enqueue(MockResponse().setBody(manifest().toString()))
            val release = updates.check("0.2.12")!!
            server.enqueue(MockResponse().setBody("bad fixture"))
            try { updates.download(release, dir); fail() } catch (_: java.io.IOException) { }
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { server.shutdown(); dir.deleteRecursively() }
        Unit
    }
}
