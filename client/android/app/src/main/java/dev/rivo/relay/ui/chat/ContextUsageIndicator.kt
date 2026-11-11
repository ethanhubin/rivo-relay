package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.json.JSONObject
import java.text.NumberFormat

internal data class ContextUsage(val used: Long?, val window: Long?) {
    val fraction: Float? get() = if (used != null && window != null)
        (used.toDouble() / window).coerceIn(0.0, 1.0).toFloat() else null
    val percent: Int? get() = if (used != null && window != null)
        (used.toDouble() * 100 / window).coerceIn(0.0, 100.0).toInt() else null
}

internal fun contextUsage(usage: JSONObject?): ContextUsage = ContextUsage(
    usage?.optJSONObject("last")?.optLong("totalTokens", -1)?.takeIf { it >= 0 },
    usage?.optLong("modelContextWindow", -1)?.takeIf { it > 0 },
)

@Composable
internal fun ContextUsageIndicator(usage: JSONObject?, threadId: String?) {
    val context = contextUsage(usage)
    var expanded by remember(threadId) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(expanded) { expanded = false }
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val percent = context.percent
    val format = NumberFormat.getIntegerInstance()
    fun count(value: Long?) = value?.let(format::format) ?: "暂不可用"
    Box {
        IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(44.dp)
            .testTag("context_usage").semantics {
                contentDescription = percent?.let { "上下文已使用 $it%，点击查看详情" } ?: "上下文用量暂不可用，点击查看详情"
            }) {
            CircularProgressIndicator(progress = { context.fraction ?: 0f },
                modifier = Modifier.size(24.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                trackColor = MaterialTheme.colorScheme.outlineVariant)
        }
        // Keep the anchor clickable. Outside-touch dismissal can close on DOWN and
        // immediately reopen on the anchor's UP; the button owns toggle dismissal.
        if (expanded) Popup(popupPositionProvider = remember(gap) { ModeMenuPosition(gap) },
            properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
            Surface(modifier = Modifier.widthIn(max = 280.dp).testTag("context_usage_tip"),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("上下文用量", style = MaterialTheme.typography.titleSmall)
                    Text(percent?.let { "已使用 $it% · 剩余 ${100 - it}%" } ?: "尚无完整的上下文统计",
                        style = MaterialTheme.typography.bodySmall)
                    Text("已使用：${count(context.used)} tokens", style = MaterialTheme.typography.bodySmall)
                    Text("窗口容量：${count(context.window)} tokens", style = MaterialTheme.typography.bodySmall)
                    Text("最近一次模型调用的统计；上下文压缩后会更新。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
