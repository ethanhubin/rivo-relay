package dev.rivo.relay.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rivo.relay.data.encoded
import dev.rivo.relay.data.objects
import dev.rivo.relay.data.string
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Read-only previews and explicit native mutations; caller owns submission IDs and reconciliation. */
@Composable
internal fun DeveloperToolsDialog(
    threadId: String,
    read: suspend (String) -> JSONObject,
    submit: (String, JSONObject, () -> Unit) -> Unit,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    val base = "/v1/threads/${encoded(threadId)}"
    var tab by remember(threadId) { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    var diff by remember(threadId) { mutableStateOf<JSONObject?>(null) }
    var context by remember(threadId) { mutableStateOf<JSONObject?>(null) }
    var workspace by remember(threadId) { mutableStateOf<JSONObject?>(null) }
    var loaded by remember(threadId, tab, generation) { mutableStateOf(false) }
    var error by remember(threadId, tab, generation) { mutableStateOf<String?>(null) }
    var cleanupConfirm by remember { mutableStateOf(false) }
    var discardConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(threadId, tab, generation) {
        try {
            when (tab) {
                0 -> diff = read("$base/diff").optJSONObject("diff") ?: error("节点未返回差异内容")
                1 -> context = read("$base/review-context")
                else -> workspace = read("$base/workspace").optJSONObject("workspace")
            }
        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "无法读取开发信息" }
        finally { loaded = true }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp)) {
                    TextButton(onDismiss) { Text("关闭") }
                    Text("开发工具", Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { generation++ }, enabled = loaded && !busy) { Text("刷新") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("文件差异", "代码审查", "工作树").forEachIndexed { index, name ->
                        FilterChip(tab == index, { tab = index }, label = { Text(name) }, modifier = Modifier.testTag("developer_tab_$index"))
                    }
                }
                if (!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                when (tab) {
                    0 -> if (loaded && error == null) {
                        val current = diff
                        if (current != null) {
                            DeveloperDiffContent(current, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                    1 -> if (loaded && error == null && context != null) ReviewControls(context!!, busy) { target ->
                        submit("$base/review", JSONObject().put("target", target), onDismiss)
                    }
                    else -> if (loaded && error == null) {
                        val current = workspace
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (current == null) Text("此会话使用项目目录，没有可清理的独立工作树。")
                            else {
                                Text("会话工作树", style = MaterialTheme.typography.titleMedium)
                                SelectionContainer { Text(current.string("path")) }
                                Text("来源：${current.string("sourcePath")}", style = MaterialTheme.typography.bodySmall)
                                Text("提交：${current.string("commit")}", style = MaterialTheme.typography.bodySmall)
                                Text(if (current.optBoolean("dirty")) "有未提交改动" else "工作区没有未提交改动", color = if (current.optBoolean("dirty")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                current.string("status").takeIf { it.isNotBlank() }?.let { Text("状态：$it", style = MaterialTheme.typography.bodySmall) }
                                current.string("archivePath").takeIf { it.isNotBlank() }?.let { archived -> SelectionContainer { Text("归档位置：$archived", style = MaterialTheme.typography.bodySmall) } }
                                Text("清理会移除该工作树目录。会话历史仍保留，清理后无法继续在原目录执行。", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { cleanupConfirm = true }, enabled = !busy && current.string("revision").isNotBlank() && current.string("status") == "ready", modifier = Modifier.testTag("cleanup_workspace")) { Text(if (current.string("status") == "removed") "工作树已清理" else "清理工作树") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (cleanupConfirm) AlertDialog(onDismissRequest = { cleanupConfirm = false }, title = { Text("清理当前工作树？") },
        text = { Text("将移除此会话的工作树目录。请确认工作已经完成，且没有正在使用此目录的任务。") },
        confirmButton = { TextButton(onClick = {
            cleanupConfirm = false
            if (workspace?.optBoolean("dirty") == true) discardConfirm = true
            else workspace?.let { current -> submit("$base/workspace/cleanup", JSONObject().put("discard", false).put("revision", current.string("revision"))) { generation++ } }
        }, enabled = !busy) { Text("继续") } }, dismissButton = { TextButton(onClick = { cleanupConfirm = false }) { Text("取消") } })
    if (discardConfirm) AlertDialog(onDismissRequest = { discardConfirm = false }, title = { Text("工作树包含未提交改动") },
        text = { Text("继续后节点会先归档当前脏目录，再移除工作树。未提交改动将不再留在原工作目录中。确定归档并清理？") },
        confirmButton = { TextButton(onClick = {
            workspace?.let { current -> submit("$base/workspace/cleanup", JSONObject().put("discard", true).put("revision", current.string("revision"))) { discardConfirm = false; generation++ } }
        }, enabled = !busy, modifier = Modifier.testTag("confirm_discard_workspace")) { Text("归档并清理") } },
        dismissButton = { TextButton(onClick = { discardConfirm = false }) { Text("保留工作树") } })
}

/** QiGuang CodexReviewScreen's unified/file controls and metadata placeholders. */
@Composable
internal fun DeveloperDiffContent(diff: JSONObject, modifier: Modifier = Modifier) {
    var unified by remember { mutableStateOf(true) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    val files = diff.objects("files")
    val selected = files.firstOrNull { it.string("path") == selectedFile } ?: files.firstOrNull()
    val patch = diff.string("patch")
    val visiblePatch = if (unified) {
        if (files.isEmpty()) patch else files.filterNot { it.optBoolean("binary") }.joinToString("") { diffForFile(patch, it.string("path")) }
    } else selected?.let { diffForFile(patch, it.string("path")) }.orEmpty()
    val hasHunks = remember(visiblePatch) { diffDisplayLines(visiblePatch).any { it.kind == "hunk" } }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiffModeButton("统一", unified, { unified = true }, "codex_diff_unified")
            DiffModeButton("文件", !unified, { unified = false }, "codex_diff_files")
            Spacer(Modifier.weight(1f))
            Text("${files.size} 个文件", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium)
        }
        if (!unified) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            files.forEach { file ->
                val path = file.string("path")
                DiffModeButton(path.substringAfterLast('/'), selected?.string("path") == path, { selectedFile = path }, "codex_diff_file_$path")
            }
        }
        if (diff.optBoolean("truncated")) Text("内容超过上限，已展示节点返回的部分差异。", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            files.isEmpty() && patch.isBlank() -> Text("当前工作空间没有未提交改动", Modifier.fillMaxWidth().padding(24.dp).testTag("codex_diff_empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            !unified && selected?.optBoolean("binary") == true -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("二进制文件只显示元数据，不加载内容", Modifier.padding(24.dp).testTag("codex_diff_placeholder"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            hasHunks || (unified && files.any { it.optBoolean("binary") }) -> DiffPatchPreview(visiblePatch,
                Modifier.weight(1f).fillMaxWidth().testTag("diff_preview"),
                metadataFiles = if (unified) files.filter { it.optBoolean("binary") }.map { it.string("path") } else emptyList(),
                copyPatch = if (unified) patch else visiblePatch)
            !unified && selected != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (diff.optBoolean("truncated")) "此文件的文本内容未包含在节点返回的部分差异中。" else "重命名或类型变化没有文本内容",
                    Modifier.padding(24.dp).testTag("codex_diff_placeholder"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            files.any { !it.optBoolean("binary") } -> Text("文件包含元数据变化，没有可显示的文本块。", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiffModeButton(label: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    OutlinedButton(onClick = onClick, modifier = Modifier.testTag(tag),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReviewControls(context: JSONObject, busy: Boolean, onReview: (JSONObject) -> Unit) {
    var mode by remember { mutableStateOf("uncommittedChanges") }
    var branch by remember(context) { mutableStateOf(context.strings("branches").firstOrNull().orEmpty()) }
    var commit by remember(context) { mutableStateOf(context.objects("commits").firstOrNull()?.string("id").orEmpty()) }
    var instructions by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("选择审查范围，结果将返回当前会话。", style = MaterialTheme.typography.bodyMedium)
        listOf("uncommittedChanges" to "未提交改动", "baseBranch" to "与分支比较", "commit" to "指定提交", "custom" to "自定义审查").forEach { (value, label) ->
            FilterChip(mode == value, { mode = value }, label = { Text(label) }, enabled = !busy)
        }
        if (mode == "baseBranch") {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = context.strings("branches").isNotEmpty() && !busy) { Text(branch.ifBlank { "没有可用分支" }) }
                DropdownMenu(expanded, { expanded = false }) { context.strings("branches").forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { branch = value; expanded = false }) } }
            }
        }
        if (mode == "commit") {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = context.objects("commits").isNotEmpty() && !busy) { Text(commit.take(12).ifBlank { "没有可用提交" }) }
                DropdownMenu(expanded, { expanded = false }) { context.objects("commits").forEach { value -> DropdownMenuItem(text = { Text(value.string("id").take(8) + " " + value.string("subject")) }, onClick = { commit = value.string("id"); expanded = false }) } }
            }
        }
        if (mode == "custom") OutlinedTextField(instructions, { instructions = it }, label = { Text("审查要求") }, minLines = 3, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val target = JSONObject().put("type", mode)
            when (mode) { "baseBranch" -> target.put("branch", branch); "commit" -> target.put("sha", commit); "custom" -> target.put("instructions", instructions.trim()) }
            onReview(target)
        }, enabled = !busy && when (mode) { "baseBranch" -> branch.isNotBlank(); "commit" -> commit.isNotBlank(); "custom" -> instructions.isNotBlank(); else -> true }, modifier = Modifier.testTag("start_review")) { Text("开始审查") }
    }
}
