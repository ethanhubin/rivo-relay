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
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("新建会话") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium)
            Text(project.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            submissionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("new_conversation_error")) }
            Text("工作区", style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { workspaceMenu = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("new_conversation_workspace")) {
                    Text(if (worktree) "工作树" else "本地目录", Modifier.weight(1f)); Text("⌄")
                }
                DropdownMenu(workspaceMenu, { workspaceMenu = false }) {
                    DropdownMenuItem(text = { Text("本地目录") }, onClick = { worktree = false; workspaceMenu = false })
                    DropdownMenuItem(text = { Column { Text("工作树"); Text("从已提交版本创建隔离工作区", style = MaterialTheme.typography.labelSmall) } },
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
                        Text(ref?.let { it.string("name") + " · " + it.string("commit").take(8) } ?: "选择来源", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text("⌄")
                    }
                    DropdownMenu(refMenu, { refMenu = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                        refs.forEach { option -> DropdownMenuItem(text = { Column { Text(option.string("name")); Text(option.string("commit").take(8), style = MaterialTheme.typography.labelSmall) } },
                            onClick = { selected = option.string("id"); refMenu = false }) }
                    }
                }
                Text("从已提交版本创建独立目录，不会复制本地未提交改动。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }, confirmButton = { Button(onClick = { onCreate(selected.takeIf { worktree }) }, enabled = enabled && (!worktree || !loading && selected != null),
        modifier = Modifier.testTag("confirm_new_conversation")) { Text(if (busy) "创建中…" else "创建") } }, dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("取消") } })
}
