package dev.rivo.relay.ui.todos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.projects.ProjectSummary
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun TodosScreen(state: TodosState, projects: List<ProjectSummary>, canDispatch: Boolean,
    loadThreads: suspend (String) -> List<ThreadSummary>, onSave: (Todo?, String, String, () -> Unit) -> Unit,
    onToggle: (Todo) -> Unit, onDelete: (Todo, () -> Unit) -> Unit, onDispatch: (Todo, String, String?, () -> Unit) -> Unit,
    onOpenThread: (Todo) -> Unit, onRefresh: () -> Unit,
    onSaveDetails: ((Todo?, String, String, JSONObject, () -> Unit) -> Unit)? = null,
    onUpdateDetails: ((Todo, JSONObject) -> Unit)? = null,
    onSynchronize: (() -> Unit)? = null, onDiscardLocal: (() -> Unit)? = null,
    readDirectory: (suspend (String) -> JSONObject)? = null, onOpenAttachment: ((String) -> Unit)? = null) {
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Todo?>(null) }
    var delivery by remember { mutableStateOf<Todo?>(null) }
    var completedExpanded by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var discardConfirm by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Box(Modifier.fillMaxSize().testTag("todos_page")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("待办", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onRefresh, enabled = !state.loading) { Text("刷新") }
            }
            if (state.pendingCount > 0 || state.syncError != null) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(if (state.pendingCount > 0) "${state.pendingCount} 项更改保存在手机，等待同步" else "本机待办副本", style = MaterialTheme.typography.labelLarge)
                    state.syncError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row {
                        TextButton(onClick = { (onSynchronize ?: onRefresh)() }, enabled = !state.syncing, modifier = Modifier.testTag("sync_todos")) { Text(if (state.syncing) "同步中…" else "同步") }
                        if (onDiscardLocal != null && state.pendingCount > 0 && state.syncError != null) TextButton(onClick = { discardConfirm = true }, enabled = !state.syncing) { Text("使用节点版本") }
                    }
                }
            }
            OutlinedTextField(search, { search = it }, singleLine = true, placeholder = { Text("搜索待办") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("todo_search"), shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }))
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            val shown = state.items.filter { search.isBlank() || it.title.contains(search, true) || it.notes.contains(search, true) }
            LazyColumn(Modifier.weight(1f).testTag("todo_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (shown.isEmpty() && !state.loading) item { Text(if (search.isBlank()) "还没有待办，点击＋创建" else "没有匹配的待办", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(shown.filter { !it.completed }, key = { it.id }) { item -> TodoCard(item, state.mutating, { onToggle(item) }, { editing = item; editor = true }, { delivery = item }, { onOpenThread(item) }, canDispatch && state.online && state.pendingCount == 0 && !state.syncing, onUpdateDetails, onOpenAttachment) }
                val completed = shown.filter { it.completed }
                if (completed.isNotEmpty()) item { TextButton(onClick = { completedExpanded = !completedExpanded }) { Text("已完成 ${completed.size} " + if (completedExpanded) "▴" else "▾") } }
                if (completedExpanded) items(completed, key = { it.id }) { item -> TodoCard(item, state.mutating, { onToggle(item) }, { editing = item; editor = true }, { delivery = item }, { onOpenThread(item) }, canDispatch && state.online && state.pendingCount == 0 && !state.syncing, onUpdateDetails, onOpenAttachment) }
            }
        }
        FloatingActionButton(onClick = { editing = null; editor = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("new_todo")) { Text("＋", style = MaterialTheme.typography.headlineSmall) }
    }
    if (editor) TodoDetailsEditor(editing, state.mutating, projects, readDirectory, onDismiss = { editor = false }, onSave = { title, notes, details ->
        val finished = { editor = false; search = "" }
        if (onSaveDetails != null) onSaveDetails(editing, title, notes, details, finished) else onSave(editing, title, notes, finished)
    }, onDelete = { editing?.let { item -> onDelete(item) { editor = false } } })
    delivery?.let { item -> DispatchSheet(item, projects, canDispatch && state.online && state.pendingCount == 0 && !state.syncing, loadThreads, { delivery = null }) { project, thread -> onDispatch(item, project, thread) { delivery = null } } }
    if (discardConfirm) AlertDialog(onDismissRequest = { discardConfirm = false }, title = { Text("使用节点上的待办？") },
        text = { Text("成功连接后，会放弃这台手机尚未同步的待办修改，并读取节点版本。已提交给 Codex 的任务不受影响。") },
        confirmButton = { TextButton(onClick = { discardConfirm = false; onDiscardLocal?.invoke() }) { Text("放弃本机更改") } },
        dismissButton = { TextButton(onClick = { discardConfirm = false }) { Text("取消") } })
}

@Composable
private fun TodoCard(item: Todo, busy: Boolean, onToggle: () -> Unit, onEdit: () -> Unit, onDispatch: () -> Unit, onOpen: () -> Unit, canDispatch: Boolean,
    onUpdateDetails: ((Todo, JSONObject) -> Unit)?, onOpenAttachment: ((String) -> Unit)?) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(item.completed, { onToggle() }, enabled = !busy, modifier = Modifier.testTag("todo_toggle_${item.id}"))
                TextButton(onEdit, modifier = Modifier.weight(1f)) { Text(item.title, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium, textDecoration = if (item.completed) TextDecoration.LineThrough else null) }
            }
            if (item.notes.isNotBlank()) Text(item.notes, Modifier.padding(start = 48.dp, end = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val dueDate = item.details.string("dueDate")
            if (dueDate.isNotBlank()) Text("截止 $dueDate" + repeatRuleLabel(item.details.string("repeatRule")).takeIf { it != "不重复" }?.let { " · $it" }.orEmpty(),
                Modifier.padding(start = 48.dp, top = 4.dp), style = MaterialTheme.typography.labelSmall)
            val subtasks = item.details.objects("subtasks")
            subtasks.take(4).forEachIndexed { index, subtask ->
                Row(Modifier.padding(start = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(subtask.optBoolean("completed"), { checked ->
                        val changed = subtasks.mapIndexed { at, value -> JSONObject(value.toString()).apply { if (at == index) put("completed", checked) } }
                        onUpdateDetails?.invoke(item, JSONObject(item.details.toString()).put("subtasks", JSONArray(changed)))
                    }, enabled = !busy && !item.completed && onUpdateDetails != null, modifier = Modifier.testTag("subtask_${subtask.string("id")}"))
                    Text(subtask.string("title"), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (subtasks.size > 4) Text("${subtasks.count { it.optBoolean("completed") }}/${subtasks.size} 个子任务完成", Modifier.padding(start = 48.dp), style = MaterialTheme.typography.labelSmall)
            item.details.objects("attachments").forEach { attachment ->
                TextButton(onClick = { onOpenAttachment?.invoke(attachment.string("path")) }, enabled = onOpenAttachment != null, modifier = Modifier.padding(start = 36.dp)) {
                    Text("附件 · ${attachment.string("name")}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (item.threadId.isNotBlank()) TextButton(onOpen, modifier = Modifier.testTag("view_todo_thread_${item.id}")) { Text("查看会话") }
                if (!item.completed && item.threadId.isBlank()) TextButton(onDispatch, enabled = canDispatch, modifier = Modifier.testTag("dispatch_todo_${item.id}")) { Text("交给 Codex") }
            }
        }
    }
}

@Composable
private fun DispatchSheet(item: Todo, projects: List<ProjectSummary>, enabled: Boolean, loadThreads: suspend (String) -> List<ThreadSummary>, onDismiss: () -> Unit, onDispatch: (String, String?) -> Unit) {
    var selectedProject by remember(item.id) { mutableStateOf(projects.firstOrNull { it.id == item.projectId } ?: projects.firstOrNull()) }
    var selectedThread by remember { mutableStateOf<String?>(null) }
    var threads by remember { mutableStateOf<List<ThreadSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedProject?.id) {
        selectedThread = null; threads = emptyList(); error = null
        selectedProject?.let { project -> loading = true; try { threads = loadThreads(project.id) } catch (e: Exception) { error = "无法读取会话，请重选项目后重试" }; loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("交给 Codex") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text("项目", style = MaterialTheme.typography.labelMedium)
            var projectMenu by remember { mutableStateOf(false) }
            Box { OutlinedButton(onClick = { projectMenu = true }, modifier = Modifier.fillMaxWidth().testTag("dispatch_project")) { Text(selectedProject?.name ?: "请选择项目") }
                DropdownMenu(projectMenu, { projectMenu = false }, modifier = Modifier.testTag("dispatch_project_menu")) { projects.forEach { project -> DropdownMenuItem(text = { Text(project.name) }, onClick = { selectedProject = project; projectMenu = false }) } }
            }
            Text("会话", style = MaterialTheme.typography.labelMedium)
            var threadMenu by remember { mutableStateOf(false) }
            Box { OutlinedButton(onClick = { threadMenu = true }, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("dispatch_thread")) { Text(threads.firstOrNull { it.id == selectedThread }?.name ?: "新建会话") }
                DropdownMenu(threadMenu, { threadMenu = false }) {
                    DropdownMenuItem(text = { Text("新建会话") }, onClick = { selectedThread = null; threadMenu = false })
                    threads.forEach { thread -> DropdownMenuItem(text = { Text(thread.name) }, onClick = { selectedThread = thread.id; threadMenu = false }) }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("提交待办标题和备注，并保留会话关联。待办完成由你确认。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button(onClick = { selectedProject?.let { onDispatch(it.id, selectedThread) } }, enabled = enabled && selectedProject != null && !loading && error == null,
        modifier = Modifier.testTag("confirm_dispatch")) { Text("开始执行") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
