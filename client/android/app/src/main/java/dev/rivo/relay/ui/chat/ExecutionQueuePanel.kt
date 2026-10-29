package dev.rivo.relay.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.rivo.relay.data.QueueTask
import dev.rivo.relay.data.string
import kotlin.math.roundToInt

/** QiGuang CodexQueuePanel: three-row summary, expand, edit/withdraw, steer and long-press reorder. */
@Composable
fun ExecutionQueuePanel(threadId: String, tasks: List<QueueTask>, enabled: Boolean, canSteer: Boolean,
    onEdit: (QueueTask) -> Unit, onWithdraw: (QueueTask) -> Unit, onSteer: (QueueTask) -> Unit,
    onReorder: (List<String>, List<String>) -> Unit) {
    var expanded by rememberSaveable(threadId) { mutableStateOf(false) }
    var order by remember(tasks) { mutableStateOf(tasks) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 28.dp.toPx() }
    LaunchedEffect(tasks) { dragging = null; offset = 0f; if (tasks.size <= 3) expanded = false }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("execution_queue"),
        shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.animateContentSize()) {
            Row(Modifier.fillMaxWidth().clickable(enabled = tasks.size > 3) { expanded = !expanded }.padding(10.dp).testTag("queue_expand"),
                verticalAlignment = Alignment.CenterVertically) {
                Text("执行队列", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("${tasks.count { it.status == "waiting" }} 项等待" + if (tasks.size > 3) if (expanded) " ▴" else " ▾" else "", style = MaterialTheme.typography.labelMedium)
            }
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
                val visibleTasks = if (expanded) order else order.take(3)
                visibleTasks.forEachIndexed { itemIndex, task ->
                    key(task.id) {
                        Row(Modifier.fillMaxWidth().testTag("queue_item_${task.id}")
                            .offset { IntOffset(0, if (dragging == task.id) offset.roundToInt() else 0) }.zIndex(if (dragging == task.id) 1f else 0f)
                            .pointerInput(task.id, enabled, tasks) {
                                if (enabled && task.status == "waiting") detectDragGesturesAfterLongPress(
                                    onDragStart = { dragging = task.id; offset = 0f },
                                    onDragCancel = { dragging = null; order = tasks; offset = 0f },
                                    onDragEnd = {
                                        val before = tasks.filter { it.status == "waiting" }.map { it.id }
                                        val after = order.filter { it.status == "waiting" }.map { it.id }
                                        dragging = null; offset = 0f
                                        if (before != after) onReorder(after, before)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume(); offset += amount.y
                                        val index = order.indexOfFirst { it.id == task.id }
                                        val target = index + if (offset > threshold) 1 else if (offset < -threshold) -1 else 0
                                        if (target != index && target in (if (expanded) order.indices else 0 until minOf(3, order.size)) && order[target].status == "waiting") {
                                            order = order.toMutableList().apply { add(target, removeAt(index)) }; offset = 0f
                                        }
                                    })
                            }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (task.status == "running") "●" else "○", color = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(task.text.ifBlank { "附件任务" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                if (task.status != "waiting") Text(when(task.status) {
                                    "running" -> "执行中"; "dispatching" -> "正在提交"; "unknown" -> "结果待核对"; else -> "执行未完成"
                                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (task.status == "waiting" && canSteer) TextButton(onClick = { onSteer(task) },
                                enabled = enabled && dragging == null && task.raw.string("mode") != "plan" && task.raw.optJSONObject("review") == null,
                                contentPadding = PaddingValues(4.dp), modifier = Modifier.testTag("queue_steer_${task.id}")) { Text("引导") }
                            if (task.status in setOf("waiting", "failed")) TextButton(onClick = { onEdit(task) }, enabled = enabled && dragging == null,
                                contentPadding = PaddingValues(4.dp), modifier = Modifier.testTag("queue_edit_${task.id}")) { Text("编辑") }
                            else if (task.status == "unknown") TextButton(onClick = { onWithdraw(task) }, enabled = enabled && dragging == null) { Text("移除") }
                        }
                        if (itemIndex < visibleTasks.lastIndex) HorizontalDivider(Modifier.padding(start = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                if (!expanded && tasks.size > 3) Text("另有 ${tasks.size - 3} 项", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
