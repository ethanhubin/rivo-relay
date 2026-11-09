package dev.rivo.relay.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONObject

internal data class CodexLinkAttachment(val source: String, val name: String)
internal val LocalCodexLinkForwarder = staticCompositionLocalOf<((CodexLinkAttachment) -> Unit)?> { null }

@Composable
internal fun ForwardLinkButton(source: String, name: String, onForwarded: () -> Unit = {}) {
    val forward = LocalCodexLinkForwarder.current ?: return
    if (source.startsWith("data:")) return
    TextButton(onClick = { forward(CodexLinkAttachment(source, name)); onForwarded() }, modifier = Modifier.testTag("forward_link_to_codex")) { Text("发送到 Codex") }
}

/** Destination reads and confirmation remain within the originating node; confirming only edits a draft. */
@Composable
internal fun CodexLinkDestinationDialog(link: CodexLinkAttachment, read: suspend (String) -> JSONObject,
    onConfirm: (String, String?, String, String) -> Unit, onDismiss: () -> Unit) {
    var projects by remember(link) { mutableStateOf(emptyList<JSONObject>()) }
    var projectId by remember(link) { mutableStateOf<String?>(null) }
    var sessions by remember(link) { mutableStateOf(emptyList<JSONObject>()) }
    var selected by remember(link, projectId) { mutableStateOf<String?>(null) }
    var loading by remember(link, projectId) { mutableStateOf(true) }
    var error by remember(link, projectId) { mutableStateOf<String?>(null) }
    var attempt by remember(link) { mutableIntStateOf(0) }
    LaunchedEffect(link, projectId, attempt) {
        loading = true; error = null
        try {
            if (projectId == null) {
                val response = read("/v1/projects?view=summary")
                coroutineContext.ensureActive()
                projects = response.objects("projects")
                projectId = projects.firstOrNull { it.string("id") != "__rivo_recent__" }?.string("id")
            } else {
                val response = read("/v1/threads?projectId=${encoded(projectId!!)}&archived=false")
                coroutineContext.ensureActive()
                sessions = response.objects("threads")
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { error = failure.message ?: "无法读取目标会话" }
        finally { coroutineContext.ensureActive(); loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("codex_link_destination"), title = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Text("发送到 Codex 会话") } },
        text = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text(link.name, style = MaterialTheme.typography.titleSmall)
            Text("确认后添加到输入框，点击发送才会提交。", style = MaterialTheme.typography.bodySmall)
            projects.filterNot { it.string("id") == "__rivo_recent__" }.forEach { project -> Row(Modifier.fillMaxWidth().clickable { projectId = project.string("id") }) {
                RadioButton(selected = projectId == project.string("id"), onClick = { projectId = project.string("id") })
                Text(project.string("name"), Modifier.padding(top = 12.dp))
            } }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (error != null) { Text(error!!, color = MaterialTheme.colorScheme.error); TextButton(onClick = { attempt++ }) { Text("重试") } }
            else if (projects.isEmpty()) Text("暂无可用 Codex 项目")
            else {
                Row(Modifier.fillMaxWidth().clickable { selected = null }.testTag("codex_link_new_session")) {
                    RadioButton(selected = selected == null, onClick = { selected = null }); Text("新建会话", Modifier.padding(top = 12.dp))
                }
                sessions.forEach { session -> Row(Modifier.fillMaxWidth().clickable { selected = session.string("id") }.testTag("codex_link_session_${session.string("id")}")) {
                    RadioButton(selected = selected == session.string("id"), onClick = { selected = session.string("id") })
                    Text(sessionTitle(session), Modifier.padding(top = 12.dp))
                } }
            }
        } } }, confirmButton = { TextButton(onClick = { projectId?.let { onConfirm(it, selected, link.source, link.name) } },
            enabled = !loading && error == null && projectId != null && (selected == null || sessions.any { it.string("id") == selected }),
            modifier = Modifier.testTag("confirm_forward_link")) { Text("确认添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
