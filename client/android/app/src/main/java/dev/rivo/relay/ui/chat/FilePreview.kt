package dev.rivo.relay.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
            // Native remote image URLs never receive the node's bearer credential.
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
    LaunchedEffect(source) {
        try { bitmap = previewBitmap(imageBytes(source, readFile)); if (bitmap == null) error = "此图片格式暂不支持预览" }
        catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "图片暂时无法读取" }
    }
    when {
        bitmap != null -> Image(bitmap!!, "会话图片", modifier.fillMaxWidth().heightIn(max = 280.dp).testTag("native_image"), contentScale = ContentScale.Fit)
        error != null -> Text(error!!, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> LinearProgressIndicator(modifier.fillMaxWidth().padding(vertical = 16.dp))
    }
}

@Composable
internal fun FilePreviewDialog(path: String, readFile: suspend (String) -> JSONObject,
    downloadFile: suspend (String, java.io.OutputStream, (Long, Long) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var file by remember(path) { mutableStateOf<JSONObject?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember(path) { mutableStateOf(false) }
    var downloadStatus by remember(path) { mutableStateOf<String?>(null) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            downloading = true; downloadStatus = "正在下载…"
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        downloadFile(path, output) { count, total ->
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
    LaunchedEffect(path) {
        try {
            if (path.substringAfterLast('.').lowercase() in setOf("apk", "zip", "7z", "rar", "exe", "dmg")) {
                file = JSONObject().put("encoding", "binary")
                return@LaunchedEffect
            }
            val loaded = readFile(path)
            file = loaded
            if (loaded.string("mimeType").startsWith("image/") && loaded.string("encoding") == "base64")
                bitmap = previewBitmap(Base64.decode(loaded.string("dataBase64"), Base64.DEFAULT))
        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "无法读取文件" }
    }
    AlertDialog(onDismissRequest = { if (!downloading) onDismiss() }, title = { Text(path.substringAfterLast('/'), maxLines = 2) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()).testTag("file_preview"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer { Text(path, style = MaterialTheme.typography.labelSmall) }
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                file == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                bitmap != null -> Image(bitmap!!, "文件图片预览", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                file!!.string("encoding") == "utf-8" -> {
                    val text = file!!.string("text")
                    SelectionContainer { Text(text.take(200_000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    if (text.length > 200_000) Text("内容较长，已显示前 200,000 个字符。请在 Desktop 查看完整文件。", style = MaterialTheme.typography.labelSmall)
                }
                else -> Text("此文件可保存到手机后打开。")
            }
            downloadStatus?.let { Text(it, modifier = Modifier.testTag("file_download_status"), style = MaterialTheme.typography.bodySmall) }
            if (downloading) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }, confirmButton = {
            TextButton(onClick = { saveFile.launch(path.replace('\\', '/').substringAfterLast('/')) }, enabled = !downloading,
                modifier = Modifier.testTag("save_file")) { Text("保存到手机") }
        }, dismissButton = { TextButton(onClick = onDismiss, enabled = !downloading) { Text("关闭") } })
}
