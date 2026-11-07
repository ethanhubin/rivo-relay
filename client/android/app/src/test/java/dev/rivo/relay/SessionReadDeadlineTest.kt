package dev.rivo.relay

import dev.rivo.relay.codex.SessionController
import dev.rivo.relay.codex.control
import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class SessionReadDeadlineTest {
    @Test fun progressingHistoryLongerThanTenSecondsCompletesWithoutUnlockingEarly() = runBlocking {
        val server = MockWebServer()
        val body = JSONObject("""{"thread":{"id":"t","name":"received","turns":[{"id":"turn","items":[{"id":"item","type":"agentMessage","text":""}]}]},"requests":[],"settings":{},"status":"idle"}""")
        body.getJSONObject("thread").getJSONArray("turns").getJSONObject(0).getJSONArray("items").getJSONObject(0).put("text", "x".repeat(12 * 1024))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path!!.startsWith("/v1/events"))
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            else MockResponse().setBody(body.toString()).throttleBody(1024, 1, TimeUnit.SECONDS)
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        val controller = SessionController(api, scope, slowReadMillis = 50) { fail(it.message) }
        try {
            val started = System.nanoTime()
            controller.open("t")
            withTimeout(2_000) { controller.state.first { it.controlNotice != null } }
            assertFalse(controller.state.value.control().canOperate)
            val ready = withTimeout(20_000) { controller.state.first { it.snapshot != null && !it.loading && !it.stale } }
            assertTrue((System.nanoTime() - started) / 1_000_000 > 10_000)
            assertEquals(12 * 1024, ready.snapshot!!.messages.single().text.length)
            assertNull(ready.readError)
            assertTrue(ready.control().canOperate)
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun coroutineAndTransportDeadlinesSayTimeoutWithoutExposingExceptionDetails() = runBlocking {
        val error = try { withTimeout(1) { delay(100) }; error("deadline did not fire") }
            catch (error: TimeoutCancellationException) { error }
        for (failure in listOf(error, InterruptedIOException("timeout secret"))) {
            val text = connectionFailure(failure, "http://node:18767")
            assertTrue(text.contains("超时"))
            assertFalse(text.contains("连接中断"))
            assertFalse(text.contains("TimeoutCancellationException"))
            assertFalse(text.contains("secret"))
        }
    }
}
