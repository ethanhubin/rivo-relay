package dev.rivo.relay.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*
import java.text.DateFormat
import java.util.Date

/** A shared compact indicator for the project, session-list and conversation headers. */
@Composable
fun UsageIndicator(usage: CodexUsage?, modifier: Modifier = Modifier, contextPercent: Int? = null,
    loading: Boolean = false, error: String? = null, onRefresh: (() -> Unit)? = null) {
    var details by remember { mutableStateOf(false) }
    val window = usage?.weeklyWindow
    if (window == null && contextPercent == null && !loading && error == null) return
    Column(modifier.testTag("usage_indicator")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (window != null || error != null) TextButton(onClick = { details = true }, contentPadding = PaddingValues(0.dp)) {
                Text(window?.let { "${usageWindowLabel(it.windowMinutes)}剩余 ${it.remainingPercent}%" } ?: "额度暂不可用", style = MaterialTheme.typography.labelSmall)
            } else if (loading) Text("正在读取额度…", style = MaterialTheme.typography.labelSmall)
            contextPercent?.let { Text("上下文 ${it.coerceIn(0, 100)}%", Modifier.padding(vertical = 12.dp).testTag("context_usage"), style = MaterialTheme.typography.labelSmall) }
        }
        window?.let { QuotaProgress(it) }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Codex 用量") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            usage?.limits.orEmpty().forEach { limit ->
                Text(limit.name, style = MaterialTheme.typography.titleSmall)
                listOfNotNull(limit.primary, limit.secondary).forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${usageWindowLabel(item.windowMinutes)}剩余 ${item.remainingPercent}%")
                        LinearProgressIndicator(progress = { item.remainingPercent / 100f }, modifier = Modifier.fillMaxWidth())
                        item.resetsAt?.let { Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1000))} 重置", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { details = false }) { Text("完成") } },
        dismissButton = { onRefresh?.let { TextButton(onClick = it, enabled = !loading) { Text("刷新") } } })
}

@Composable
private fun QuotaProgress(window: CodexUsageWindow) {
    val remaining = window.remainingPercent / 100f
    val time = quotaTimeRemaining(window, System.currentTimeMillis() / 1000)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    val ahead = MaterialTheme.colorScheme.tertiary
    val behind = MaterialTheme.colorScheme.error
    Canvas(Modifier.fillMaxWidth().height(3.dp).semantics { contentDescription = "额度剩余 ${window.remainingPercent}%" }) {
        val count = 7
        val gap = 1.dp.toPx().coerceAtMost(size.width / (count - 1))
        val width = (size.width - gap * (count - 1)) / count
        repeat(count) { i ->
            val left = i * (width + gap)
            drawRect(track, Offset(left, 0f), Size(width, size.height))
            drawRect(fill, Offset(left, 0f), Size(width * (remaining * count - i).coerceIn(0f, 1f), size.height))
            time?.let {
                val start = maxOf(left, minOf(remaining, it) * size.width)
                val end = minOf(left + width, maxOf(remaining, it) * size.width)
                if (end > start) drawRect(if (remaining >= it) ahead else behind, Offset(start, 0f), Size(end - start, size.height))
            }
        }
    }
}
