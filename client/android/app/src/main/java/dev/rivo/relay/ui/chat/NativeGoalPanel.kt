package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.string
import org.json.JSONObject

internal fun goalStatusLabel(status: String): String = when (status) {
    "active" -> "进行中"
    "paused" -> "已暂停"
    "blocked" -> "受阻"
    "usageLimited" -> "额度受限"
    "budgetLimited" -> "预算已用尽"
    "complete" -> "已完成"
    else -> "未知状态（$status）"
}

@Composable
fun NativeGoalPanel(goal: JSONObject?, enabled: Boolean, onAction: (JSONObject) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val status = goal?.string("status").orEmpty()
    if (goal == null) {
        TextButton(onClick = { editing = true }, enabled = enabled, modifier = Modifier.testTag("create_goal")) { Text("设置持续目标") }
    } else {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("native_goal")) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { expanded = !expanded }) { Text("目标 · ${goalStatusLabel(status)}") }
                    if (status == "active") TextButton(onClick = { onAction(JSONObject().put("operation", "pause")) }, enabled = enabled) { Text("暂停目标") }
                    else if (status in setOf("paused", "blocked", "usageLimited", "budgetLimited")) TextButton(onClick = { onAction(JSONObject().put("operation", "resume")) }, enabled = enabled) { Text("继续目标") }
                }
                Text(goal.string("objective"), maxLines = if (expanded) 5 else 1, overflow = TextOverflow.Ellipsis)
                if (expanded) {
                    val budget = (goal.opt("tokenBudget") as? Number)?.toLong()
                    Text("Token ${goal.optLong("tokensUsed")}" + (budget?.let { " / $it" } ?: " · 未设预算") +
                        " · 已用 ${goal.optLong("timeUsedSeconds") / 60} 分钟", style = MaterialTheme.typography.labelMedium)
                    Row {
                        TextButton(onClick = { editing = true }, enabled = enabled) { Text("编辑目标") }
                        TextButton(onClick = { clearing = true }, enabled = enabled) { Text("清除目标") }
                    }
                }
            }
        }
    }
    if (editing) {
        var objective by remember { mutableStateOf(goal?.string("objective").orEmpty()) }
        var budget by remember { mutableStateOf((goal?.opt("tokenBudget") as? Number)?.toLong()?.toString().orEmpty()) }
        val validBudget = budget.isBlank() || (budget.toLongOrNull()?.let { it > 0 } == true)
        AlertDialog(onDismissRequest = { editing = false }, title = { Text("持续目标") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Codex 会跨轮次持续推进，直至完成、暂停或遇到限制。保存后启用目标。")
                OutlinedTextField(objective, { objective = it }, label = { Text("希望完成什么") }, minLines = 3, maxLines = 8)
                OutlinedTextField(budget, { budget = it }, label = { Text("Token 预算（可选）") }, singleLine = true, isError = !validBudget,
                    supportingText = { Text("留空不修改已有预算") })
            }
        }, confirmButton = {
            TextButton(onClick = {
                val body = JSONObject().put("operation", "set").put("objective", objective.trim())
                budget.toLongOrNull()?.let { body.put("tokenBudget", it) }
                onAction(body); editing = false
            }, enabled = enabled && objective.isNotBlank() && objective.length <= 200000 && validBudget) { Text("保存并启用") }
        }, dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } })
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("清除目标？") },
        text = { Text("移除此会话的持续目标。聊天记录会保留；当前轮次可通过停止按钮单独停止。") },
        confirmButton = { TextButton(onClick = { onAction(JSONObject().put("operation", "clear")); clearing = false }, enabled = enabled) { Text("清除") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("取消") } })
}
