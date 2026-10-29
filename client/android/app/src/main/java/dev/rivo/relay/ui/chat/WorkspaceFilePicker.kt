package dev.rivo.relay.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rivo.relay.data.objects
import dev.rivo.relay.data.string
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** QiGuang AttachmentSourcePicker's full-screen workspace browser, backed by native fs/readDirectory. */
@Composable
internal fun WorkspaceFilePicker(root: String, readDirectory: suspend (String) -> JSONObject,
    onDismiss: () -> Unit, onSelect: (JSONObject) -> Unit) {
    var directories by remember(root) { mutableStateOf(listOf(root)) }
    val path = directories.last()
    var retry by remember(root) { mutableIntStateOf(0) }
    var entries by remember(path, retry) { mutableStateOf(emptyList<JSONObject>()) }
    var loading by remember(path, retry) { mutableStateOf(true) }
    var error by remember(path, retry) { mutableStateOf<String?>(null) }
    val goBack = { if (directories.size > 1) directories = directories.dropLast(1) else onDismiss() }
    LaunchedEffect(path, retry) {
        loading = true; error = null; entries = emptyList()
        try {
            require(path.isNotBlank()) { "尚未取得会话工作区，请刷新会话后重试" }
            entries = readDirectory(path).objects("entries")
                .filter { entry -> val name = entry.string("fileName")
                    name.isNotBlank() && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() } &&
                        (entry.optBoolean("isDirectory") || entry.optBoolean("isFile")) }
                .distinctBy { it.string("fileName") }
                .sortedWith(compareBy<JSONObject> { !it.optBoolean("isDirectory") }.thenBy { it.string("fileName").lowercase() })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "目录读取失败" }
        finally { loading = false }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = goBack)
        Surface(Modifier.fillMaxSize().testTag("attachment_source_browser"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = goBack, modifier = Modifier.testTag("attachment_source_back")) { Text("‹ 返回") }
                    Column(Modifier.weight(1f)) {
                        Text("从工作区添加", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(path.ifBlank { "工作区" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("attachment_source_path"))
                    }
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
                HorizontalDivider()
                Box(Modifier.fillMaxSize()) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(error!!, color = MaterialTheme.colorScheme.error)
                            Button(onClick = { retry++ }) { Text("重试") }
                        }
                        else -> LazyColumn(Modifier.fillMaxSize().testTag("workspace_files"),
                            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (entries.isEmpty()) item { Text("这里还没有可附加的文件", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(entries, key = { it.string("fileName") }) { entry ->
                                val name = entry.string("fileName")
                                val directory = entry.optBoolean("isDirectory")
                                val separator = if (path.contains('\\')) "\\" else "/"
                                val target = path.trimEnd('/', '\\') + separator + name
                                WorkspaceAttachmentRow(entry, target) {
                                    if (directory) directories = directories + target
                                    else onSelect(JSONObject().put("type", "mention").put("name", name).put("path", target))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Same dimensions, colours, hierarchy and tap target as QiGuang AttachmentSourceRow. */
@Composable
private fun WorkspaceAttachmentRow(entry: JSONObject, path: String, onClick: () -> Unit) {
    val name = entry.string("fileName")
    val directory = entry.optBoolean("isDirectory")
    val mime = fileMimeType(name, entry.string("mimeType"))
    val typeLabel = when {
        directory -> "夹"
        mime.startsWith("image/") -> "图"
        mime.startsWith("video/") -> "视"
        mime.startsWith("audio/") -> "音"
        else -> "文"
    }
    val size = when {
        entry.has("size") && !entry.isNull("size") -> entry.optLong("size", -1)
        entry.has("bytes") && !entry.isNull("bytes") -> entry.optLong("bytes", -1)
        else -> -1
    }
    val detail = if (directory) "文件夹" else "$mime · ${if (size >= 0) formatTransferBytes(size) else "大小未知"}"
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("attachment_entry_$path"),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.size(40.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) { Text(typeLabel, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
