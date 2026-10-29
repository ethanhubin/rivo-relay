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

@Composable
fun TodosScreen(state: TodosState, projects: List<ProjectSummary>, canDispatch: Boolean,
    loadThreads: suspend (String) -> List<ThreadSummary>, onSave: (Todo?, String, String, () -> Unit) -> Unit,
    onToggle: (Todo) -> Unit, onDelete: (Todo, () -> Unit) -> Unit, onDispatch: (Todo, String, String?, () -> Unit) -> Unit,
    onOpenThread: (Todo) -> Unit, onRefresh: () -> Unit) {
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Todo?>(null) }
    var delivery by remember { mutableStateOf<Todo?>(null) }
    var completedExpanded by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Box(Modifier.fillMaxSize().testTag("todos_page")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("待办", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onRefresh, enabled = !state.loading) { Text("刷新") }
            }
            OutlinedTextField(search, { search = it }, singleLine = true, placeholder = { Text("搜索待办") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("todo_search"), shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }))
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            val shown = state.items.filter { search.isBlank() || it.title.contains(search, true) || it.notes.contains(search, true) }
            LazyColumn(Modifier.weight(1f).testTag("todo_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (shown.isEmpty() && !state.loading) item { Text(if (search.isBlank()) "还没有待办，点击＋创建" else "没有匹配的待办", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(shown.filter { !it.completed }, key = { it.id }) { item -> TodoCard(item, state.mutating, { onToggle(item) }, { editing = item; editor = true }, { delivery = item }, { onOpenThread(item) }, canDispatch) }
                val completed = shown.filter { it.completed }
                if (completed.isNotEmpty()) item { TextButton(onClick = { completedExpanded = !completedExpanded }) { Text("已完成 ${completed.size} " + if (completedExpanded) "▴" else "▾") } }
                if (completedExpanded) items(completed, key = { it.id }) { item -> TodoCard(item, state.mutating, { onToggle(item) }, { editing = item; editor = true }, { delivery = item }, { onOpenThread(item) }, canDispatch) }
            }
        }
        FloatingActionButton(onClick = { editing = null; editor = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("new_todo")) { Text("＋", style = MaterialTheme.typography.headlineSmall) }
    }
    if (editor) TodoEditor(editing, state.mutating, onDismiss = { editor = false }, onSave = { title, notes -> onSave(editing, title, notes) { editor = false; search = "" } }, onDelete = { editing?.let { item -> onDelete(item) { editor = false } } })
    delivery?.let { item -> DispatchSheet(item, projects, canDispatch, loadThreads, { delivery = null }) { project, thread -> onDispatch(item, project, thread) { delivery = null } } }
}

@Composable
private fun TodoCard(item: Todo, busy: Boolean, onToggle: () -> Unit, onEdit: () -> Unit, onDispatch: () -> Unit, onOpen: () -> Unit, canDispatch: Boolean) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(item.completed, { onToggle() }, enabled = !busy, modifier = Modifier.testTag("todo_toggle_${item.id}"))
                TextButton(onEdit, modifier = Modifier.weight(1f)) { Text(item.title, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium, textDecoration = if (item.completed) TextDecoration.LineThrough else null) }
            }
            if (item.notes.isNotBlank()) Text(item.notes, Modifier.padding(start = 48.dp, end = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (item.threadId.isNotBlank()) TextButton(onOpen, modifier = Modifier.testTag("view_todo_thread_${item.id}")) { Text("查看会话") }
                if (!item.completed && item.threadId.isBlank()) TextButton(onDispatch, enabled = canDispatch, modifier = Modifier.testTag("dispatch_todo_${item.id}")) { Text("交给 Codex") }
            }
        }
    }
}

@Composable
private fun TodoEditor(item: Todo?, busy: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onDelete: () -> Unit) {
    var title by rememberSaveable(item?.id) { mutableStateOf(item?.title.orEmpty()) }
    var notes by rememberSaveable(item?.id) { mutableStateOf(item?.notes.orEmpty()) }
    var deleteConfirm by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (item == null) "新建待办" else "编辑待办") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("待办标题") }, modifier = Modifier.fillMaxWidth().testTag("todo_title"))
            OutlinedTextField(notes, { notes = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth().testTag("todo_notes"), minLines = 3)
            if (item != null) TextButton(onClick = { deleteConfirm = true }, enabled = !busy) { Text("删除待办", color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { Button(onClick = { onSave(title, notes) }, enabled = title.isNotBlank() && !busy, modifier = Modifier.testTag("save_todo")) { Text("保存") } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("取消") } })
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("删除这条待办？") }, text = { Text("待办会被删除，已关联的 Codex 会话仍会保留。") },
        confirmButton = { Button(onClick = { deleteConfirm = false; onDelete() }, enabled = !busy) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("取消") } })
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
