package dev.rivo.relay.ui.chat

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import dev.rivo.relay.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal val LocalCodexMediaClient = staticCompositionLocalOf<CodexMediaClient?> { null }
internal val LocalMediaAssets = staticCompositionLocalOf<List<org.json.JSONObject>> { emptyList() }
internal val LocalMediaSourceIndex = staticCompositionLocalOf<Map<String, org.json.JSONObject>?> { null }
internal val LocalCodexGallery = staticCompositionLocalOf<List<String>> { emptyList() }
internal val LocalMediaEdited = staticCompositionLocalOf<((Uri) -> Unit)?> { null }

@Composable
internal fun mediaAssetsBySource(): Map<String, org.json.JSONObject> {
    val provided = LocalMediaSourceIndex.current
    val assets = LocalMediaAssets.current
    return provided ?: remember(assets) { nativeMediaSourceIndex(assets) }
}

internal fun nativeImageSource(part: org.json.JSONObject, cwd: String): String? {
    val type = part.string("type").replace("_", "").lowercase()
    if (type !in setOf("image", "localimage", "inputimage")) return null
    val value = listOf("path", "url", "imageUrl", "image_url").firstNotNullOfOrNull { part.string(it).takeIf(String::isNotBlank) } ?: return null
    return if (value.startsWith("data:image/") || isHttpWebLink(value)) value else nativeFilePath(value, cwd) ?: value
}
internal fun generatedImageSource(message: NativeMessage, assets: List<org.json.JSONObject>): String? = generatedImageSource(message, nativeMediaIndex(assets))
internal fun generatedImageSource(message: NativeMessage, assets: Map<NativeMediaIdentity, org.json.JSONObject>): String? {
    assets[NativeMediaIdentity(message.turnId, message.id)]?.takeIf { it.string("status") == "ready" }
        ?.string("originalUrl")?.takeIf(String::isNotBlank)?.let { return it }
    message.raw.string("savedPath").takeIf(String::isNotBlank)?.let { return it }
    return message.raw.string("result").takeIf(String::isNotBlank)?.let { result ->
        if (result.startsWith("data:image/") || isHttpWebLink(result) || result.startsWith('/')) result else "data:image/png;base64,$result"
    }
}

internal fun gallerySources(messages: List<NativeMessage>, cwd: String, assets: List<org.json.JSONObject> = emptyList()): List<String> =
    NativeGalleryCache().sources(messages, cwd, nativeMediaIndex(assets))

@Composable
internal fun CodexImageGallery(source: String, origin: Rect = Rect.Zero, onSave: (String) -> Unit,
    onShare: (String) -> Unit, onDismiss: () -> Unit) {
    val client = LocalCodexMediaClient.current
    val gallery = LocalCodexGallery.current
    val clipboard = LocalClipboardManager.current
    val edited = LocalMediaEdited.current
    val sources = remember(client, source) { (gallery + source).distinct() }
    var metadata by remember(client, sources) { mutableStateOf(emptyMap<String, org.json.JSONObject>()) }
    LaunchedEffect(client, sources) {
        if (client != null) {
            // One bounded request; reading an image never imports it into the library.
            try { metadata = client.api.call("/v1/media/library?threadId=${encoded(client.threadId)}").objects("items")
                 .flatMap { asset -> listOfNotNull(asset.string("source").takeIf { it.isNotBlank() }, asset.string("originalUrl").takeIf { it.isNotBlank() }).map { it to asset } }.toMap() }
            catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }
    // Preserve the viewer identity while metadata arrives: no zoom/page reset on lookup completion.
    val items = sources.map { path -> val info = metadata[path]
        ImageViewerItem(path, path, mediaId = info?.string("id"), forwardSource = if (path.startsWith("/v1/media/") || path.startsWith("data:")) info?.string("source")?.takeIf { it.isNotBlank() } else path, hasGenerationInfo = info?.optBoolean("hasGenerationInfo") == true) }
    val viewer = remember(sources, source, origin) { ImageViewerSource(items, sources.indexOf(source), origin) }
    key(client) { FullScreenImage(viewer.copy(items = items), onDismiss, onSave = onSave,
        onGenerationInfo = { id -> requireNotNull(client).generationInfo(id) },
        onCopyPrompt = { clipboard.setText(AnnotatedString(it)) }, onShare = onShare, onEditComplete = edited) }
}

@Composable
internal fun MediaAttachmentEditor(source: Uri, onDismiss: () -> Unit, onComplete: (Uri) -> Unit) {
    val client = LocalCodexMediaClient.current
    key(client, source) { MediaAttachmentEditorContent(source, client, onDismiss, onComplete) }
}

@Composable
private fun MediaAttachmentEditorContent(source: Uri, client: CodexMediaClient?, onDismiss: () -> Unit, onComplete: (Uri) -> Unit) {
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var temporarySource by remember(source) { mutableStateOf<File?>(null) }
    DisposableEffect(source) { onDispose { temporarySource?.delete() } }
    var draft by remember(source) { mutableStateOf<DraftImage?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    LaunchedEffect(source, client) {
        try {
            val uri = when {
                source.toString().startsWith("data:image/") -> {
                    val value = source.toString()
                    require(value.length <= 28 * 1024 * 1024 && value.substringBefore(',').endsWith(";base64")) { "图片编码无效" }
                    val file = File(File(context.cacheDir, "previews").apply { mkdirs() }, "edit-source-${UUID.randomUUID()}")
                    temporarySource = file
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val bytes = android.util.Base64.decode(value.substringAfter(','), android.util.Base64.DEFAULT)
                        require(bytes.size <= 20 * 1024 * 1024) { "单张图片最大支持 20 MiB" }
                        file.writeBytes(bytes)
                    }
                    Uri.fromFile(file)
                }
                source.scheme in setOf("content", "file") -> source
                else -> Uri.fromFile(requireNotNull(client).cached(context, source.toString()))
            }
            draft = prepareImage(context, uri)
        } catch (e: Exception) {
            if (e is CancellationException) { temporarySource?.delete(); throw e }
            error = e.message ?: "无法读取图片"
        }
    }
    draft?.let { image -> AttachmentImageEditor(image, onDismiss, { result ->
        if (!exporting) {
            exporting = true
            exportScope.launch {
                val file = File(File(context.cacheDir, "previews"), "edited-${UUID.randomUUID()}.jpg")
                var delivered = false
                try {
                    withContext(Dispatchers.IO) { file.parentFile?.mkdirs(); file.writeBytes(result.bytes) }
                    onComplete(Uri.fromFile(file))
                    delivered = true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    error = e.message ?: "无法保存编辑后的图片"
                } finally {
                    if (!delivered) withContext(NonCancellable + Dispatchers.IO) { file.delete() }
                    exporting = false
                }
            }
        }
    }, alwaysExport = true) } ?: Dialog(onDismissRequest = onDismiss) { Surface {
        if (error != null) TextButton(onDismiss) { Text(error!!) } else CircularProgressIndicator()
    } }
    if (draft != null && error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("图片保存失败") },
        text = { Text(error.orEmpty()) }, confirmButton = { TextButton(onClick = { error = null }) { Text("返回编辑") } })
}
