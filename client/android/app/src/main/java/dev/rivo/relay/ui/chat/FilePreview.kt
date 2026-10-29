package dev.rivo.relay.ui.chat

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import dev.rivo.relay.data.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

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

private suspend fun imageBytes(source: String, readFile: suspend (String) -> JSONObject): ByteArray = withContext(Dispatchers.IO) {
    when {
        source.startsWith("data:image/") -> {
            require(source.length < 6 * 1024 * 1024 && source.substringBefore(',').endsWith(";base64")) { "图片编码无法预览" }
            Base64.decode(source.substringAfter(','), Base64.DEFAULT)
        }
        source.startsWith("https://") -> {
            // Remote image URLs never receive the node credential; do not follow credential-leaking redirects.
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).callTimeout(20, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(source).build()).execute().use { response ->
                require(response.isSuccessful) { "无法读取远程图片" }
                val body = requireNotNull(response.body)
                require(body.contentLength() <= MAX_PREVIEW_BYTES) { "图片超过预览大小限制" }
                body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= MAX_PREVIEW_BYTES) { "图片超过预览大小限制" }; output.write(buffer, 0, count) }
                    output.toByteArray()
                }
            }
        }
        else -> {
            val file = readFile(source)
            require(file.string("encoding") == "base64") { "节点返回的内容不是图片" }
            Base64.decode(file.string("dataBase64"), Base64.DEFAULT)
        }
    }
}

@Composable
internal fun NativeImage(source: String, readFile: suspend (String) -> JSONObject, modifier: Modifier = Modifier) {
    var bitmap by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var fullscreen by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        try { bitmap = previewBitmap(imageBytes(source, readFile)); if (bitmap == null) error = "此图片格式暂不支持预览" }
        catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "图片暂时无法读取" }
    }
    when {
        bitmap != null -> Image(bitmap!!, "会话图片，点击放大", modifier.fillMaxWidth().heightIn(max = 280.dp).clickable { fullscreen = true }.testTag("native_image"), contentScale = ContentScale.Fit)
        error != null -> Text(error!!, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> LinearProgressIndicator(modifier.fillMaxWidth().padding(vertical = 16.dp))
    }
    if (fullscreen && bitmap != null) ImageViewerDialog(bitmap!!) { fullscreen = false }
}

@Composable
internal fun ImageViewerDialog(bitmap: ImageBitmap, onDismiss: () -> Unit) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Image(bitmap, "全屏图片，可双指缩放", Modifier.fillMaxSize().transformable(transform).graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }, contentScale = ContentScale.Fit)
            Row(Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha = .7f))) {
                TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("重置", color = Color.White) }
                TextButton(onClick = onDismiss) { Text("关闭", color = Color.White) }
            }
        }
    }
}

@Composable
internal fun FilePreviewDialog(path: String, readFile: suspend (String) -> JSONObject,
    downloadFile: suspend (String, java.io.OutputStream, (Long, Long) -> Unit) -> Unit, onDismiss: () -> Unit) {
    val requestedLine = remember(path) { Regex("(?::|#L)([0-9]+)(?::[0-9]+)?$").find(path)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } }
    val initialPath = remember(path) { nativeFilePath(path, "") ?: path }
    var currentPath by remember(path) { mutableStateOf(initialPath) }
    var file by remember(currentPath) { mutableStateOf<JSONObject?>(null) }
    var error by remember(currentPath) { mutableStateOf<String?>(null) }
    var bitmap by remember(currentPath) { mutableStateOf<ImageBitmap?>(null) }
    var fullscreen by remember(currentPath) { mutableStateOf(false) }
    var markdown by remember(currentPath) { mutableStateOf(requestedLine == null && currentPath.substringAfterLast('.').lowercase() in setOf("md", "markdown")) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    val mime = fileMimeType(currentPath, file?.string("mimeType").orEmpty())
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) scope.launch {
            downloading = true; downloadStatus = "正在下载…"
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        downloadFile(currentPath, output) { count, total ->
                            downloadStatus = if (total > 0) "正在下载 ${count * 100 / total}%" else "正在下载 ${count / 1024} KiB"
                        }
                    } ?: error("无法写入所选位置")
                }
                downloadStatus = "已保存到手机，可在文件管理器中打开"
            } catch (e: Exception) {
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                if (e is CancellationException) throw e
                downloadStatus = e.message ?: "下载失败，请重试"
            } finally { downloading = false }
        }
    }
    fun openExternally(share: Boolean) {
        scope.launch {
            downloading = true; downloadStatus = "正在准备文件…"
            var cached: File? = null
            try {
                cached = withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "previews").apply { mkdirs() }
                    directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > TimeUnit.DAYS.toMillis(1) }?.forEach { it.delete() }
                    val name = currentPath.replace('\\', '/').substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeLast(160).ifBlank { "file" }
                    File(directory, "${UUID.randomUUID()}-$name").also { destination ->
                        try {
                            destination.outputStream().use { output ->
                                downloadFile(currentPath, output) { count, total ->
                                    downloadStatus = if (total > 0) "正在下载 ${count * 100 / total}%" else "正在下载 ${count / 1024} KiB"
                                }
                            }
                        } catch (e: Exception) { destination.delete(); throw e }
                    }
                }
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", cached!!)
                val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                    if (share) { type = mime; putExtra(Intent.EXTRA_STREAM, uri) } else setDataAndType(uri, mime)
                    clipData = ClipData.newRawUri("文件", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, if (share) "分享文件" else "打开文件"))
                downloadStatus = null
            } catch (e: Exception) {
                cached?.delete()
                if (e is CancellationException) throw e
                downloadStatus = e.message ?: "无法打开文件，可保存到手机后打开。"
            } finally { downloading = false }
        }
    }
    LaunchedEffect(currentPath) {
        try {
            if (currentPath.substringAfterLast('.').lowercase() in setOf("apk", "zip", "7z", "rar", "exe", "dmg", "mp3", "m4a", "mp4", "webm", "mov", "wav", "ogg")) {
                file = JSONObject().put("encoding", "binary")
            } else {
                val loaded = readFile(currentPath)
                file = loaded
                if (loaded.string("mimeType").startsWith("image/") && loaded.string("encoding") == "base64")
                    bitmap = previewBitmap(Base64.decode(loaded.string("dataBase64"), Base64.DEFAULT))
            }
        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "无法读取文件" }
    }
    Dialog(onDismissRequest = { if (!downloading) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().testTag("file_preview")) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(currentPath.substringAfterLast('/'), Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 2, style = MaterialTheme.typography.titleMedium)
                    TextButton(onDismiss, enabled = !downloading) { Text("关闭") }
                }
                SelectionContainer { Text(currentPath, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall) }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { saveFile.launch(currentPath.replace('\\', '/').substringAfterLast('/')) }, enabled = !downloading, modifier = Modifier.testTag("save_file")) { Text("保存到手机") }
                    TextButton(onClick = { openExternally(false) }, enabled = !downloading, modifier = Modifier.testTag("open_file")) { Text("系统打开") }
                    TextButton(onClick = { openExternally(true) }, enabled = !downloading, modifier = Modifier.testTag("share_file")) { Text("分享") }
                    if (mime == "text/markdown" && file?.string("encoding") == "utf-8") TextButton(onClick = { markdown = !markdown }) { Text(if (markdown) "源码" else "预览") }
                }
                downloadStatus?.let { Text(it, Modifier.padding(horizontal = 16.dp).testTag("file_download_status"), style = MaterialTheme.typography.bodySmall) }
                if (downloading) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    error != null -> Text(error!! + "\n可尝试保存或使用系统打开。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    file == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    bitmap != null -> Image(bitmap!!, "文件图片预览，点击全屏", Modifier.weight(1f).fillMaxWidth().clickable { fullscreen = true }, contentScale = ContentScale.Fit)
                    mime == "application/pdf" && file!!.string("encoding") in setOf("base64", "utf-8") -> {
                        val bytes = remember(file) { if (file!!.string("encoding") == "base64") Base64.decode(file!!.string("dataBase64"), Base64.DEFAULT) else file!!.string("text").toByteArray(Charsets.UTF_8) }
                        PdfFilePreview(bytes, Modifier.weight(1f))
                    }
                    file!!.string("encoding") == "utf-8" -> {
                        val text = file!!.string("text")
                        if (markdown) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            MessageContent(text.take(200_000), currentPath.substringBeforeLast('/')) { selected -> if (!downloading) currentPath = selected }
                            if (text.length > 200_000) Text("预览已截断，保存文件可查看全部内容。")
                        } else SourceCodePreview(text, currentPath, Modifier.weight(1f).fillMaxWidth(), initialLine = requestedLine.takeIf { currentPath == initialPath })
                    }
                    else -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (mime.startsWith("audio/") || mime.startsWith("video/")) "使用系统播放器打开此媒体文件。" else "此格式可用手机上的应用打开。")
                        Text("系统打开和分享会先下载临时文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (fullscreen && bitmap != null) ImageViewerDialog(bitmap!!) { fullscreen = false }
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
