package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*

@Composable
internal fun NativeAgentDetails(activity: NativeAgentActivity, onOpenAgent: ((String) -> Unit)?) {
    Column(Modifier.fillMaxWidth().testTag("native_agent_details"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (activity.model.isNotBlank()) Text(activity.model, style = MaterialTheme.typography.labelSmall)
        if (activity.prompt.isNotBlank()) AgentText("任务 / 消息", activity.prompt)
        var visibleAgents by rememberSaveable { mutableIntStateOf(NATIVE_ACTIVITY_BATCH_SIZE) }
        activity.agents.take(visibleAgents).forEach { agent -> key(agent.threadId) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(agent.name, style = MaterialTheme.typography.titleSmall)
                    Text(nativeAgentStatusLabel(agent.status), style = MaterialTheme.typography.labelSmall,
                        color = if (agent.status in setOf("errored", "failed", "notFound")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (agent.message.isNotBlank()) AgentText("结果", agent.message)
                    if (onOpenAgent != null && agent.threadId.isNotBlank()) TextButton(
                        onClick = { onOpenAgent(agent.threadId) }, modifier = Modifier.testTag("open_agent_${agent.threadId}")) { Text("查看代理会话") }
                }
            }
        } }
        if (visibleAgents < activity.agents.size) TextButton(onClick = { visibleAgents += NATIVE_ACTIVITY_BATCH_SIZE }) { Text("显示更多代理") }
    }
}

@Composable
private fun AgentText(label: String, text: String) {
    var limit by rememberSaveable(text) { mutableIntStateOf(MARKDOWN_PREVIEW_MAX_CHARS) }
    val preview = remember(text, limit) { boundedMarkdownPreview(text, limit) }
    Text(label, style = MaterialTheme.typography.labelSmall)
    SelectionContainer { Text(preview.text, style = MaterialTheme.typography.bodySmall) }
    if (preview.truncated) TextButton(onClick = { limit = (limit.toLong() + MARKDOWN_PREVIEW_MAX_CHARS).coerceAtMost(text.length.toLong()).toInt() }) { Text("继续显示") }
}
