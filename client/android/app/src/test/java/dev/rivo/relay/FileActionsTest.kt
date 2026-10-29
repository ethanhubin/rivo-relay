package dev.rivo.relay

import dev.rivo.relay.ui.chat.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class FileActionsTest {
    @Test fun routesLocalFilesAndPublicUrlsByType() {
        val fixtures = mapOf(
            "a.PNG" to FileOpenKind.IMAGE,
            "movie.mp4" to FileOpenKind.VIDEO,
            "recording.flac" to FileOpenKind.AUDIO,
            "README.md" to FileOpenKind.TEXT,
            "data.jsonl" to FileOpenKind.TEXT,
            "notebook.ipynb" to FileOpenKind.TEXT,
            "schema.xsd" to FileOpenKind.TEXT,
            "vector.svg" to FileOpenKind.TEXT,
            "report.pdf" to FileOpenKind.PDF,
            "release.apk" to FileOpenKind.APK,
            "report.docx" to FileOpenKind.EXTERNAL,
            "archive.zip" to FileOpenKind.EXTERNAL,
        )
        fixtures.forEach { (name, kind) ->
            assertEquals(name, kind, fileOpenKind("/project/$name", fileMimeType(name)))
            assertEquals(name, kind, fileOpenKind("https://example.com/$name?access=fixture#part", fileMimeType(name)))
        }
    }

    @Test fun declaredMimeRoutesExtensionlessFilesAndIgnoresParameters() {
        assertEquals(FileOpenKind.PDF, fileOpenKind("/download/123", "APPLICATION/PDF; charset=binary"))
        assertEquals(FileOpenKind.TEXT, fileOpenKind("/download/123", "application/json; charset=utf-8"))
        assertEquals(FileOpenKind.TEXT, fileOpenKind("/download/123", "application/xml"))
        assertEquals(FileOpenKind.IMAGE, fileOpenKind("/download/123", "IMAGE/JPEG"))
        assertEquals(FileOpenKind.AUDIO, fileOpenKind("/download/123", "audio/flac"))
        assertEquals(FileOpenKind.APK, fileOpenKind("/download/123", "application/vnd.android.package-archive"))
    }

    @Test fun publicDisplayNamesExcludeQueriesAndDecodeSpaces() {
        assertEquals("report final.pdf", fileDisplayName("https://example.com/report%20final.pdf?token=secret#page=1"))
        assertEquals("文件", fileDisplayName("https://example.com/?token=secret"))
        assertEquals("README.md", fileDisplayName("C:\\project\\README.md"))
    }

    @Test fun nativeDownloadKeepsNativeSourceAndStreamsDirectly() = runBlocking {
        val output = ByteArrayOutputStream()
        val seen = mutableListOf<Pair<Long, Long>>()
        downloadFileSource("/project/file.txt", output, { bytes, total -> seen += bytes to total }) { path, stream, progress ->
            assertEquals("/project/file.txt", path)
            stream.write("native".toByteArray())
            progress(6, 6)
        }
        assertEquals("native", output.toString("UTF-8"))
        assertEquals(listOf(6L to 6L), seen)
    }

    @Test fun publicRedirectDownloadsWithoutNodeAuthOrNativeFallback() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { destination ->
            destination.enqueue(MockResponse().setBody("public result"))
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/report.txt?download=1")))
            val output = ByteArrayOutputStream()
            var finalProgress = 0L to -1L
            downloadFileSource(first.url("/redirect").toString(), output, { bytes, total -> finalProgress = bytes to total }) { _, _, _ -> error("Native client must not receive public URLs") }
            assertEquals("public result", output.toString("UTF-8"))
            assertEquals(13L to 13L, finalProgress)
            val requests = listOf(first.takeRequest(3, TimeUnit.SECONDS)!!, destination.takeRequest(3, TimeUnit.SECONDS)!!)
            requests.forEach { request ->
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Cookie"))
            }
            assertEquals("/report.txt?download=1", requests.last().path)
        } }
    }

    @Test fun failedAndOversizedDownloadsDoNotWriteAFile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", 1024L * 1024 * 1024 + 1))
            repeat(2) {
                val output = ByteArrayOutputStream()
                val failure = runCatching {
                    downloadFileSource(server.url("/file").toString(), output, { _, _ -> }) { _, _, _ -> error("native") }
                }.exceptionOrNull()
                assertNotNull(failure)
                assertEquals(0, output.size())
                if (it == 0) assertTrue(failure is IOException)
                else assertTrue(failure!!.message.orEmpty().contains("1 GiB"))
            }
        }
    }

    @Test fun cancellationStopsAWaitingPublicRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val output = ByteArrayOutputStream()
            val job = launch(Dispatchers.IO) {
                downloadFileSource(server.url("/waiting").toString(), output, { _, _ -> }) { _, _, _ -> error("native") }
            }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(0, output.size())
        }
    }
}
