package dev.rivo.relay.ui.todos

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.view.Window
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.WorkspaceFilePicker
import dev.rivo.relay.ui.projects.ProjectSummary
import dev.rivo.relay.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

internal fun repeatRuleLabel(rule: String) = when (rule) {
    "daily" -> "每天"; "weekly" -> "每周"; "monthly" -> "每月"; "yearly" -> "每年"; else -> "不重复"
}

internal fun todoReminderAt(due: String, time: String, enabled: Boolean): Long {
    if (!enabled || due.isBlank()) return 0L
    return LocalDate.parse(due).atTime(LocalTime.parse(time)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** QiGuang's bottom checklist editor, adapted to Relay's node attachment references and CRUD. */
@Composable
internal fun TodoDetailsEditor(item: Todo?, busy: Boolean, projects: List<ProjectSummary>,
    readDirectory: (suspend (String) -> JSONObject)?, onDismiss: () -> Unit,
    onSave: (String, String, JSONObject) -> Unit, onDelete: () -> Unit,
    onStartCodex: ((String, String, JSONObject) -> Unit)? = null,
    onOpenAttachment: ((String) -> Unit)? = null, onOpenThread: (() -> Unit)? = null) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val existingList = item?.details?.objects("subtasks")?.isNotEmpty() == true
    var title by rememberSaveable(item?.id) { mutableStateOf(if (existingList) item?.title.orEmpty() else "") }
    var notes by rememberSaveable(item?.id) { mutableStateOf(item?.notes.orEmpty()) }
    var due by rememberSaveable(item?.id) { mutableStateOf(item?.details?.string("dueDate").orEmpty()) }
    var repeat by rememberSaveable(item?.id) { mutableStateOf(item?.details?.string("repeatRule")?.ifBlank { "none" } ?: "none") }
    var reminderEnabled by rememberSaveable(item?.id) { mutableStateOf((item?.details?.optLong("reminderAt") ?: 0) > 0) }
    var reminderTime by rememberSaveable(item?.id) { mutableStateOf(item?.details?.optLong("reminderAt")?.takeIf { it > 0 }
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString() } ?: "09:00") }
    var subtasksJson by rememberSaveable(item?.id) { mutableStateOf(if (existingList) item?.details?.optJSONArray("subtasks").toString() else
        JSONArray().put(JSONObject().put("id", UUID.randomUUID().toString()).put("title", item?.title.orEmpty()).put("completed", item?.completed ?: false)).toString()) }
    var attachmentsJson by rememberSaveable(item?.id) { mutableStateOf(item?.details?.optJSONArray("attachments")?.toString() ?: "[]") }
    var deleteConfirm by remember { mutableStateOf(false) }
    var reminderOpen by remember { mutableStateOf(false) }
    var chooseAttachmentProject by remember { mutableStateOf(false) }
    var attachmentRoot by remember { mutableStateOf<String?>(null) }
    var permissionError by remember { mutableStateOf(false) }
    var focusLine by remember(item?.id) { mutableIntStateOf(0) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reminderEnabled = granted; permissionError = !granted
    }
    val subtasks = JSONObject().put("items", JSONArray(subtasksJson)).objects("items")
    val attachments = JSONObject().put("items", JSONArray(attachmentsJson)).objects("items")
    val filled = subtasks.filter { it.string("title").isNotBlank() }
    val normalizedTitle = title.trim().ifBlank { if (filled.size == 1) filled.single().string("title").trim().take(200) else "待办清单" }
    fun details() = JSONObject().put("dueDate", due).put("repeatRule", repeat)
        .put("reminderAt", todoReminderAt(due, reminderTime, reminderEnabled))
        .put("subtasks", JSONArray(if (title.isBlank() && filled.size == 1) emptyList<JSONObject>() else filled.map { JSONObject(it.toString()).put("title", it.string("title").trim()) }))
        .put("attachments", JSONArray(attachmentsJson))
    val hasContent = title.isNotBlank() || filled.isNotEmpty()
    fun dismiss() { if (!busy) { focus.clearFocus(force = true); keyboard?.hide(); onDismiss() } }
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect {
            window.setDimAmount(.20f)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        TodoEditorBackHandler(window, ::dismiss)
        Box(Modifier.fillMaxSize().testTag("todo_editor_scrim")) {
            Box(Modifier.matchParentSize().clickable(enabled = !busy, onClick = ::dismiss))
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().padding(horizontal = RivoSpacing.Md, vertical = RivoSpacing.KeyboardGap)
                .animateContentSize().testTag("todo_editor"), shape = RoundedCornerShape(RivoRadius.Panel), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = RivoSpacing.PanelContentHorizontal, vertical = RivoSpacing.Lg)) {
                    BasicTextField(title, { title = it.take(200) }, Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("todo_title"), enabled = !busy,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (title.isEmpty()) Text("待办清单", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
                    subtasks.forEachIndexed { index, task ->
                        val requester = remember(task.string("id")) { FocusRequester() }
                        LaunchedEffect(focusLine) { if (focusLine == index) {
                            requester.requestFocus()
                            for (attempt in 0 until 12) { if (window.decorView.hasWindowFocus()) break; withFrameNanos { } }
                            if (window.decorView.hasWindowFocus()) { withFrameNanos { }; keyboard?.show() }
                            focusLine = -1
                        } }
                        Row(Modifier.fillMaxWidth().heightIn(min = RivoSize.ControlHeight), verticalAlignment = Alignment.Top) {
                            TodoCheckbox(task.optBoolean("completed"), enabled = !busy, tag = "todo_editor_subtask_check_$index", size = 20.dp) {
                                subtasksJson = JSONArray(subtasks.mapIndexed { i, current -> JSONObject(current.toString()).apply { if (i == index) put("completed", !task.optBoolean("completed")) } }).toString()
                            }
                            Spacer(Modifier.width(RivoSpacing.Sm))
                            BasicTextField(task.string("title"), { value ->
                                subtasksJson = JSONArray(subtasks.mapIndexed { i, current -> JSONObject(current.toString()).apply { if (i == index) put("title", value.take(500)) } }).toString()
                            }, Modifier.weight(1f).padding(top = 8.dp).focusRequester(requester).testTag("todo_subtask_input_$index"), enabled = !busy,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = if (subtasks.size >= 64 && index == subtasks.lastIndex) ImeAction.Done else ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = {
                                    if (index < subtasks.lastIndex) focusLine = index + 1
                                    else if (task.string("title").isNotBlank() && subtasks.size < 64) {
                                        subtasksJson = JSONArray(subtasks + JSONObject().put("id", UUID.randomUUID().toString()).put("title", "").put("completed", false)).toString()
                                        focusLine = index + 1
                                    }
                                }, onDone = { focus.clearFocus(); keyboard?.hide() }),
                                decorationBox = { inner -> Box { if (task.string("title").isEmpty()) Text(if (subtasks.size == 1 && !existingList) "回车即可连续添加待办" else "待办事项", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
                            if (subtasks.size > 1) TextButton(onClick = { subtasksJson = JSONArray(subtasks.filterIndexed { i, _ -> i != index }).toString() }, enabled = !busy,
                                modifier = Modifier.size(36.dp).semantics { contentDescription = "移除子任务" }, contentPadding = PaddingValues(0.dp)) { Text("×") }
                        }
                    }
                    BasicTextField(notes, { notes = it.take(4000) }, Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("todo_notes"), enabled = !busy,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner -> Box { if (notes.isEmpty()) Text("备注", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
                    Spacer(Modifier.height(if (subtasks.size == 1 && !existingList) 32.dp else RivoSpacing.Md))
                    if (attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = RivoSpacing.Sm), horizontalArrangement = Arrangement.spacedBy(RivoSpacing.Sm)) {
                        attachments.forEachIndexed { index, attachment -> Surface(Modifier.width(96.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { onOpenAttachment?.invoke(attachment.string("path")) }, enabled = onOpenAttachment != null,
                                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("附件") }
                                    TextButton(onClick = { attachmentsJson = JSONArray(attachments.filterIndexed { i, _ -> i != index }).toString() }, enabled = !busy,
                                        modifier = Modifier.size(28.dp).testTag("todo_attachment_remove_$index"), contentPadding = PaddingValues(0.dp)) { Text("×") }
                                }
                                Text(attachment.string("name"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
                        } }
                    }
                    if (permissionError) Text("未获得通知权限，可在系统设置中开启。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (due.isNotBlank()) Text(buildString { append(due); if (reminderEnabled) append(" $reminderTime"); if (repeat != "none") append(" · ${repeatRuleLabel(repeat)}") },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); reminderOpen = true }, enabled = !busy, modifier = Modifier.testTag("todo_due_date"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("◴", style = MaterialTheme.typography.titleLarge) }
                        TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); chooseAttachmentProject = true }, enabled = !busy && readDirectory != null && projects.isNotEmpty() && attachments.size < 16,
                            modifier = Modifier.testTag("todo_add_attachment"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("＋附件") }
                        if (due.isNotBlank()) TextButton(onClick = { due = ""; reminderEnabled = false; repeat = "none" }, enabled = !busy, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("清除") }
                        if (onStartCodex != null) TextButton(onClick = { onStartCodex(normalizedTitle, notes, details()) }, enabled = hasContent && !busy,
                            modifier = Modifier.testTag("todo_start_codex"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Codex") }
                        if (onOpenThread != null) TextButton(onClick = onOpenThread, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("会话") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onSave(normalizedTitle, notes, details()) }, enabled = hasContent && !busy, modifier = Modifier.testTag("save_todo"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(if (busy) "保存中…" else "完成", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (item != null) TextButton(onClick = { deleteConfirm = true }, enabled = !busy, contentPadding = PaddingValues(0.dp)) { Text("删除待办", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (reminderOpen) TodoReminderDialog(due, reminderTime, repeat, { reminderOpen = false }) { dateTime, rule ->
        due = dateTime.toLocalDate().toString(); reminderTime = dateTime.toLocalTime().toString(); repeat = rule; reminderOpen = false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else reminderEnabled = true
    }
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("删除这条待办？") }, text = { Text("待办会被删除，已关联的 Codex 会话仍会保留。") },
        confirmButton = { Button(onClick = { deleteConfirm = false; onDelete() }, enabled = !busy) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("取消") } })
    if (chooseAttachmentProject) AlertDialog(onDismissRequest = { chooseAttachmentProject = false }, title = { Text("选择文件所在项目") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { projects.forEach { project -> TextButton(onClick = { attachmentRoot = project.path; chooseAttachmentProject = false }) { Text(project.name) } } }
    }, confirmButton = { TextButton(onClick = { chooseAttachmentProject = false }) { Text("取消") } })
    val root = attachmentRoot
    if (root != null && readDirectory != null) WorkspaceFilePicker(root, readDirectory, { attachmentRoot = null }) { reference ->
        val path = reference.string("path")
        if (attachments.none { it.string("path") == path }) attachmentsJson = JSONArray(attachments + JSONObject().put("name", reference.string("name")).put("path", path).put("type", "file")).toString()
        attachmentRoot = null
    }
}

@Composable
private fun TodoEditorBackHandler(window: Window, onBack: () -> Unit) {
    if (Build.VERSION.SDK_INT >= 33) {
        val currentOnBack by rememberUpdatedState(onBack)
        DisposableEffect(window) {
            val callback = OnBackInvokedCallback { currentOnBack() }
            window.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
            onDispose { window.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
        }
    } else BackHandler(onBack = onBack)
}

/** Calendar, time and repeat selection preserve QiGuang's confirmation boundary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoReminderDialog(initialDueDate: String, initialTime: String, initialRepeatRule: String,
    onDismiss: () -> Unit, onConfirm: (LocalDateTime, String) -> Unit) {
    val context = LocalContext.current
    val date = remember { runCatching { LocalDate.parse(initialDueDate) }.getOrDefault(LocalDate.now()) }
    var time by remember { mutableStateOf(runCatching { LocalTime.parse(initialTime) }.getOrDefault(LocalTime.of(9, 0))) }
    var repeat by remember { mutableStateOf(initialRepeatRule) }
    var repeatMenu by remember { mutableStateOf(false) }
    val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    fun selectedDate() = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: date
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 10.dp).heightIn(max = 820.dp).testTag("todo_reminder_dialog"),
            shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = RivoSpacing.Md, vertical = RivoSpacing.Xxl),
                verticalArrangement = Arrangement.spacedBy(RivoSpacing.Lg)) {
                Text("设置提醒时间", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("提醒时间", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(RivoSpacing.Sm)) {
                        Surface(Modifier.testTag("todo_reminder_date"), shape = RoundedCornerShape(RivoRadius.Control), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(selectedDate().toString(), Modifier.padding(horizontal = RivoSpacing.Md, vertical = RivoSpacing.Sm), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Surface(onClick = { TimePickerDialog(context, { _, hour, minute -> time = LocalTime.of(hour, minute) }, time.hour, time.minute, true).show() },
                            modifier = Modifier.testTag("todo_reminder_time"), shape = RoundedCornerShape(RivoRadius.Control), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Text(time.format(DateTimeFormatter.ofPattern("HH:mm")), Modifier.padding(horizontal = RivoSpacing.Md, vertical = RivoSpacing.Sm))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth()) {
                    Surface(onClick = { repeatMenu = true }, modifier = Modifier.fillMaxWidth().testTag("todo_repeat"), color = Color.Transparent) {
                        Row(Modifier.fillMaxWidth().padding(vertical = RivoSpacing.Sm), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("重复提醒", style = MaterialTheme.typography.titleMedium)
                            Text("${repeatRuleLabel(repeat)}  ⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    DropdownMenu(repeatMenu, { repeatMenu = false }) { listOf("none", "daily", "weekly", "monthly", "yearly").forEach { rule ->
                        DropdownMenuItem(text = { Text(repeatRuleLabel(rule)) }, onClick = { repeat = rule; repeatMenu = false }, modifier = Modifier.testTag("todo_repeat_$rule"))
                    } }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DatePicker(picker, modifier = Modifier.fillMaxWidth(), title = null, headline = null, showModeToggle = false)
                if (repeat != "none") Text("手动完成后创建下一次待办。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RivoSpacing.Md)) {
                    OutlinedButton(onDismiss, Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) { Text("取消") }
                    Button(onClick = { onConfirm(LocalDateTime.of(selectedDate(), time), repeat) }, Modifier.weight(1f).heightIn(min = 52.dp).testTag("todo_reminder_confirm"), shape = RoundedCornerShape(16.dp)) { Text("确定") }
                }
            }
        }
    }
}
