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
    val percent = context.percent ?: return
    val fraction = context.fraction ?: return
    var expanded by remember(threadId) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(expanded) { expanded = false }
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val format = NumberFormat.getIntegerInstance()
    fun count(value: Long?) = value?.let(format::format) ?: "暂不可用"
    Box {
        IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(44.dp)
            .testTag("context_usage").semantics {
                contentDescription = "上下文已使用 $percent%，点击查看详情"
            }) {
            CircularProgressIndicator(progress = { fraction },
                modifier = Modifier.size(24.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                trackColor = MaterialTheme.colorScheme.outlineVariant)
        }
        // A focusable popup consumes the outside tap, so tapping the anchor closes
        // the tip without passing the same gesture through and reopening it.
        if (expanded) Popup(popupPositionProvider = remember(gap) { ModeMenuPosition(gap) },
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true, dismissOnClickOutside = true)) {
            Surface(modifier = Modifier.widthIn(max = 280.dp).testTag("context_usage_tip"),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("上下文用量", style = MaterialTheme.typography.titleSmall)
                    Text("已使用：$percent%", style = MaterialTheme.typography.bodySmall)
                    Text("已使用：${count(context.used)} tokens", style = MaterialTheme.typography.bodySmall)
                    Text("总容量：${count(context.window)} tokens", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
