package dev.rivo.relay.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.*
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(state: SessionState, busy: Boolean, blocked: Boolean, models: List<JSONObject>, onBack: () -> Unit,
    onSend: (JSONArray, String?, String?, () -> Unit) -> Unit, onStop: () -> Unit,
    onRespond: (NativeRequest, JSONObject) -> Unit, onRefresh: () -> Unit, onLoadModels: () -> Unit, onEarlier: () -> Unit,
    readFile: suspend (String) -> JSONObject,
    readDirectory: suspend (String) -> JSONObject = { error("节点不支持目录浏览") },
    uploadFile: suspend (String, String, java.io.File) -> JSONObject = { _, _, _ -> error("节点不支持文件上传") },
    downloadFile: suspend (String, java.io.OutputStream, (Long, Long) -> Unit) -> Unit = { _, _, _ -> error("节点不支持文件下载") },
    onQueue: (JSONObject, () -> Unit) -> Unit = { _, _ -> },
    draftStore: ConversationStore? = null, onPersistDraft: (String, ConversationDraft) -> Unit = { _, _ -> },
    onSendWithMode: ((JSONArray, String?, String?, String?, () -> Unit) -> Unit)? = null,
    readTool: suspend (String) -> JSONObject = { error("节点工具不可用") },
    submitTool: (String, JSONObject, () -> Unit) -> Unit = { _, _, _ -> },
    usage: CodexUsage? = null, onFillHistory: () -> Unit = {}, pinned: Boolean = false, canManage: Boolean = false,
    onPin: ((Boolean) -> Unit)? = null, onArchive: (() -> Unit)? = null) {
    val snapshot = state.snapshot
    var editingQueue by remember(state.id) { mutableStateOf<QueueTask?>(null) }
    var removingQueue by remember(state.id) { mutableStateOf<QueueTask?>(null) }
    var draft by rememberSaveable(state.id) { mutableStateOf("") }
    var stopConfirm by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    BackHandler(settingsOpen) { settingsOpen = false }
    var developerOpen by remember(state.id) { mutableStateOf(false) }
    var selectedMode by rememberSaveable(state.id) { mutableStateOf<String?>(null) }
    var draftLoaded by remember(state.id, draftStore) { mutableStateOf(draftStore == null) }
    var selectedModel by rememberSaveable(state.id) { mutableStateOf<String?>(null) }
    var selectedEffort by rememberSaveable(state.id) { mutableStateOf<String?>(null) }
    var images by remember(state.id) { mutableStateOf(emptyList<DraftImage>()) }
    var editingImage by remember(state.id) { mutableStateOf<DraftImage?>(null) }
    var files by remember(state.id) { mutableStateOf(emptyList<JSONObject>()) }
    var preparing by remember { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var workspaceFileDialog by remember { mutableStateOf(false) }
    var filePath by remember { mutableStateOf<String?>(null) }
    var fileAction by remember { mutableStateOf<FileAction?>(null) }
    var inputFocusRequest by remember(state.id) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val currentSessionId by rememberUpdatedState(state.id)
    fun openFile(path: String) { fileAction = null; filePath = path }
    fun addImages(uris: List<android.net.Uri>) {
        val targetId = state.id
        if (preparing || !draftLoaded || busy || blocked) return
        scope.launch {
            preparing = true; attachmentError = null
            val available = (3 - images.size).coerceAtLeast(0)
            try { uris.take(available).forEach { uri ->
                val image = prepareImage(context, uri)
                if (currentSessionId == targetId && images.size < 3) images = images + image
            }; if (uris.size > available && currentSessionId == targetId) attachmentError = "最多添加 3 张图片，其余图片未添加" }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (currentSessionId == targetId) attachmentError = e.message ?: "无法添加图片" }
            finally { preparing = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        if (uris.isNotEmpty()) addImages(uris)
    }
    LaunchedEffect(state.id, draftStore) {
        val id = state.id
        if (id != null && draftStore != null) {
            val saved = draftStore.loadDraft(id)
            draft = saved.text; images = saved.images.map { DraftImage(it.id, it.bytes) }; files = saved.files
            selectedModel = saved.model; selectedEffort = saved.effort; selectedMode = saved.mode
        }
        draftLoaded = true
    }
    val currentDraft = ConversationDraft(draft, images.map { StoredDraftImage(it.id, it.bytes) }, files, selectedModel, selectedEffort, selectedMode)
    val latestDraft by rememberUpdatedState(currentDraft)
    val loadedDraft by rememberUpdatedState(draftLoaded)
    val persistDraft by rememberUpdatedState(onPersistDraft)
    LaunchedEffect(state.id, draftLoaded, draft, images, files, selectedModel, selectedEffort, selectedMode) {
        if (draftLoaded) state.id?.let { onPersistDraft(it, currentDraft) }
    }
    DisposableEffect(state.id) {
        val id = state.id
        onDispose { if (id != null && loadedDraft) persistDraft(id, latestDraft) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && files.size < 16 && !preparing) scope.launch {
            val sessionId = state.id ?: return@launch
            preparing = true; attachmentError = null
            try {
                val available = (16 - files.size).coerceAtLeast(0)
                for (uri in uris.take(available)) {
                if (currentSessionId != sessionId) break
                val prepared = prepareFile(context, uri)
                val uploaded = try { uploadFile(sessionId, prepared.name, prepared.file) } finally { prepared.file.delete() }
                if (currentSessionId == sessionId) files = files + JSONObject().put("type", "mention")
                    .put("name", uploaded.getString("name")).put("path", uploaded.getString("path"))
                }
                if (uris.size > available && currentSessionId == sessionId) attachmentError = "最多添加 16 个文件，其余文件未上传"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (currentSessionId == sessionId) attachmentError = e.message ?: "文件上传失败" }
            finally { preparing = false }
        }
    }
    val list = key(state.id) { rememberLazyListState() }
    var followLatest by remember(state.id) { mutableStateOf(true) }
    val timeline = remember(snapshot?.messages) { nativeTimelineRows(snapshot?.messages.orEmpty()) }
    val modelChangeRows = remember(timeline, snapshot?.turnSettings) {
        val changes = nativeModelChanges(snapshot?.turnSettings.orEmpty())
        val seen = mutableSetOf<String>()
        timeline.mapNotNull { row ->
            val turnId = when (row) { is NativeTimelineRow.Message -> row.message.turnId; is NativeTimelineRow.Activities -> row.messages.first().turnId }
            if (seen.add(turnId)) changes[turnId]?.let { row.key to it } else null
        }.toMap()
    }
    val scrollConnection = remember(list) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0f) followLatest = false
            return Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (consumed.y < 0f && !list.canScrollForward) followLatest = true
            return Offset.Zero
        }
    } }
    // A trailing anchor reaches the end even when the final message is taller
    // than the viewport. Layout observation also covers streaming text, images,
    // approval cards and keyboard/queue height changes without counting messages.
    LaunchedEffect(list) {
        snapshotFlow {
            val layout = list.layoutInfo
            listOf(followLatest, list.isScrollInProgress, list.canScrollForward,
                layout.totalItemsCount, layout.viewportEndOffset,
                layout.visibleItemsInfo.lastOrNull()?.let { it.offset + it.size })
        }.collect {
            if (followLatest && !list.isScrollInProgress && list.canScrollForward && list.layoutInfo.totalItemsCount > 0)
                // Layout snapshots can be delivered while LazyColumn is placing/discarding items.
                // Request the next measurement instead of forcing a nested synchronous remeasure.
                list.requestScrollToItem(list.layoutInfo.totalItemsCount - 1)
        }
    }
    LaunchedEffect(list, state.id, state.historyCursor, state.loading, state.loadingEarlier, state.stale) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.size > 0 && !list.canScrollForward && !list.canScrollBackward }
            .collect { unfilled -> if (unfilled && !state.loading && !state.loadingEarlier && !state.stale && state.historyCursor != null) onFillHistory() }
    }
    val showLatest by remember(list) { derivedStateOf { list.canScrollForward } }
    val quoteText: ((String) -> Unit)? = if (!busy && !blocked && draftLoaded) ({ text ->
        draft = appendQuoteToDraft(draft, text); inputFocusRequest++
    }) else null
    val saveFile: (String) -> Unit = { fileAction = FileAction.SAVE; filePath = it }
    fun openSettings() { settingsOpen = !settingsOpen; if (settingsOpen) onLoadModels() }
    Column(Modifier.fillMaxSize().imePadding().testTag("chat_page")) {
        NativeChatHeader(state, usage, onBack, ::openSettings, onRefresh, { developerOpen = true }, { stopConfirm = true },
            !busy && !blocked && !state.stale && snapshot?.readOnly != true, pinned, canManage, onPin, onArchive)
        if (snapshot?.readOnly == true) Text("此会话由另一运行时持有，可查看历史或保存待发任务。队列等待会话可执行后继续。",
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("external_writer_notice"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection).testTag("chat_history"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state.historyCursor != null) item { TextButton(onClick = { followLatest = false; onEarlier() }, enabled = !state.loadingEarlier, modifier = Modifier.fillMaxWidth().testTag("load_earlier")) { Text(if (state.loadingEarlier) "正在加载…" else "加载更早的消息") } }
            if (!state.loading && snapshot != null && snapshot.messages.isEmpty()) item {
                Text("继续这个项目中的工作", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(timeline, key = { it.key }) { row ->
                modelChangeRows[row.key]?.let { label -> Text(label,
                    Modifier.fillMaxWidth().padding(bottom = 10.dp).testTag("model_change_notice"),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (row is NativeTimelineRow.Activities) NativeActivityGroup(row) { path -> nativeFilePath(path, snapshot?.settings?.string("cwd").orEmpty())?.let(::openFile) }
                else if (row is NativeTimelineRow.Message) {
                val message = row.message
                val user = message.type in setOf("userMessage", "user_message")
                val assistant = message.type in setOf("agentMessage", "agent_message", "exitedReviewMode")
                var expanded by remember(message.id) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                    if (user) Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 326.dp)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val content = message.content
                            if (content.isEmpty()) MessageContent(message.text, snapshot?.settings?.string("cwd").orEmpty(), onQuoteText = quoteText, onSaveFile = saveFile, onFile = ::openFile)
                            content.forEach { part -> when (part.string("type")) {
                                "text" -> MessageContent(part.string("text"), snapshot?.settings?.string("cwd").orEmpty(), onQuoteText = quoteText, onSaveFile = saveFile, onFile = ::openFile)
                                "image" -> part.string("url").takeIf { it.isNotBlank() }?.let { NativeImage(it, readFile, downloadFile = downloadFile) } ?: Text("图片引用：${part.string("fileId")}")
                                "localImage" -> NativeImage(nativeFilePath(part.string("path"), snapshot?.settings?.string("cwd").orEmpty()) ?: part.string("path"), readFile, downloadFile = downloadFile)
                                "mention", "skill" -> TextButton(onClick = { nativeFilePath(part.string("path"), snapshot?.settings?.string("cwd").orEmpty())?.let(::openFile) }) { Text((if (part.string("type") == "skill") "技能：" else "文件：") + part.string("name")) }
                                else -> Text(part.toString(2), style = MaterialTheme.typography.bodySmall)
                            } }
                        }
                    } else if (assistant) {
                        MessageContent(message.text, snapshot?.settings?.string("cwd").orEmpty(), onQuoteText = quoteText, onSaveFile = saveFile, onFile = ::openFile)
                        if (snapshot?.messages?.lastOrNull { it.turnId == message.turnId && it.type in setOf("agentMessage", "agent_message", "exitedReviewMode") }?.id == message.id)
                            ChangeSummary(snapshot.changes.firstOrNull { it.string("turnId") == message.turnId }) { developerOpen = true }
                    }
                    else if (message.type == "imageGeneration" && (message.raw.string("savedPath").isNotBlank() || message.raw.string("result").isNotBlank())) {
                        NativeImage(message.raw.string("savedPath").ifBlank { "data:image/png;base64," + message.raw.string("result") }, readFile, downloadFile = downloadFile)
                    }
                    else Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                                Text((when (message.type) { "commandExecution" -> "执行命令"; "fileChange" -> "文件变更"; "reasoning" -> "思考摘要"; else -> message.type }) + if (expanded) " ▴" else " ▾")
                            }
                            if (expanded) SelectionContainer { Text(message.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                }
            }
            snapshot?.requests?.forEach { request -> item(key = request.key) {
                NativeRequestPanel(request, !busy && !blocked && !state.stale && !snapshot.readOnly) { onRespond(request, it) }
            } }
            item(key = "relay_latest_anchor") { Spacer(Modifier.fillMaxWidth().height(1.dp).testTag("latest_message_anchor")) }
        }
        if (showLatest) SmallFloatingActionButton(
            onClick = { followLatest = true; scope.launch { list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("jump_to_latest")
                .semantics { contentDescription = "回到最新消息" },
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ) { Text("↓", style = MaterialTheme.typography.titleLarge) }
        }
        NativePlan(snapshot?.plan)
        if (snapshot?.queue?.isNotEmpty() == true) ExecutionQueuePanel(snapshot.id, snapshot.queue, !busy && !blocked && !state.stale,
            canSteer = !snapshot.readOnly && snapshot.activeTurnId.isNotBlank(),
            onEdit = { editingQueue = it }, onWithdraw = { removingQueue = it },
            onSteer = { task -> onQueue(JSONObject().put("operation", "steer").put("itemId", task.id).put("revision", task.revision).put("expectedTurnId", snapshot.activeTurnId), {}) },
            onReorder = { ids, expected -> onQueue(JSONObject().put("operation", "reorder").put("ids", JSONArray(ids)).put("expectedIds", JSONArray(expected)), {}) })
        MessageComposer(
            text = draft, onTextChange = { draft = it }, images = images,
            onEditImage = { focus.clearFocus(); keyboard?.hide(); editingImage = it },
            onRemoveImage = { image -> images = images.filterNot { it.id == image.id } },
            files = files, onRemoveFile = { reference -> files = files.filterNot { it === reference } },
            onOpenFile = { reference -> nativeFilePath(reference.string("path"), snapshot?.settings?.string("cwd").orEmpty())?.let(::openFile) },
            preparing = preparing, error = attachmentError, enabled = !busy && !blocked && draftLoaded,
            canSend = (draft.isNotBlank() || images.isNotEmpty() || files.isNotEmpty()) && !state.stale && snapshot != null,
            isRunning = snapshot?.activeTurnId?.isNotBlank() == true, canStop = !busy && !blocked && !state.stale && snapshot?.readOnly != true, busy = busy,
            onAddImage = { focus.clearFocus(); keyboard?.hide(); picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onAddFile = { focus.clearFocus(); keyboard?.hide(); filePicker.launch(arrayOf("*/*")) },
            onAddVideo = { focus.clearFocus(); keyboard?.hide(); filePicker.launch(arrayOf("video/*")) },
            onAddWorkspaceFile = { focus.clearFocus(); keyboard?.hide(); workspaceFileDialog = true }, onSettings = ::openSettings,
            discussionMode = (selectedMode ?: snapshot?.settings?.optJSONObject("collaborationMode")?.string("mode")) == "plan",
            onToggleMode = { selectedMode = if ((selectedMode ?: snapshot?.settings?.optJSONObject("collaborationMode")?.string("mode")) == "plan") "default" else "plan" },
            focusRequest = inputFocusRequest, onPasteImages = ::addImages,
            settingsExpanded = settingsOpen, onDismissSettings = { settingsOpen = false }, settingsContent = {
                NativeComposerSettings(snapshot?.settings, models, selectedModel, selectedEffort, selectedMode, !busy && !blocked && draftLoaded,
                    onModel = { modelId ->
                        selectedModel = modelId
                        val capability = models.firstOrNull { it.string("model").ifBlank { it.string("id") } == modelId }
                        val supported = capability?.objects("supportedReasoningEfforts").orEmpty().map { it.string("reasoningEffort") }
                        selectedEffort = capability?.string("defaultReasoningEffort")?.takeIf { it in supported }
                    }, onEffort = { selectedEffort = it }, onInheritMode = { selectedMode = null })
            },
            onSend = {
                val accepted = { followLatest = true; draft = ""; images = emptyList<DraftImage>(); files = emptyList<JSONObject>();
                    state.id?.let { onPersistDraft(it, ConversationDraft(model = selectedModel, effort = selectedEffort, mode = selectedMode)) }; Unit }
                if (onSendWithMode != null) onSendWithMode(nativeInputs(draft, images, files), selectedModel, selectedEffort, selectedMode, accepted)
                else onSend(nativeInputs(draft, images, files), selectedModel, selectedEffort, accepted)
            },
            onStop = { stopConfirm = true },
            queueing = snapshot != null && (snapshot.readOnly || snapshot.activeTurnId.isNotBlank() || snapshot.requests.isNotEmpty() || snapshot.queue.isNotEmpty()),
        )
    }
    if (developerOpen && state.id != null) DeveloperToolsDialog(state.id, readTool, submitTool, busy || blocked || state.stale, onDismiss = { developerOpen = false })
    editingImage?.let { image ->
        key(image) {
            AttachmentImageEditor(image, onDismiss = { editingImage = null }, onComplete = { edited ->
                images = images.map { if (it.id == image.id) edited else it }
                editingImage = null
            })
        }
    }
    editingQueue?.let { task ->
        var text by remember(task.id) { mutableStateOf(task.text) }
        AlertDialog(onDismissRequest = { if (!busy) editingQueue = null }, title = { Text("编辑待发任务") },
            text = { Column { if (task.error.isNotBlank()) Text(task.error, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().testTag("queue_edit_text"), maxLines = 6)
                Text("附件和运行设置会保留。保存后重新等待执行。", style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { onQueue(JSONObject().put("operation", "edit").put("itemId", task.id).put("revision", task.revision).put("text", text)) { editingQueue = null } }, enabled = !busy && !blocked && text.isNotBlank(), modifier = Modifier.testTag("queue_save")) { Text("保存") } },
            dismissButton = { Row { TextButton(onClick = { editingQueue = null; removingQueue = task }, enabled = !busy && !blocked) { Text("撤回") }; TextButton(onClick = { editingQueue = null }, enabled = !busy) { Text("取消") } } })
    }
    removingQueue?.let { task -> AlertDialog(onDismissRequest = { if (!busy) removingQueue = null },
        title = { Text(if (task.status == "unknown") "移除待核对记录？" else "撤回待发任务？") },
        text = { Text(if (task.status == "unknown") "仅移除此队列记录，不会停止或重新发送原任务。请先核对会话历史。" else "该任务将从等待队列移除，不再执行。") },
        confirmButton = { TextButton(onClick = { onQueue(JSONObject().put("operation", "remove").put("itemId", task.id).put("revision", task.revision)) { removingQueue = null } }, enabled = !busy && !blocked, modifier = Modifier.testTag("queue_remove")) { Text("确认撤回") } },
        dismissButton = { TextButton(onClick = { removingQueue = null }) { Text("取消") } }) }
    filePath?.let { FilePreviewDialog(it, readFile, downloadFile, initialAction = fileAction) { filePath = null; fileAction = null } }
    if (workspaceFileDialog) WorkspaceFilePicker(snapshot?.settings?.string("cwd").orEmpty(), readDirectory,
        onDismiss = { workspaceFileDialog = false }, onSelect = { file ->
            if (files.size < 16 && files.none { it.string("path") == file.string("path") }) files = files + file
            workspaceFileDialog = false
        })
    if (stopConfirm) AlertDialog(onDismissRequest = { stopConfirm = false }, title = { Text("停止当前任务？") },
        text = { Text("将中断当前 Codex 执行。已产生的内容和改动会保留。") },
        confirmButton = { Button(onClick = { stopConfirm = false; onStop() }) { Text("停止任务") } },
        dismissButton = { TextButton(onClick = { stopConfirm = false }) { Text("取消") } })

}

internal fun nativePermissionSummary(settings: JSONObject): String {
    val sandbox = settings.optJSONObject("sandbox") ?: settings.optJSONObject("sandboxPolicy")
    val access = when (sandbox?.string("type")) {
        "dangerFullAccess", "danger-full-access" -> "完整文件访问"
        "workspaceWrite", "workspace-write" -> "工作区读写"
        "readOnly", "read-only" -> "只读"
        "externalSandbox", "external-sandbox" -> "外部沙盒"
        else -> "沿用 Desktop 权限"
    }
    val approval = when (val policy = settings.opt("approvalPolicy")) {
        "never" -> "原生审批：不要求"
        "on-request" -> "原生审批：按需"
        "on-failure" -> "原生审批：失败时"
        "untrusted" -> "原生审批：不受信任操作"
        is JSONObject -> "原生审批：Desktop 的细粒度规则"
        else -> "原生审批：沿用 Desktop"
    }
    return "$access\n$approval"
}
