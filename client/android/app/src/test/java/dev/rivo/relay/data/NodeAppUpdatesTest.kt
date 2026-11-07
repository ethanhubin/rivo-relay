package dev.rivo.relay.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class NodeAppUpdatesTest {
    private val bytes = "apk fixture".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun manifest() = JSONObject().put("version", "0.2.13").put("versionCode", 25).put("sha256", hash)
        .put("size", bytes.size).put("apkPath", "/v1/app-updates/apks/$hash.apk").put("notes", "node update")
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
