package dev.rivo.relay.data

import org.junit.Assert.*
import org.junit.Test

class PublicUpdateLinkTest {
    @Test fun onlyImmutableDeploymentApksReceiveDigestValidation() {
        val hash = "a".repeat(64)
        val url = "https://134.175.156.219:8443/v1/app-updates/apks/$hash.apk"
        assertEquals(hash, PublicUpdateLink.digest(url))
        assertEquals(hash, PublicUpdateLink.digest(url.replace("app-updates/apks", "releases/files")))
        for (other in listOf(url.replace("https:", "http:"), url.replace(":8443", ":80"),
            url.replace("134.175.156.219", "other.example"), "$url?token=x", "$url#x",
            url.replace("https://", "https://user:pass@"), url.replace(".apk", ".zip"),
            url.replace(hash, "arbitrary"))) assertNull(other, PublicUpdateLink.digest(other))
    }

    @Test fun singleGetVerifiesHashWithoutProbesOrSegments() = kotlinx.coroutines.runBlocking {
        val bytes = ByteArray(1024 * 1024 + 17) { (it % 251).toByte() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    assertNull(request.getHeader("Authorization"))
                    assertEquals("GET", request.method)
                    assertNull(request.getHeader("Range"))
                    assertNull(request.getHeader("Connection")?.takeIf { it.equals("close", true) })
                    return if (request.method == "HEAD") okhttp3.mockwebserver.MockResponse().setHeader("Content-Length", bytes.size)
                    else okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(bytes)).setHeader("ETag", "\"fixture\"")
                }
            }
            val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder()
                    .scheme("http").host("localhost").port(server.port).build()).build())
            }.build()
            val dir = java.nio.file.Files.createTempDirectory("link-fallback").toFile()
            try {
                val source = "https://134.175.156.219:8443/v1/app-updates/apks/$hash.apk"
                assertArrayEquals(bytes, PublicUpdateLink.download(source, dir, client) { _, _ -> }!!.readBytes())
                try {
                    PublicUpdateLink.download(source.replace(hash, "f".repeat(64)), dir, client) { _, _ -> }
                    fail("Fallback must reject a mismatched APK digest")
                } catch (_: java.io.IOException) { }
                assertEquals(2, server.requestCount)
            } finally { dir.deleteRecursively() }
        }
        Unit
    }
}
