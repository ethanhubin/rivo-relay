package dev.rivo.relay.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*

/** QiGuang CodexActivityGroup: one compact summary row; commands and context compaction stay summary-only. */
@Composable
internal fun NativeActivityGroup(row: NativeTimelineRow.Activities, isCurrent: Boolean = false, resourceItems: List<NativeResource>? = null, agentDetails: Map<Pair<String, String>, NativeAgentActivity> = emptyMap(), onOpenAgent: ((String) -> Unit)? = null, onFile: (String) -> Unit) {
    var expanded by rememberSaveable(row.key) { mutableStateOf(false) }
    var visibleDetails by rememberSaveable(row.key) { mutableIntStateOf(NATIVE_ACTIVITY_BATCH_SIZE) }
    val clipboard = LocalClipboardManager.current
    val detailMessages = remember(row.messages) { row.messages.filter { it.activity?.kind !in setOf("command", "context") } }
    val canExpand = detailMessages.isNotEmpty()
    val resources = resourceItems ?: remember(row.messages) { nativeResources(row.messages) }
    val connector = remember(row.messages) { row.messages.mapNotNull { nativeConnector(it.raw) }.distinct().singleOrNull() }
    val failedCount = remember(detailMessages) { detailMessages.count { it.activity?.status == "failed" } }
    val unknownCount = remember(detailMessages) { detailMessages.count { it.activity?.status == "unknown" } }
    val label = remember(row.messages) { nativeActivityGroupLabel(row.messages) }
    val imagePath = row.messages.singleOrNull()?.activity?.takeIf { it.kind == "image" }?.filePaths?.firstOrNull()
    Column(Modifier.fillMaxWidth().testTag("native_activity_group"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChatTimelineSummaryRow(
            modifier = if (canExpand) Modifier.testTag("native_activity_toggle") else Modifier,
            onClick = if (canExpand) ({ if (imagePath != null) onFile(imagePath) else expanded = !expanded }) else null,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (connector != null) dev.rivo.relay.ui.resources.ConnectorIcon(connector) else NativeActivityGlyph(row.messages.mapNotNull { it.activity?.kind }.distinct().singleOrNull() ?: "turn")
            if (canExpand) Text(if (expanded) "▾" else "›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isCurrent) ShimmeringStageText(label,
                modifier = Modifier.weight(1f).testTag("activity_group_shimmer"), maxLines = if (canExpand) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            else Text(label, maxLines = if (canExpand) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).testTag("activity_group_summary"))
            if (failedCount > 0) Text("${failedCount} 项活动失败", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("activity_failure_count"))
            if (unknownCount > 0) Text("${unknownCount} 项状态待核验", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("activity_unknown_count"))
        }
        resources.forEach { resource -> dev.rivo.relay.ui.resources.ResourceRow(resource) }
        if (expanded && canExpand) {
        detailMessages.take(visibleDetails).forEach { message -> key(message.turnId, message.id) { message.activity?.let { activity ->
            var visibleOutput by rememberSaveable { mutableIntStateOf(MARKDOWN_PREVIEW_MAX_CHARS) }
            var visibleFiles by rememberSaveable { mutableIntStateOf(NATIVE_ACTIVITY_BATCH_SIZE) }
            Column(Modifier.fillMaxWidth().padding(start = 23.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                SelectionContainer { Text(activity.summary, style = MaterialTheme.typography.bodySmall,
                    color = if (activity.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("activity_summary_${message.id}")) }
                activity.filePaths.take(visibleFiles).forEach { path -> Text(path, modifier = Modifier.clickable(role = Role.Button) { onFile(path) },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                if (visibleFiles < activity.filePaths.size) TextButton(onClick = { visibleFiles += NATIVE_ACTIVITY_BATCH_SIZE }) { Text("显示更多文件（共 ${activity.filePaths.size} 项）") }
                val agents = agentDetails[message.turnId to message.id] ?: remember(message.raw) { nativeAgentActivity(message.raw) }
                if (agents != null) NativeAgentDetails(agents, onOpenAgent)
                if (agents == null && activity.detail.isNotBlank() && activity.detail.trim() != activity.summary.trim()) Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        val preview = remember(activity.detail, visibleOutput) { boundedMarkdownPreview(activity.detail, visibleOutput) }
                        SelectionContainer { Text(preview.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                        if (activity.detail.length > MARKDOWN_PREVIEW_MAX_CHARS) TextButton(onClick = { clipboard.setText(AnnotatedString(activity.detail)) }, modifier = Modifier.testTag("activity_copy_all_${message.id}")) { Text("复制完整输出") }
                        if (preview.truncated) TextButton(onClick = { visibleOutput = (visibleOutput.toLong() + MARKDOWN_PREVIEW_MAX_CHARS).coerceAtMost(activity.detail.length.toLong()).toInt() }, modifier = Modifier.testTag("activity_output_more_${message.id}")) { Text("继续显示输出（共 ${activity.detail.length} 字符）") }
                    }
                }
                if (activity.exitCode != null || activity.status in setOf("failed", "unknown")) Text(
                    listOfNotNull(when (activity.status) { "failed" -> if (activity.kind == "tool") "工具调用失败（不代表会话未完成）" else "活动执行失败"; "unknown" -> "状态待核验"; else -> null }, activity.exitCode?.let { "退出码 $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (activity.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } } }
        if (visibleDetails < detailMessages.size) TextButton(onClick = { visibleDetails = (visibleDetails + NATIVE_ACTIVITY_BATCH_SIZE).coerceAtMost(detailMessages.size) }, modifier = Modifier.testTag("activity_show_more")) { Text("继续显示 ${minOf(NATIVE_ACTIVITY_BATCH_SIZE, detailMessages.size - visibleDetails)} 项（共 ${detailMessages.size} 项）") }
        }
    }
}

internal const val NATIVE_ACTIVITY_BATCH_SIZE = 16
