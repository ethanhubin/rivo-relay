package dev.rivo.relay.ui.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import dev.rivo.relay.ui.theme.RivoSpacing
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.text.Normalizer

fun validProjectFolderName(name: String): Boolean = name.isNotBlank() && name.length <= 120 && name == name.trim() &&
    name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() }

private fun childDirectory(path: String, name: String): String {
    val separator = if (path.contains('\\')) "\\" else "/"
    return path.trimEnd('/', '\\') + separator + name
}

/** QiGuang's full-screen directory browser, with Relay's existing native-host API. */
@Composable
fun ProjectDirectoryDialog(readDirectory: suspend (String) -> JSONObject, writable: Boolean,
    onDismiss: () -> Unit, onAdd: (path: String, folderName: String?) -> Unit, initialPath: String = "",
    submissionError: String? = null, nodeName: String = "", busy: Boolean = false) {
    var requestedPath by remember { mutableStateOf(initialPath) }
    var path by remember { mutableStateOf("") }
    var parentPath by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var createFolder by remember(path) { mutableStateOf(false) }
    var folderName by remember(path) { mutableStateOf("") }
    LaunchedEffect(requestedPath, retry) {
        loading = true; error = null; entries = emptyList()
        try {
            val result = readDirectory(requestedPath)
            val resolvedPath = result.string("path")
            require(resolvedPath.isNotBlank()) { "节点未返回有效目录" }
            path = resolvedPath
            parentPath = result.string("parentPath").takeIf { it.isNotBlank() && it != resolvedPath }
            entries = result.objects("entries").filter { it.optBoolean("isDirectory") }
                .map { it.string("fileName") }.filter(::validProjectFolderName).distinct().sortedBy { it.lowercase() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "目录读取失败" }
        finally { loading = false }
    }
    val goBack: () -> Unit = { parentPath?.let { requestedPath = it } ?: onDismiss() }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(enabled = !busy, onBack = goBack)
        Surface(Modifier.fillMaxSize().systemBarsPadding().testTag("project_directory_browser"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = RivoSpacing.Page, vertical = RivoSpacing.Sm), verticalArrangement = Arrangement.spacedBy(RivoSpacing.Xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = goBack, enabled = !busy && !loading, modifier = Modifier.testTag("project_directory_parent")) { Text("‹ 返回") }
                            Text("添加 Codex 项目", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                        if (nodeName.isNotBlank()) Text(nodeName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(path.ifBlank { "正在读取节点目录…" }, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("project_directory_path"),
                            maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = RivoSpacing.Page, vertical = RivoSpacing.Sm), horizontalArrangement = Arrangement.spacedBy(RivoSpacing.Sm)) {
                        OutlinedButton(onClick = { folderName = ""; createFolder = true }, enabled = writable && !busy && !loading && error == null && path.isNotBlank(),
                            modifier = Modifier.weight(1f).testTag("project_new_folder")) { Text("新建目录") }
                        Button(onClick = { onAdd(path, null) }, enabled = writable && !busy && !loading && error == null && path.isNotBlank(),
                            modifier = Modifier.weight(1f).testTag("confirm_add_project")) { Text(if (busy) "添加中…" else "使用此目录") }
                    }
                }
                submissionError?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = RivoSpacing.Page, vertical = 8.dp).testTag("project_create_error"),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        loading || busy -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        error != null -> Column(Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { retry++ }, enabled = !busy) { Text("重试") }
                        }
                        entries.isEmpty() -> Text("此目录中没有子目录", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> LazyColumn(Modifier.fillMaxSize().testTag("project_directories")) {
                            items(entries, key = { it }) { name ->
                                Surface(onClick = { requestedPath = childDirectory(path, name) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("project_directory_$name"), color = MaterialTheme.colorScheme.background) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = RivoSpacing.Page, vertical = RivoSpacing.Md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RivoSpacing.Md)) {
                                        Text("📁", style = MaterialTheme.typography.titleLarge)
                                        Column(Modifier.weight(1f)) {
                                            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (name.startsWith('.')) Text("隐藏目录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (createFolder) AlertDialog(onDismissRequest = { createFolder = false }, title = { Text("新建并添加项目") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(RivoSpacing.Sm)) {
            Text("将在以下位置新建目录：\n$path")
            OutlinedTextField(folderName, { folderName = it }, label = { Text("目录名称") }, singleLine = true, enabled = writable,
                isError = folderName.isNotEmpty() && !validProjectFolderName(folderName.trim()), modifier = Modifier.fillMaxWidth().testTag("project_folder_name"))
        }
    }, confirmButton = { Button(onClick = { createFolder = false; onAdd(path, Normalizer.normalize(folderName.trim(), Normalizer.Form.NFC)) },
        enabled = writable && validProjectFolderName(folderName.trim()), modifier = Modifier.testTag("confirm_create_project_folder")) { Text("创建并添加") } },
        dismissButton = { TextButton(onClick = { createFolder = false }) { Text("取消") } })
}
