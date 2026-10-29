package dev.rivo.relay.ui.todos

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.projects.ProjectSummary
import dev.rivo.relay.ui.theme.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class SavedTodoNavigation(val existingId: String?, val previousIds: Set<String>, val title: String, val dispatch: Boolean)

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
    var completedExpanded by rememberSaveable { mutableStateOf(true) }
    var search by rememberSaveable { mutableStateOf("") }
    var discardConfirm by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var collapsedIds by remember { mutableStateOf(emptySet<String>()) }
    var scrollToId by remember { mutableStateOf<String?>(null) }
    var savedNavigation by remember { mutableStateOf<SavedTodoNavigation?>(null) }
    val currentItems by rememberUpdatedState(state.items)
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val query = search.trim()
    val shown = state.items.filter { query.isBlank() || it.title.contains(query, true) || it.notes.contains(query, true) ||
        it.details.objects("subtasks").any { task -> task.string("title").contains(query, true) } ||
        it.details.objects("attachments").any { file -> file.string("name").contains(query, true) } }
    val visibleIds = shown.map { it.id }.toSet()
    LaunchedEffect(visibleIds) { selectedIds = selectedIds.intersect(visibleIds) }
    LaunchedEffect(state.items, savedNavigation) {
        val request = savedNavigation ?: return@LaunchedEffect
        val saved = if (request.existingId != null) state.items.firstOrNull { it.id == request.existingId }
            else state.items.firstOrNull { it.id !in request.previousIds && it.title == request.title }
        if (saved != null) {
            if (request.dispatch) delivery = saved else scrollToId = saved.id
            savedNavigation = null
        }
    }
    LaunchedEffect(scrollToId, shown) { val index = shown.filterNot { it.completed }.indexOfFirst { it.id == scrollToId }
        if (index >= 0 && scrollToId != null) { listState.animateScrollToItem(index); scrollToId = null } }
    BackHandler(selectedIds.isNotEmpty()) { deleteConfirm = false; selectedIds = emptySet() }
    fun toggleSelection(id: String) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    fun save(title: String, notes: String, details: JSONObject, startCodex: Boolean) {
        val existing = editing
        val knownIds = currentItems.map { it.id }.toSet()
        val finished = {
            editor = false; search = ""
            savedNavigation = SavedTodoNavigation(existing?.id, knownIds, title.trim(), startCodex)
        }
        if (onSaveDetails != null) onSaveDetails(existing, title, notes, details, finished) else onSave(existing, title, notes, finished)
    }
    Box(Modifier.fillMaxSize().testTag("todos_page")) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = RivoSpacing.Md, vertical = 12.dp)) {
                if (selectedIds.isEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("待办", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    TextButton(onRefresh, enabled = !state.loading) { Text("刷新") }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selectedIds = emptySet() }, modifier = Modifier.size(RivoSize.MinTouchTarget).testTag("todo_selection_close"), contentPadding = PaddingValues(0.dp)) { Text("×", style = MaterialTheme.typography.headlineMedium) }
                        TextButton(onClick = { selectedIds = if (selectedIds.containsAll(visibleIds)) emptySet() else visibleIds }, modifier = Modifier.size(RivoSize.MinTouchTarget).testTag("todo_selection_all").semantics { contentDescription = "全选待办" }, contentPadding = PaddingValues(0.dp)) { Text("☷", style = MaterialTheme.typography.headlineSmall) }
                    }
                    Text("已选择${selectedIds.size}项", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                }
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
            Surface(Modifier.fillMaxWidth().padding(horizontal = RivoSpacing.Md), shape = RoundedCornerShape(RivoRadius.Panel), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().heightIn(min = RivoSize.MinTouchTarget).padding(horizontal = RivoSpacing.Lg), verticalAlignment = Alignment.CenterVertically) {
                    Text("⌕", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(RivoSpacing.Md))
                    BasicTextField(search, { search = it }, modifier = Modifier.weight(1f).testTag("todo_search"), singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }),
                        decorationBox = { inner -> Box { if (search.isEmpty()) Text("搜索待办", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
                    if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("清除") }
                }
            }
            if (state.loading || state.mutating) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            LazyColumn(Modifier.weight(1f).testTag("todo_list"), state = listState,
                contentPadding = PaddingValues(start = RivoSpacing.Md, end = RivoSpacing.Md, top = RivoSpacing.Lg, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(RivoSpacing.Md)) {
                if (shown.isEmpty() && !state.loading) item { Text(if (query.isBlank()) "还没有待办，点击＋创建" else "没有匹配的待办", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                fun androidx.compose.foundation.lazy.LazyListScope.rows(values: List<Todo>) {
                    items(values, key = { it.id }) { item ->
                        TodoCard(item, state.mutating, { if (!item.completed) completedExpanded = true; onToggle(item) }, { editing = item; editor = true },
                            onUpdateDetails, selectedIds.isNotEmpty(), item.id in selectedIds, { toggleSelection(item.id) },
                            item.id !in collapsedIds, { collapsedIds = if (item.id in collapsedIds) collapsedIds - item.id else collapsedIds + item.id }, query,
                            Modifier.animateItem(fadeInSpec = tween(220), placementSpec = tween(420), fadeOutSpec = tween(180)))
                    }
                }
                rows(shown.filterNot { it.completed })
                val completed = shown.filter { it.completed }
                if (completed.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().clickable { completedExpanded = !completedExpanded }.padding(vertical = RivoSpacing.Md).testTag("todo_completed_header"), verticalAlignment = Alignment.CenterVertically) {
                        TodoExpandButton(completedExpanded, true, "todo_completed_expand") { completedExpanded = !completedExpanded }
                        Spacer(Modifier.width(RivoSpacing.Sm))
                        Text("已完成 ${completed.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (completedExpanded) rows(completed)
            }
        }
        if (selectedIds.isNotEmpty()) Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            TextButton(onClick = { deleteConfirm = true }, enabled = !state.mutating, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("todo_selection_delete")) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("🗑", style = MaterialTheme.typography.titleLarge); Text("删除", style = MaterialTheme.typography.labelMedium) }
            }
        } else if (!editor) FloatingActionButton(onClick = { editing = null; editor = true }, shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(62.dp).testTag("new_todo")) { Text("＋", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light) }
    }
    if (editor) TodoDetailsEditor(editing, state.mutating, projects, readDirectory, onDismiss = { editor = false },
        onSave = { title, notes, details -> save(title, notes, details, false) }, onDelete = { editing?.let { item -> onDelete(item) { editor = false } } },
        onStartCodex = if (canDispatch && state.online && state.pendingCount == 0 && !state.syncing && editing?.completed != true && editing?.threadId.orEmpty().isBlank())
            { { title, notes, details -> save(title, notes, details, true) } } else null,
        onOpenAttachment = onOpenAttachment,
        onOpenThread = editing?.takeIf { it.threadId.isNotBlank() }?.let { item -> { editor = false; onOpenThread(item) } })
    delivery?.let { item -> DispatchSheet(item, projects, canDispatch && state.online && state.pendingCount == 0 && !state.syncing, loadThreads, { delivery = null }) { project, thread -> onDispatch(item, project, thread) { delivery = null } } }
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("删除 ${selectedIds.size} 项待办？") },
        text = { Text("所选待办及其清单项目将被永久删除。已关联的 Codex 会话仍会保留。") },
        confirmButton = { Button(onClick = {
            val pending = currentItems.filter { it.id in selectedIds }.toMutableList()
            deleteConfirm = false; selectedIds = emptySet()
            fun next() { if (pending.isNotEmpty()) onDelete(pending.removeAt(0)) { next() } }
            next()
        }, enabled = !state.mutating, modifier = Modifier.testTag("todo_confirm_delete")) { Text("确认删除") } }, dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("取消") } })
    if (discardConfirm) AlertDialog(onDismissRequest = { discardConfirm = false }, title = { Text("使用节点上的待办？") },
        text = { Text("成功连接后，会放弃这台手机尚未同步的待办修改，并读取节点版本。已提交给 Codex 的任务不受影响。") },
        confirmButton = { TextButton(onClick = { discardConfirm = false; onDiscardLocal?.invoke() }) { Text("放弃本机更改") } },
        dismissButton = { TextButton(onClick = { discardConfirm = false }) { Text("取消") } })
}

@Composable
private fun TodoCard(item: Todo, busy: Boolean, onToggle: () -> Unit, onEdit: () -> Unit,
    onUpdateDetails: ((Todo, JSONObject) -> Unit)?, selecting: Boolean, selected: Boolean, onSelect: () -> Unit,
    expanded: Boolean, onToggleExpanded: () -> Unit, query: String, modifier: Modifier = Modifier) {
    val subtasks = item.details.objects("subtasks")
    if (selecting) {
        Surface(onClick = onSelect, enabled = !busy, modifier = modifier.fillMaxWidth().alpha(if (item.completed) .58f else 1f)
            .testTag("todo_content_${item.id}"), shape = RoundedCornerShape(RivoRadius.Panel),
            color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))) {
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = RivoSpacing.Lg, vertical = RivoSpacing.Md), verticalAlignment = Alignment.CenterVertically) {
                Text("☰", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f))
                Spacer(Modifier.width(RivoSpacing.Lg))
                Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (subtasks.isEmpty()) FontWeight.Normal else FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtasks.isNotEmpty()) {
                    Text("${subtasks.count { it.optBoolean("completed") }} / ${subtasks.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(RivoSpacing.Lg))
                }
                Surface(Modifier.size(28.dp), shape = CircleShape, color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    border = BorderStroke(1.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                    if (selected) Box(contentAlignment = Alignment.Center) { Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                }
            }
        }
        return
    }
    val cardAlpha by animateFloatAsState(if (item.completed && !selecting) .58f else 1f, tween(260), label = "todoCardAlpha")
    Surface(shape = RoundedCornerShape(RivoRadius.Card), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().alpha(cardAlpha).animateContentSize().testTag("todo_${item.id}")) {
        Column(Modifier.padding(vertical = RivoSpacing.Sm)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).combinedClickable(enabled = !busy,
                onClick = { if (selecting) onSelect() else onEdit() }, onLongClick = onSelect, onLongClickLabel = "选择待办")
                .testTag("todo_content_${item.id}").padding(horizontal = RivoSpacing.Md, vertical = RivoSpacing.Sm), verticalAlignment = Alignment.CenterVertically) {
                TodoCheckbox(if (selecting) selected else item.completed, enabled = !busy, tag = "todo_toggle_${item.id}") { if (selecting) onSelect() else onToggle() }
                Spacer(Modifier.width(RivoSpacing.Sm))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    TodoAnimatedTitle(item.title, item.completed, subtasks.isNotEmpty())
                    val labels = buildList {
                        val reminder = item.details.optLong("reminderAt")
                        if (reminder > 0) add("◴ " + Instant.ofEpochMilli(reminder).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")))
                        else item.details.string("dueDate").takeIf { it.isNotBlank() }?.let(::add)
                        repeatRuleLabel(item.details.string("repeatRule")).takeIf { it != "不重复" }?.let(::add)
                        val attachments = item.details.objects("attachments").size
                        if (attachments > 0) add("附件 $attachments")
                        if (item.threadId.isNotBlank()) add("Codex")
                        if (item.notes.isNotBlank()) add(item.notes)
                    }
                    if (labels.isNotEmpty()) Text(labels.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (subtasks.isNotEmpty() && !selecting) {
                    Text("${subtasks.count { it.optBoolean("completed") }} / ${subtasks.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TodoExpandButton(expanded, !busy, "todo_expand_${item.id}", onToggleExpanded)
                }
            }
            AnimatedVisibility(subtasks.isNotEmpty() && expanded && !selecting) {
                Column { subtasks.forEachIndexed { index, subtask ->
                    if (query.isBlank() || item.title.contains(query, true) || subtask.string("title").contains(query, true)) Row(
                        Modifier.fillMaxWidth().heightIn(min = RivoSize.ControlHeight).padding(start = 45.dp, end = RivoSpacing.Md), verticalAlignment = Alignment.CenterVertically) {
                        TodoCheckbox(subtask.optBoolean("completed"), enabled = !busy && !item.completed && onUpdateDetails != null, tag = "subtask_${subtask.string("id")}", size = 21.dp) {
                            val changed = subtasks.mapIndexed { at, value -> JSONObject(value.toString()).apply { if (at == index) put("completed", !subtask.optBoolean("completed")) } }
                            onUpdateDetails?.invoke(item, JSONObject(item.details.toString()).put("subtasks", JSONArray(changed)))
                        }
                        Spacer(Modifier.width(RivoSpacing.Sm))
                        Text(subtask.string("title"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textDecoration = if (subtask.optBoolean("completed")) TextDecoration.LineThrough else null)
                    }
                } }
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
        selectedProject?.let { project ->
            loading = true
            try { threads = loadThreads(project.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "无法读取会话，请重选项目后重试" }
            finally { loading = false }
        }
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
