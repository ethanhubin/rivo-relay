package dev.rivo.relay

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.ui.chat.HtmlFilePreview
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

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        compose.runOnUiThread {
            findWebView(compose.activity.window.decorView)!!.evaluateJavascript(script) { result = it; done.countDown() }
        }
        assertTrue("WebView evaluation timed out", done.await(5, TimeUnit.SECONDS))
        return result
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
