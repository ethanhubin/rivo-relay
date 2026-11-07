package dev.rivo.relay.ui.chat

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import dev.rivo.relay.data.ResumableDownload
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class FileAction { SAVE, OPEN, SHARE, COPY }
internal enum class FileOpenKind { IMAGE, VIDEO, AUDIO, TEXT, PDF, APK, EXTERNAL }
internal typealias NativeCachedDownload = suspend (String, (Long, Long) -> Unit) -> File
internal val LocalNativeFileCache = staticCompositionLocalOf<NativeCachedDownload?> { null }

/** Type routing and action-sheet layout adapted from QiGuang ChatResourceResolver/MainScreen. */
internal fun fileDisplayName(source: String): String {
    if (source.startsWith("data:image/")) return "image." + source.substringAfter("data:image/").substringBefore(';').replace("jpeg", "jpg")
    val path = if (isHttpWebLink(source)) runCatching { URI(source).path }.getOrNull().orEmpty() else source
    return path.substringBefore('#').substringBefore('?').replace('\\', '/').substringAfterLast('/').ifBlank { "文件" }
}

internal fun fileOpenKind(source: String, mime: String = ""): FileOpenKind {
    val normalizedMime = mime.substringBefore(';').trim().lowercase()
    val extension = fileDisplayName(source).substringAfterLast('.', "").lowercase()
    return when {
        extension == "apk" || normalizedMime == "application/vnd.android.package-archive" -> FileOpenKind.APK
        extension == "svg" || normalizedMime == "image/svg+xml" -> FileOpenKind.TEXT
        normalizedMime.startsWith("image/") || extension in setOf("png", "jpg", "jpeg", "webp", "gif", "avif", "bmp", "heic", "heif") -> FileOpenKind.IMAGE
        normalizedMime.startsWith("video/") || extension in (dev.rivo.relay.data.VideoFormatPolicy.acceptedExtensions - "ts") -> FileOpenKind.VIDEO
        normalizedMime.startsWith("audio/") || extension in setOf("aac", "flac", "m4a", "mp3", "ogg", "opus", "wav", "weba") -> FileOpenKind.AUDIO
        extension == "pdf" || normalizedMime == "application/pdf" -> FileOpenKind.PDF
        normalizedMime.startsWith("text/") || normalizedMime in setOf("application/json", "application/xml") || extension in setOf("md", "mdx", "markdown", "txt", "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "json", "jsonl", "ipynb", "geojson", "yaml", "yml", "xml", "xsl", "xsd", "html", "htm", "css", "scss", "sh", "zsh", "bash", "sql", "cs", "c", "h", "cc", "cpp", "hpp", "rs", "go", "toml", "gradle", "properties", "swift", "log") -> FileOpenKind.TEXT
        else -> FileOpenKind.EXTERNAL
    }
}

@Composable
internal fun FileOpenActions(name: String, isApk: Boolean, onAction: (FileAction) -> Unit, onDismiss: () -> Unit, source: String? = null) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("file_open_actions"),
        title = { Text(name) },
        text = { Column {
            Text(if (isApk) "选择保存到本机或打开 APK。" else "选择打开文件、保存到本机或分享。")
            if (!isApk) TextButton(onClick = { onAction(FileAction.SHARE) }, modifier = Modifier.testTag("share_file")) { Text("分享") }
            source?.let { ForwardLinkButton(it, name, onDismiss) }
        } },
        confirmButton = { TextButton(onClick = { onAction(FileAction.OPEN) }, modifier = Modifier.testTag("open_file")) { Text(if (isApk) "打开" else "用其他应用打开") } },
        dismissButton = { Row {
            TextButton(onClick = { onAction(FileAction.SAVE) }, modifier = Modifier.testTag("save_file")) { Text("保存到本机") }
            TextButton(onClick = onDismiss) { Text("取消") }
        } })
}

internal data class FileTransferState(val title: String, val running: Boolean = true, val failed: Boolean = false,
    val message: String = "正在准备…", val bytes: Long = 0, val total: Long = -1, val bytesPerSecond: Long = 0)

@Composable
internal fun FileTransferDialog(state: FileTransferState, onCancel: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = {}, modifier = Modifier.testTag("file_transfer_dialog"),
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(state.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.running) {
                if (state.total > 0) LinearProgressIndicator(progress = { (state.bytes.toDouble() / state.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(formatTransferBytes(state.bytes) + (if (state.total > 0) " / ${formatTransferBytes(state.total)} · ${state.bytes * 100 / state.total}%" else "") +
                    if (state.bytesPerSecond > 0) " · ${formatTransferBytes(state.bytesPerSecond)}/s" else "", modifier = Modifier.testTag("file_transfer_details"))
            }
            Text(state.message, color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { when {
            state.running -> TextButton(onClick = onCancel, modifier = Modifier.testTag("file_transfer_cancel")) { Text("取消") }
            state.failed -> TextButton(onClick = onRetry, modifier = Modifier.testTag("file_transfer_retry")) { Text("重试") }
            else -> TextButton(onClick = onClose, modifier = Modifier.testTag("file_transfer_close")) { Text("关闭") }
        } }, dismissButton = { if (state.failed) TextButton(onClick = onClose) { Text("关闭") } })
}

internal fun formatTransferBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GiB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private val publicFileClient = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(true)
    .followSslRedirects(false).callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(180, TimeUnit.SECONDS).build()

/** Generic public files omit node credentials; media viewers choose their authenticated proxy first. */
internal suspend fun downloadFileSource(source: String, output: OutputStream, progress: (Long, Long) -> Unit,
    nativeDownload: suspend (String, OutputStream, (Long, Long) -> Unit) -> Unit) {
    if (source.startsWith("data:image/")) {
        require(source.length <= 28 * 1024 * 1024 && source.substringBefore(',').endsWith(";base64")) { "图片编码无法读取" }
        val bytes = android.util.Base64.decode(source.substringAfter(','), android.util.Base64.DEFAULT)
        require(bytes.size <= 20 * 1024 * 1024) { "单张图片最大支持 20 MiB" }
        withContext(Dispatchers.IO) { output.write(bytes); output.flush() }
        progress(bytes.size.toLong(), bytes.size.toLong()); return
    }
    if (!isHttpWebLink(source)) { nativeDownload(source, output, progress); return }
    suspendCancellableCoroutine<Unit> { continuation ->
        val call = publicFileClient.newCall(Request.Builder().url(source).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try { response.use {
                    if (!it.isSuccessful) throw IOException("下载失败：HTTP ${it.code}")
                    val body = it.body ?: throw IOException("文件内容为空")
                    val limit = 1024L * 1024 * 1024
                    val total = body.contentLength()
                    require(total <= limit) { "文件最大支持 1 GiB" }
                    var count = 0L
                    body.byteStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            if (call.isCanceled()) throw IOException("下载已取消")
                            val size = input.read(buffer); if (size < 0) break
                            count += size; require(count <= limit) { "文件最大支持 1 GiB" }
                            output.write(buffer, 0, size); progress(count, total)
                        }
                    }
                    require(total < 0 || count == total) { "下载不完整，请重试" }
                    output.flush()
                    if (continuation.isActive) continuation.resume(Unit)
                } } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
}

internal suspend fun readFileSource(source: String, nativeRead: suspend (String) -> JSONObject): JSONObject {
    if (!isHttpWebLink(source)) return nativeRead(source)
    val output = object : java.io.ByteArrayOutputStream() {
        override fun write(buffer: ByteArray, offset: Int, size: Int) {
            require(count + size <= 4 * 1024 * 1024) { "文件超过预览大小，请保存到本机" }
            super.write(buffer, offset, size)
        }
    }
    downloadFileSource(source, output, { _, _ -> }) { _, _, _ -> error("Expected public link") }
    val mime = fileMimeType(fileDisplayName(source))
    val bytes = output.toByteArray()
    return if (fileOpenKind(source, mime) == FileOpenKind.TEXT) JSONObject().put("encoding", "utf-8").put("mimeType", mime).put("text", bytes.toString(Charsets.UTF_8))
        else JSONObject().put("encoding", "base64").put("mimeType", mime).put("dataBase64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
}

internal suspend fun cacheFileSource(context: Context, source: String,
    download: suspend (String, OutputStream, (Long, Long) -> Unit) -> Unit,
    nativeCache: NativeCachedDownload? = null, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
    if (isHttpWebLink(source) && nativeCache != null && fileOpenKind(source) in setOf(FileOpenKind.IMAGE, FileOpenKind.VIDEO, FileOpenKind.AUDIO)) return@withContext nativeCache(source, progress)
    if (isHttpWebLink(source)) return@withContext ResumableDownload.get(File(context.cacheDir, "downloads"), "public", source,
        publicFileClient, Request.Builder().url(source).build(), progress)
    if (!source.startsWith("data:") && nativeCache != null) return@withContext nativeCache(source, progress)
    val directory = File(context.cacheDir, "previews").apply { mkdirs() }
    directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > TimeUnit.DAYS.toMillis(1) }?.forEach { it.delete() }
    val name = fileDisplayName(source).replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeLast(160).ifBlank { "file" }
    val file = File(directory, "${UUID.randomUUID()}-$name")
    try { file.outputStream().use { downloadFileSource(source, it, progress, download) }; file }
    catch (e: Exception) { file.delete(); throw e }
}

internal fun externalFileIntent(uri: Uri, name: String, mime: String, share: Boolean): Intent =
    Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
        if (share) { type = mime; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TITLE, name) } else setDataAndType(uri, mime)
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

internal fun openCachedFile(context: Context, file: File, name: String, mime: String, share: Boolean) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file, name)
    val intent = externalFileIntent(uri, name, mime, share)
    context.startActivity(if (share) Intent.createChooser(intent, "发送到微信 / 企业微信等应用") else intent)
}

internal suspend fun copyCachedImage(context: Context, file: File, name: String) {
    val typed = typedMediaCache(context, file, fileMimeType(name))
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", typed, name)
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: error("无法访问剪贴板")
    clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "图片", uri))
}

internal fun mediaActionMetadata(source: String, assets: List<JSONObject>, image: Boolean): Pair<String, String> {
    val asset = assets.firstOrNull { it.optString("originalUrl") == source || it.optString("source") == source }
    val declared = asset?.optString("mimeType").orEmpty()
    var name = if (source.startsWith("/v1/media/")) asset?.optString("name")?.takeIf { it.isNotBlank() } ?: "image" else fileDisplayName(source)
    val mime = fileMimeType(name, declared).let { if (image && it == "application/octet-stream") "image/png" else it }
    if (source.startsWith("/v1/media/")) {
        val extension = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "video/mp4" -> "mp4"; "audio/mpeg" -> "mp3"; else -> mime.substringAfter('/').substringBefore('+').takeIf { it.all(Char::isLetterOrDigit) } ?: "bin" }
        if (!name.endsWith(".$extension", true)) name += ".$extension"
    }
    return name to mime
}

/** FileProvider resolves MIME from the real file suffix, not its displayName query. */
internal suspend fun typedMediaCache(context: Context, file: File, mime: String): File = withContext(Dispatchers.IO) {
    val extension = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> error("此图片格式不能复制到剪贴板") }
    if (fileMimeType(file.name) == mime) return@withContext file
    val directory = File(context.cacheDir, "previews").apply { mkdirs() }
    var total = directory.listFiles()?.sumOf { it.length() } ?: 0L
    directory.listFiles()?.sortedBy { it.lastModified() }?.forEach { cached ->
        if (System.currentTimeMillis() - cached.lastModified() > TimeUnit.DAYS.toMillis(1) || total > 2L * 1024 * 1024 * 1024) {
            val size = cached.length(); if (cached.delete()) total -= size
        }
    }
    val key = java.security.MessageDigest.getInstance("SHA-256").digest("${file.absolutePath}:${file.length()}:${file.lastModified()}:$mime".toByteArray()).joinToString("") { "%02x".format(it) }
    val target = File(directory, "shared-$key.$extension")
    if (target.isFile && target.length() == file.length()) return@withContext target
    val temporary = File.createTempFile("typed-", ".part", directory)
    try {
        file.inputStream().use { input -> temporary.outputStream().use { output ->
            val buffer = ByteArray(65536)
            while (true) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
        } }
        check(temporary.renameTo(target)) { "无法准备图片剪贴板" }
        target
    } finally { temporary.delete() }
}
