package dev.rivo.relay

import dev.rivo.relay.data.RelayApi
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NetworkTransportTest {
    @Test fun longLivedStreamsDoNotQueueForegroundJsonReads() = runBlocking {
        val server = MockWebServer()
        val connected = CountDownLatch(5)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/v1/events")) MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(" ".repeat(1000)).throttleBody(1, 1, TimeUnit.SECONDS)
                else MockResponse().setBody("{\"fresh\":true}")
        }
        server.start()
        val api = RelayApi(server.url("/").toString())
        val streams = (1..5).map { id -> launch {
            api.events(id.toString()).collect { if (it.type == "connected") connected.countDown() }
        } }
        try {
            withContext(Dispatchers.IO) { assertTrue(connected.await(5, TimeUnit.SECONDS)) }
            assertTrue(withTimeout(2000) { api.call("/fresh") }.getBoolean("fresh"))
        } finally {
            streams.forEach { it.cancel() }
            streams.forEach { it.join() }
            server.shutdown()
        }
    }

    @Test fun stalledBackgroundReadsDoNotBlockForegroundControl() = runBlocking {
        val server = MockWebServer()
        val started = CountDownLatch(2)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/background")) {
                    started.countDown()
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                } else MockResponse().setBody("{\"fresh\":true}")
        }
        server.start()
        val api = RelayApi(server.url("/").toString())
        val reads = (1..12).map { id -> launch { api.call("/background/$id", background = true) } }
        try {
            withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
            assertTrue(withTimeout(2000) { api.call("/control") }.getBoolean("fresh"))
            assertEquals(3, server.requestCount)
        } finally {
            reads.forEach { it.cancel() }
            reads.forEach { it.join() }
            server.shutdown()
        }
    }

    @Test fun routeChangeCancelsStalledReadsButPreservesSubmittedWrite() = runBlocking {
        val server = MockWebServer()
        val getStarted = CountDownLatch(1)
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/stalled" -> { getStarted.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                "/write" -> { writeStarted.countDown(); releaseWrite.await(5, TimeUnit.SECONDS); MockResponse().setBody("{\"accepted\":true}") }
                else -> MockResponse().setBody("{\"fresh\":true}")
            }
        }
        server.start()
        val api = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        try {
            val read = async { runCatching { api.call("/stalled") } }
            val write = async { api.call("/write", "POST", JSONObject()) }
            withContext(Dispatchers.IO) {
                assertTrue(getStarted.await(5, TimeUnit.SECONDS))
                assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
            }
            api.resetTransport()
            assertTrue(withTimeout(3000) { read.await() }.isFailure)
            releaseWrite.countDown()
            assertTrue(withTimeout(3000) { write.await() }.getBoolean("accepted"))
            assertTrue(api.call("/fresh").getBoolean("fresh"))
            assertEquals(3, server.requestCount)
        } finally { releaseWrite.countDown(); server.shutdown() }
    }
}
