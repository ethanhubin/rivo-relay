package dev.rivo.relay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rivo.relay.data.*
import dev.rivo.relay.codex.BatchSubmission
import dev.rivo.relay.codex.PendingSubmission
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.codex.pinnedThreadsToObserve
import dev.rivo.relay.ui.login.LoginScreen
import dev.rivo.relay.ui.projects.*
import dev.rivo.relay.ui.sessions.SessionsScreen
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.todos.TodosScreen
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject

@Composable
fun RelayApp(vm: AppViewModel = viewModel()) {
    val app by vm.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            val node = app.node
            if (node == null) LoginScreen(app.pairing, app.error, vm::pair, vm::setError, app.savedNodes, vm::selectNode)
            else key(node) { ConnectedApp(node, app.error, vm::setError, vm::disconnect) }
        }
    }
}

@Composable
private fun ConnectedApp(node: NodeRuntime, error: String?, setError: (String?) -> Unit, disconnect: () -> Unit) {
    var route by rememberSaveable { mutableStateOf(node.conversations.getMetadata("lastRoute")?.takeIf { it in setOf("projects", "sessions", "chat", "todos") } ?: "projects") }
    var threadId by rememberSaveable { mutableStateOf(node.conversations.getMetadata("lastThreadId")) }
    var chatParent by rememberSaveable { mutableStateOf("sessions") }
    var connectionOpen by remember { mutableStateOf(false) }
    var addProject by remember { mutableStateOf(false) }
    var newConversation by remember { mutableStateOf(false) }
    var todoAttachment by remember { mutableStateOf<String?>(null) }
    var endingPending by remember { mutableStateOf<PendingSubmission?>(null) }
    val catalog by node.catalog.state.collectAsStateWithLifecycle()
    val session by node.session.state.collectAsStateWithLifecycle()
    val todos by node.todos.state.collectAsStateWithLifecycle()
    val submission by node.submissions.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val writable = !submission.busy && submission.pending == null
    val projectsWritable = writable && catalog.projectsFresh
    val threadsWritable = writable && catalog.threadsFresh
    // Use only the pinned IDs as the effect key: periodic timestamp refreshes retain tracker baselines.
    val pinnedThreads = pinnedThreadsToObserve(catalog)
    LaunchedEffect(pinnedThreads) { pinnedThreads.forEach { node.notifications.watch(it) } }
    val displayError = error ?: if (route == "chat" && session.id == threadId) session.readError ?: session.historyError else null
    LaunchedEffect(submission.notice) {
        if (submission.notice != null && submission.pending == null) {
            node.catalog.refreshProjects(); node.catalog.refreshThreads(); node.todos.refresh(); node.session.refresh()
        }
    }
    fun openChat(id: String, parent: String) { node.conversations.putMetadata("lastThreadId", id); threadId = id; chatParent = parent; route = "chat"; setError(null) }
    fun back() { setError(null); route = if (route == "chat") chatParent else "projects"; if (route == "sessions") node.catalog.refreshThreads(); if (route == "todos") node.todos.refresh() }
    BackHandler(route in setOf("chat", "sessions"), ::back)
    val todoNavigation by node.todoNavigation.collectAsStateWithLifecycle()
    LaunchedEffect(todoNavigation) { if (todoNavigation) { route = "todos"; node.todos.refresh(); node.consumeTodoNavigation() } }
    val notificationThread by node.navigation.collectAsStateWithLifecycle()
    LaunchedEffect(notificationThread) { notificationThread?.let { openChat(it, "projects"); node.consumeNavigation() } }
    LaunchedEffect(route) { node.conversations.putMetadata("lastRoute", route); node.catalog.refreshUsage() }
    LaunchedEffect(session.snapshot, session.stale) {
        if (route == "chat" && !session.stale && session.snapshot?.id == threadId) threadId?.let { node.catalog.markRead(it, session.snapshot?.updatedAt ?: 0) }
    }

    LaunchedEffect(route, catalog.selectedProject?.id, owner) {
        if (route == "sessions" && catalog.selectedProject != null) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { node.catalog.refreshThreads(); kotlinx.coroutines.delay(15_000) }
        }
    }
    LaunchedEffect(route, owner) {
        if (route == "todos") owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { node.todos.observe() }
    }
    LaunchedEffect(route, owner) {
        if (route == "projects") owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            node.catalog.observeProjects()
        }
    }
    LaunchedEffect(route, threadId, owner) {
        val id = threadId
        if (route == "chat" && id != null) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            node.session.open(id)
            try { awaitCancellation() } finally { node.session.pause() }
        } else node.session.close()
    }
    Column(Modifier.fillMaxSize()) {
        displayError?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag("app_error")) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                    Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { if (error != null) setError(null) else node.session.dismissReadError() }) { Text("关闭") }
                }
            }
        }
        submission.pending?.let { pending ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("${pending.label}结果待核对，不会重复发送", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(node.submissions::reconcile, enabled = !submission.busy, modifier = Modifier.testTag("reconcile_submission")) { Text("核对结果") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        pending.threadId?.let { id -> TextButton(onClick = { openChat(id, if (route == "chat") chatParent else if (route == "todos") "todos" else "sessions") }) { Text("查看原会话") } }
                        TextButton(onClick = { endingPending = pending }, enabled = !submission.busy, modifier = Modifier.testTag("finish_reconciliation")) { Text("结束核对") }
                    }
                }
            }
        }
        if (submission.pending == null) submission.notice?.let { notice ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = node.submissions::clearNotice) { Text("关闭") }
            }
        }
        if ((route == "projects" && !catalog.projectsFresh && catalog.projects.isNotEmpty()) ||
            (route == "sessions" && !catalog.threadsFresh && catalog.threads.isNotEmpty())) {
            Text("内容尚未同步，连接节点后可修改", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("catalog_cached_notice"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.weight(1f)) {
            when (route) {
                "projects" -> ProjectsScreen(catalog.projects, node.connection.name, catalog.loading, { project -> node.catalog.project(project); route = "sessions"; setError(null) },
                    { connectionOpen = true }, { node.catalog.refreshProjects() }, { if (node.catalog.state.value.projectsFresh) addProject = true },
                    usage = catalog.usage, usageLoading = catalog.usageLoading, usageError = catalog.usageError,
                    onRefreshUsage = { node.catalog.refreshUsage(true) }, canAdd = projectsWritable)
                "sessions" -> SessionsScreen(catalog.selectedProject?.name ?: "会话", catalog.threads, catalog.loading, threadsWritable,
                    ::back, { openChat(it.id, "sessions") }, { if (node.catalog.state.value.threadsFresh) newConversation = true }, node.catalog::refreshThreads,
                    onPin = { thread, pinned ->
                        if (node.catalog.state.value.threadsFresh) node.submissions.submit("/v1/threads/${encoded(thread.id)}/pin", JSONObject().put("pinned", pinned), if (pinned) "置顶会话" else "取消置顶") {
                            if (pinned) node.notifications.watch(thread.id, requestPermission = true)
                            node.catalog.refreshThreads()
                        }
                    }, archived = catalog.archived, onArchivedChanged = node.catalog::setArchived,
                    onBatchPin = { threads, pinned ->
                        if (node.catalog.state.value.threadsFresh) node.submissions.submitBatch(threads.map { BatchSubmission("/v1/threads/${encoded(it.id)}/pin", JSONObject().put("pinned", pinned), "批量置顶") }) {
                            if (pinned) threads.forEach { node.notifications.watch(it.id, requestPermission = true) }
                            node.catalog.refreshThreads()
                        }
                    }, onArchive = { threads, archived ->
                        if (node.catalog.state.value.threadsFresh) node.submissions.submitBatch(threads.map { BatchSubmission("/v1/threads/${encoded(it.id)}/archive", JSONObject().put("archived", archived), if (archived) "归档会话" else "恢复会话") }) { node.catalog.refreshThreads() }
                    }, unreadThreadIds = catalog.unreadThreadIds, usage = catalog.usage, usageLoading = catalog.usageLoading,
                    usageError = catalog.usageError, onRefreshUsage = { node.catalog.refreshUsage(true) }, projectKey = catalog.selectedProject?.id.orEmpty())
                "chat" -> ChatScreen(session.takeIf { it.id == threadId } ?: SessionState(id = threadId, loading = true, stale = true), submission.busy, submission.pending != null, catalog.models, ::back,
                    onSend = { _, _, _, _ -> }, onSendWithMode = { input, model, effort, mode, accepted ->
                        val current = session.snapshot
                        val id = threadId
                        if (current != null && id != null && session.id == id && current.id == id) {
                            val queued = current.readOnly || current.activeTurnId.isNotBlank() || current.requests.isNotEmpty() || current.queue.isNotEmpty()
                            val body = JSONObject().put("input", input)
                            if (queued) body.put("operation", "enqueue")
                            model?.let { body.put("model", it) }; effort?.let { body.put("effort", it) }; mode?.let { body.put("mode", it) }
                            setError(null)
                            node.submissions.submit("/v1/threads/${encoded(id)}/" + if (queued) "queue" else "turns", body, if (queued) "加入队列" else "发送任务") { receipt ->
                                node.notifications.watch(id, requestPermission = true, turnId = receipt.string("turnId"))
                                if (threadId == id) { accepted(); node.session.refresh() }
                            }
                        }
                    }, onStop = {
                        session.snapshot?.takeIf { !it.readOnly && it.id == threadId && session.id == threadId }?.let { snapshot ->
                            node.submissions.submit("/v1/threads/${encoded(snapshot.id)}/interrupt", JSONObject().put("turnId", snapshot.activeTurnId), "停止任务") { node.session.refresh() }
                        }
                    }, onRespond = { request, response ->
                        if (session.snapshot?.readOnly != true && session.id == threadId && session.snapshot?.id == threadId && session.snapshot?.requests?.any { it.key == request.key } == true) {
                            node.submissions.submit("/v1/requests/respond", JSONObject().put("key", request.key).put("response", response), "回答 Codex") { node.session.refresh() }
                        }
                    }, onRefresh = node.session::refresh, onLoadModels = { node.catalog.loadModels() }, onEarlier = node.session::loadEarlier,
                    readFile = { path -> node.api.call("/v1/files?path=" + encoded(path)) },
                    readDirectory = { path -> node.api.call("/v1/directories?path=" + encoded(path)) },
                    uploadFile = { id, name, file -> node.api.uploadFile(id, name, file) },
                    downloadFile = { path, output, progress -> node.api.downloadFile(path, output, progress) },
                    draftStore = node.conversations, onPersistDraft = node::persistDraft, usage = catalog.usage, onFillHistory = node.session::ensureVisibleHistory,
                    readTool = { path -> node.api.call(path) }, submitTool = { path, body, accepted ->
                        val targetThreadId = threadId
                        node.submissions.submit(path, body, "更新会话") { receipt ->
                            targetThreadId?.let { node.notifications.watch(it, requestPermission = true, turnId = receipt.string("turnId")) }
                            if (threadId == targetThreadId && route == "chat") { accepted(); node.session.refresh() }
                            node.catalog.refreshThreads()
                        }
                    }, pinned = catalog.threads.firstOrNull { it.id == threadId }?.pinned == true,
                    canManage = !session.stale && session.id == threadId && session.snapshot?.id == threadId && session.snapshot?.readOnly == false && !submission.busy && submission.pending == null,
                    onPin = { pinned ->
                        val current = node.session.state.value
                        val id = threadId
                        if (id != null && !current.stale && current.id == id && current.snapshot?.id == id && current.snapshot?.readOnly == false)
                            node.submissions.submit("/v1/threads/${encoded(id)}/pin", JSONObject().put("pinned", pinned), if (pinned) "置顶会话" else "取消置顶") {
                                if (pinned) node.notifications.watch(id, requestPermission = true)
                                node.catalog.refreshThreads()
                            }
                    }, onArchive = {
                        val current = node.session.state.value
                        val id = threadId
                        val snapshot = current.snapshot
                        if (id != null && !current.stale && current.id == id && snapshot?.id == id && !snapshot.readOnly && snapshot.activeTurnId.isBlank() && snapshot.queue.isEmpty() && snapshot.requests.isEmpty())
                            node.submissions.submit("/v1/threads/${encoded(id)}/archive", JSONObject().put("archived", true), "归档会话") {
                                node.catalog.refreshThreads()
                                if (route == "chat" && threadId == id) back()
                            }
                    }, onQueue = { body, accepted ->
                        val id = threadId
                        if (id != null && session.id == id) node.submissions.submit("/v1/threads/${encoded(id)}/queue", body, "更新执行队列") { accepted(); node.session.refresh() }
                    })
                "todos" -> TodosScreen(todos, catalog.projects, projectsWritable, node.catalog::threadsFor,
                    node.todos::save, node.todos::toggle, node.todos::delete,
                    onDispatch = dispatch@{ todo, projectId, selectedThread, accepted ->
                        if (!node.catalog.state.value.projectsFresh) return@dispatch
                        val body = JSONObject().put("projectId", projectId); selectedThread?.let { body.put("threadId", it) }
                        node.todos.prepareDispatch(todo.id) { fresh ->
                            node.submissions.submit("/v1/todos/${encoded(fresh.id)}/dispatch", body, "交付待办") { receipt ->
                                accepted(); node.todos.refresh(); val id = receipt.string("threadId")
                                if (id.isNotBlank()) { node.notifications.watch(id, requestPermission = true, turnId = receipt.string("turnId")); openChat(id, "todos") }
                            }
                        }
                    }, onOpenThread = { openChat(it.threadId, "todos") }, onRefresh = { node.todos.refresh(); node.catalog.refreshProjects() },
                    onSaveDetails = node.todos::saveDetails, onUpdateDetails = node.todos::updateDetails,
                    onSynchronize = node.todos::synchronizeNow, onDiscardLocal = node.todos::discardLocalChanges,
                    readDirectory = { path -> node.api.call("/v1/directories?path=" + encoded(path)) }, onOpenAttachment = { todoAttachment = it })
            }
        }
        if (route == "projects" || route == "todos") NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            NavigationBarItem(selected = route != "todos", onClick = { route = "projects"; setError(null); node.catalog.refreshProjects() },
                icon = { Text("⌘") }, label = { Text("Codex") }, modifier = Modifier.testTag("nav_codex"))
            NavigationBarItem(selected = route == "todos", onClick = { route = "todos"; setError(null); node.todos.refresh(); node.catalog.refreshProjects() },
                icon = { Text("✓") }, label = { Text("待办") }, modifier = Modifier.testTag("nav_todos"))
        }
    }
    endingPending?.let { pending -> AlertDialog(onDismissRequest = { endingPending = null }, title = { Text("结束核对？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("原操作可能仍在执行；请先检查原会话。此操作只解除手机等待，不取消或重新发送原任务，服务记录保留。")
            Text("提交 ID：${pending.id}", style = MaterialTheme.typography.labelSmall)
        } }, confirmButton = { TextButton(onClick = { node.submissions.finishReconciliation(pending.id); endingPending = null }, enabled = !submission.busy && submission.pending?.id == pending.id,
            modifier = Modifier.testTag("confirm_finish_reconciliation")) { Text("结束手机等待") } }, dismissButton = { TextButton(onClick = { endingPending = null }) { Text("继续核对") } }) }
    if (connectionOpen) AlertDialog(onDismissRequest = { connectionOpen = false }, title = { Text(node.connection.name) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(node.connection.base); Text("连接凭据已加密保存在此设备。移除本机登录后，可重新扫码连接。", style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { TextButton(onClick = { connectionOpen = false }) { Text("完成") } }, dismissButton = { TextButton(onClick = disconnect) { Text("切换节点") } })
    todoAttachment?.let { path -> dev.rivo.relay.ui.chat.FilePreviewDialog(path,
        { p -> node.api.call("/v1/files?path=" + encoded(p)) }, { p, output, progress -> node.api.downloadFile(p, output, progress) }) { todoAttachment = null } }
    if (addProject) ProjectDirectoryDialog(
        readDirectory = { path -> node.api.call("/v1/project-directories?path=" + encoded(path)) }, writable = projectsWritable,
        submissionError = error ?: submission.notice, nodeName = node.connection.name, busy = submission.busy,
        onDismiss = { addProject = false }, onAdd = { path, folderName ->
            val body = JSONObject().put("path", path); folderName?.let { body.put("folderName", it) }
            if (node.catalog.state.value.projectsFresh) node.submissions.submit("/v1/projects", body, "添加项目") { addProject = false; node.catalog.refreshProjects() }
        })
    if (newConversation) catalog.selectedProject?.let { project ->
        NewConversationDialog(project, { node.api.call("/v1/projects/${encoded(project.id)}/git") }, threadsWritable,
            submissionError = error ?: submission.notice, busy = submission.busy,
            onCreate = { ref ->
                val body = JSONObject().put("projectId", project.id); ref?.let { body.put("worktreeRef", it) }
                if (node.catalog.state.value.threadsFresh) node.submissions.submit("/v1/threads", body, "新建会话") { receipt ->
                    newConversation = false
                    val id = receipt.string("threadId"); if (id.isNotBlank()) openChat(id, "sessions") else node.catalog.refreshThreads()
                }
            }, onDismiss = { newConversation = false })
    }
}
