package dev.rivo.relay.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*

/** QiGuang CodexActivityGroup: one compact summary row; only non-command details expand on demand. */
@Composable
internal fun NativeActivityGroup(row: NativeTimelineRow.Activities, isCurrent: Boolean = false, onFile: (String) -> Unit) {
    var expanded by rememberSaveable(row.key) { mutableStateOf(false) }
    val detailMessages = row.messages.filter { it.activity?.kind != "command" }
    val canExpand = detailMessages.isNotEmpty()
    val failedCount = detailMessages.count { it.activity?.status == "failed" }
    val imagePath = row.messages.singleOrNull()?.activity?.takeIf { it.kind == "image" }?.filePaths?.firstOrNull()
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("native_activity_group"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).then(if (canExpand) Modifier.clickable(role = Role.Button) { if (imagePath != null) onFile(imagePath) else expanded = !expanded }.testTag("native_activity_toggle") else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canExpand) Text(if (expanded) "▾" else "›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isCurrent) ShimmeringStageText(nativeActivityGroupLabel(row.messages),
                modifier = Modifier.weight(1f).testTag("activity_group_shimmer"), maxLines = if (canExpand) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            else Text(nativeActivityGroupLabel(row.messages), maxLines = if (canExpand) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).testTag("activity_group_summary"))
            if (failedCount > 0) Text("${failedCount} 项未完成", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("activity_failure_count"))
        }
        if (expanded && canExpand) detailMessages.forEach { message -> message.activity?.let { activity ->
            Column(Modifier.fillMaxWidth().padding(start = 23.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                SelectionContainer { Text(activity.summary, style = MaterialTheme.typography.bodySmall,
                    color = if (activity.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("activity_summary_${message.id}")) }
                activity.filePaths.forEach { path -> Text(path, modifier = Modifier.clickable(role = Role.Button) { onFile(path) },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                if (activity.detail.isNotBlank() && activity.detail.trim() != activity.summary.trim()) Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer { Text(boundedMarkdownPreview(activity.detail).let { it.text + if (it.truncated) "\n… 输出已截断 …" else "" },
                        Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
                if (activity.exitCode != null || activity.status == "failed") Text(
                    listOfNotNull(if (activity.status == "failed") "执行未完成" else null, activity.exitCode?.let { "退出码 $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (activity.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
    }
}
