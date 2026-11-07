@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package dev.rivo.relay.ui.chat

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.io.File

/** QiGuang WorkspaceAudioPlayer with Relay file actions and lifecycle ownership. */
@Composable
internal fun MediaFilePreview(name: String, video: Boolean, file: File?, error: String?, onRetry: () -> Unit,
    onAction: (FileAction) -> Unit, onDismiss: () -> Unit, source: String? = null) {
    if (video && file != null && error == null) {
        val item = remember(file, source) { VideoViewerItem(null, android.net.Uri.fromFile(file).toString(), source = source ?: name, displayName = name, mimeType = fileMimeType(name)) }
        FullScreenVideo(remember(item) { VideoViewerSource(listOf(item), 0, androidx.compose.ui.geometry.Rect.Zero) }, onDismiss,
            onDownload = { onAction(FileAction.SAVE) }, onShare = { onAction(FileAction.SHARE) }, onOpenExternal = { onAction(FileAction.OPEN) })
        return
    }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var player by remember(file) { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    var prepared by remember(file) { mutableStateOf(false) }
    var mediaError by remember(file) { mutableStateOf<String?>(null) }
    var position by remember(file) { mutableLongStateOf(0) }
    var duration by remember(file) { mutableLongStateOf(0) }
    var seeking by remember(file) { mutableStateOf(false) }
    var attempt by remember(file) { mutableIntStateOf(0) }
    DisposableEffect(file, video, error, attempt, owner) {
        var audio: ExoPlayer? = null
        var listener: Player.Listener? = null
        prepared = false; playing = false
        if (file != null && !video && error == null) {
            try {
                val created = ExoPlayer.Builder(context).build()
                audio = created
                val playbackListener = object : Player.Listener {
                    override fun onEvents(current: Player, events: Player.Events) {
                        playing = current.isPlaying
                        prepared = current.playerError == null && current.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED)
                        duration = current.duration.takeIf { it != C.TIME_UNSET && it >= 0 } ?: 0L
                        if (!seeking) position = current.currentPosition.coerceAtLeast(0)
                    }
                    override fun onPlayerError(failure: PlaybackException) {
                        mediaError = when (failure.errorCode) {
                            in 2000..2999 -> "无法读取音频文件，请重试"
                            in 3000..4999 -> "手机暂不支持此音频格式，或文件内容已损坏"
                            else -> "音频播放失败，请重试或使用其他应用打开"
                        }
                        created.pause(); playing = false; prepared = false
                    }
                }
                listener = playbackListener
                created.addListener(playbackListener)
                created.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                created.setHandleAudioBecomingNoisy(true)
                created.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player = created
                created.playWhenReady = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                created.prepare()
            } catch (e: Exception) { mediaError = e.message ?: "无法打开媒体" }
        }
        onDispose {
            listener?.let { audio?.removeListener(it) }
            audio?.release()
            if (player === audio) player = null
        }
    }
    DisposableEffect(owner, player) {
        val current = player
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) current?.pause()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { current?.pause(); playing = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(playing, player) {
        while (playing) {
            if (!seeking) position = player?.currentPosition?.coerceAtLeast(0) ?: 0L
            delay(250)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = !video)) {
        Surface(Modifier.then(if (video) Modifier.fillMaxSize() else Modifier.fillMaxWidth()), shape = if (video) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.extraLarge) {
            Column(Modifier.then(if (video) Modifier.systemBarsPadding() else Modifier.padding(vertical = 12.dp)).testTag(if (video) "video_preview" else "audio_preview")) {
                if (video) FilePreviewToolbar(name, { onAction(FileAction.SAVE) }, { onAction(FileAction.SHARE) }, onDismiss, source)
                else Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(FileIcons.MusicNote, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(name, style = MaterialTheme.typography.titleMedium)
                }
                val failure = error ?: mediaError
                when {
                    failure != null -> Column(Modifier.padding(24.dp)) {
                        Text(failure, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { mediaError = null; attempt++; onRetry() }, modifier = Modifier.testTag("audio_retry")) { Text("重试") }
                        TextButton(onClick = { onAction(FileAction.OPEN) }) { Text("用其他应用打开") }
                    }
                    file == null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在载入媒体…")
                    }
                    else -> Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!prepared) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), onValueChange = {
                            seeking = true; position = it.toLong()
                        }, onValueChangeFinished = { player?.seekTo(position); seeking = false },
                            valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = prepared,
                            modifier = Modifier.testTag("audio_progress"))
                        Text("${mediaTime(position)} / ${mediaTime(duration)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("audio_position"))
                        IconButton(onClick = {
                            player?.let { current ->
                                if (current.isPlaying) current.pause() else {
                                    if (current.playbackState == Player.STATE_ENDED) current.seekTo(0)
                                    current.play()
                                }
                            }
                        }, enabled = prepared, modifier = Modifier.testTag("audio_play_pause")) {
                            Icon(if (playing) FileIcons.Pause else FileIcons.PlayArrow, if (playing) "暂停" else "播放")
                        }
                    }
                }
                if (!video) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onAction(FileAction.SHARE) }) { Text("分享") }
                    TextButton(onClick = { onAction(FileAction.SAVE) }) { Text("保存到本机") }
                    source?.let { ForwardLinkButton(it, name, onDismiss) }
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
