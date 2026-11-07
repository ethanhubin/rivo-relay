package dev.rivo.relay.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.QueueTask

@Composable
internal fun RecoveryTaskPanel(task: QueueTask, enabled: Boolean, readOnly: Boolean = false, onAction: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("codex_recovery_panel"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("任务需要恢复", style = MaterialTheme.typography.titleSmall)
            Text(task.text, Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
            if (task.error.isNotBlank()) Text(task.error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (readOnly) "会话由其他进程持有，暂时无法继续。可以忽略这条恢复记录；原会话历史不会删除。" else "继续会检查原会话的执行现状并完成剩余工作，不会重新发送原始指令和附件。", style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                TextButton(onClick = { onAction("dismiss") }, enabled = enabled, modifier = Modifier.testTag("codex_recovery_dismiss")) { Text("忽略") }
                Button(onClick = { onAction("recover") }, enabled = enabled && !readOnly, modifier = Modifier.testTag("codex_recovery_continue")) { Text("继续") }
            }
        }
    }
}
