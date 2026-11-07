package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.rivo.relay.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

internal val LocalMediaAttachment = staticCompositionLocalOf<(suspend (File, String, String) -> Boolean)?> { null }

/** A library selection is explicitly copied/uploaded before becoming a native attachment. */
@Composable
internal fun CodexMediaLibrary(imageSlots: Int, fileSlots: Int, onSelected: () -> Unit) {
    val client = LocalCodexMediaClient.current ?: return
    val attach = LocalMediaAttachment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var onlyFavorites by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var items by remember(client, onlyFavorites) { mutableStateOf(emptyList<JSONObject>()) }
    var loading by remember(client) { mutableStateOf(true) }
    var error by remember(client) { mutableStateOf<String?>(null) }
    var busy by remember(client) { mutableStateOf(false) }
    var deleting by remember(client) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(client, onlyFavorites, revision) {
        loading = true; error = null
        try {
            repeat(8) { attempt ->
                items = client.api.call("/v1/media/library" + if (onlyFavorites) "?favorite=true" else "").objects("items")
                loading = false
                if (items.none { it.string("status") == "pending" }) return@LaunchedEffect
                kotlinx.coroutines.delay(1000L * (attempt + 1).coerceAtMost(4))
            }
        }
        catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
        finally { loading = false }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!onlyFavorites, { onlyFavorites = false }, { Text("全部") })
        FilterChip(onlyFavorites, { onlyFavorites = true }, { Text("收藏") })
        TextButton({ revision++ }, enabled = !busy) { Text("刷新") }
    }
    if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { TextButton({ revision++ }, enabled = !busy) { Text("$it · 重试") } }
    if (!loading && items.isEmpty()) Text("暂无已保存媒体", style = MaterialTheme.typography.bodyMedium)
    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 340.dp).testTag("codex_media_library"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.string("id") }) { record ->
            val id = record.string("id")
            val image = record.string("mediaType") == "image"
            val name = mediaActionMetadata(record.string("originalUrl"), listOf(record), image).first
            Surface(shape = MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (image) AsyncImage(client.imageRequest(context, record.string("thumbnailUrl"), true).build(), null,
                        imageLoader = client.imageLoader, modifier = Modifier.size(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, maxLines = 2)
                        Text(if (record.optBoolean("hasGenerationInfo")) "Codex 生成" else record.string("mimeType"), style = MaterialTheme.typography.labelSmall)
                        if (record.string("status") != "ready") Text(if (record.string("status") == "pending") "正在登记…" else record.string("error").ifBlank { "媒体暂不可用" }, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(enabled = !busy, onClick = { busy = true; scope.launch { try {
                        client.favorite(id, !record.optBoolean("favorite")); revision++
                    } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message } finally { busy = false } } }) { Text(if (record.optBoolean("favorite")) "取消收藏" else "收藏") }
                    TextButton(enabled = !busy, onClick = { deleting = record }) { Text("移除") }
                    TextButton(enabled = !busy && attach != null && (if (image) imageSlots > 0 else fileSlots > 0) && record.string("status") == "ready", onClick = {
                        busy = true; error = null
                        scope.launch { try {
                            val file = client.cached(context, record.string("originalUrl"))
                            if (attach?.invoke(file, name, record.string("mimeType")) == true) onSelected()
                        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message } finally { busy = false } }
                    }, modifier = Modifier.testTag("attach_media_$id")) { Text("添加") }
                }
            } }
        }
    }
    deleting?.let { selected -> AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("从媒体库移除？") },
        text = { Text("移除保存的媒体副本，原生会话和原始文件会保留。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; scope.launch { try {
            client.api.call("/v1/media/library/${encoded(selected.string("id"))}", "DELETE"); deleting = null; revision++
        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message } finally { busy = false } } }) { Text("移除") } },
        dismissButton = { TextButton({ deleting = null }, enabled = !busy) { Text("取消") } }) }
}
