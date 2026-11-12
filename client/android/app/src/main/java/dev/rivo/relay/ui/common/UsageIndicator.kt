package dev.rivo.relay.ui.common

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.progressSemantics
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
import dev.rivo.relay.ui.theme.MoonlightDanger
import dev.rivo.relay.ui.theme.MoonlightSuccess
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
        window?.let { CodexQuotaProgressBar(it, Modifier.fillMaxWidth().height(3.dp)) }
    }
    if (details) UsageDetailsDialog(usage, loading, error, onRefresh, { details = false })
}

@Composable
fun UsageDetailsDialog(usage: CodexUsage?, loading: Boolean, error: String?, onRefresh: (() -> Unit)?,
    onDismiss: () -> Unit, nodeName: String = "当前节点", canReset: Boolean = false, resetPending: Boolean = false,
    resetNotice: String? = null, onReset: (() -> Unit)? = null, onReconcile: (() -> Unit)? = null) {
    var confirming by remember { mutableStateOf(false) }
    val available = usage?.resetCredits?.availableCount
    val allowed = canReset && !loading && error == null && usage?.resetSupported == true && (available ?: 0) > 0
    val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Codex 用量 · $nodeName") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (usage?.resetSupported != true) "当前节点或账户尚未提供额度重置能力"
                else available?.let { "可用重置次数：$it" } ?: "重置次数暂不可用", Modifier.testTag("usage_reset_count"))
            usage?.resetCredits?.credits?.filter { it.status == "available" }?.forEach { credit ->
                credit.expiresAt?.let { Text("重置机会有效期至 ${format.format(Date(it * 1000))}", style = MaterialTheme.typography.bodySmall) }
            }
            usage?.limits.orEmpty().forEach { limit ->
                Text(limit.name, style = MaterialTheme.typography.titleSmall)
                listOfNotNull(limit.primary, limit.secondary).forEach { item ->
                    Text("${usageWindowLabel(item.windowMinutes)}剩余 ${item.remainingPercent}%")
                    LinearProgressIndicator(progress = { item.remainingPercent / 100f }, modifier = Modifier.fillMaxWidth())
                    item.resetsAt?.let { Text("${format.format(Date(it * 1000))} 重置", style = MaterialTheme.typography.bodySmall) }
                }
            }
            resetNotice?.let { Text(it, Modifier.testTag("usage_reset_notice")) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (resetPending) {
                Text("额度重置结果待核对，请勿重复操作")
                onReconcile?.let { TextButton(onClick = it, modifier = Modifier.testTag("reconcile_usage_reset")) { Text("核对结果") } }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (onReset != null) TextButton(onClick = { confirming = true; onRefresh?.invoke() }, enabled = allowed,
                modifier = Modifier.testTag("reset_usage")) { Text("重置额度") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { onRefresh?.let { TextButton(onClick = it, enabled = !loading) { Text("刷新") } } })
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("确认重置额度？") },
        text = { Text("将消耗 $nodeName 当前登录账户的 1 次重置机会（当前可用 ${available ?: "未知"} 次），恢复符合条件的额度。完成后可手动继续任务。") },
        confirmButton = { TextButton(enabled = allowed, onClick = { confirming = false; onReset?.invoke() },
            modifier = Modifier.testTag("confirm_usage_reset")) { Text("确认重置") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } })
}

@Composable
fun CodexQuotaProgressBar(window: CodexUsageWindow, modifier: Modifier = Modifier,
    contentDescription: String = "Codex 周额度与本周时间进度") {
    val remaining = window.remainingPercent / 100f
    val time = quotaTimeRemaining(window, System.currentTimeMillis() / 1000)
    val track = MaterialTheme.colorScheme.secondaryContainer
    val fill = MaterialTheme.colorScheme.primary
    Canvas(modifier.progressSemantics(remaining).semantics { this.contentDescription = contentDescription }) {
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
                if (end > start) drawRect(if (remaining >= it) MoonlightSuccess else MoonlightDanger, Offset(start, 0f), Size(end - start, size.height))
            }
        }
    }
}
