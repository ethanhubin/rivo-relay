package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    var expanded by remember(plan.string("turnId")) { mutableStateOf(false) }
    val done = steps.count { it.string("status") == "completed" }
    TextButton(onClick = { expanded = !expanded }) { Text("执行计划 $done/${steps.size} 已完成" + if (expanded) " ▴" else " ▾") }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("执行计划") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            plan.string("explanation").takeIf { it.isNotBlank() }?.let { Text(it) }
            steps.forEach { step -> Text((when(step.string("status")) { "completed" -> "✓ "; "inProgress", "in_progress" -> "进行中 · "; else -> "○ " }) + step.string("step")) }
        } }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("关闭") } })
}

@Composable
internal fun ChangeSummary(changes: JSONObject?, onOpen: () -> Unit) {
    val files = changes?.objects("files").orEmpty()
    if (files.isEmpty()) return
    TextButton(onClick = onOpen) { Text("${files.size} 个文件 · +${files.sumOf { it.optInt("added") }} / −${files.sumOf { it.optInt("removed") }} · 查看改动") }
}
