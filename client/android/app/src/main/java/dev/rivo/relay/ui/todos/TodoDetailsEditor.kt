package dev.rivo.relay.ui.todos

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.WorkspaceFilePicker
import dev.rivo.relay.ui.projects.ProjectSummary
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

internal fun repeatRuleLabel(rule: String) = when (rule) {
    "daily" -> "每天"; "weekly" -> "每周"; "monthly" -> "每月"; "yearly" -> "每年"; else -> "不重复"
}

internal fun todoReminderAt(due: String, time: String, enabled: Boolean): Long {
    if (!enabled || due.isBlank()) return 0L
    return LocalDate.parse(due).atTime(LocalTime.parse(time)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

@Composable
internal fun TodoDetailsEditor(item: Todo?, busy: Boolean, projects: List<ProjectSummary>,
    readDirectory: (suspend (String) -> JSONObject)?, onDismiss: () -> Unit,
    onSave: (String, String, JSONObject) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var title by rememberSaveable(item?.id) { mutableStateOf(item?.title.orEmpty()) }
    var notes by rememberSaveable(item?.id) { mutableStateOf(item?.notes.orEmpty()) }
    var due by rememberSaveable(item?.id) { mutableStateOf(item?.details?.string("dueDate").orEmpty()) }
    var repeat by rememberSaveable(item?.id) { mutableStateOf(item?.details?.string("repeatRule")?.ifBlank { "none" } ?: "none") }
    var reminderEnabled by rememberSaveable(item?.id) { mutableStateOf((item?.details?.optLong("reminderAt") ?: 0) > 0) }
    var reminderTime by rememberSaveable(item?.id) { mutableStateOf(item?.details?.optLong("reminderAt")?.takeIf { it > 0 }
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString() } ?: "09:00") }
    var subtasksJson by rememberSaveable(item?.id) { mutableStateOf(item?.details?.optJSONArray("subtasks")?.toString() ?: "[]") }
    var attachmentsJson by rememberSaveable(item?.id) { mutableStateOf(item?.details?.optJSONArray("attachments")?.toString() ?: "[]") }
    var newSubtask by rememberSaveable { mutableStateOf("") }
    var deleteConfirm by remember { mutableStateOf(false) }
    var repeatMenu by remember { mutableStateOf(false) }
    var chooseAttachmentProject by remember { mutableStateOf(false) }
    var attachmentRoot by remember { mutableStateOf<String?>(null) }
    var permissionError by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reminderEnabled = granted; permissionError = !granted
    }
    val subtasks = JSONObject().put("items", JSONArray(subtasksJson)).objects("items")
    val attachments = JSONObject().put("items", JSONArray(attachmentsJson)).objects("items")
    fun details() = JSONObject().put("dueDate", due).put("repeatRule", repeat)
        .put("reminderAt", todoReminderAt(due, reminderTime, reminderEnabled))
        .put("subtasks", JSONArray(subtasksJson)).put("attachments", JSONArray(attachmentsJson))
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (item == null) "新建待办" else "编辑待办") },
        text = { Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it.take(200) }, label = { Text("待办标题") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("todo_title"))
            OutlinedTextField(notes, { notes = it.take(4000) }, label = { Text("备注") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("todo_notes"), minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val selected = runCatching { LocalDate.parse(due) }.getOrDefault(LocalDate.now())
                    DatePickerDialog(context, { _, year, month, day -> due = LocalDate.of(year, month + 1, day).toString() },
                        selected.year, selected.monthValue - 1, selected.dayOfMonth).show()
                }, enabled = !busy, modifier = Modifier.testTag("todo_due_date")) { Text(if (due.isBlank()) "＋ 截止日期" else "截止 $due") }
                if (due.isNotBlank()) TextButton(onClick = { due = ""; reminderEnabled = false; repeat = "none" }, enabled = !busy) { Text("清除") }
            }
            if (due.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("提醒", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Switch(reminderEnabled, { checked ->
                        if (!checked) reminderEnabled = false
                        else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else reminderEnabled = true
                    }, enabled = !busy, modifier = Modifier.testTag("todo_reminder"))
                    if (reminderEnabled) TextButton(onClick = {
                        val time = LocalTime.parse(reminderTime)
                        TimePickerDialog(context, { _, hour, minute -> reminderTime = LocalTime.of(hour, minute).toString() }, time.hour, time.minute, true).show()
                    }, enabled = !busy) { Text(reminderTime) }
                }
                if (reminderEnabled) Text(if (todoReminderAt(due, reminderTime, true) <= System.currentTimeMillis()) "提醒时间已过，保存后不会补发。" else "在这台手机提醒；系统省电时可能稍有延迟。", style = MaterialTheme.typography.bodySmall)
                if (permissionError) Text("未获得通知权限，可在系统设置中开启。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Box {
                    OutlinedButton(onClick = { repeatMenu = true }, enabled = !busy, modifier = Modifier.testTag("todo_repeat")) { Text("重复 · ${repeatRuleLabel(repeat)}") }
                    DropdownMenu(repeatMenu, { repeatMenu = false }) {
                        listOf("none", "daily", "weekly", "monthly", "yearly").forEach { rule ->
                            DropdownMenuItem(text = { Text(repeatRuleLabel(rule)) }, onClick = { repeat = rule; repeatMenu = false })
                        }
                    }
                }
                if (repeat != "none") Text("手动完成后创建下一次待办，子任务重新开始。Codex 会话不会自动标记待办完成。", style = MaterialTheme.typography.bodySmall)
            }
            Text("子任务", style = MaterialTheme.typography.titleSmall)
            subtasks.forEachIndexed { index, task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(task.optBoolean("completed"), { checked ->
                        subtasksJson = JSONArray(subtasks.mapIndexed { i, current -> JSONObject(current.toString()).apply { if (i == index) put("completed", checked) } }).toString()
                    }, enabled = !busy)
                    Text(task.string("title"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { subtasksJson = JSONArray(subtasks.filterIndexed { i, _ -> i != index }).toString() }, enabled = !busy) { Text("移除") }
                }
            }
            if (subtasks.size < 64) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newSubtask, { newSubtask = it.take(500) }, placeholder = { Text("添加子任务") }, enabled = !busy,
                    modifier = Modifier.weight(1f).testTag("todo_subtask_title"), singleLine = true)
                TextButton(onClick = {
                    subtasksJson = JSONArray(subtasks + JSONObject().put("id", UUID.randomUUID().toString()).put("title", newSubtask.trim()).put("completed", false)).toString()
                    newSubtask = ""
                }, enabled = !busy && newSubtask.isNotBlank(), modifier = Modifier.testTag("add_subtask")) { Text("添加") }
            }
            Text("附件引用", style = MaterialTheme.typography.titleSmall)
            attachments.forEachIndexed { index, attachment -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(attachment.string("name"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { attachmentsJson = JSONArray(attachments.filterIndexed { i, _ -> i != index }).toString() }, enabled = !busy) { Text("移除") }
            } }
            TextButton(onClick = { chooseAttachmentProject = true }, enabled = !busy && readDirectory != null && projects.isNotEmpty() && attachments.size < 16,
                modifier = Modifier.testTag("todo_add_attachment")) { Text("＋ 选择节点文件") }
            if (item != null) TextButton(onClick = { deleteConfirm = true }, enabled = !busy) { Text("删除待办", color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { Button(onClick = { onSave(title, notes, details()) }, enabled = title.isNotBlank() && !busy,
            modifier = Modifier.testTag("save_todo")) { Text("保存") } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("取消") } })
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("删除这条待办？") }, text = { Text("待办会被删除，已关联的 Codex 会话仍会保留。") },
        confirmButton = { Button(onClick = { deleteConfirm = false; onDelete() }, enabled = !busy) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("取消") } })
    if (chooseAttachmentProject) AlertDialog(onDismissRequest = { chooseAttachmentProject = false }, title = { Text("选择文件所在项目") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { projects.forEach { project ->
            TextButton(onClick = { attachmentRoot = project.path; chooseAttachmentProject = false }) { Text(project.name) }
        } }
    }, confirmButton = { TextButton(onClick = { chooseAttachmentProject = false }) { Text("取消") } })
    val root = attachmentRoot
    if (root != null && readDirectory != null) WorkspaceFilePicker(root, readDirectory, { attachmentRoot = null }) { reference ->
        val path = reference.string("path")
        if (attachments.none { it.string("path") == path }) attachmentsJson = JSONArray(attachments + JSONObject().put("name", reference.string("name")).put("path", path).put("type", "file")).toString()
        attachmentRoot = null
    }
}
