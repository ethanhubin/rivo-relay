package dev.rivo.relay.ui.projects

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.objects
import dev.rivo.relay.data.string
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

fun validProjectFolderName(name: String): Boolean = name.isNotBlank() && name == name.trim() &&
    name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() }

private fun childDirectory(path: String, name: String): String {
    val separator = if (path.contains('\\')) "\\" else "/"
    return path.trimEnd('/', '\\') + separator + name
}

/** Uses the authenticated native-host directory endpoint, including remote nodes and Windows paths. */
@Composable
fun ProjectDirectoryDialog(readDirectory: suspend (String) -> JSONObject, writable: Boolean,
    onDismiss: () -> Unit, onAdd: (path: String, folderName: String?) -> Unit, initialPath: String = "") {
    var requestedPath by remember { mutableStateOf(initialPath) }
    var path by remember { mutableStateOf("") }
    var pathDraft by remember { mutableStateOf(initialPath) }
    var parentPath by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var createFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    LaunchedEffect(requestedPath, retry) {
        loading = true; error = null; entries = emptyList()
        try {
            val result = readDirectory(requestedPath)
            val resolvedPath = result.string("path")
            require(resolvedPath.isNotBlank()) { "节点未返回有效目录" }
            path = resolvedPath; pathDraft = resolvedPath
            parentPath = result.string("parentPath").takeIf { it.isNotBlank() && it != resolvedPath }
            entries = result.objects("entries").filter { it.optBoolean("isDirectory") }
                .map { it.string("fileName") }.filter(::validProjectFolderName).distinct().sortedBy { it.lowercase() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "目录读取失败" }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = { if (writable) onDismiss() }, title = { Text("添加项目") }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("选择节点上的目录，或在当前目录新建项目文件夹。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(pathDraft, { pathDraft = it }, label = { Text("节点目录") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("project_directory_path"), enabled = writable)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { parentPath?.let { requestedPath = it; createFolder = false; folderName = "" } },
                    enabled = parentPath != null && !loading && writable, modifier = Modifier.testTag("project_directory_parent")) { Text("↑ 上一级") }
                TextButton(onClick = { requestedPath = pathDraft.trim(); retry++; createFolder = false; folderName = "" },
                    enabled = pathDraft.isNotBlank() && !loading && writable, modifier = Modifier.testTag("project_directory_open")) { Text("打开目录") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }, enabled = writable) { Text("重试") } }
            if (!loading && error == null && entries.isEmpty()) Text("当前目录没有子文件夹", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).testTag("project_directories")) {
                items(entries, key = { it }) { name ->
                    TextButton(onClick = { requestedPath = childDirectory(path, name); createFolder = false; folderName = "" },
                        enabled = writable && !loading, modifier = Modifier.fillMaxWidth().testTag("project_directory_$name")) {
                        Text("▸ $name", Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (createFolder) {
                OutlinedTextField(folderName, { folderName = it }, label = { Text("新文件夹名称") }, singleLine = true,
                    enabled = writable, isError = folderName.isNotEmpty() && !validProjectFolderName(folderName),
                    modifier = Modifier.fillMaxWidth().testTag("project_folder_name"))
                TextButton(onClick = { createFolder = false; folderName = "" }, enabled = writable) { Text("使用当前目录") }
            } else TextButton(onClick = { createFolder = true }, enabled = writable && !loading && error == null && path.isNotBlank(),
                modifier = Modifier.testTag("project_new_folder")) { Text("＋ 新建文件夹") }
        }
    }, confirmButton = {
        Button(onClick = { onAdd(path, folderName.takeIf { createFolder }) },
            enabled = writable && !loading && error == null && path.isNotBlank() && (!createFolder || validProjectFolderName(folderName)),
            modifier = Modifier.testTag("confirm_add_project")) { Text(if (createFolder) "创建并添加" else "选择此目录") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = writable) { Text("取消") } })
}
