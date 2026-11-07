package dev.rivo.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CodexMediaIntegrationTest {
    @Test fun mediaOriginIsProxiedWithNodeCredentialsAndScopedCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer(); server.start()
        val first = CodexMediaClient(RelayApi(server.url("/").toString(), "device-one"), "thread", context)
        val second = CodexMediaClient(RelayApi(server.url("/").toString(), "device-two"), "thread", context)
        try {
            val source = "https://media.example/image.png?signature=private"
            val request = first.imageRequest(context, source, true).build()
            assertTrue(request.data.toString().startsWith(server.url("/v1/media/thumbnail").toString()))
            assertEquals("Bearer device-one", request.headers["Authorization"])
            assertNotEquals(request.memoryCacheKey, second.imageRequest(context, source, true).build().memoryCacheKey)
            val local = first.imageRequest(context, "file:///private/test.png", false).build()
            assertNull(local.headers["Authorization"])
            server.enqueue(MockResponse().setBody("{\"items\":[]}"))
            assertFalse(first.favorite(source, null).favorite)
            val lookup = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", lookup.method)
            assertEquals(source, lookup.requestUrl!!.queryParameter("source"))
            assertEquals(1, server.requestCount) // Viewing does not import.
        } finally { first.close(); second.close(); server.shutdown() }
    }
    @Test fun explicitFavoriteImportsOnceAndKeepsNativeProvenanceFacts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer(); server.start()
        val client = CodexMediaClient(RelayApi(server.url("/").toString(), "token"), "t", context)
        val id = "a".repeat(64)
        try {
            server.enqueue(MockResponse().setBody("{\"items\":[]}"))
            server.enqueue(MockResponse().setBody("{\"id\":\"$id\",\"favorite\":false}"))
            server.enqueue(MockResponse().setBody("{\"id\":\"$id\",\"favorite\":true}"))
            assertTrue(client.favorite("/native/image.png", true).favorite)
            assertEquals("GET", server.takeRequest().method)
            val imported = server.takeRequest(); assertEquals("POST", imported.method)
            assertEquals("/native/image.png", org.json.JSONObject(imported.body.readUtf8()).getString("source"))
            assertEquals("PATCH", server.takeRequest().method)
            server.enqueue(MockResponse().setBody("""{"threadId":"t","turnId":"turn","itemId":"item","provenance":{"promptKind":"revised","facts":{"revisedPrompt":"Revised description","quality":"high"}}}"""))
            val info = client.generationInfo(id)
            assertEquals("Revised description", info.prompt)
            assertEquals("模型修订提示词", info.parameters["提示词来源"])
            assertEquals("item", info.parameters["itemId"])
        } finally { client.close(); server.shutdown() }
    }
}
