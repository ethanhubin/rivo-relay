package dev.rivo.relay.ui.chat

import android.media.MediaPlayer
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.io.File

/** Same media-viewer flow as QiGuang, using Android's bundled decoder. */
@Composable
internal fun MediaFilePreview(name: String, video: Boolean, file: File?, error: String?, onRetry: () -> Unit,
    onAction: (FileAction) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    var prepared by remember(file) { mutableStateOf(false) }
    var mediaError by remember(file) { mutableStateOf<String?>(null) }
    var position by remember(file) { mutableLongStateOf(0) }
    var duration by remember(file) { mutableLongStateOf(0) }
    DisposableEffect(file, video) {
        var audio: MediaPlayer? = null
        if (file != null && !video) {
            try {
                val created = MediaPlayer()
                audio = created
                created.apply {
                    setDataSource(file.absolutePath)
                    setOnPreparedListener { prepared = true; duration = it.duration.toLong(); player = it }
                    setOnCompletionListener { playing = false; position = duration }
                    setOnErrorListener { _, _, _ -> mediaError = "手机暂不支持此媒体格式"; playing = false; prepared = false; true }
                    prepareAsync()
                }
            } catch (e: Exception) { mediaError = e.message ?: "无法打开媒体" }
        }
        onDispose { player = null; audio?.release() }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { runCatching { player?.pause() }; videoView?.pause(); playing = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); videoView?.stopPlayback() }
    }
    LaunchedEffect(playing, player) {
        while (playing) { position = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0); delay(250) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = !video)) {
        Surface(Modifier.then(if (video) Modifier.fillMaxSize() else Modifier.fillMaxWidth()), shape = if (video) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.extraLarge) {
            Column(Modifier.then(if (video) Modifier.systemBarsPadding() else Modifier.padding(vertical = 12.dp)).testTag(if (video) "video_preview" else "audio_preview")) {
                if (video) FilePreviewToolbar(name, { onAction(FileAction.SAVE) }, { onAction(FileAction.SHARE) }, onDismiss)
                else Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(FileIcons.MusicNote, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(name, style = MaterialTheme.typography.titleMedium)
                }
                val failure = error ?: mediaError
                when {
                    failure != null -> Column(Modifier.padding(24.dp)) {
                        Text(failure, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { mediaError = null; onRetry() }) { Text("重试") }
                        TextButton(onClick = { onAction(FileAction.OPEN) }) { Text("用其他应用打开") }
                    }
                    file == null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在载入媒体…")
                    }
                    video -> key(file.absolutePath) { AndroidView(factory = {
                        VideoView(context).apply {
                            videoView = this
                            setMediaController(MediaController(context).also { it.setAnchorView(this) })
                            setOnErrorListener { _, _, _ -> mediaError = "手机暂不支持此媒体格式"; true }
                            setVideoPath(file.absolutePath)
                            setOnPreparedListener { if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start() }
                        }
                    }, onRelease = { it.stopPlayback(); if (videoView === it) videoView = null }, modifier = Modifier.fillMaxWidth().weight(1f)) }
                    else -> Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), onValueChange = {
                            position = it.toLong(); player?.seekTo(it.toInt())
                        }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = prepared)
                        Text("${mediaTime(position)} / ${mediaTime(duration)}", style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { if (playing) player?.pause() else { if (position >= duration) player?.seekTo(0); player?.start() }; playing = !playing }, enabled = prepared) {
                            Icon(if (playing) FileIcons.Pause else FileIcons.PlayArrow, if (playing) "暂停" else "播放")
                        }
                    }
                }
                if (!video) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onAction(FileAction.SHARE) }) { Text("分享") }
                    TextButton(onClick = { onAction(FileAction.SAVE) }) { Text("保存到本机") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

private fun mediaTime(value: Long) = "%d:%02d".format(value / 60000, value / 1000 % 60)

/** Local HTML is untrusted: no JavaScript, file/content access, or app bridge. */
@Composable
internal fun HtmlFilePreview(html: String, modifier: Modifier = Modifier) {
    val handler = LocalUriHandler.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
    AndroidView(factory = { context -> WebView(context).apply {
        webView = this
        settings.javaScriptEnabled = false
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.setSupportMultipleWindows(false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && request.hasGesture() && isHttpWebLink(request.url.toString())) runCatching { handler.openUri(request.url.toString()) }
                return true
            }
        }
    } }, update = { view -> if (view.tag != html) { view.tag = html; view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) } }, modifier = modifier.fillMaxWidth())
}
