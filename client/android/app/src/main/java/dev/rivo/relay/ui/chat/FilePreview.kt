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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

private const val MAX_PREVIEW_BYTES = 4 * 1024 * 1024

internal suspend fun previewBitmap(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    if (bytes.size > MAX_PREVIEW_BYTES) return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth < 1 || bounds.outHeight < 1) return@withContext null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

private suspend fun imageBytes(source: String, readFile: suspend (String) -> JSONObject): ByteArray {
    if (source.startsWith("data:image/")) {
        require(source.length <= 6 * 1024 * 1024 && source.substringBefore(',').endsWith(";base64")) { "图片编码无法预览" }
        return Base64.decode(source.substringAfter(','), Base64.DEFAULT)
    }
    val file = readFileSource(source, readFile)
    require(file.string("encoding") == "base64") { "节点返回的内容不是图片" }
    return Base64.decode(file.string("dataBase64"), Base64.DEFAULT)
}

@Composable
internal fun NativeImage(source: String, readFile: suspend (String) -> JSONObject,
    downloadFile: suspend (String, OutputStream, (Long, Long) -> Unit) -> Unit = { path, output, progress ->
        val bytes = imageBytes(path, readFile); output.write(bytes); progress(bytes.size.toLong(), bytes.size.toLong())
    }, modifier: Modifier = Modifier) {
    var bitmap by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var fullscreen by remember(source) { mutableStateOf(false) }
    var imageAction by remember(source) { mutableStateOf<FileAction?>(null) }
    var menu by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        try { bitmap = previewBitmap(imageBytes(source, readFile)); if (bitmap == null) error = "此图片格式暂不支持预览" }
        catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "图片暂时无法读取" }
    }
    when {
        bitmap != null -> Box(modifier) {
            Image(bitmap!!, "会话图片，点击放大", Modifier.fillMaxWidth().heightIn(max = 280.dp)
                .combinedClickable(onClick = { imageAction = null; fullscreen = true }, onLongClick = { menu = true }).testTag("native_image"), contentScale = ContentScale.Fit)
            if (menu) Popup(alignment = Alignment.TopCenter, onDismissRequest = { menu = false }, properties = PopupProperties(focusable = true)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp) {
                    Row(Modifier.testTag("image_actions").padding(horizontal = 8.dp)) {
                        listOf("复制" to FileAction.COPY, "保存" to FileAction.SAVE, "分享" to FileAction.SHARE).forEach { (label, action) ->
                            TextButton(onClick = { menu = false; imageAction = action; fullscreen = true }, modifier = Modifier.testTag("image_action_${action.name.lowercase()}")) { Text(label) }
                        }
                    }
                }
            }
        }
        error != null -> Text(error!!, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> LinearProgressIndicator(modifier.fillMaxWidth().padding(vertical = 16.dp))
    }
    if (fullscreen) FilePreviewDialog(source, readFile, downloadFile, initialAction = imageAction) { fullscreen = false; imageAction = null }
}

@Composable
internal fun ImageViewerDialog(bitmap: ImageBitmap, onSave: (() -> Unit)? = null, onShare: (() -> Unit)? = null, onDismiss: () -> Unit) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Image(bitmap, "全屏图片，可双指缩放", Modifier.fillMaxSize().pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 3f; offset = Offset.Zero })
            }.transformable(transform).graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }, contentScale = ContentScale.Fit)
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color.Black.copy(alpha = .6f)), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss) { Icon(FileIcons.Close, "关闭", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                onShare?.let { IconButton(it, Modifier.testTag("share_file")) { Icon(FileIcons.Share, "分享", tint = Color.White) } }
                onSave?.let { IconButton(it, Modifier.testTag("save_file")) { Icon(FileIcons.SaveAlt, "保存到本机", tint = Color.White) } }
            }
        }
    }
}

/** QiGuang's file-kind routing: binary files open a compact action dialog, previews own their viewer. */
@Composable
internal fun FilePreviewDialog(path: String, readFile: suspend (String) -> JSONObject,
    downloadFile: suspend (String, OutputStream, (Long, Long) -> Unit) -> Unit,
    initialAction: FileAction? = null, onDismiss: () -> Unit) {
    val requestedLine = remember(path) { Regex("(?::|#L)([0-9]+)(?::[0-9]+)?$").find(path)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } }
    val initialPath = remember(path) { if (isHttpWebLink(path)) path.replace(Regex("#L[0-9]+$"), "") else nativeFilePath(path, "") ?: path }
    var currentPath by remember(path) { mutableStateOf(initialPath) }
    var currentLine by remember(path) { mutableStateOf(requestedLine) }
    val name = fileDisplayName(currentPath)
    var file by remember(currentPath) { mutableStateOf<JSONObject?>(null) }
    val mime = fileMimeType(name, file?.string("mimeType").orEmpty())
    val kind = fileOpenKind(currentPath, mime)
    val compact = kind in setOf(FileOpenKind.APK, FileOpenKind.EXTERNAL)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var transfer by remember(path) { mutableStateOf<FileTransferState?>(null) }
    var transferJob by remember(path) { mutableStateOf<Job?>(null) }
    var lastAction by remember(path) { mutableStateOf<FileAction?>(null) }
    var actionSource by remember(path) { mutableStateOf(initialPath) }
    val transferGeneration = remember(path) { AtomicLong() }
    var pickerOpen by remember(path) { mutableStateOf(false) }
    var bitmap by remember(currentPath) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(currentPath) { mutableStateOf<String?>(null) }
    var previewAttempt by remember(currentPath) { mutableIntStateOf(0) }
    var media by remember(currentPath) { mutableStateOf<File?>(null) }
    var formatted by remember(currentPath) { mutableStateOf(currentLine == null) }

    fun startTransfer(action: FileAction, source: String, target: Uri? = null) {
        val filename = fileDisplayName(source)
        val contentType = fileMimeType(filename)
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
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(target, "w")?.use { output -> downloadFileSource(source, output, report, downloadFile) } ?: error("无法写入所选位置")
                    }
                    transfer = FileTransferState(title, running = false, message = "已保存到本机")
                } else {
                    val cached = media?.takeIf { source == currentPath } ?: cacheFileSource(context, source, downloadFile, report)
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
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pickerOpen = false
        val uri = result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }
        if (uri != null) startTransfer(FileAction.SAVE, actionSource, uri) else if (initialAction != null) onDismiss()
    }
    fun performAction(action: FileAction, source: String = currentPath) {
        if (action == FileAction.SAVE) {
            lastAction = action; actionSource = source; transfer = null; pickerOpen = true
            saveFile.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = fileMimeType(fileDisplayName(source)); putExtra(Intent.EXTRA_TITLE, fileDisplayName(source))
            })
        } else startTransfer(action, source)
    }
    LaunchedEffect(path, initialAction) { initialAction?.let { performAction(it) } }
    LaunchedEffect(currentPath, previewAttempt) {
        if (compact || initialAction != null) return@LaunchedEffect
        error = null
        try {
            when (kind) {
                FileOpenKind.IMAGE -> bitmap = previewBitmap(imageBytes(currentPath, readFile)) ?: error("此图片格式暂不支持预览")
                FileOpenKind.AUDIO, FileOpenKind.VIDEO -> media = cacheFileSource(context, currentPath, downloadFile) { _, _ -> }
                else -> file = readFileSource(currentPath, readFile)
            }
        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "无法读取文件" }
    }
    if (initialAction == null) when {
        compact -> if (transfer == null && !pickerOpen) FileOpenActions(name, kind == FileOpenKind.APK, { performAction(it) }, onDismiss)
        kind == FileOpenKind.IMAGE && bitmap != null -> ImageViewerDialog(bitmap!!, { performAction(FileAction.SAVE) }, { performAction(FileAction.SHARE) }, onDismiss)
        kind == FileOpenKind.AUDIO || kind == FileOpenKind.VIDEO -> MediaFilePreview(name, kind == FileOpenKind.VIDEO, media, error,
            onRetry = { media = null; previewAttempt++ }, onAction = { performAction(it) }, onDismiss = onDismiss)
        else -> Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().systemBarsPadding().testTag("file_preview")) {
                    FilePreviewToolbar(name, { performAction(FileAction.SAVE) }, { performAction(FileAction.SHARE) }, onDismiss)
                    when {
                        error != null -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(error!!, color = MaterialTheme.colorScheme.error)
                            Row { TextButton(onClick = { previewAttempt++ }) { Text("重试") }; TextButton(onClick = { performAction(FileAction.OPEN) }) { Text("用其他应用打开") } }
                        }
                        file == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
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
    transfer?.let { state -> FileTransferDialog(state,
        onCancel = { transferGeneration.incrementAndGet(); transferJob?.cancel(); transfer = null; if (compact || initialAction != null) onDismiss() },
        onRetry = { lastAction?.let { performAction(it, actionSource) } },
        onClose = { transfer = null; if (compact || initialAction != null) onDismiss() }) }
}

@Composable
internal fun FilePreviewToolbar(name: String, onSave: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onDismiss) { Icon(FileIcons.Close, "关闭") }
            Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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
        else if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
