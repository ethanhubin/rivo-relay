package dev.rivo.relay.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*
import org.json.JSONObject

internal fun contextPercent(usage: JSONObject?): Int? {
    val window = usage?.optLong("modelContextWindow", 0) ?: 0
    val last = usage?.optJSONObject("last") ?: return null
    val used = last.optLong("totalTokens", -1)
    return if (window > 0 && used >= 0) (used.toDouble() * 100 / window).toInt().coerceIn(0, 100) else null
}

@Composable
internal fun NativePlan(plan: JSONObject?) {
    if (plan == null || plan.string("status") == "completed") return
    val steps = plan.objects("plan")
    if (steps.isEmpty()) return
    var expanded by rememberSaveable(plan.string("turnId")) { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val done = steps.count { nativePlanStatus(it.string("status")) == "completed" }
    val density = LocalDensity.current
    val gap = with(density) { 4.dp.roundToPx() }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).onSizeChanged { width = it.width }, contentAlignment = Alignment.Center) {
        if (expanded) Popup(popupPositionProvider = remember(gap) { NativePlanPositionProvider(gap) }, onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true, clippingEnabled = true)) {
            Surface(Modifier.width(with(density) { width.toDp() }).testTag("codex_plan_popup"), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).testTag("codex_plan_items")
                    .padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    plan.string("explanation").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    steps.forEachIndexed { index, step ->
                        val status = nativePlanStatus(step.string("status"))
                        val label = when (status) { "completed" -> "已完成"; "in_progress" -> "进行中"; "failed" -> "失败"; "canceled" -> "已停止"; "interrupted" -> "已中断"; else -> "待处理" }
                        val symbol = when (status) { "completed" -> "✓"; "in_progress" -> "●"; "failed" -> "×"; "canceled" -> "–"; "interrupted" -> "!"; else -> "○" }
                        val color = when (status) { "failed" -> MaterialTheme.colorScheme.error; "in_progress", "completed" -> MaterialTheme.colorScheme.primary
                            "canceled", "interrupted" -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                        Row(Modifier.fillMaxWidth().testTag("codex_plan_item_$status"), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(symbol, color = color, style = MaterialTheme.typography.bodyMedium)
                            Text(step.string("step"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                color = if (status == "pending") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            Text(label, color = color, style = MaterialTheme.typography.labelSmall)
                        }
                        if (index < steps.lastIndex) HorizontalDivider(Modifier.padding(start = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        Surface(Modifier.testTag("codex_plan_card"), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), tonalElevation = 2.dp) {
            Box(Modifier.clickable { expanded = !expanded }.padding(horizontal = 8.dp, vertical = 3.dp).testTag("codex_plan_header"), contentAlignment = Alignment.Center) {
                Text("执行计划 $done/${steps.size} 已完成", Modifier.testTag("codex_plan_progress"), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun nativePlanStatus(status: String): String = when (status.lowercase().replace("_", "").replace("-", "")) {
    "completed", "complete", "done", "success", "succeeded" -> "completed"
    "inprogress", "running", "active" -> "in_progress"
    "failed", "error" -> "failed"
    "canceled", "cancelled", "stopped" -> "canceled"
    "interrupted" -> "interrupted"
    else -> "pending"
}

private class NativePlanPositionProvider(private val gap: Int): PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        return IntOffset(preferredX.coerceIn(0, maxX), (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0))
    }
}

@Composable
internal fun ChangeSummary(changes: JSONObject?, onOpen: () -> Unit) {
    val files = changes?.objects("files").orEmpty()
    if (files.isEmpty()) return
    TextButton(onClick = onOpen) { Text("${files.size} 个文件 · +${files.sumOf { it.optInt("added") }} / −${files.sumOf { it.optInt("removed") }} · 查看改动") }
}
