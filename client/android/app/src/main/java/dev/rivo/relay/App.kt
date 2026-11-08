package dev.rivo.relay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
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
import dev.rivo.relay.codex.control
import dev.rivo.relay.codex.catalogCacheNoticeVisible
import dev.rivo.relay.codex.canCompose
import dev.rivo.relay.codex.pinnedThreadsToObserve
import dev.rivo.relay.ui.login.LoginScreen
import dev.rivo.relay.ui.projects.*
import dev.rivo.relay.ui.sessions.SessionsScreen
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.todos.TodosScreen
import dev.rivo.relay.ui.todos.stageTodoAttachments
import dev.rivo.relay.ui.settings.SettingsScreen
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.json.JSONObject

private val pendingProjectSaver = Saver<ProjectSummary?, ArrayList<String>>(
    save = { project -> project?.let { arrayListOf(it.id, it.name, it.path, it.threadCount.toString(), it.outstandingWorkCount.toString()) } ?: arrayListOf() },
    restore = { values -> if (values.size == 5) ProjectSummary(values[0], values[1], values[2], values[3].toInt(), values[4].toInt()) else null })

@Composable
fun RelayApp(vm: AppViewModel = viewModel()) {
    val app by vm.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            val node = app.node
            if (node == null) {
                LoginScreen(app.pairing || app.reconnecting, app.error, vm::pair, vm::setError)
            }
            else key(node) { ConnectedApp(node, app.error, vm::setError, app.pairing, vm::pair, vm::retryConnection, app.savedNodes, vm::selectNode, vm::settingsConnection, vm::setNodeConnectionMode) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectedApp(node: NodeRuntime, error: String?, setError: (String?) -> Unit, pairing: Boolean, pair: (String, String) -> Unit, retryConnection: () -> Unit,
    savedNodes: List<dev.rivo.relay.connection.SavedNode>, selectNode: (String) -> Unit, settingsConnection: (String) -> dev.rivo.relay.connection.NodeConnection?,
    setNodeConnectionMode: (String, dev.rivo.relay.connection.ConnectionMode) -> dev.rivo.relay.connection.NodeConnection?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var route by rememberSaveable { mutableStateOf(node.conversations.getMetadata("lastRoute")?.let { if (it == "new") "sessions" else it }?.takeIf { it in setOf("projects", "sessions", "chat", "todos", "settings") } ?: "projects") }
    var threadId by rememberSaveable { mutableStateOf(node.conversations.getMetadata("lastThreadId")) }
    var chatParent by rememberSaveable { mutableStateOf("sessions") }
    var addProject by remember { mutableStateOf(false) }
    var addNode by rememberSaveable { mutableStateOf(false) }
    var pendingGeneration by rememberSaveable { mutableStateOf(0) }
    var pendingDraft by remember { mutableStateOf(ConversationDraft()) }
    var pendingProject by rememberSaveable(stateSaver = pendingProjectSaver) { mutableStateOf<ProjectSummary?>(null) }
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRef by remember(pendingId) { mutableStateOf(pendingId?.let(node.conversations::pendingWorktreeRef)) }
    var createdThread by remember(pendingId) { mutableStateOf(pendingId?.let(node.conversations::pendingThreadId)) }
    var pendingReady by remember(pendingId) { mutableStateOf(pendingRef == null || createdThread != null) }
    var listedPending by remember { mutableStateOf(emptyList<PendingConversation>()) }
    val pendingRevision by node.pendingRevision.collectAsStateWithLifecycle()
    val handoffScope = rememberCoroutineScope()
    var draftReloadKey by remember(node) { mutableLongStateOf(0L) }
    var defaultSettings by remember(node) { mutableStateOf(node.conversations.defaultSettings()) }
    fun saveDefaults(settings: JSONObject) {
        handoffScope.launch {
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { node.conversations.saveDefaultSettings(settings) }; defaultSettings = node.conversations.defaultSettings() }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; setError(e.message ?: "无法保存默认模型设置") }
        }
    }
    var todoAttachment by remember { mutableStateOf<String?>(null) }
    var endingPending by remember { mutableStateOf<PendingSubmission?>(null) }
    var handoffPrompt by remember { mutableStateOf<Triple<String, String, JSONObject>?>(null) }
    var handoffLoading by remember { mutableStateOf(false) }
    fun executeHandoff(id: String, operation: String, preview: JSONObject, confirmed: Boolean) {
        if (route != "chat" || threadId != id || (operation != "takeover" && node.session.state.value.stale) || node.network.value == null || node.session.state.value.id != id || node.submissions.state.value.pending != null || node.submissions.state.value.busy) return
        node.submissions.submit("/v1/threads/${encoded(id)}/ownership",
            JSONObject().put("operation", operation).put("ticket", preview.getString("ticket")).put("confirmed", confirmed).apply {
                preview.optJSONObject("workspaceRebind")?.string("path")?.takeIf(String::isNotBlank)?.let { put("rebindWorkspace", it) }
            },
            when (operation) { "takeover" -> "强制接管"; "resume" -> "恢复操作"; else -> "主动释放" }) { node.session.refresh() }
    }
    fun requestHandoff(operation: String) {
        val id = threadId ?: return
        val current = node.session.state.value
        if (route != "chat" || current.id != id || (operation != "takeover" && current.stale) || node.network.value == null || handoffLoading || node.submissions.state.value.busy || node.submissions.state.value.pending != null) return
        handoffLoading = true
        handoffScope.launch {
            try {
                val preview = kotlinx.coroutines.withTimeout(30000) { node.api.call("/v1/threads/${encoded(id)}/ownership?operation=$operation") }
                if (route == "chat" && threadId == id && node.session.state.value.id == id && node.network.value != null && (operation == "takeover" || !node.session.state.value.stale)) {
                    if (operation == "takeover" || preview.getBoolean("requiresConfirmation") || preview.optJSONObject("workspaceRebind") != null) handoffPrompt = Triple(id, operation, preview)
                    else executeHandoff(id, operation, preview, false)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                if (route == "chat" && threadId == id) setError(if (e is ApiException) e.message else node.api.describeFailure(e))
            } finally { handoffLoading = false }
        }
    }
    fun openPending(project: ProjectSummary, resumeId: String? = null) {
        val generation = ++pendingGeneration
        val expectedRoute = route
        val expectedProject = node.catalog.state.value.selectedProject?.id
        val expectedThread = threadId
        handoffScope.launch {
            val id = try { resumeId ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { node.conversations.createPending(project.id) } }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; setError(e.message ?: "无法保存待发送会话"); return@launch }
            if (pendingGeneration != generation || route != expectedRoute || threadId != expectedThread ||
                node.catalog.state.value.selectedProject?.id != expectedProject) return@launch
            pendingDraft = ConversationDraft()
            pendingProject = project
            pendingId = id
            pendingRef = node.conversations.pendingWorktreeRef(id)
            pendingReady = pendingRef == null
            createdThread = node.conversations.pendingThreadId(id)
            route = "new"
            setError(null)
        }
    }
    val catalog by node.catalog.state.collectAsStateWithLifecycle()
    val session by node.session.state.collectAsStateWithLifecycle()
    val network by node.network.collectAsStateWithLifecycle()
    val todos by node.todos.state.collectAsStateWithLifecycle()
    val submission by node.submissions.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val writable = network != null && !submission.busy && submission.pending == null && !handoffLoading
    val projectsWritable = writable && catalog.projectsFresh
    val threadsWritable = writable && catalog.threadsFresh
    val visiblePending = listedPending.filter { it.projectId == catalog.selectedProject?.id }
    // Use only the pinned IDs as the effect key: periodic timestamp refreshes retain tracker baselines.
    val pinnedThreads = pinnedThreadsToObserve(catalog)
    LaunchedEffect(node, catalog.selectedProject?.id, catalog.threadsFresh, catalog.archived, pinnedThreads) {
        if (catalog.threadsFresh && !catalog.archived) {
            catalog.selectedProject?.id?.let { node.notifications.updatePinned(it, pinnedThreads) }
        }
    }
    val displayError = error ?: if (route == "chat" && session.id == threadId) session.readError ?: session.historyError else null
    LaunchedEffect(route, threadId) { handoffPrompt = null }
    LaunchedEffect(node, network) {
        if (network != null) node.reconcilePendingReceipts()
    }
    LaunchedEffect(submission.pending?.id, network) {
        if (submission.pending != null && network != null) {
            kotlinx.coroutines.delay(3000)
            while (true) {
                node.submissions.reconcile()
                kotlinx.coroutines.delay(10000)
            }
        }
    }
    LaunchedEffect(submission.resolvedRevision) {
        if (submission.resolvedRevision > 0) {
            node.catalog.refreshProjects(); node.catalog.refreshThreads(); node.todos.refresh(); node.session.refresh()
        }
    }
    fun openChat(id: String, parent: String) { node.conversations.putMetadata("lastThreadId", id); threadId = id; chatParent = parent; route = "chat"; setError(null) }
    fun forwardLink(projectId: String, targetThreadId: String?, source: String, name: String) {
        val project = node.catalog.state.value.projects.firstOrNull { it.id == projectId }
        if (project == null) { setError("项目已变化，请重新选择目标"); return }
        val currentRoute = route
        val sourceThread = threadId
        handoffScope.launch {
            try {
                val newPendingId = if (targetThreadId == null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { node.conversations.createPending(projectId) } else null
                val key = targetThreadId ?: "pending:$newPendingId"
                val old = node.conversations.loadDraft(key)
                val attachment = "链接附件：$name\n$source"
                val merged = old.copy(text = listOf(old.text, attachment).filter(String::isNotBlank).joinToString("\n\n"))
                node.conversations.saveDraftOrdered(key, merged)
                if (route == currentRoute && threadId == sourceThread) {
                    draftReloadKey++
                    node.catalog.project(project)
                    if (targetThreadId == null) openPending(project, newPendingId) else openChat(targetThreadId, "sessions")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (route == currentRoute && threadId == sourceThread) setError(e.message ?: "无法转发链接到会话草稿") }
        }
    }
    fun back() {
        setError(null)
        if (route == "new" && !submission.busy && submission.pending == null) pendingId?.let { id ->
            val captured = node.conversations.captureDraftAsync("pending:$id")
            handoffScope.launch {
                try {
                    val draft = captured.await()
                    if (!draft.hasContent && node.conversations.pendingThreadId(id) == null)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { node.conversations.finishPending(id) }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; setError(e.message ?: "无法清理空草稿") }
            }
        }
        route = if (route == "new") "sessions" else if (route == "chat") chatParent else "projects"
        if (route == "sessions") node.catalog.refreshThreads()
        if (route == "todos") node.todos.refresh()
    }
    BackHandler(route in setOf("chat", "sessions", "new"), ::back)
    val todoNavigation by node.todoNavigation.collectAsStateWithLifecycle()
    LaunchedEffect(todoNavigation) { if (todoNavigation) { route = "todos"; node.todos.refresh(); node.consumeTodoNavigation() } }
    val notificationThread by node.navigation.collectAsStateWithLifecycle()
    LaunchedEffect(notificationThread) { notificationThread?.let { openChat(it, "projects"); node.consumeNavigation() } }
    LaunchedEffect(route) { node.conversations.putMetadata("lastRoute", route); node.catalog.refreshUsage() }
    LaunchedEffect(session.snapshot, session.stale) {
        if (route == "chat" && !session.stale && session.snapshot?.id == threadId) threadId?.let { node.catalog.markRead(it, session.snapshot?.updatedAt ?: 0) }
    }

    LaunchedEffect(route, pendingId, pendingRevision) {
        val id = pendingId
        if (route == "new" && id != null) {
            if (node.conversations.pendingCompleted(id)) {
                node.conversations.completedPendingThreadId(id)?.let { openChat(it, "sessions") }
            } else {
                createdThread = node.conversations.pendingThreadId(id)
                if (createdThread != null) pendingReady = true
            }
        }
    }
    LaunchedEffect(route, catalog.selectedProject?.id, pendingRevision) {
        val projectId = catalog.selectedProject?.id
        if (route == "sessions" && projectId != null) {
            listedPending = node.conversations.listPending(projectId)
        } else listedPending = emptyList()
    }
    LaunchedEffect(route, catalog.selectedProject?.id, owner) {
        if (route == "sessions" && catalog.selectedProject != null) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            node.catalog.observeThreads()
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
    LaunchedEffect(route, threadId, owner, network) {
        val id = threadId
        if (route == "chat" && id != null) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            node.session.open(id, online = network != null)
            try { awaitCancellation() } finally { node.session.pause() }
        } else node.session.close()
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f)) {
            when (route) {
                "settings" -> SettingsScreen(connection = node.connection, api = node.api,
                    resolveConnection = settingsConnection, setNodeConnectionMode = setNodeConnectionMode,
                    savedNodes = savedNodes,
                    onAddNode = { setError(null); addNode = true }, canAddNode = !submission.busy && !handoffLoading)
                "projects" -> ProjectsScreen(catalog.projects, node.connection.name, catalog.loading, { project -> node.catalog.project(project); route = "sessions"; setError(null) },
                    { route = "settings"; setError(null) }, { node.catalog.refreshProjects() }, { if (node.catalog.state.value.projectsFresh) addProject = true },
                    usage = catalog.usage, usageLoading = catalog.usageLoading, usageError = catalog.usageError,
                    onRefreshUsage = { node.catalog.refreshUsage(true) }, canAdd = projectsWritable,
                    savedNodes = savedNodes, currentBase = node.connection.base,
                    pinnedProjectIds = catalog.pinnedProjectIds, onPinProject = node.catalog::setProjectPinned,
                    nodeConnected = when {
                        network == null -> false
                        catalog.projectsFresh -> true
                        catalog.projectsLoading -> null
                        else -> false
                    },
                    // A stalled request must not trap the user on this node; pending IDs are stored per node.
                    onSelectNode = selectNode,
                    onAddNode = { setError(null); addNode = true }, canAddNode = !submission.busy && !handoffLoading)
                "sessions" -> SessionsScreen(catalog.selectedProject?.name ?: "会话", catalog.threads.filterNot { thread -> visiblePending.any { node.conversations.pendingThreadId(it.id) == thread.id } }, catalog.loading, writable && catalog.selectedProject != null,
                    ::back, { openChat(it.id, "sessions") }, { if (writable) node.catalog.state.value.selectedProject?.let { openPending(it) } }, { node.catalog.refreshThreads() },
                    canManageThreads = threadsWritable,
                    onPin = { thread, pinned ->
                        if (node.catalog.state.value.threadsFresh) node.submissions.submit("/v1/threads/${encoded(thread.id)}/pin", JSONObject().put("pinned", pinned), if (pinned) "置顶会话" else "取消置顶") {
                            node.catalog.refreshThreads()
                        }
                    }, archived = catalog.archived, onArchivedChanged = node.catalog::setArchived,
                    onArchive = { threads, archived ->
                        if (node.catalog.state.value.threadsFresh) node.submissions.submitBatch(threads.map { BatchSubmission("/v1/threads/${encoded(it.id)}/archive", JSONObject().put("archived", archived), if (archived) "归档会话" else "恢复会话") }) { node.catalog.refreshThreads() }
                    }, unreadThreadIds = catalog.unreadThreadIds, usage = catalog.usage, usageLoading = catalog.usageLoading,
                    usageError = catalog.usageError, onRefreshUsage = { node.catalog.refreshUsage(true) }, projectKey = catalog.selectedProject?.id.orEmpty(),
                    initialCount = catalog.selectedProject?.threadCount ?: 0,
                    pendingConversations = visiblePending, canDiscardPending = writable,
                    onResumePending = { id -> node.catalog.state.value.selectedProject?.takeIf { selected -> visiblePending.any { it.id == id && it.projectId == selected.id } }?.let { openPending(it, id) } },
                    onDiscardPending = { id ->
                        handoffScope.launch { try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { node.conversations.finishPending(id) }
                            node.session.pendingMessagesFor("pending:$id").forEach { message ->
                                node.session.resolveSubmission(message.submissionId, JSONObject().put("status", "failed"))
                            }
                            listedPending = listedPending.filterNot { it.id == id }
                        }
                        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; setError(e.message ?: "无法删除待发送草稿") } }
                    })
                "new" -> pendingProject?.let { project ->
                    val localId = pendingId ?: return@let
                    val draftKey = "pending:$localId"
                    val generation = pendingGeneration
                    val pendingMessages = (node.session.pendingMessagesFor(draftKey) + createdThread?.let(node.session::pendingMessagesFor).orEmpty()).distinctBy { it.clientId }
                    ChatScreen(SessionState(id = draftKey, pendingMessages = pendingMessages, overlayRevision = session.overlayRevision), submission.busy, submission.pending != null || network == null || handoffLoading, catalog.models,
                        onBack = ::back, onSend = { _, _, _, _ -> }, onStop = {}, onRespond = { _, _ -> },
                        onRefresh = {}, onLoadModels = node.catalog::loadModels, onEarlier = {},
                        readFile = { path -> node.api.readFile(path) },
                        pendingTitle = project.name, pendingReady = pendingReady,
                        pendingGoalApplied = node.conversations.pendingAppliedGoal(localId) != null,
                        pendingFirstTurnSent = node.conversations.pendingGoalContinuation(localId) != null,
                        pendingReceiptUnresolved = node.conversations.pendingHasUnresolvedSubmission(localId),
                        onReconcilePending = node::reconcilePendingReceipts,
                        readGoalCapability = { node.api.call("/v1/goals/capabilities").getBoolean("goalSupported") },
                        onOpenPendingThread = { node.conversations.pendingThreadId(localId)?.let { openChat(it, "sessions") } },
                        pendingPermissionsEditable = createdThread == null && node.conversations.pendingThreadId(localId) == null, draftStore = node.conversations, pendingCwd = project.path,
                        readDirectory = { path -> node.api.call("/v1/directories?path=" + encoded(path)) },
                        uploadFile = { _, name, file -> node.api.uploadProjectFile(project.id, name, file) },
                        defaultSettings = defaultSettings, onSaveDefaults = ::saveDefaults,
                        modelCatalogNotice = catalog.modelsError ?: if (catalog.modelsStale) "模型目录暂未更新，当前使用最近可用选项" else null,
                        onPersistDraft = { target, draft ->
                            if (target == draftKey && !node.conversations.pendingCompleted(localId)) {
                                if (pendingGeneration == generation) pendingDraft = draft
                                node.persistDraft(draftKey, draft)
                                node.conversations.pendingThreadId(localId)?.let { node.persistDraft(it, draft) }
                            }
                        },
                        mediaApi = node.api,
                        cachedFile = { path, progress -> node.api.cachedFile(context.cacheDir, path, progress) },
                        pendingWorkspace = { PendingWorkspaceSelector(project,
                            { node.api.call("/v1/projects/${encoded(project.id)}/git") },
                            writable && createdThread == null && node.conversations.pendingThreadId(localId) == null &&
                                !node.conversations.pendingHasUnresolvedSubmission(localId), initialRef = pendingRef) { ref, ready ->
                                if (createdThread == null) {
                                    pendingRef = ref; pendingReady = ready
                                    try { node.conversations.setPendingWorktreeRef(localId, ref) }
                                    catch (e: Exception) { setError(e.message ?: "无法保存工作区选择") }
                                } else pendingReady = true
                            } },
                        onConfiguredSend = sendPending@{ input, model, effort, mode, serviceTier, permissions, accepted ->
                            if (node.conversations.pendingCompleted(localId)) {
                                node.conversations.completedPendingThreadId(localId)?.let { openChat(it, "sessions") }
                                return@sendPending
                            }
                            if (node.conversations.pendingHasUnresolvedSubmission(localId)) {
                                setError("此新建流程还有未确认的提交，请先核对结果，避免重复发送。")
                                node.reconcilePendingReceipts()
                                return@sendPending
                            }
                            val draftForSubmission = pendingDraft
                            val sendId = java.util.UUID.randomUUID().toString()
                            val body = JSONObject().put("input", input)
                            model?.let { body.put("model", it) }; effort?.let { body.put("effort", it) }; mode?.let { body.put("mode", it) }; serviceTier?.let { body.put("serviceTier", it) }
                            fun clearPreviousCreate() {
                                node.session.pendingMessagesFor(draftKey).filter { it.kind == "create" }.forEach { message ->
                                    node.session.resolveSubmission(message.submissionId, JSONObject().put("status", "failed"))
                                }
                            }
                            val goalAction = pendingGoalAction(permissions.optJSONObject("goal")?.toString(), node.conversations.pendingAppliedGoal(localId))
                            if (goalAction != null) body.put("requireGoalSupport", true)
                            fun finishGoal(id: String) {
                                val continuation = node.conversations.pendingGoalContinuation(localId) ?: return
                                val action = JSONObject(continuation.getString("goalAction"))
                                node.submissions.submit("/v1/threads/${encoded(id)}/goal", action, "启用新会话目标",
                                    beforeSend = { pending ->
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            node.conversations.checkpointPendingSubmission(localId, pending.id, "finishGoal", action.toString())
                                        }
                                    }) {
                                    accepted()
                                    if (route == "new" && pendingGeneration == generation) openChat(id, "sessions")
                                }
                            }
                            fun sendFirst(id: String) {
                                if (node.conversations.pendingGoalContinuation(localId) != null) {
                                    finishGoal(id)
                                    return
                                }
                                node.submissions.submit("/v1/threads/${encoded(id)}/turns", body, "发送任务",
                                    submissionId = sendId,
                                    prepared = { pending ->
                                        clearPreviousCreate()
                                        node.session.beginSubmission(id, pending.id, input)
                                    },
                                    beforeSend = { pending ->
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            node.conversations.checkpointPendingSubmission(localId, pending.id,
                                                if (goalAction == null) "send" else "sendWithGoal", goalAction?.toString())
                                        }
                                        node.conversations.checkpointSentDraft(pending.id, id, draftForSubmission)
                                        node.session.persistSubmission(pending.id)
                                    }) { receipt ->
                                    node.notifications.watch(id, requestPermission = true, turnId = receipt.string("turnId"))
                                    if (goalAction != null) finishGoal(id)
                                    else {
                                        accepted()
                                        if (route == "new" && pendingGeneration == generation) openChat(id, "sessions")
                                    }
                                }
                            }
                            setError(null)
                            val existing = createdThread ?: node.conversations.pendingThreadId(localId)
                            if (existing != null) sendFirst(existing)
                            else {
                                val create = JSONObject(permissions.toString()).apply { remove("goal") }.put("projectId", project.id)
                                pendingRef?.let { create.put("worktreeRef", it) }
                                node.submissions.submit("/v1/threads", create, "新建会话",
                                    prepared = { pending ->
                                        clearPreviousCreate()
                                        node.session.beginSubmission(draftKey, pending.id, input, "create", sendId)
                                    },
                                    beforeSend = { pending ->
                                        node.conversations.saveDraftOrdered(draftKey, node.conversations.captureDraftAsync(draftKey).await())
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { node.conversations.checkpointPendingSubmission(localId, pending.id, "create") }
                                        node.session.persistSubmission(pending.id)
                                    }) { receipt ->
                                    val id = receipt.string("threadId")
                                    if (id.isNotBlank()) {
                                        // The submitted input stays frozen, but late attachment or
                                        // draft updates belong to the new chat's next composer.
                                        node.persistDraft(id, node.conversations.currentDraft(draftKey) ?: draftForSubmission)
                                        if (pendingGeneration == generation) createdThread = id
                                        sendFirst(id)
                                    }
                                    else setError("创建结果缺少会话 ID，请返回会话列表核对")
                                }
                            }
                        })
                }
                "chat" -> ChatScreen(session.takeIf { it.id == threadId }?.let { if (network == null) it.copy(stale = true, controlNotice = "网络不可用，恢复连接并同步后才能操作") else it } ?: SessionState(id = threadId, loading = true, stale = true), submission.busy, submission.pending != null || handoffLoading || network == null, catalog.models, ::back,
                    nodeName = node.connection.name, writerBusy = handoffLoading, onWriterAction = ::requestHandoff,
                    takeoverBlockedReason = when {
                        network == null -> "网络不可用，请恢复节点连接后接管"
                        handoffLoading -> "正在核验接管条件，请稍候"
                        submission.busy -> "正在提交操作，请等待结果后接管"
                        submission.pending != null -> "上一操作结果待核对，请先完成核对再接管"
                        else -> null
                    },
                    defaultSettings = defaultSettings, onSaveDefaults = ::saveDefaults, onForwardLink = ::forwardLink, draftReloadKey = draftReloadKey,
                    modelCatalogNotice = catalog.modelsError ?: if (catalog.modelsStale) "模型目录暂未更新，当前使用最近可用选项" else null,
                    onSend = { _, _, _, _ -> }, onConfiguredSend = { input, model, effort, mode, serviceTier, _, accepted ->
                        val currentState = node.session.state.value
                        val current = currentState.snapshot
                        val id = threadId
                        if (current != null && id != null && currentState.id == id && current.id == id && !currentState.stale &&
                            current.requests.isEmpty() && currentState.canCompose() && writable) {
                            val queued = currentState.control().canOperate && current.shouldQueueInput
                            val body = JSONObject().put("input", input)
                            if (queued) body.put("operation", "enqueue")
                            model?.let { body.put("model", it) }; effort?.let { body.put("effort", it) }; mode?.let { body.put("mode", it) }; serviceTier?.let { body.put("serviceTier", it) }
                            setError(null)
                            val sentDraft = node.conversations.captureDraftAsync(id)
                            node.submissions.submit("/v1/threads/${encoded(id)}/" + if (queued) "queue" else "turns", body, if (queued) "加入队列" else "发送任务",
                                prepared = { pending -> node.session.beginSubmission(id, pending.id, input, if (queued) "queue" else "turn") },
                                beforeSend = { pending ->
                                    node.conversations.checkpointSentDraft(pending.id, id, sentDraft.await())
                                    node.session.persistSubmission(pending.id)
                                }) { receipt ->
                                node.notifications.watch(id, requestPermission = true, turnId = receipt.string("turnId"))
                                if (threadId == id) { accepted(); node.session.refresh() }
                            }
                        }
                    }, onSettingsChange = { settings ->
                        val current = node.session.state.value
                        val id = threadId
                        if (id != null && current.id == id && current.snapshot?.id == id && !current.stale && current.control().canOperate && writable)
                            node.submissions.submit("/v1/threads/${encoded(id)}/settings", settings, "更新会话设置") { if (threadId == id) node.session.refresh() }
                    }, onStop = {
                        node.session.state.value.let { current -> current.snapshot?.takeIf { writable && current.control().canOperate && it.id == threadId && current.id == threadId } }?.let { snapshot ->
                            node.submissions.submit("/v1/threads/${encoded(snapshot.id)}/interrupt", JSONObject().put("turnId", snapshot.activeTurnId), "停止任务") { node.session.refresh() }
                        }
                    }, onRespond = { request, response ->
                        val current = node.session.state.value
                        if (writable && current.control().canOperate && current.id == threadId && current.snapshot?.id == threadId && current.snapshot?.requests?.any { it.key == request.key } == true) {
                            node.submissions.submit("/v1/requests/respond", JSONObject().put("key", request.key).put("response", response), "回答 Codex") { node.session.refresh() }
                        }
                    }, onRefresh = node.session::refresh, onLoadModels = { node.catalog.loadModels() }, onEarlier = node.session::loadEarlier,
                    readFile = { path -> node.api.readFile(path) },
                    readDirectory = { path -> node.api.call("/v1/directories?path=" + encoded(path)) },
                    uploadFile = { id, name, file -> node.api.uploadFile(id, name, file) },
                    downloadFile = { path, output, progress -> node.api.downloadFile(path, output, progress) },
                    mediaApi = node.api,
                    cachedFile = { path, progress -> node.api.cachedFile(context.cacheDir, path, progress) },
                    draftStore = node.conversations, onPersistDraft = node::persistDraft, usage = catalog.usage, onFillHistory = node.session::ensureVisibleHistory,
                    readTool = { path -> node.api.call(path) }, submitTool = submitTool@{ path, body, accepted ->
                        if (!writable || !node.session.state.value.control().canOperate) return@submitTool
                        val targetThreadId = threadId
                        node.submissions.submit(path, body, "更新会话") { receipt ->
                            targetThreadId?.let { node.notifications.watch(it, requestPermission = true, turnId = receipt.string("turnId")) }
                            if (threadId == targetThreadId && route == "chat") { accepted(); node.session.refresh() }
                            node.catalog.refreshThreads()
                        }
                    }, pinned = catalog.threads.firstOrNull { it.id == threadId }?.pinned == true,
                    canManage = !session.stale && session.id == threadId && session.snapshot?.id == threadId && session.control().canOperate && writable,
                    onPin = { pinned ->
                        val current = node.session.state.value
                        val id = threadId
                        if (id != null && !current.stale && current.id == id && current.snapshot?.id == id && writable && current.control().canOperate)
                            node.submissions.submit("/v1/threads/${encoded(id)}/pin", JSONObject().put("pinned", pinned), if (pinned) "置顶会话" else "取消置顶") {
                                node.catalog.refreshThreads()
                            }
                    }, onArchive = {
                        val current = node.session.state.value
                        val id = threadId
                        val snapshot = current.snapshot
                        if (id != null && !current.stale && current.id == id && snapshot?.id == id && writable && current.control().canOperate && snapshot.activeTurnId.isBlank() && snapshot.queue.isEmpty() && snapshot.requests.isEmpty())
                            node.submissions.submit("/v1/threads/${encoded(id)}/archive", JSONObject().put("archived", true), "归档会话") {
                                node.catalog.refreshThreads()
                                if (route == "chat" && threadId == id) back()
                            }
                    }, onGoal = { body ->
                        val id = threadId
                        val current = node.session.state.value
                        if (id != null && current.id == id && !current.stale && writable && current.control().canOperate)
                            node.submissions.submit("/v1/threads/${encoded(id)}/goal", body, "更新目标") {
                                if (route == "chat" && threadId == id) node.session.refresh()
                            }
                    }, onQueue = { body, accepted ->
                        val id = threadId
                        val current = node.session.state.value
                        if (id != null && current.id == id && !current.stale && (current.control().canOperate || body.string("operation") == "dismiss") && writable) node.submissions.submit("/v1/threads/${encoded(id)}/queue", body, "更新执行队列",
                            prepared = { pending ->
                                if (body.string("operation") == "steer") current.snapshot?.queue?.firstOrNull { it.id == body.string("itemId") }?.let { task ->
                                    task.raw.optJSONArray("input")?.let { input -> node.session.beginSubmission(id, pending.id, input, "guidance", task.id) }
                                }
                            }, beforeSend = { pending -> node.session.persistSubmission(pending.id) }) {
                            accepted()
                            if (route == "chat" && threadId == id) node.session.refresh()
                        }
                    })
                "todos" -> TodosScreen(todos, catalog.projects, projectsWritable, node.catalog::threadsFor,
                    node.todos::save, node.todos::toggle, node.todos::delete,
                    onDispatch = dispatch@{ todo, projectId, selectedThread, accepted ->
                        if (!node.catalog.state.value.projectsFresh) return@dispatch
                        val body = JSONObject().put("projectId", projectId); selectedThread?.let { body.put("threadId", it) }
                        node.todos.prepareDispatch(todo.id) { fresh ->
                            node.submissions.submit("/v1/todos/${encoded(fresh.id)}/dispatch", body, "交付待办",
                                prepareBody = { request -> stageTodoAttachments(context, node.api, fresh, request) }) { receipt ->
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
        val cached = catalogCacheNoticeVisible(route, catalog, network != null)
        if (displayError != null || submission.pending != null || submission.notice != null || cached) {
            Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("bottom_notice_panel"),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), tonalElevation = 3.dp, shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    displayError?.let { message ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.testTag("app_error")) {
                            Text("${node.connection.name}：$message", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = retryConnection, modifier = Modifier.testTag("retry_connection")) { Text("重连") }
                        }
                    }
                    submission.pending?.let { pending ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("${node.connection.name}：正在确认“${pending.label}”是否送达，暂时无法提交新操作。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { endingPending = pending }, enabled = !submission.busy,
                                modifier = Modifier.testTag("finish_reconciliation")) { Text("处理记录") }
                            pending.threadId?.takeIf { it.isNotBlank() && (route != "chat" || threadId != it) }?.let { id ->
                                TextButton(onClick = { openChat(id, "projects") }) { Text("查看会话") }
                            }
                        }
                    }
                    if (submission.pending == null) submission.notice?.let { notice ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (cached) Text("${node.connection.name}：暂未同步，显示上次内容", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("catalog_cached_notice"))
                    if (displayError != null || submission.pending == null && submission.notice != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                setError(null)
                                node.session.dismissReadError()
                                node.submissions.clearNotice()
                            }, modifier = Modifier.testTag("dismiss_bottom_notice")) { Text("关闭") }
                        }
                    }
                }
            }
        }
        if (route in setOf("projects", "todos", "settings")) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            NavigationBarItem(selected = route == "projects", onClick = { route = "projects"; setError(null); node.catalog.refreshProjects() },
                icon = { Text("⌘") }, label = { Text("Codex") }, modifier = Modifier.testTag("nav_codex"))
            NavigationBarItem(selected = route == "todos", onClick = { route = "todos"; setError(null); node.todos.refresh(); node.catalog.refreshProjects() },
                icon = { Text("✓") }, label = { Text("待办") }, modifier = Modifier.testTag("nav_todos"))
            NavigationBarItem(selected = route == "settings", onClick = { route = "settings"; setError(null) },
                icon = { Text("⚙") }, label = { Text("设置") }, modifier = Modifier.testTag("nav_settings"))
        }
    }
    handoffPrompt?.let { (id, operation, preview) ->
        ModalBottomSheet(onDismissRequest = { handoffPrompt = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
                val rebound = preview.optJSONObject("workspaceRebind")
                Text(if (rebound != null) "确认接管与工作区恢复" else if (operation == "release") "确认释放权限及影响范围" else "确认强制接管及影响范围", style = MaterialTheme.typography.titleLarge)
                Text("节点：${node.connection.name}", style = MaterialTheme.typography.bodyMedium)
                if (rebound != null) Text("原工作树已不存在，将改用 ${rebound.string("path")} 目录。原路径：${rebound.string("missingPath")}",
                    modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.error)
                Text("继续会结束列出的持锁进程及其子进程，停止这些会话的运行任务；不会自动重发。", modifier = Modifier.padding(vertical = 12.dp))
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    val affected = preview.getJSONArray("affectedThreads")
                    for (i in 0 until affected.length()) {
                        val item = affected.getJSONObject(i)
                        Text("• " + item.getString("title") + "\n" + item.getString("id"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { handoffPrompt = null }) { Text("取消") }
                    TextButton(onClick = { handoffPrompt = null; executeHandoff(id, operation, preview, true) },
                        enabled = network != null && !submission.busy && submission.pending == null, modifier = Modifier.testTag("confirm_shared_handoff")) { Text(if (operation == "takeover") "结束进程并接管" else "结束进程并释放") }
                }
            }
        }
    }
    endingPending?.let { pending -> AlertDialog(onDismissRequest = { endingPending = null }, title = { Text("清除手机上的等待记录？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("原操作可能仍在执行；请先检查原会话。此操作只解除手机等待，不取消或重新发送原任务，服务记录保留。")
            Text("节点：${node.connection.name}\n操作：${pending.label}\n提交 ID：${pending.id}", style = MaterialTheme.typography.labelSmall)
        } }, confirmButton = { TextButton(onClick = { node.submissions.finishReconciliation(pending.id); endingPending = null }, enabled = !submission.busy && submission.pending?.id == pending.id,
            modifier = Modifier.testTag("confirm_finish_reconciliation")) { Text("清除等待记录") } }, dismissButton = { TextButton(onClick = { endingPending = null }) { Text("返回") } }) }
    todoAttachment?.let { path -> dev.rivo.relay.ui.chat.FilePreviewDialog(path,
        { p -> node.api.call("/v1/files?path=" + encoded(p)) }, { p, output, progress -> node.api.downloadFile(p, output, progress) }) { todoAttachment = null } }
    if (addNode) androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (!pairing) { addNode = false; setError(null) } },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LoginScreen(pairing, error, pair, setError,
                onBack = { addNode = false; setError(null) })
        }
    }
    if (addProject) ProjectDirectoryDialog(
        readDirectory = { path -> node.api.call("/v1/project-directories?path=" + encoded(path)) }, writable = projectsWritable,
        submissionError = error ?: submission.notice, nodeName = node.connection.name, busy = submission.busy,
        onDismiss = { addProject = false }, onAdd = { path, folderName ->
            val body = JSONObject().put("path", path).put("trustConfirmed", true); folderName?.let { body.put("folderName", it) }
            if (node.catalog.state.value.projectsFresh) node.submissions.submit("/v1/projects", body, "添加项目") { addProject = false; node.catalog.refreshProjects() }
        })
}
