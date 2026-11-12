package dev.rivo.relay.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.string
import org.json.JSONObject

internal fun goalStatusLabel(status: String): String = when (status) {
    "pending" -> "发送后启用"
    "active" -> "进行中"
    "paused" -> "已暂停"
    "blocked" -> "受阻"
    "usageLimited" -> "额度受限"
    "budgetLimited" -> "预算已用尽"
    "complete" -> "已完成"
    else -> "未知状态（$status）"
}

@Composable
fun NativeGoalPanel(goal: JSONObject?, enabled: Boolean, onAction: (JSONObject) -> Unit,
    editing: Boolean, pending: Boolean = false, onEditingChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val status = goal?.string("status").orEmpty()
    val savedObjective = goal?.string("objective").orEmpty()
    val savedBudget = (goal?.opt("tokenBudget") as? Number)?.toLong()?.toString().orEmpty()
    var objective by rememberSaveable(savedObjective, savedBudget) { mutableStateOf(savedObjective) }
    var budget by rememberSaveable(savedObjective, savedBudget) { mutableStateOf(savedBudget) }
    fun cancelEditing() { objective = savedObjective; budget = savedBudget; onEditingChange(false) }
    if (goal != null) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
                // Cross the panel's 6dp bottom gap and the composer's 8dp top gap, then overlap by 2dp.
                .offset(y = 14.dp + 2.dp)
                // Composer inset (12dp) + top corner radius (28dp).
                .padding(horizontal = 40.dp, vertical = 6.dp).testTag("native_goal")) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp + 2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(vertical = 8.dp)) { Text(if (pending) "目标草稿" else "目标 · ${goalStatusLabel(status)}") }
                    if (status == "active") TextButton(onClick = { onAction(JSONObject().put("operation", "pause")) }, enabled = enabled, contentPadding = PaddingValues(vertical = 8.dp)) { Text("暂停目标") }
                    else if (status in setOf("paused", "blocked", "usageLimited", "budgetLimited")) TextButton(onClick = { onAction(JSONObject().put("operation", "resume")) }, enabled = enabled, contentPadding = PaddingValues(vertical = 8.dp)) { Text("尝试继续") }
                }
                Text(goal.string("objective"), maxLines = if (expanded) 5 else 1, overflow = TextOverflow.Ellipsis)
                if (expanded) {
                    val budget = (goal.opt("tokenBudget") as? Number)?.toLong()
                    Text(if (pending) budget?.let { "Token 预算：$it" } ?: "未设 Token 预算"
                        else "Token ${goal.optLong("tokensUsed")}" + (budget?.let { " / $it" } ?: " · 未设预算") +
                            " · 已用 ${goal.optLong("timeUsedSeconds") / 60} 分钟", style = MaterialTheme.typography.labelMedium)
                    if (!pending) Text("暂停目标不等同于停止当前轮次；队列中的其他任务仍可执行。", style = MaterialTheme.typography.bodySmall)
                    if (!pending && status in setOf("budgetLimited", "usageLimited")) Text(
                        if (status == "budgetLimited") "预算已用尽，可编辑预算后尝试启用。" else "请先恢复可用额度，再尝试继续。",
                        style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { clearing = true }, enabled = enabled, contentPadding = PaddingValues(vertical = 8.dp)) { Text("清除目标") }
                    }
                }
            }
        }
    }
    if (editing) {
        val validBudget = budget.isBlank() || (budget.toLongOrNull()?.let { it > 0 } == true)
        AlertDialog(onDismissRequest = ::cancelEditing, title = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Text("持续目标") } }, text = { dev.rivo.relay.ui.chat.ThemedTextContextMenu {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (pending) "目标保存在草稿中，发送首条任务时启用。Codex 会跨轮次持续推进，直至完成、暂停或遇到限制。" else "Codex 会跨轮次持续推进，直至完成、暂停或遇到限制。保存后启用目标。")
                OutlinedTextField(objective, { objective = it }, label = { Text("希望完成什么") }, minLines = 3, maxLines = 8)
                OutlinedTextField(budget, { budget = it }, label = { Text("Token 预算（可选）") }, singleLine = true, isError = !validBudget,
                    supportingText = { Text(if (pending) "留空不设置新预算；此前已提交的预算不会因此清除" else if (goal == null) "留空不设置预算" else "留空保留已有预算；预算是否允许继续由原生运行时判断") })
            }
        } }, confirmButton = {
            TextButton(onClick = {
                val body = JSONObject().put("operation", "set").put("objective", objective.trim())
                budget.toLongOrNull()?.let { body.put("tokenBudget", it) }
                onAction(body); onEditingChange(false)
            }, enabled = enabled && objective.isNotBlank() && objective.length <= 200000 && validBudget) { Text(if (pending) "保存目标" else "保存并启用") }
        }, dismissButton = { TextButton(onClick = ::cancelEditing) { Text("取消") } })
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Text("清除目标？") } },
        text = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Text(if (pending) "移除草稿中的目标。若此前已提交目标，下一次发送时会清除会话中的目标。" else "移除此会话的持续目标。聊天记录和队列保留；当前轮次可通过停止按钮单独停止。") } },
        confirmButton = { TextButton(onClick = { onAction(JSONObject().put("operation", "clear")); clearing = false }, enabled = enabled) { Text("清除") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("取消") } })
}
