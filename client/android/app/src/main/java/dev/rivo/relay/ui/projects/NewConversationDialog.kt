package dev.rivo.relay.ui.projects

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

/** Uses the same compact setting rows as QiGuang's pending-session workspace selector. */
@Composable
fun NewConversationDialog(project: ProjectSummary, readGit: suspend () -> JSONObject, enabled: Boolean,
    onCreate: (String?) -> Unit, onDismiss: () -> Unit, submissionError: String? = null, busy: Boolean = false) {
    var git by remember(project.id) { mutableStateOf<JSONObject?>(null) }
    var error by remember(project.id) { mutableStateOf<String?>(null) }
    var loading by remember(project.id) { mutableStateOf(true) }
    var retry by remember(project.id) { mutableIntStateOf(0) }
    var worktree by remember(project.id) { mutableStateOf(false) }
    var selected by remember(project.id) { mutableStateOf<String?>(null) }
    var workspaceMenu by remember { mutableStateOf(false) }
    var refMenu by remember { mutableStateOf(false) }
    LaunchedEffect(project.id, retry) {
        loading = true; error = null
        try { git = readGit().optJSONObject("git"); selected = git?.string("head") }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法读取 Git 来源" }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Text("新建会话") } }, text = { dev.rivo.relay.ui.chat.ThemedTextContextMenu {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium)
            Text(project.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            submissionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("new_conversation_error")) }
            Text("工作区", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton(onClick = { workspaceMenu = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("new_conversation_workspace"), shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp)) {
                    Text(if (worktree) "工作树" else "本地目录", Modifier.weight(1f)); dev.rivo.relay.ui.chat.SettingsRightArrow()
                }
                DropdownMenu(workspaceMenu && enabled, { workspaceMenu = false }) {
                    DropdownMenuItem(text = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Text("本地目录") } }, onClick = { worktree = false; workspaceMenu = false })
                    DropdownMenuItem(text = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Column { Text("工作树"); Text("从已提交版本创建隔离工作区", style = MaterialTheme.typography.labelSmall) } } },
                        enabled = git?.optBoolean("available") == true, onClick = { worktree = true; workspaceMenu = false })
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }, enabled = enabled) { Text("重试") } }
            if (worktree) {
                Text("来源分支", style = MaterialTheme.typography.labelLarge)
                val refs = git?.objects("refs").orEmpty()
                val ref = refs.firstOrNull { it.string("id") == selected }
                Box {
                    OutlinedButton(onClick = { refMenu = true }, enabled = enabled && !loading, modifier = Modifier.fillMaxWidth().testTag("new_conversation_ref")) {
                        Text(ref?.let { it.string("name") + " · " + it.string("commit").take(8) } ?: "选择来源", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); dev.rivo.relay.ui.chat.SettingsRightArrow()
                    }
                    DropdownMenu(refMenu && enabled, { refMenu = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                        refs.forEach { option -> DropdownMenuItem(text = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Column { Text(option.string("name")); Text(option.string("commit").take(8), style = MaterialTheme.typography.labelSmall) } } },
                            onClick = { selected = option.string("id"); refMenu = false }) }
                    }
                }
                Text("从已提交版本创建独立目录，不会复制本地未提交改动。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } }, confirmButton = { Button(onClick = { onCreate(selected.takeIf { worktree }) }, enabled = enabled && (!worktree || !loading && selected != null),
        modifier = Modifier.testTag("confirm_new_conversation")) { Text(if (busy) "创建中…" else "创建") } }, dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("取消") } })
}

@Composable
fun PendingWorkspaceSelector(project: ProjectSummary, readGit: suspend () -> JSONObject, enabled: Boolean, initialRef: String? = null,
    onSelection: (String?, Boolean) -> Unit) {
    var git by remember(project.id) { mutableStateOf<JSONObject?>(null) }
    var error by remember(project.id) { mutableStateOf<String?>(null) }
    var loading by remember(project.id) { mutableStateOf(true) }
    var retry by remember(project.id) { mutableIntStateOf(0) }
    var worktree by remember(project.id) { mutableStateOf(initialRef != null) }
    var selected by remember(project.id) { mutableStateOf(initialRef) }
    var workspaceMenu by remember { mutableStateOf(false) }
    var refMenu by remember { mutableStateOf(false) }
    LaunchedEffect(project.id, worktree, retry) {
        if (!worktree) return@LaunchedEffect
        loading = true; error = null
        try {
            git = readGit().optJSONObject("git")
            selected = selected?.takeIf { ref -> git?.objects("refs").orEmpty().any { it.string("id") == ref } }
                ?: git?.string("head")?.takeIf { head -> git?.objects("refs").orEmpty().any { it.string("id") == head } }
            if (git?.optBoolean("available") != true) error = "当前项目没有可用的 Git 工作树来源"
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法读取 Git 来源" }
        finally { loading = false }
    }

    LaunchedEffect(worktree, selected, loading, error) {
        onSelection(selected.takeIf { worktree }, !worktree || (!loading && error == null && selected != null))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("工作区", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton(onClick = { workspaceMenu = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("new_conversation_workspace"), shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp)) {
                    Text(if (worktree) "工作树" else "本地目录", Modifier.weight(1f)); dev.rivo.relay.ui.chat.SettingsRightArrow()
                }
                DropdownMenu(workspaceMenu && enabled, { workspaceMenu = false }) {
                    DropdownMenuItem(text = { Text("本地目录") }, onClick = { worktree = false; workspaceMenu = false })
                    DropdownMenuItem(text = { Column { Text("工作树"); Text("从已提交版本创建隔离工作区", style = MaterialTheme.typography.labelSmall) } },
                        onClick = { worktree = true; workspaceMenu = false })
                }
            }
            if (worktree && loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.takeIf { worktree }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }, enabled = enabled) { Text("重试") } }
            if (worktree) {
                Text("来源分支", style = MaterialTheme.typography.labelLarge)
                val refs = git?.objects("refs").orEmpty()
                val ref = refs.firstOrNull { it.string("id") == selected }
                Box {
                    OutlinedButton(onClick = { refMenu = true }, enabled = enabled && !loading, modifier = Modifier.fillMaxWidth().testTag("new_conversation_ref")) {
                        Text(ref?.let { it.string("name") + " · " + it.string("commit").take(8) } ?: "选择来源", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); dev.rivo.relay.ui.chat.SettingsRightArrow()
                    }
                    DropdownMenu(refMenu && enabled, { refMenu = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                        refs.forEach { option -> DropdownMenuItem(text = { Column { Text(option.string("name")); Text(option.string("commit").take(8), style = MaterialTheme.typography.labelSmall) } },
                            onClick = { selected = option.string("id"); refMenu = false }) }
                    }
                }
                Text("从已提交版本创建独立目录，不会复制本地未提交改动。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
    }
}
