package dev.rivo.relay

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.*
import dev.rivo.relay.ui.theme.RivoTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class CodexMediaInteractionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val id = "b".repeat(64)
    private fun png(): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(360, 220, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(50, 120, 180)); compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
        }; output.toByteArray()
    }
    private fun record() = """{"id":"$id","threadId":"thread","turnId":"turn","itemId":"image","name":"生成图片","mediaType":"image","mimeType":"image/png","status":"ready","favorite":false,"hasGenerationInfo":true,"originalUrl":"/v1/media/library/$id/content","thumbnailUrl":"/v1/media/library/$id/thumbnail","provenance":{"promptKind":"original","facts":{"prompt":"画一片蓝色天空","quality":"high"}}}"""
    private fun server(bytes: ByteArray) = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path!!.contains("/provenance") -> MockResponse().setBody(record())
            request.path!!.endsWith("/content") || request.path!!.endsWith("/thumbnail") -> MockResponse().setHeader("Content-Type", "image/png").setHeader("ETag", "\"fixture-v1\"").setBody(Buffer().write(bytes))
            request.path!!.startsWith("/v1/media/capabilities") -> MockResponse().setBody("""{"speech":{"transcription":false,"streaming":false,"reason":"节点未配置语音服务"}}""")
            else -> MockResponse().setBody("{\"items\":[${record() }]}")
        } }; start()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        android.os.SystemClock.sleep(250)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(compose.activity.getExternalFilesDir(null), "verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @Test fun originalImageCanRetryWithoutClosingGallery() {
        val bytes = png()
        val originals = AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/content") -> if (originals.incrementAndGet() == 1) MockResponse().setResponseCode(503)
                    else MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
                request.path!!.endsWith("/thumbnail") -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
                else -> MockResponse().setBody("{\"items\":[${record()}]}")
            } }; start()
        }
        val client = CodexMediaClient(RelayApi(server.url("/").toString(), "retry-fixture"), "thread", compose.activity)
        try {
            compose.setContent { RivoTheme { CompositionLocalProvider(LocalCodexMediaClient provides client) {
                CodexImageGallery("/v1/media/library/$id/content", onSave = {}, onShare = {}, onDismiss = {})
            } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("fullscreen_original_error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("fullscreen_original_error").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("fullscreen_original_loaded").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, originals.get())
        } finally { client.close(); server.shutdown() }
    }

    @Test fun changingMediaClientCancelsOldLibraryAttachment() {
        val bytes = png()
        val started = AtomicInteger()
        val releaseOldResponse = CountDownLatch(1)
        val oldServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/content") -> {
                    started.incrementAndGet()
                    check(releaseOldResponse.await(15, TimeUnit.SECONDS)) { "Old response was never released" }
                    MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
                }
                request.path!!.endsWith("/thumbnail") -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
                else -> MockResponse().setBody("{\"items\":[${record()}]}")
            } }; start()
        }
        val newServer = server(bytes)
        val old = CodexMediaClient(RelayApi(oldServer.url("/").toString(), "old-node"), "old-thread", compose.activity)
        val next = CodexMediaClient(RelayApi(newServer.url("/").toString(), "new-node"), "new-thread", compose.activity)
        val selected = mutableStateOf(old)
        val attachments = AtomicInteger()
        try {
            compose.setContent { RivoTheme { CompositionLocalProvider(LocalCodexMediaClient provides selected.value,
                LocalMediaAttachment provides { _, _, _ -> attachments.incrementAndGet(); true }) {
                CodexMediaLibrary(10, 16, {})
            } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("attach_media_$id").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("attach_media_$id").performClick()
            compose.waitUntil(10_000) { started.get() > 0 }
            // Do not wait for Compose idleness while its transfer indicator is animating:
            // doing so can let the old download finish before the node switch is applied.
            compose.runOnUiThread { selected.value = next }
            compose.waitUntil(10_000) { newServer.requestCount > 0 }
            releaseOldResponse.countDown()
            compose.runOnIdle { assertEquals(0, attachments.get()) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("attach_media_$id").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("attach_media_$id").performClick()
            compose.waitUntil(10_000) { attachments.get() == 1 }
        } finally { releaseOldResponse.countDown(); old.close(); next.close(); oldServer.shutdown(); newServer.shutdown() }
    }

    @Test fun generatedGalleryShowsRecordedProvenanceWithoutImporting() {
        val server = server(png())
        val client = CodexMediaClient(RelayApi(server.url("/").toString(), "test"), "thread", compose.activity)
        try {
            compose.setContent { RivoTheme { CompositionLocalProvider(LocalCodexMediaClient provides client,
                LocalImageFavorite provides { source, desired -> client.favorite(source, desired) }) {
                CodexImageGallery("/v1/media/library/$id/content", onSave = {}, onShare = {}, onDismiss = {})
            } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("fullscreen_original_loaded").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("fullscreen_more").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("fullscreen_info").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("fullscreen_info").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("画一片蓝色天空").fetchSemanticsNodes().isNotEmpty() }
            screenshot("codex-media-provenance")
            repeat(server.requestCount) { assertEquals("GET", server.takeRequest().method) }
        } finally { client.close(); server.shutdown() }
    }
    @Test fun librarySelectionDownloadsBeforeAttachingAndVoiceStaysUnavailable() {
        val bytes = png(); val server = server(bytes)
        val client = CodexMediaClient(RelayApi(server.url("/").toString(), "test"), "thread", compose.activity)
        var attached: ByteArray? = null
        try {
            compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) { CompositionLocalProvider(LocalCodexMediaClient provides client,
                LocalMediaAttachment provides { file, _, _ -> attached = file.readBytes(); true }) {
                AttachmentSourceDialog(10, 16, {}, {}, {}, {}, {})
            } } } }
            compose.onNodeWithTag("attachment_tab_media").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("attach_media_$id").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("attach_media_$id").performClick()
            compose.waitUntil(10_000) { attached != null }
            assertArrayEquals(bytes, attached)
            screenshot("codex-media-library")
        } finally { client.close(); server.shutdown() }
    }
}
