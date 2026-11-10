package dev.rivo.relay

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import dev.rivo.relay.connection.NodeRoutes
import dev.rivo.relay.ui.sites.SitesWebProxy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in: externally supplied, temporary device credential; never included in APK assets. */
class SitesRelayLiveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun publishedSiteLoadsThroughVpsWithoutSystemProxy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "sites-relay-verification.json")
        assumeTrue("Live verification configuration missing", file.exists())
        val config = JSONObject(file.readText())
        val routes = NodeRoutes.from(config.getJSONObject("routes"))
        val proxy = SitesWebProxy(routes, config.getString("token"))
        var view: WebView? = null
        val ready = CountDownLatch(1)
        compose.runOnUiThread {
            ProxyController.getInstance().setProxyOverride(ProxyConfig.Builder()
                .addProxyRule("http://127.0.0.1:${proxy.port}").removeImplicitRules().build(), ContextCompat.getMainExecutor(context)) { ready.countDown() }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        try {
            compose.setContent { AndroidView(factory = { ctx -> WebView(ctx).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE; clearCache(true)
                webViewClient = android.webkit.WebViewClient()
                view = this; loadUrl(config.getString("url"))
            } }) }
            fun evaluate(script: String): String {
                val done = CountDownLatch(1); var result = ""
                compose.runOnUiThread { view?.evaluateJavascript(script) { result = it; done.countDown() } }
                assertTrue(done.await(5, TimeUnit.SECONDS)); return result
            }
            compose.waitUntil(60_000) { view != null && evaluate("document.readyState === 'complete' && !!document.querySelector('iframe[srcdoc]')") == "true" }
            assertEquals("true", evaluate("location.protocol === 'https:' && !document.body.innerText.includes('ERR_')"))
            assertEquals("true", evaluate("document.title.length > 0"))
            assertTrue("Page bytes must have crossed the VPS tunnel", proxy.receivedBytes.get() > 1000)
            println("Sites relay received bytes: " + proxy.receivedBytes.get())
            Thread.sleep(2000)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "sites-relay-live.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            println("Sites relay live page title: " + evaluate("document.title"))
            println("Sites relay live resource count: " + evaluate("performance.getEntriesByType('resource').length"))
        } finally {
            val cleared = CountDownLatch(1)
            compose.runOnUiThread {
                view?.destroy()
                ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(context)) { cleared.countDown() }
            }
            cleared.await(10, TimeUnit.SECONDS); proxy.close()
        }
    }
}
