package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.objects
import dev.rivo.relay.data.string
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Browse the current thread's native workspace, including a remote native host. */
@Composable
internal fun WorkspaceFilePicker(root: String, readDirectory: suspend (String) -> JSONObject,
    onDismiss: () -> Unit, onSelect: (JSONObject) -> Unit) {
    var directories by remember(root) { mutableStateOf(listOf(root)) }
    val path = directories.last()
    var entries by remember { mutableStateOf(emptyList<JSONObject>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(path, retry) {
        loading = true; error = null; entries = emptyList()
        try {
            require(path.isNotBlank()) { "尚未取得会话工作区，请刷新会话后重试" }
            entries = readDirectory(path).objects("entries")
                .filter { entry -> val name = entry.string("fileName")
                    name.isNotBlank() && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() } &&
                        (entry.optBoolean("isDirectory") || entry.optBoolean("isFile")) }
                .sortedWith(compareBy<JSONObject> { !it.optBoolean("isDirectory") }.thenBy { it.string("fileName").lowercase() })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "目录读取失败" }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("节点工作区文件") }, text = {
        Column(Modifier.fillMaxWidth()) {
            Text(path, style = MaterialTheme.typography.bodySmall)
            if (directories.size > 1) TextButton(onClick = { directories = directories.dropLast(1) }, modifier = Modifier.testTag("workspace_parent")) { Text("↑ 上一级") }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text("重试") } }
            if (!loading && error == null && entries.isEmpty()) Text("此目录没有可选文件")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("workspace_files")) {
                items(entries, key = { it.string("fileName") }) { entry ->
                    val name = entry.string("fileName")
                    val directory = entry.optBoolean("isDirectory")
                    TextButton(onClick = {
                        val separator = if (path.contains('\\')) "\\" else "/"
                        val target = path.trimEnd('/', '\\') + separator + name
                        if (directory) directories = directories + target
                        else onSelect(JSONObject().put("type", "mention").put("name", name).put("path", target))
                    }, modifier = Modifier.fillMaxWidth()) { Text((if (directory) "▸ " else "文件 · ") + name, modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
