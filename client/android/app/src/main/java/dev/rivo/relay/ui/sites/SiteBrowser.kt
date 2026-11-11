package dev.rivo.relay.ui.sites

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rivo.relay.data.resourceWebUrl
import dev.rivo.relay.data.RelayApi
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

/** Website TLS and cookies remain owned by WebView; Relay auth only enters the tunnel. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SiteBrowser(initialUrl: String, title: String, api: RelayApi? = null, useRelay: Boolean = false, onClose: () -> Unit) {
    val safeUrl = resourceWebUrl(initialUrl) ?: return
    val context = LocalContext.current
    var proxyReady by remember(api, useRelay) { mutableStateOf(!useRelay) }
    var proxyError by remember(api, useRelay) { mutableStateOf<String?>(null) }
    DisposableEffect(api, useRelay) {
        var disposed = false
        var proxy: SitesWebProxy? = null
        var configured = false
        if (useRelay) {
            try {
                check(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) { "请更新 Android System WebView 后使用 Sites 中转" }
                check(api != null) { "Sites 中转需要已配对节点" }
                proxy = api.sitesWebProxy()
                val config = ProxyConfig.Builder().addProxyRule("http://127.0.0.1:${proxy.port}")
                    .removeImplicitRules().build()
                ProxyController.getInstance().setProxyOverride(config, ContextCompat.getMainExecutor(context)) {
                    if (!disposed) proxyReady = true
                }
                configured = true
            } catch (e: Exception) { proxy?.close(); proxyError = e.message ?: "无法建立 Sites 中转" }
        }
        onDispose {
            disposed = true
            if (configured) ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(context)) { proxy?.close() }
            else proxy?.close()
        }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember(safeUrl) { mutableStateOf(safeUrl) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    fun back() { if (webView?.canGoBack() == true) webView?.goBack() else onClose() }
    DisposableEffect(Unit) { onDispose { webView?.apply { stopLoading(); onPause(); removeAllViews(); destroy() }; webView = null } }
    Dialog(onDismissRequest = { back() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { back() }) { Text("返回") }
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(Uri.parse(currentUrl).host.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (proxyError != null) Text(proxyError!!, modifier = Modifier.padding(16.dp))
                if (loading && proxyError == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = dev.rivo.relay.ui.theme.RivoSpacing.Page))
                if (error) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text("网页加载失败", modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    TextButton(onClick = { error = false; webView?.reload() }) { Text("重试") }
                }
                if (proxyReady) AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setSupportMultipleWindows(false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = resourceWebUrl(request.url.toString()) == null
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                resourceWebUrl(url)?.let { currentUrl = it }; loading = true; error = false
                            }
                            override fun onPageFinished(view: WebView, url: String) { loading = false }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                                if (request.isForMainFrame) { error = true; loading = false }
                            }
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: android.net.http.SslError) {
                                handler.cancel(); error = true; loading = false
                            }
                        }
                        webView = this
                        loadUrl(safeUrl)
                    }
                })
            }
        }
    }
}
