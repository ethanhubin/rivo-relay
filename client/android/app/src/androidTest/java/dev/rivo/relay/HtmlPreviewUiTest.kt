package dev.rivo.relay

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.view.inspector.WindowInspectorCompat
import dev.rivo.relay.ui.chat.CodexLinkAttachment
import dev.rivo.relay.ui.chat.FilePreviewDialog
import dev.rivo.relay.ui.chat.HtmlFilePreview
import dev.rivo.relay.ui.chat.LocalCodexLinkForwarder
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HtmlPreviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun previewWebView(): WebView = WindowInspectorCompat.getGlobalWindowViews().firstNotNullOf(::findWebView)

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        compose.runOnUiThread {
            previewWebView().evaluateJavascript(script) { result = it; done.countDown() }
        }
        assertTrue("WebView evaluation timed out", done.await(5, TimeUnit.SECONDS))
        return result
    }

    @Test fun htmlPreviewUsesOneToolbarAndKeepsThePageWhenTogglingFullscreen() {
        val path = "/workspace/prototype.html"
        val html = """
            <button id="counter" onclick="this.textContent = Number(this.textContent) + 1">0</button>
            <div style="height: 2200px"></div>
        """.trimIndent()
        var visible by mutableStateOf(true)
        var forwarded: CodexLinkAttachment? = null
        compose.setContent {
            RivoTheme {
                CompositionLocalProvider(LocalCodexLinkForwarder provides { forwarded = it }) {
                    if (visible) FilePreviewDialog(
                        path = path,
                        readFile = { JSONObject().put("encoding", "utf-8").put("mimeType", "text/html").put("text", html) },
                        downloadFile = { _, _, _ -> error("Preview must not download the file") },
                        onDismiss = { visible = false },
                    )
                }
            }
        }

        val fullscreen = compose.onNodeWithContentDescription("全屏预览")
        val exitFullscreen = compose.onNodeWithContentDescription("退出全屏")
        val more = compose.onNodeWithContentDescription("更多")
        fullscreen.assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("源码").assertDoesNotExist()
        compose.onNodeWithTag("forward_link_to_codex").assertDoesNotExist()
        compose.onNodeWithTag("share_file").assertDoesNotExist()
        compose.onNodeWithTag("save_file").assertDoesNotExist()
        more.performClick()
        compose.onNodeWithText("发送到 Codex").assertIsDisplayed()
        compose.onNodeWithText("分享").assertIsDisplayed()
        compose.onNodeWithText("保存到本机").assertIsDisplayed()
        compose.onNodeWithText("源码").performClick()
        compose.onNodeWithTag("source_copy").assertIsDisplayed()
        more.performClick()
        compose.onNodeWithText("预览").performClick()
        compose.onNodeWithTag("source_copy").assertDoesNotExist()

        compose.waitUntil(15_000) {
            evaluate("document.readyState === 'complete' && document.documentElement.scrollHeight > window.innerHeight") == "true"
        }
        val webView = compose.runOnUiThread { previewWebView() }
        val previewHeight = compose.runOnUiThread { webView.height }
        evaluate("document.getElementById('counter').click(); window.scrollTo(0, 160)")
        compose.waitUntil(5_000) { evaluate("window.scrollY > 0") == "true" }
        val scrollOffset = evaluate("window.scrollY")

        fun systemBarsAreVisible(visible: Boolean): Boolean = compose.runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(webView) ?: return@runOnUiThread false
            insets.isVisible(WindowInsetsCompat.Type.statusBars()) == visible &&
                insets.isVisible(WindowInsetsCompat.Type.navigationBars()) == visible
        }

        fullscreen.performClick()
        exitFullscreen.assertIsDisplayed()
        fullscreen.assertDoesNotExist()
        more.assertDoesNotExist()
        compose.waitUntil(5_000) { systemBarsAreVisible(false) }
        compose.runOnUiThread {
            assertSame(webView, previewWebView())
            assertTrue(webView.height > previewHeight)
        }
        assertEquals(scrollOffset, evaluate("window.scrollY"))
        assertEquals("\"1\"", evaluate("document.getElementById('counter').textContent"))
        evaluate("document.getElementById('counter').click()")
        exitFullscreen.performClick()
        compose.waitUntil(5_000) { systemBarsAreVisible(true) }
        fullscreen.assertIsDisplayed()
        assertEquals("\"2\"", evaluate("document.getElementById('counter').textContent"))

        fullscreen.performClick()
        exitFullscreen.assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        fullscreen.assertIsDisplayed()
        exitFullscreen.assertDoesNotExist()
        more.performClick()
        compose.onNodeWithText("发送到 Codex").performClick()
        compose.onNodeWithTag("file_preview").assertDoesNotExist()
        assertEquals(CodexLinkAttachment(path, "prototype.html"), forwarded)
    }

    @Test fun actualSessionHtmlBuildsVisibleContentAndHandlesInput() {
        val html = InstrumentationRegistry.getInstrumentation().context.assets
            .open("visualization-session-layout.html").bufferedReader().use { it.readText() }
        compose.setContent { HtmlFilePreview(html, Modifier.fillMaxSize()) }
        compose.waitUntil(15_000) { evaluate("!!document.querySelector('#rivo-bottom textarea')") == "true" }
        assertEquals("true", evaluate("document.querySelector('#rivo-bottom').innerText.includes('今天想从哪里开始')"))
        assertEquals("true", evaluate("document.querySelector('#rivo-bottom h3').getBoundingClientRect().height > 0"))
        assertEquals("true", evaluate("document.documentElement.clientWidth < 600"))
        assertEquals("true", evaluate("""(() => {
            const root = document.querySelector('#rivo-bottom');
            const input = root.querySelector('textarea');
            input.value = '手机交互正常'; input.dispatchEvent(new Event('input'));
            root.querySelector('.rivo-send').click();
            root.querySelector('.rivo-mode').click();
            return root.querySelector('h3').textContent === '手机交互正常'
                && root.querySelector('.rivo-mode').textContent.includes('讨论模式');
        })()"""))
        compose.runOnUiThread {
            val settings = findWebView(compose.activity.window.decorView)!!.settings
            assertTrue(settings.javaScriptEnabled)
            assertTrue(settings.blockNetworkLoads)
            assertFalse(settings.allowFileAccess)
            assertFalse(settings.allowContentAccess)
            assertFalse(settings.domStorageEnabled)
        }
        assertEquals("true", evaluate("document.querySelector('meta[http-equiv]').content.includes(\"connect-src 'none'\")"))
        assertEquals("\"undefined\"", evaluate("typeof window.openai"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val painted = CountDownLatch(1)
        compose.runOnUiThread {
            findWebView(compose.activity.window.decorView)!!.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { painted.countDown() }
            })
        }
        assertTrue("WebView did not paint", painted.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(500)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val colors = mutableSetOf<Int>()
        for (y in 100 until screenshot.height - 100 step 8) for (x in 20 until screenshot.width - 20 step 8) colors += screenshot.getPixel(x, y)
        assertTrue("Rendered preview is blank", colors.size > 20)
        val output = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "html-preview.png")
        output.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
