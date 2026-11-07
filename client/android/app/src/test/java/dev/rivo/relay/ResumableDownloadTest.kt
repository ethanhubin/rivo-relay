package dev.rivo.relay

import dev.rivo.relay.data.ResumableDownload
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ResumableDownloadTest {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    @Test fun interruptedBodyResumesOnlyWithMatchingStrongValidator() = runBlocking {
        val directory = Files.createTempDirectory("relay-download").toFile()
        try { MockWebServer().use { server ->
            val body = "abcdefghij".repeat(2000)
            server.enqueue(MockResponse().setBody(body).setHeader("ETag", "\"v1\"")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val request = Request.Builder().url(server.url("/file")).build()
            assertNotNull(runCatching { ResumableDownload.get(directory, "node-a", "file", client, request) { _, _ -> } }.exceptionOrNull())
            val offset = directory.listFiles()!!.single { it.extension == "part" }.length()
            assertTrue(offset in 1 until body.length.toLong())
            server.enqueue(MockResponse().setResponseCode(206).setBody(body.substring(offset.toInt()))
                .setHeader("ETag", "\"v1\"").setHeader("Content-Range", "bytes $offset-${body.length - 1}/${body.length}"))
            val file = ResumableDownload.get(directory, "node-a", "file", client, request) { _, _ -> }
            assertEquals(body, file.readText())
            server.takeRequest()
            val resumed = server.takeRequest()
            assertEquals("bytes=$offset-", resumed.getHeader("Range"))
            assertEquals("\"v1\"", resumed.getHeader("If-Range"))
            assertEquals("identity", resumed.getHeader("Accept-Encoding"))
        } } finally { directory.deleteRecursively() }
    }

    @Test fun changedFileOrServerIgnoringRangeReplacesThePartialFile() = runBlocking {
        val directory = Files.createTempDirectory("relay-download").toFile()
        try { MockWebServer().use { server ->
            val request = Request.Builder().url(server.url("/file")).build()
            server.enqueue(MockResponse().setBody("old content").setHeader("ETag", "\"old\""))
            val oldFile = ResumableDownload.get(directory, "node", "file", client, request) { _, _ -> }
            server.enqueue(MockResponse().setBody("new").setHeader("ETag", "\"new\""))
            val file = ResumableDownload.get(directory, "node", "file", client, request) { _, _ -> }
            assertEquals("new", file.readText())
            assertEquals("old content", oldFile.readText()) // Already published provider/player URI stays valid.
            assertNotEquals(oldFile, file)
            server.takeRequest()
            assertEquals("bytes=11-", server.takeRequest().getHeader("Range"))
        } } finally { directory.deleteRecursively() }
    }

    @Test fun completedCacheIsRevalidatedAndNodeScopesDoNotShareBytes() = runBlocking {
        val directory = Files.createTempDirectory("relay-download").toFile()
        try { MockWebServer().use { server ->
            val request = Request.Builder().url(server.url("/file")).build()
            server.enqueue(MockResponse().setBody("one").setHeader("ETag", "\"1\""))
            val first = ResumableDownload.get(directory, "node-a", "/same/path", client, request) { _, _ -> }
            server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */3").setHeader("ETag", "\"1\""))
            assertEquals("one", ResumableDownload.get(directory, "node-a", "/same/path", client, request) { _, _ -> }.readText())
            server.enqueue(MockResponse().setBody("two").setHeader("ETag", "\"2\""))
            val second = ResumableDownload.get(directory, "node-b", "/same/path", client, request) { _, _ -> }
            assertNotEquals(first, second)
            assertEquals("two", second.readText())
            server.takeRequest(); server.takeRequest()
            assertNull(server.takeRequest().getHeader("Range"))
        } } finally { directory.deleteRecursively() }
    }

    @Test fun failedRevalidationKeepsPublishedFileAndResumesOnlyNewPartialVersion() = runBlocking {
        val directory = Files.createTempDirectory("relay-download").toFile()
        try { MockWebServer().use { server ->
            val request = Request.Builder().url(server.url("/file")).build()
            server.enqueue(MockResponse().setBody("old file").setHeader("ETag", "\"old\""))
            val published = ResumableDownload.get(directory, "node", "file", client, request) { _, _ -> }
            val fresh = "new body".repeat(3000)
            server.enqueue(MockResponse().setBody(fresh).setHeader("ETag", "\"new\"")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            assertNotNull(runCatching { ResumableDownload.get(directory, "node", "file", client, request) { _, _ -> } }.exceptionOrNull())
            assertEquals("old file", published.readText())
            val offset = directory.listFiles()!!.single { it.extension == "part" }.length()
            assertTrue(offset in 1 until fresh.length.toLong())
            server.enqueue(MockResponse().setResponseCode(206).setBody(fresh.substring(offset.toInt()))
                .setHeader("ETag", "\"new\"").setHeader("Content-Range", "bytes $offset-${fresh.length - 1}/${fresh.length}"))
            assertEquals(fresh, ResumableDownload.get(directory, "node", "file", client, request) { _, _ -> }.readText())
            server.takeRequest(); server.takeRequest()
            assertEquals("\"new\"", server.takeRequest().getHeader("If-Range"))
        } } finally { directory.deleteRecursively() }
    }

    @Test fun invalid206AndStale416NeverPublishMixedContent() = runBlocking {
        val directory = Files.createTempDirectory("relay-download").toFile()
        try { MockWebServer().use { server ->
            val request = Request.Builder().url(server.url("/file")).build()
            server.enqueue(MockResponse().setBody("abc").setHeader("ETag", "\"v1\""))
            ResumableDownload.get(directory, "node", "file", client, request) { _, _ -> }
            server.enqueue(MockResponse().setResponseCode(206).setBody("wrong")
                .setHeader("ETag", "\"v1\"").setHeader("Content-Range", "bytes 1-5/9"))
            server.enqueue(MockResponse().setBody("fresh").setHeader("ETag", "\"v2\""))
            assertEquals("fresh", ResumableDownload.get(directory, "node", "file", client, request) { _, _ -> }.readText())
            server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */5").setHeader("ETag", "\"changed\""))
            server.enqueue(MockResponse().setBody("changed").setHeader("ETag", "\"changed\""))
            assertEquals("changed", ResumableDownload.get(directory, "node", "file", client, request) { _, _ -> }.readText())
            val requests = (1..5).map { server.takeRequest() }
            assertNull(requests[2].getHeader("Range")); assertNull(requests[4].getHeader("Range"))
        } } finally { directory.deleteRecursively() }
    }

    @Test fun weakOrAbsentValidatorsForceFreshReadsAndOversizedBodiesAreRejected() = runBlocking {
        val directory = Files.createTempDirectory("relay-download").toFile()
        try { MockWebServer().use { server ->
            val request = Request.Builder().url(server.url("/file")).build()
            server.enqueue(MockResponse().setBody("one").setHeader("ETag", "W/\"1\""))
            ResumableDownload.get(directory, "node", "file", client, request) { _, _ -> }
            server.enqueue(MockResponse().setBody("two"))
            assertEquals("two", ResumableDownload.get(directory, "node", "file", client, request) { _, _ -> }.readText())
            server.takeRequest(); assertNull(server.takeRequest().getHeader("Range"))
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", 1024L * 1024 * 1024 + 1))
            assertTrue(runCatching { ResumableDownload.get(directory, "node", "oversized", client, request) { _, _ -> } }
                .exceptionOrNull()!!.message.orEmpty().contains("1 GiB"))
        } } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationClosesWriterBeforeTheSameKeyCanRetry() = runBlocking {
        val directory = Files.createTempDirectory("relay-download").toFile()
        try { MockWebServer().use { server ->
            val request = Request.Builder().url(server.url("/file")).build()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val waiting = launch { ResumableDownload.get(directory, "node", "file", client, request) { _, _ -> } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            withTimeout(3000) { waiting.cancelAndJoin() }
            server.enqueue(MockResponse().setBody("retry").setHeader("ETag", "\"v1\""))
            assertEquals("retry", withTimeout(3000) { ResumableDownload.get(directory, "node", "file", client, request) { _, _ -> } }.readText())
        } } finally { directory.deleteRecursively() }
    }
}
