package dev.rivo.relay

import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SpeechRepositoryTest {
    @Test fun capabilityIsDiscoveredOncePerAuthenticatedNodeClient() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"speech":{"streaming":true,"sampleRate":16000,"encoding":"pcm_s16le","state":"configured"}}"""))
        server.start()
        try {
            val api = RelayApi(server.url("/").toString(), "fixture-token")
            assertTrue(SpeechRepository(api).capability().available)
            assertTrue(SpeechRepository(api).capability().available)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/v1/media/capabilities", request.path)
            assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun unconfiguredSpeechRemainsUnavailableWithAConcreteReason() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"speech":{"streaming":false,"state":"unconfigured"}}"""))
        server.start()
        try {
            val capability = SpeechRepository(RelayApi(server.url("/").toString())).capability()
            assertFalse(capability.available)
            assertTrue(capability.reason!!.contains("尚未配置"))
        } finally { server.shutdown() }
    }

    @Test fun streamingUsesRawLittleEndianPcmAndTheSameNodeCredential() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"stream_id":"stream-1"}"""))
        server.enqueue(MockResponse().setBody("""{"stream_id":"stream-1","text":"语音"}"""))
        server.enqueue(MockResponse().setBody("""{"stream_id":"stream-1","text":"完整语音"}"""))
        server.start()
        try {
            val repository = SpeechRepository(RelayApi(server.url("/").toString(), "fixture-token"))
            val stream = repository.start("上下文")
            val raw = byteArrayOf(0x34, 0x12, -1, -1)
            assertEquals("语音", repository.chunk(stream, raw))
            assertEquals("完整语音", repository.finish(stream))
            assertEquals("/v1/speech/streams", server.takeRequest().path)
            val chunk = server.takeRequest()
            assertEquals("/v1/speech/streams/stream-1/chunks", chunk.path)
            assertEquals("application/octet-stream", chunk.getHeader("Content-Type"))
            assertEquals("Bearer fixture-token", chunk.getHeader("Authorization"))
            assertArrayEquals(raw, chunk.body.readByteArray())
            assertEquals("/v1/speech/streams/stream-1/finish", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun failedChunkIsReportedWithoutReplayOrTreatingItAsEmptyTranscript() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"speech_expired","message":"实时语音已过期"}}"""))
        server.start()
        try {
            try {
                SpeechRepository(RelayApi(server.url("/").toString())).chunk("expired", byteArrayOf(0, 0))
                fail("expired stream accepted")
            } catch (error: ApiException) { assertEquals("speech_expired", error.code) }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
