package dev.rivo.relay.ui.projects

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

@Composable
fun NewConversationDialog(project: ProjectSummary, readGit: suspend () -> JSONObject, enabled: Boolean,
    onCreate: (String?) -> Unit, onDismiss: () -> Unit) {
    var git by remember(project.id) { mutableStateOf<JSONObject?>(null) }
    var error by remember(project.id) { mutableStateOf<String?>(null) }
    var worktree by remember(project.id) { mutableStateOf(false) }
    var selected by remember(project.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(project.id) {
        try { git = readGit().optJSONObject("git"); selected = git?.string("head") }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法读取 Git 来源" }
    }
    AlertDialog(onDismissRequest = { if (enabled) onDismiss() }, title = { Text("新建会话") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(project.name)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!worktree, { worktree = false }, label = { Text("本地目录") })
                FilterChip(worktree, { worktree = true }, label = { Text("独立工作树") }, enabled = git?.optBoolean("available") == true)
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (worktree) {
                Text("从已提交版本创建独立目录，不会复制本地未提交改动。", style = MaterialTheme.typography.bodySmall)
                git?.objects("refs")?.forEach { ref ->
                    TextButton(onClick = { selected = ref.string("id") }) { Text((if (selected == ref.string("id")) "✓ " else "") + ref.string("name") + " · " + ref.string("commit").take(8)) }
                }
            } else Text(project.path, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button(onClick = { onCreate(if (worktree) selected else null) }, enabled = enabled && (!worktree || selected != null)) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = enabled) { Text("取消") } })
}
