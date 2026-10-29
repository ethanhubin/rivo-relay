package dev.rivo.relay

import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RelayTransportTest {
    @Test fun binaryUploadAndDownloadStreamBeyondPreviewLimit() = runBlocking {
        val server = MockWebServer()
        val bytes = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody("{\"path\":\"/remote/test.zip\"}"))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        server.start()
        val file = java.io.File.createTempFile("relay-transfer-", ".zip")
        try {
            file.writeBytes(bytes)
            val api = RelayApi(server.url("/").toString().trimEnd('/'), "test-token")
            api.uploadFile("thread", "test.zip", file)
            val uploaded = server.takeRequest()
            assertEquals("application/octet-stream", uploaded.getHeader("Content-Type"))
            assertArrayEquals(bytes, uploaded.body.readByteArray())
            assertEquals("Bearer test-token", uploaded.getHeader("Authorization"))
            val output = java.io.ByteArrayOutputStream()
            var received = 0L
            api.downloadFile("/remote/test.zip", output) { count, _ -> received = count }
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals(bytes.size.toLong(), received)
            assertTrue(server.takeRequest().path!!.startsWith("/v1/files/download?"))
        } finally { file.delete(); server.shutdown() }
    }

    @Test fun lostWriteResponseIsNeverAutomaticallyReplayed() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.start()
        try {
            val api = RelayApi(server.url("/").toString().trimEnd('/'), "test-token")
            val error = runCatching { api.call("/v1/threads/thread-one/turns", "POST", JSONObject().put("submissionId", "stable-id").put("text", "isolated test")) }.exceptionOrNull()
            assertNotNull(error)
            assertEquals(1, server.requestCount)
            assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
        } finally { server.shutdown() }
    }
    @Test fun cancelledReadCancelsUnderlyingCall() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
        try {
            val job = launch { RelayApi(server.url("/").toString().trimEnd('/')).call("/v1/projects") }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun nativeUnavailableIsDistinctFromRevokedRelayIdentity() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"code\":\"native_unavailable\",\"message\":\"Desktop unavailable\"}}")); server.start()
        try {
            val error = runCatching { RelayApi(server.url("/").toString().trimEnd('/')).call("/v1/projects") }.exceptionOrNull() as ApiException
            assertEquals(503, error.status); assertEquals("native_unavailable", error.code)
        } finally { server.shutdown() }
    }
}
