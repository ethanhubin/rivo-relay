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

/** Website browsing is isolated from Relay transport: no node headers or native JS bridge. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SiteBrowser(initialUrl: String, title: String, onClose: () -> Unit) {
    val safeUrl = resourceWebUrl(initialUrl) ?: return
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
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text("网页加载失败", modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    TextButton(onClick = { error = false; webView?.reload() }) { Text("重试") }
                }
                AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
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
