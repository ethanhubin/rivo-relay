package dev.rivo.relay.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.content.Intent
import android.app.Activity
import java.util.concurrent.atomic.AtomicLong
import android.provider.Settings
import android.provider.DocumentsContract
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rivo.relay.data.string
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

private const val MAX_PREVIEW_BYTES = 20 * 1024 * 1024

internal suspend fun previewBitmap(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    if (bytes.size > MAX_PREVIEW_BYTES) return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth < 1 || bounds.outHeight < 1) return@withContext null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

internal suspend fun previewBitmap(file: File, maxEdge: Int): ImageBitmap? = withContext(Dispatchers.Default) {
    require(maxEdge in 1..4096)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth < 1 || bounds.outHeight < 1) return@withContext null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

private suspend fun imageBytes(source: String, readFile: suspend (String) -> JSONObject): ByteArray {
    if (source.startsWith("data:image/")) {
        require(source.length <= 28 * 1024 * 1024 && source.substringBefore(',').endsWith(";base64")) { "图片编码无法预览" }
        return withContext(Dispatchers.Default) { Base64.decode(source.substringAfter(','), Base64.DEFAULT).also { require(it.size <= MAX_PREVIEW_BYTES) { "单张图片最大支持 20 MiB" } } }
    }
    val file = readFileSource(source, readFile)
    require(file.string("encoding") == "base64") { "节点返回的内容不是图片" }
    return withContext(Dispatchers.Default) {
        val encoded = file.string("dataBase64")
        require(encoded.length <= 28 * 1024 * 1024) { "单张图片最大支持 20 MiB" }
        Base64.decode(encoded, Base64.DEFAULT).also { require(it.size <= MAX_PREVIEW_BYTES) { "单张图片最大支持 20 MiB" } }
    }
}

@Composable
internal fun NativeImage(source: String, readFile: suspend (String) -> JSONObject,
    downloadFile: suspend (String, OutputStream, (Long, Long) -> Unit) -> Unit = { path, output, progress ->
        val bytes = imageBytes(path, readFile); output.write(bytes); progress(bytes.size.toLong(), bytes.size.toLong())
    }, modifier: Modifier = Modifier, identity: String = source, compact: Boolean = false, remaining: Int = 0,
    fadeIn: Boolean = false, loadingContent: (@Composable () -> Unit)? = null) {
    var bitmap by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var origin by remember(source) { mutableStateOf(Rect.Zero) }
    var attempt by remember(source) { mutableIntStateOf(0) }
    var fullscreen by remember(identity) { mutableStateOf(false) }
    var viewerSource by remember(identity) { mutableStateOf(source) }
    var imageAction by remember(source) { mutableStateOf<FileAction?>(null) }
    var menu by remember(source) { mutableStateOf(false) }
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val context = LocalContext.current
    val nativeCache = LocalNativeFileCache.current
    val mediaClient = LocalCodexMediaClient.current
    val mediaSources = mediaAssetsBySource()
    val forwardSource = if (source.startsWith("/v1/media/") || source.startsWith("data:")) mediaSources[source]?.string("source")?.takeIf(String::isNotBlank) else source
    val previewEdge = (maxOf(windowSize.width, windowSize.height) / 2).coerceIn(1, 1536)
    LaunchedEffect(source, previewEdge, mediaClient, mediaClient?.revision, attempt) {
        error = null
        try {
            bitmap = if (mediaClient != null && !source.startsWith("data:")) {
                if (bitmap == null && mediaClient.hasVersion(source)) {
                    val cached = mediaClient.imageLoader.execute(mediaClient.imageRequest(context, source, true)
                        .size(previewEdge).allowHardware(false)
                        .diskCachePolicy(coil.request.CachePolicy.DISABLED)
                        .networkCachePolicy(coil.request.CachePolicy.DISABLED).build())
                    bitmap = (cached.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.asImageBitmap()
                }
                mediaClient.verify(source)
                val result = dev.rivo.relay.data.retryChangedMedia(refresh = { mediaClient.verify(source) }) {
                val request = mediaClient.imageRequest(context, source, true).size(previewEdge).allowHardware(false).build()
                val result = mediaClient.imageLoader.execute(request)
                if (result is coil.request.ErrorResult) throw result.throwable
                result
                }
                (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.asImageBitmap()
            } else if (isHttpWebLink(source) || (!source.startsWith("data:") && nativeCache != null))
                previewBitmap(cacheFileSource(context, source, downloadFile, nativeCache) { _, _ -> }, previewEdge)
            else previewBitmap(imageBytes(source, readFile))
            if (bitmap == null) error = "此图片格式暂不支持预览"
        }
        catch (e: Exception) { if (e is CancellationException) throw e; bitmap = dev.rivo.relay.data.imageAfterValidationFailure(bitmap, e); error = e.message ?: "图片暂时无法读取" }
    }
    val ready = bitmap != null
    var reveal by remember(identity) { mutableFloatStateOf(if (fadeIn) 0f else 1f) }
    LaunchedEffect(ready, identity) {
        if (!ready || reveal == 1f) return@LaunchedEffect
        val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        if (scale > 0f) {
            val start = withFrameNanos { it }
            while (reveal < 1f) {
                reveal = withFrameNanos { ((it - start) / (240_000_000f * scale)).coerceIn(0f, 1f) }
            }
        } else reveal = 1f
    }
    when {
        bitmap != null -> Box(if (compact) modifier.size(76.dp, 88.dp)
            .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)) else modifier) {
            Image(bitmap!!, "会话图片，点击放大", (if (compact) Modifier.fillMaxSize() else Modifier.widthIn(max = with(density) { (windowSize.width / 2).toDp() }).heightIn(max = with(density) { (windowSize.height / 2).toDp() }))
                .then(if (loadingContent != null) Modifier.clip(MaterialTheme.shapes.large) else Modifier)
                .onGloballyPositioned { origin = it.boundsInWindow() }.combinedClickable(onClick = { imageAction = null; viewerSource = source; fullscreen = true }, onLongClick = { menu = true }).testTag("native_image"), contentScale = ContentScale.Fit)
            if (reveal < 1f) {
                ImageGenerationPlaceholder(generating = false, animate = false,
                    modifier = Modifier.matchParentSize().graphicsLayer { alpha = 1f - reveal }, showLabel = false)
            }
            if (compact && remaining > 0) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Text("+$remaining", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            }
            if (menu) Popup(alignment = Alignment.TopCenter, onDismissRequest = { menu = false }, properties = PopupProperties(focusable = true)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp) {
                    Row(Modifier.testTag("image_actions").padding(horizontal = 8.dp)) {
                        forwardSource?.let { ForwardLinkButton(it, fileDisplayName(it)) { menu = false } }
                        listOf("复制" to FileAction.COPY, "保存" to FileAction.SAVE, "分享" to FileAction.SHARE).forEach { (label, action) ->
                            TextButton(onClick = { menu = false; imageAction = action; viewerSource = source; fullscreen = true }, modifier = Modifier.testTag("image_action_${action.name.lowercase()}")) { Text(label) }
                        }
                    }
                }
            }
        }
        error != null -> Text(if (compact) "加载失败\n点击重试" else "${error!!} · 点击重试", (if (compact) modifier.size(76.dp, 88.dp) else modifier).clickable { attempt++ }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        loadingContent != null -> loadingContent()
        else -> if (compact) Box(modifier.size(76.dp, 88.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else LinearProgressIndicator(modifier.fillMaxWidth().padding(vertical = 16.dp))
    }
    if (fullscreen) FilePreviewDialog(viewerSource, readFile, downloadFile, initialAction = imageAction, forceKind = FileOpenKind.IMAGE, origin = origin) { fullscreen = false; imageAction = null }
}

@Composable
internal fun ImageViewerDialog(bitmap: ImageBitmap, onSave: (() -> Unit)? = null, onShare: (() -> Unit)? = null, onDismiss: () -> Unit, source: String? = null) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
 dev.rivo.relay.ui.chat.ThemedTextContextMenu {

        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Image(bitmap, "全屏图片，可双指缩放", Modifier.fillMaxSize().pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 3f; offset = Offset.Zero })
            }.transformable(transform).graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }, contentScale = ContentScale.Fit)
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color.Black.copy(alpha = .6f)), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss) { Icon(FileIcons.Close, "关闭", tint = Color.White) }
                source?.let { ForwardLinkButton(it, fileDisplayName(it), onDismiss) }
                Spacer(Modifier.weight(1f))
                onShare?.let { IconButton(it, Modifier.testTag("share_file")) { Icon(FileIcons.Share, "分享", tint = Color.White) } }
                onSave?.let { IconButton(it, Modifier.testTag("save_file")) { Icon(FileIcons.SaveAlt, "保存到本机", tint = Color.White) } }
            }
        }

}
}
}

/** QiGuang's file-kind routing: binary files open a compact action dialog, previews own their viewer. */
@Composable
internal fun FilePreviewDialog(path: String, readFile: suspend (String) -> JSONObject,
    downloadFile: suspend (String, OutputStream, (Long, Long) -> Unit) -> Unit,
    initialAction: FileAction? = null, forceKind: FileOpenKind? = null, origin: Rect = Rect.Zero, onDismiss: () -> Unit) {
    val requestedLine = remember(path) { Regex("(?::|#L)([0-9]+)(?::[0-9]+)?$").find(path)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } }
    val initialPath = remember(path) { if (isHttpWebLink(path)) path.replace(Regex("#L[0-9]+$"), "") else nativeFilePath(path, "") ?: path }
    var currentPath by remember(path) { mutableStateOf(initialPath) }
    var currentLine by remember(path) { mutableStateOf(requestedLine) }
    val mediaAssets = LocalMediaAssets.current
    val mediaAsset = mediaAssetsBySource()[currentPath]
    val name = if (currentPath.startsWith("/v1/media/")) "image." + (mediaAsset?.string("mimeType")?.substringAfter('/') ?: "png") else fileDisplayName(currentPath)
    var file by remember(currentPath) { mutableStateOf<JSONObject?>(null) }
    val mime = fileMimeType(name, mediaAsset?.string("mimeType") ?: file?.string("mimeType").orEmpty())
    val kind = forceKind ?: fileOpenKind(currentPath, mime)
    val compact = kind in setOf(FileOpenKind.APK, FileOpenKind.EXTERNAL)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaClient = LocalCodexMediaClient.current
    val nativeCache = LocalNativeFileCache.current
    var transfer by remember(path) { mutableStateOf<FileTransferState?>(null) }
    var transferJob by remember(path) { mutableStateOf<Job?>(null) }
    val transferJobHolder = remember(path) { arrayOfNulls<Job>(1) }
    var lastAction by remember(path) { mutableStateOf<FileAction?>(null) }
    var actionSource by remember(path) { mutableStateOf(initialPath) }
    val transferGeneration = remember(path) { AtomicLong() }
    DisposableEffect(path) { onDispose { transferGeneration.incrementAndGet(); transferJobHolder[0]?.cancel() } }
    var pickerRequest by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pickerOpen by remember(path) { mutableStateOf(false) }
    var bitmap by remember(currentPath) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(currentPath) { mutableStateOf<String?>(null) }
    var previewAttempt by remember(currentPath) { mutableIntStateOf(0) }
    var media by remember(currentPath) { mutableStateOf<File?>(null) }
    var formatted by remember(currentPath) { mutableStateOf(currentLine == null) }

    suspend fun cachedSource(source: String, progress: (Long, Long) -> Unit): File {
        val mediaKind = kind in setOf(FileOpenKind.IMAGE, FileOpenKind.VIDEO, FileOpenKind.AUDIO) || fileOpenKind(source) in setOf(FileOpenKind.IMAGE, FileOpenKind.VIDEO, FileOpenKind.AUDIO)
        return if (mediaClient != null && mediaKind && !source.startsWith("data:") && !source.startsWith("content:") && !source.startsWith("file:")) mediaClient.cached(context, source, progress)
        else cacheFileSource(context, source, downloadFile, nativeCache, progress)
    }
    fun startTransfer(action: FileAction, source: String, target: Uri? = null) {
        val (filename, contentType) = mediaActionMetadata(source, mediaAssets, kind == FileOpenKind.IMAGE)
        val operation = transferGeneration.incrementAndGet()
        transferJob?.cancel()
        actionSource = source
        lastAction = action
        val title = when (action) { FileAction.SAVE -> "保存 $filename"; FileAction.OPEN -> if (kind == FileOpenKind.APK) "下载并安装 $filename" else "打开 $filename"; FileAction.SHARE -> "分享 $filename"; FileAction.COPY -> "复制 $filename" }
        if (action == FileAction.OPEN && kind == FileOpenKind.APK && !context.packageManager.canRequestPackageInstalls()) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
            transfer = FileTransferState(title, running = false, message = "请允许安装未知应用，然后再次点“打开”。")
            return
        }
        transfer = FileTransferState(title)
        transferJob = scope.launch {
            val started = System.currentTimeMillis()
            var lastUpdate = 0L
            val report: (Long, Long) -> Unit = { bytes, total ->
                val now = System.currentTimeMillis()
                if (transferGeneration.get() == operation && (now - lastUpdate >= 100 || bytes == total)) {
                    lastUpdate = now
                    scope.launch {
                        if (transferGeneration.get() == operation && transfer?.running == true)
                            transfer = FileTransferState(title, message = "正在下载…", bytes = bytes, total = total, bytesPerSecond = bytes * 1000 / (now - started).coerceAtLeast(1))
                    }
                }
            }
            try {
                if (action == FileAction.SAVE) {
                    requireNotNull(target)
                    val cached = cachedSource(source, report)
                    ensureActive()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(target, "w")?.use { output ->
                            cached.inputStream().use { input ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    ensureActive()
                                    val size = input.read(buffer); if (size < 0) break
                                    output.write(buffer, 0, size)
                                }
                            }
                        } ?: error("无法写入所选位置")
                    }
                    transfer = FileTransferState(title, running = false, message = "已保存到本机")
                } else {
                    val cached = media?.takeIf { source == currentPath } ?: cachedSource(source, report)
                    ensureActive()
                    if (transferGeneration.get() != operation) return@launch
                    if (action == FileAction.COPY) {
                        copyCachedImage(context, cached, filename)
                        android.widget.Toast.makeText(context, "图片已复制", android.widget.Toast.LENGTH_SHORT).show()
                    } else openCachedFile(context, cached, filename, contentType, action == FileAction.SHARE)
                    transfer = null
                    if (compact || initialAction != null) onDismiss()
                }
            } catch (e: Exception) {
                if (target != null) withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) } }
                if (e is CancellationException) throw e
                if (transferGeneration.get() != operation) return@launch
                transfer = FileTransferState(title, running = false, failed = true,
                    message = if (e is android.content.ActivityNotFoundException) "手机上没有可打开此格式的应用，可以保存到本机。" else e.message ?: "下载失败，请重试")
            }
        }
        transferJobHolder[0] = transferJob
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pickerOpen = false
        val request = pickerRequest
        pickerRequest = null
        val uri = result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }
        if (uri != null && request?.first == path) startTransfer(FileAction.SAVE, request.second, uri)
        else if (uri != null) scope.launch(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
        else if (request?.first == path && initialAction != null) onDismiss()
    }
    fun performAction(action: FileAction, source: String = currentPath) {
        if (action == FileAction.SAVE) {
            if (pickerRequest != null) return
            lastAction = action; actionSource = source; transfer = null; pickerOpen = true
            pickerRequest = path to source
            saveFile.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                val (filename, contentType) = mediaActionMetadata(source, mediaAssets, kind == FileOpenKind.IMAGE)
                addCategory(Intent.CATEGORY_OPENABLE); type = contentType; putExtra(Intent.EXTRA_TITLE, filename)
            })
        } else startTransfer(action, source)
    }
    LaunchedEffect(path, initialAction) { initialAction?.let { performAction(it) } }
    LaunchedEffect(currentPath, previewAttempt) {
        if (compact || initialAction != null) return@LaunchedEffect
        error = null
        try {
            when (kind) {
                FileOpenKind.IMAGE -> {
                    if (mediaClient != null) return@LaunchedEffect
                    bitmap = if (isHttpWebLink(currentPath) || (!currentPath.startsWith("data:") && nativeCache != null))
                        previewBitmap(cachedSource(currentPath) { _, _ -> }, 4096)
                    else previewBitmap(imageBytes(currentPath, readFile))
                    if (bitmap == null) error("此图片格式暂不支持预览")
                }
                FileOpenKind.AUDIO, FileOpenKind.VIDEO -> media = cachedSource(currentPath) { _, _ -> }
                else -> file = readFileSource(currentPath, readFile)
            }
        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "无法读取文件" }
    }
    if (initialAction == null) when {
        compact -> if (transfer == null && !pickerOpen) FileOpenActions(name, kind == FileOpenKind.APK, { performAction(it) }, onDismiss, currentPath)
        kind == FileOpenKind.IMAGE && LocalCodexMediaClient.current != null -> CodexImageGallery(currentPath, origin = origin, onSave = { performAction(FileAction.SAVE, it) }, onShare = { performAction(FileAction.SHARE, it) }, onDismiss = onDismiss)
        kind == FileOpenKind.IMAGE && bitmap != null -> ImageViewerDialog(bitmap!!, { performAction(FileAction.SAVE) }, { performAction(FileAction.SHARE) }, onDismiss, currentPath)
        kind == FileOpenKind.AUDIO || kind == FileOpenKind.VIDEO -> MediaFilePreview(name, kind == FileOpenKind.VIDEO, media, error,
            onRetry = { media = null; previewAttempt++ }, onAction = { performAction(it) }, onDismiss = onDismiss, source = currentPath)
        else -> Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
 dev.rivo.relay.ui.chat.ThemedTextContextMenu {

            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().systemBarsPadding().testTag("file_preview")) {
                    FilePreviewToolbar(name, { performAction(FileAction.SAVE) }, { performAction(FileAction.SHARE) }, onDismiss, currentPath)
                    when {
                        error != null -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(error!!, color = MaterialTheme.colorScheme.error)
                            Row { TextButton(onClick = { previewAttempt++ }) { Text("重试") }; TextButton(onClick = { performAction(FileAction.OPEN) }) { Text("用其他应用打开") } }
                        }
                        file == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = dev.rivo.relay.ui.theme.RivoSpacing.Page))
                        kind == FileOpenKind.PDF -> PdfFilePreview(Base64.decode(file!!.string("dataBase64"), Base64.DEFAULT), Modifier.weight(1f))
                        else -> {
                            val text = if (file!!.string("encoding") == "base64") Base64.decode(file!!.string("dataBase64"), Base64.DEFAULT).toString(Charsets.UTF_8) else file!!.string("text")
                            val markdown = name.substringAfterLast('.').lowercase() in setOf("md", "mdx", "markdown")
                            val html = mime == "text/html"
                            if (markdown || html) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { formatted = !formatted }) { Text(if (formatted) "源码" else "预览") }
                            }
                            when {
                                formatted && markdown -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                    MessageContent(text.take(200_000), currentPath.substringBeforeLast('/'), onSaveFile = { selected -> performAction(FileAction.SAVE, selected) }) { selected ->
                                        currentLine = Regex("(?::|#L)([0-9]+)(?::[0-9]+)?$").find(selected)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
                                        currentPath = if (isHttpWebLink(selected)) selected.replace(Regex("#L[0-9]+$"), "") else nativeFilePath(selected, currentPath.substringBeforeLast('/')) ?: selected
                                    }
                                    if (text.length > 200_000) Text("预览已截断，保存文件可查看全部内容。")
                                }
                                formatted && html -> HtmlFilePreview(text, Modifier.weight(1f))
                                else -> SourceCodePreview(text, currentPath, Modifier.weight(1f).fillMaxWidth(), initialLine = currentLine)
                            }
                        }
                    }
                }
            }

}
}
    }
    transfer?.let { state -> FileTransferDialog(state,
        onCancel = { transferGeneration.incrementAndGet(); transferJob?.cancel(); transfer = null; if (compact || initialAction != null) onDismiss() },
        onRetry = { lastAction?.let { performAction(it, actionSource) } },
        onClose = { transfer = null; if (compact || initialAction != null) onDismiss() }) }
}

@Composable
internal fun FilePreviewToolbar(name: String, onSave: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit, source: String? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onDismiss) { Icon(FileIcons.Close, "关闭") }
            Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            source?.let { ForwardLinkButton(it, name, onDismiss) }
            IconButton(onShare, Modifier.testTag("share_file")) { Icon(FileIcons.Share, "分享") }
            IconButton(onSave, Modifier.testTag("save_file")) { Icon(FileIcons.SaveAlt, "保存到本机") }
        }
    }
}
@Composable
private fun PdfFilePreview(bytes: ByteArray, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var page by remember(bytes) { mutableIntStateOf(0) }
    var count by remember(bytes) { mutableIntStateOf(0) }
    var bitmap by remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(bytes) { mutableStateOf<String?>(null) }
    LaunchedEffect(bytes, page) {
        bitmap = null; error = null
        try {
            val rendered = withContext(Dispatchers.IO) {
                require(bytes.size <= MAX_PREVIEW_BYTES) { "PDF 超过预览大小限制，请使用系统打开。" }
                val temporary = File.createTempFile("relay-pdf-", ".pdf", context.cacheDir)
                try {
                    temporary.writeBytes(bytes)
                    PdfRenderer(ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                        require(pdf.pageCount > 0) { "PDF 没有页面" }
                        pdf.openPage(page.coerceAtMost(pdf.pageCount - 1)).use { source ->
                            val scale = minOf(1600f / source.width, 2400f / source.height, 2f)
                            val output = Bitmap.createBitmap((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            output.eraseColor(android.graphics.Color.WHITE)
                            source.render(output, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            output.asImageBitmap() to pdf.pageCount
                        }
                    }
                } finally { temporary.delete() }
            }
            bitmap = rendered.first; count = rendered.second
        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "PDF 无法预览，请使用系统打开。" }
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { page-- }, enabled = page > 0 && bitmap != null) { Text("上一页") }
            Text(if (count == 0) "载入 PDF" else "${page + 1} / $count")
            TextButton(onClick = { page++ }, enabled = page + 1 < count && bitmap != null) { Text("下一页") }
        }
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (bitmap != null) Image(bitmap!!, "PDF 第 ${page + 1} 页", Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
        else if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = dev.rivo.relay.ui.theme.RivoSpacing.Page))
    }
}
