package dev.rivo.relay.ui.chat

import dev.rivo.relay.codex.executionActive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.codex.control
import dev.rivo.relay.codex.displayControl
import dev.rivo.relay.codex.ControlState
import dev.rivo.relay.codex.canManageQueue
import dev.rivo.relay.codex.canResumeQueue
import dev.rivo.relay.codex.canContinueTask
import dev.rivo.relay.codex.canCompose
import dev.rivo.relay.data.*
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

private class ChatDraftPersistence(var draft: ConversationDraft, var loaded: Boolean,
    var persist: (String, ConversationDraft) -> Unit)

private data class HistoryScrollAnchor(val key: String, val offset: Int, val firstKey: String?)

private val chatActivitySpacing = 6.dp
private val chatSectionSpacing = 12.dp

@Composable
fun ChatScreen(state: SessionState, busy: Boolean, blocked: Boolean, models: List<JSONObject>, onBack: () -> Unit,
    onDismissFailedMessage: (String) -> Unit = {},
    onSend: (JSONArray, String?, String?, () -> Unit) -> Unit, onStop: () -> Unit,
    onAnswerQuestions: (String, JSONArray, () -> Unit) -> Unit = { _, _, _ -> },
    onRespond: (NativeRequest, JSONObject) -> Unit, onRefresh: () -> Unit, onLoadModels: () -> Unit, onEarlier: () -> Unit,
    readFile: suspend (String) -> JSONObject,
    predictMessage: suspend (String, String) -> JSONObject = { _, _ -> JSONObject() },
    readDirectory: suspend (String) -> JSONObject = { error("节点不支持目录浏览") },
    uploadFile: suspend (String, String, java.io.File) -> JSONObject = { _, _, _ -> error("节点不支持文件上传") },
    downloadFile: suspend (String, java.io.OutputStream, (Long, Long) -> Unit) -> Unit = { _, _, _ -> error("节点不支持文件下载") },
    onQueue: (JSONObject, () -> Unit) -> Unit = { _, _ -> },
    onGoal: (JSONObject) -> Unit = {},
    onContinue: () -> Unit = {},
    onFork: ((String, String?) -> Unit)? = null,
    draftStore: ConversationStore? = null, onPersistDraft: (String, ConversationDraft) -> Unit = { _, _ -> },
    onSendWithMode: ((JSONArray, String?, String?, String?, () -> Unit) -> Unit)? = null,
    readTool: suspend (String) -> JSONObject = { error("节点工具不可用") },
    submitTool: (String, JSONObject, () -> Unit) -> Unit = { _, _, _ -> },
    usage: CodexUsage? = null, onFillHistory: () -> Unit = {}, pinned: Boolean = false, canManage: Boolean = false,
    onPin: ((Boolean) -> Unit)? = null, onArchive: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
    pendingTitle: String? = null, pendingReady: Boolean = true, pendingGoalApplied: Boolean = false, pendingFirstTurnSent: Boolean = false,
    pendingReceiptUnresolved: Boolean = false, onReconcilePending: (() -> Unit)? = null,
    readGoalCapability: (suspend () -> Boolean)? = null, onOpenPendingThread: (() -> Unit)? = null, pendingPermissionsEditable: Boolean = true,
    pendingWorkspace: (@Composable () -> Unit)? = null,
    attachmentsEnabled: Boolean = true,
    onConfiguredSend: ((JSONArray, String?, String?, String?, String?, JSONObject, () -> Unit) -> Unit)? = null,
    onSettingsChange: ((JSONObject) -> Unit)? = null,
    recoveryState: RecoveryState? = null, takeoverBlockedReason: String? = null, nodeName: String = "当前节点", writerBusy: Boolean = false, onWriterAction: ((String) -> Unit)? = null,
    defaultSettings: JSONObject? = null, onSaveDefaults: ((JSONObject) -> Unit)? = null, modelCatalogNotice: String? = null,
    onForwardLink: ((String, String?, String, String) -> Unit)? = null, draftReloadKey: Long = 0, pendingCwd: String = "",
    onOpenAgent: ((String) -> Unit)? = null,
    mediaApi: dev.rivo.relay.data.RelayApi? = null,
    cachedFile: (suspend (String, (Long, Long) -> Unit) -> java.io.File)? = null) {
    val mediaContext = androidx.compose.ui.platform.LocalContext.current
    val mediaClient = remember(mediaApi, state.id) { mediaApi?.let { dev.rivo.relay.data.CodexMediaClient(it, state.id.orEmpty(), mediaContext) } }
    DisposableEffect(mediaClient) { onDispose { mediaClient?.close() } }
    var forkTurn by remember(state.id) { mutableStateOf<String?>(null) }
    var forkContext by remember(state.id, forkTurn) { mutableStateOf<JSONObject?>(null) }
    var forkError by remember(state.id, forkTurn) { mutableStateOf<String?>(null) }
    var forkRetry by remember(state.id, forkTurn) { mutableIntStateOf(0) }
    LaunchedEffect(state.id, forkTurn, forkRetry) {
        forkContext = null; forkError = null
        if (forkTurn != null) try {
            forkContext = readTool("/v1/threads/${encoded(state.id.orEmpty())}/fork-context")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { forkError = e.message ?: "无法读取分叉工作区" }
    }
    if (forkTurn != null) AlertDialog(
        onDismissRequest = { if (!busy) forkTurn = null },
        title = { Text("从这里创建聊天分支") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            forkError?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { forkRetry++ }) { Text("重试") } }
            val context = forkContext
            val ready = !busy && !blocked && !state.stale && context != null && forkError == null
            val git = context?.optJSONObject("git")
            val head = git?.string("head")?.takeIf { it.isNotBlank() }
            val canCreateWorktree = ready && context?.string("projectId").orEmpty().isNotBlank() &&
                git?.optBoolean("available") == true && head != null
            if (context == null && forkError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(enabled = ready, modifier = Modifier.fillMaxWidth().testTag("fork_use_workspace"),
                onClick = { forkTurn?.let { onFork?.invoke(it, null) }; forkTurn = null }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("使用工作空间", style = MaterialTheme.typography.titleSmall)
                    Text("两个聊天共用同一组文件", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(enabled = canCreateWorktree, modifier = Modifier.fillMaxWidth().testTag("fork_create_worktree"),
                onClick = { if (canCreateWorktree) { forkTurn?.let { onFork?.invoke(it, head) }; forkTurn = null } }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("创建新工作树", style = MaterialTheme.typography.titleSmall)
                    Text(if (ready && !canCreateWorktree) "当前工作空间没有可用的 Git 工作树来源" else "新聊天将拥有独立的工作目录",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("新工作树基于当前 HEAD，不复制未提交改动。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = {},
        dismissButton = { TextButton(enabled = !busy, onClick = { forkTurn = null }) { Text("取消") } })
    val snapshot = state.snapshot
    androidx.compose.runtime.SideEffect { mediaClient?.updateRevision(state.mediaValidationEpoch) }
    var mediaAssets by remember(mediaClient) { mutableStateOf(emptyList<JSONObject>()) }
    val generated = remember(snapshot?.messages) { generatedMediaRequests(snapshot?.messages.orEmpty()) }
    val mediaIndex = remember(mediaAssets) { nativeMediaIndex(mediaAssets) }
    val mediaSources = remember(mediaAssets) { nativeMediaSourceIndex(mediaAssets) }
    LaunchedEffect(mediaClient, generated, state.stale) {
        if (mediaClient != null && generated.isNotEmpty() && !state.stale) {
            var attempt = 0
            while (true) {
                try {
                    mediaAssets = mediaClient.api.call("/v1/media/library?threadId=${dev.rivo.relay.data.encoded(mediaClient.threadId)}").objects("items")
                    val indexed = nativeMediaIndex(mediaAssets)
                    if (generated.all { request -> indexed[request.identity]?.string("status")?.let { it != "pending" } == true }) return@LaunchedEffect
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; if (attempt >= 2) return@LaunchedEffect }
                // Imports may outlive a turn. Keep polling at a bounded rate until ready/failed or this screen leaves.
                kotlinx.coroutines.delay(1000L * (++attempt).coerceAtMost(30))
            }
        }
    }
    val cwd = snapshot?.settings?.string("cwd")?.takeIf(String::isNotBlank) ?: pendingCwd
    val galleryCache = remember(mediaClient, state.id) { NativeGalleryCache() }
    val gallery = key(galleryCache) {
        val sources by produceState(emptyList<String>(), snapshot?.messages, cwd, mediaIndex) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val context = kotlinx.coroutines.currentCoroutineContext()
                galleryCache.sources(snapshot?.messages.orEmpty(), cwd, mediaIndex) { context.ensureActive() }
            }
        }
        sources
    }
    var removingQueue by remember(state.id) { mutableStateOf<QueueTask?>(null) }
    var draft by rememberSaveable(state.id) { mutableStateOf("") }
    var stopConfirm by remember(state.id) { mutableStateOf(false) }
    var settingsOpen by remember(state.id) { mutableStateOf(false) }
    BackHandler(settingsOpen) { settingsOpen = false }
    var developerOpen by remember(state.id) { mutableStateOf(false) }
    var selectedMode by rememberSaveable(state.id) { mutableStateOf<String?>(null) }
    var goalModeSelected by rememberSaveable(state.id) { mutableStateOf(false) }
    var pendingGoal by rememberSaveable(state.id) { mutableStateOf<String?>(null) }
    var goalCapability by remember(state.id) { mutableStateOf<Boolean?>(null) }
    var goalCapabilityError by remember(state.id) { mutableStateOf<String?>(null) }
    var goalCapabilityRetry by remember(state.id) { mutableIntStateOf(0) }
    LaunchedEffect(state.id, pendingTitle, goalCapabilityRetry) {
        if (pendingTitle == null || readGoalCapability == null) return@LaunchedEffect
        goalCapability = null; goalCapabilityError = null
        try { goalCapability = readGoalCapability() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { goalCapabilityError = "无法确认节点的目标能力，请重试；普通消息仍可发送。" }
    }
    var draftLoaded by remember(state.id, draftStore) { mutableStateOf(draftStore == null) }
    var selectedModel by rememberSaveable(state.id) { mutableStateOf<String?>(null) }
    var selectedEffort by rememberSaveable(state.id) { mutableStateOf<String?>(null) }
    var selectedSandbox by rememberSaveable(state.id) { mutableStateOf<String?>(null) }
    var selectedApproval by rememberSaveable(state.id) { mutableStateOf<String?>(null) }
    var selectedServiceTier by rememberSaveable(state.id) { mutableStateOf<String?>(null) }
    var images by remember(state.id) { mutableStateOf(emptyList<DraftImage>()) }
    var editingImage by remember(state.id) { mutableStateOf<DraftImage?>(null) }
    var files by remember(state.id) { mutableStateOf(emptyList<JSONObject>()) }
    var preparing by remember(state.id) { mutableStateOf(false) }
    var attachmentError by remember(state.id) { mutableStateOf<String?>(null) }
    var workspaceFileDialog by remember(state.id) { mutableStateOf(false) }
    var filePath by remember(state.id) { mutableStateOf<String?>(null) }
    var fileAction by remember(state.id) { mutableStateOf<FileAction?>(null) }
    var forwardingLink by remember(state.id) { mutableStateOf<CodexLinkAttachment?>(null) }
    var inputFocusRequest by remember(state.id) { mutableIntStateOf(0) }
    var imagePickerTarget by remember { mutableStateOf<String?>(null) }
    var filePickerTarget by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val currentSessionId by rememberUpdatedState(state.id)
    val canOperate = state.control().canOperate
    val inputLocked = blocked || busy || writerBusy || pendingFirstTurnSent || pendingReceiptUnresolved || (pendingTitle == null && !state.canCompose()) || snapshot?.requests?.isNotEmpty() == true
    val latestInputLocked by rememberUpdatedState(inputLocked)
    LaunchedEffect(inputLocked) {
        if (inputLocked) {
            editingImage = null; workspaceFileDialog = false; removingQueue = null; stopConfirm = false
        }
    }
    val settingsUnavailable = blocked || writerBusy || pendingFirstTurnSent || pendingReceiptUnresolved ||
        (pendingTitle == null && !state.canCompose()) || snapshot?.requests?.isNotEmpty() == true
    LaunchedEffect(settingsUnavailable) {
        if (settingsUnavailable) settingsOpen = false
    }
    fun openFile(path: String) { fileAction = null; filePath = path }
    var pendingImageUris by androidx.compose.runtime.saveable.rememberSaveable(state.id) { mutableStateOf(arrayListOf<String>()) }
    fun addImages(uris: List<android.net.Uri>) {
        val targetId = state.id
        if (preparing || !draftLoaded || busy || inputLocked) return
        scope.launch {
            preparing = true; attachmentError = null
            val available = (10 - images.size).coerceAtLeast(0)
            try { uris.take(available).forEach { uri ->
                val image = prepareImage(context, uri)
                if (currentSessionId == targetId && !latestInputLocked && images.size < 10) {
                    require(images.sumOf { it.bytes.size.toLong() } + image.bytes.size <= 40L * 1024 * 1024) { "图片总大小不能超过 40 MiB" }; images = images + image
                }
            }; if (uris.size > available && currentSessionId == targetId) attachmentError = "最多添加 10 张图片，其余图片未添加" }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (currentSessionId == targetId) attachmentError = e.message ?: "无法添加图片" }
            finally { if (currentSessionId == targetId) preparing = false }
        }
    }
    LaunchedEffect(pendingImageUris, preparing, draftLoaded, busy, inputLocked) {
        if (pendingImageUris.isNotEmpty() && !preparing && draftLoaded && !busy && !inputLocked) {
            val uris = pendingImageUris.map(android.net.Uri::parse)
            pendingImageUris = arrayListOf()
            addImages(uris)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty() && imagePickerTarget == state.id) addImages(uris)
        imagePickerTarget = null
    }
    LaunchedEffect(state.id, draftStore, draftReloadKey) {
        val id = state.id
        if (id != null && draftStore != null) {
            draftLoaded = false
            val saved = draftStore.loadDraft(id)
            pendingGoal = saved.pendingGoal.takeIf { pendingTitle != null }
            draft = saved.text; images = saved.images.map { DraftImage(it.id, it.bytes) }; files = saved.files
            selectedModel = saved.model; selectedEffort = saved.effort; selectedMode = saved.mode
            selectedServiceTier = saved.serviceTier
            selectedSandbox = saved.sandbox; selectedApproval = saved.approvalPolicy
        }
        if (pendingTitle != null) {
            selectedSandbox = newSessionSetting(selectedSandbox, defaultSettings, "sandbox")
            selectedApproval = newSessionSetting(selectedApproval, defaultSettings, "approvalPolicy")
            selectedModel = selectedModel ?: defaultSettings?.string("model")?.takeIf(String::isNotBlank)
            selectedEffort = selectedEffort ?: defaultSettings?.string("effort")?.takeIf(String::isNotBlank)
            selectedServiceTier = selectedServiceTier ?: defaultSettings?.string("serviceTier")?.takeIf(String::isNotBlank)
        }
        draftLoaded = true
    }
    val predictionTurn = snapshot?.turnSettings?.lastOrNull()?.turnId
    var prediction by remember(state.id, predictionTurn) { mutableStateOf<String?>(null) }
    var predictionConsumed by remember(state.id, predictionTurn) { mutableStateOf(false) }
    val predictionEligible = snapshot != null && !snapshot.isOngoing && !state.stale && !blocked && canOperate &&
        pendingTitle == null && selectedMode != "plan" && !goalModeSelected && snapshot.goal?.string("status") != "active" && images.isEmpty() && files.isEmpty()
    LaunchedEffect(state.id, predictionTurn, predictionEligible) {
        prediction = null
        val id = state.id
        if (predictionEligible && id != null && predictionTurn != null) {
            try {
                val result = predictMessage(id, predictionTurn)
                if (result.string("sourceTurnId") == predictionTurn) prediction = result.optString("text").takeUnless { it.isBlank() || it == "null" }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    val visiblePrediction = prediction.takeIf { predictionEligible && !predictionConsumed && draft.isEmpty() && !busy }
    val sendText = if (draft.isEmpty()) visiblePrediction ?: draft else draft
    val newSessionModels = if (pendingTitle != null) newSessionModelSettings(models, selectedModel, selectedEffort) else null
    val effectiveModel = if (pendingTitle != null) newSessionModels?.model else selectedModel
    val effectiveEffort = if (pendingTitle != null) newSessionModels?.effort else selectedEffort
    val currentDraft = ConversationDraft(draft, images.map { StoredDraftImage(it.id, it.bytes) }, files, effectiveModel, effectiveEffort, selectedMode, selectedServiceTier, selectedSandbox, selectedApproval, pendingGoal)
    var submittedDraft by remember(state.id) { mutableStateOf<ConversationDraft?>(null) }
    val unchangedSubmission = submittedDraft != null && submittedDraft == currentDraft
    val composerInput = remember(draft, images, files, pendingGoal, unchangedSubmission, state.pendingMessages.isEmpty()) {
        if (unchangedSubmission || state.pendingMessages.isEmpty()) JSONArray() else runCatching {
            nativeInputs(draft.ifBlank { pendingGoal?.let { JSONObject(it).string("objective") }.orEmpty() }, images, files)
        }.getOrDefault(JSONArray())
    }
    val submissionInFlight = busy || pendingFirstTurnSent || state.pendingMessages.any { it.delivery in setOf("submitting", "unknown") }
    val hideSubmitted = pendingTitle == null && hideSubmittedDraft(submissionInFlight, unchangedSubmission,
        composerInput, state.pendingMessages)
    // A masked draft is the recovery copy of an unresolved submission, not a new empty draft.
    val textEditable = draftLoaded && !busy && !pendingFirstTurnSent && !pendingReceiptUnresolved && !hideSubmitted
    LaunchedEffect(textEditable) {
        if (!textEditable) { focus.clearFocus(); keyboard?.hide() }
    }
    // Drop the mask after a definitive outcome. Never mutate the recovery copy here.
    SideEffect { if (!submissionInFlight && submittedDraft != null) submittedDraft = null }

    val latestDraft by rememberUpdatedState(currentDraft)
    // Disposal belongs to the departing chat. Updated-state delegates shared across
    // identities would observe the newly opened draft and its persistence callback.
    val draftPersistence = remember(state.id, draftStore) { ChatDraftPersistence(currentDraft, draftLoaded, onPersistDraft) }
    SideEffect {
        draftPersistence.draft = currentDraft
        draftPersistence.loaded = draftLoaded
        draftPersistence.persist = onPersistDraft
    }
    fun editQueuedTask(task: QueueTask) {
        val targetId = state.id
        // Function references can stay stable across skipped recompositions; read the
        // latest draft at click time rather than capturing an earlier composition.
        val before = latestDraft
        val restored = queueTaskDraft(task)
        onQueue(JSONObject().put("operation", if (task.recoveryRequired) "dismiss" else "remove").put("itemId", task.id).put("revision", task.revision)) {
            if (currentSessionId == targetId && latestDraft == before) {
                targetId?.let { onPersistDraft(it, ConversationDraft(restored.text,
                    restored.images.map { image -> StoredDraftImage(image.id, image.bytes) }, restored.files,
                    restored.model, restored.effort, restored.mode, restored.serviceTier)) }
                draft = restored.text; images = restored.images; files = restored.files
                selectedModel = restored.model; selectedEffort = restored.effort; selectedMode = restored.mode; selectedServiceTier = restored.serviceTier
                inputFocusRequest++
            }
        }
    }
    LaunchedEffect(state.id, draftLoaded, draft, images, files, effectiveModel, effectiveEffort, selectedMode, selectedServiceTier) {
        if (draftLoaded) state.id?.let { onPersistDraft(it, currentDraft) }
    }
    DisposableEffect(state.id, draftStore) {
        val id = state.id
        onDispose { if (id != null && draftPersistence.loaded) draftPersistence.persist(id, draftPersistence.draft) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val chosenTarget = filePickerTarget
        filePickerTarget = null
        if (chosenTarget == state.id && uris.isNotEmpty() && files.size < 16 && !preparing && !busy && !inputLocked) scope.launch {
            val sessionId = state.id ?: return@launch
            preparing = true; attachmentError = null
            try {
                val available = (16 - files.size).coerceAtLeast(0)
                for (uri in uris.take(available)) {
                if (currentSessionId != sessionId || latestInputLocked) break
                val prepared = prepareFile(context, uri)
                if (latestInputLocked) { prepared.file.delete(); break }
                val uploaded = try { uploadFile(sessionId, prepared.name, prepared.file) } finally { prepared.file.delete() }
                if (currentSessionId == sessionId && !latestInputLocked) files = files + JSONObject().put("type", "mention")
                    .put("name", uploaded.getString("name")).put("path", uploaded.getString("path"))
                }
                if (uris.size > available && currentSessionId == sessionId) attachmentError = "最多添加 16 个文件，其余文件未上传"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (currentSessionId == sessionId) attachmentError = e.message ?: "文件上传失败" }
            finally { if (currentSessionId == sessionId) preparing = false }
        }
    }
    val list = key(state.id) { rememberLazyListState() }
    var followLatest by remember(state.id) { mutableStateOf(true) }
    var scrollingTowardLatest by remember(state.id) { mutableStateOf(false) }
    val followThresholdPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    val latestAssistant = snapshot?.messages?.lastOrNull { it.type in setOf("agentMessage", "agent_message") }
    val streamingIdentity = latestAssistant?.let { "${state.id}:${it.turnId}:${it.id}" }
    val streamingText = rememberStreamingMessageText(streamingIdentity, latestAssistant?.text.orEmpty(),
        shouldBufferMessage(latestAssistant, snapshot))
    val timelineCache = remember(state.id) { NativeTimelineCache() }
    // Transport/permission revalidation must not regroup the visible running turn.
    val timeline = remember(state.id, snapshot?.messages, snapshot?.turnSettings, state.pendingMessages, snapshot?.activeTurnId, pendingTitle) {
        timelineCache.project(projectedSessionMessages(snapshot, if (pendingTitle == null) state.pendingMessages else emptyList()), snapshot?.activeTurnId, snapshot?.turnSettings.orEmpty())
    }
    val agentDetails = remember(snapshot?.messages) { nativeAgentDetailsByMessage(snapshot?.messages.orEmpty()) }
    val resourceRows = remember(timeline) { nativeResourceRows(timeline) }
    // Preserve lastOrNull semantics, including review replies and empty turn IDs.
    val lastReplyIds = remember(snapshot?.messages) {
        snapshot?.messages.orEmpty()
            .filter { it.type in setOf("agentMessage", "agent_message", "exitedReviewMode") }
            .associate { it.turnId to it.id }
    }
    val turnFooterCache = remember(state.id) { NativeTurnFooterCache() }
    val turnFooter = remember(turnFooterCache, snapshot, state.synchronization) {
        turnFooterCache.project(snapshot, state.stale || state.loading || state.readError != null,
            System.currentTimeMillis() / 1000, android.os.SystemClock.elapsedRealtime())
    }
    val turnFooterBoundary = turnFooter?.let { nativeTurnFooterBoundary(timeline, it) }
    val historicalTiming = remember(timeline, snapshot?.turnSettings, turnFooter?.turn?.turnId) {
        nativeHistoricalTimingRows(timeline, snapshot?.turnSettings.orEmpty(), turnFooter?.turn?.turnId)
    }
    val inlineHistoricalTiming = remember(historicalTiming, lastReplyIds) {
        historicalTiming.values.flatten().filter { it.turnId in lastReplyIds }.associateBy { it.turnId }
    }
    val standaloneHistoricalTiming = remember(historicalTiming, inlineHistoricalTiming) {
        historicalTiming.mapValues { (_, timings) -> timings.filter { it.turnId !in inlineHistoricalTiming } }
            .filterValues { it.isNotEmpty() }
    }
    var historyAnchor by remember(state.id) { mutableStateOf<HistoryScrollAnchor?>(null) }
    // The paging button can be the first visible item. Anchor a timeline row
    // instead, retaining its viewport position when older rows are inserted.
    SideEffect {
        val anchor = historyAnchor
        if (anchor != null && timeline.firstOrNull()?.key != anchor.firstKey) {
            val index = timeline.indexOfFirst { it.key == anchor.key }
            if (index >= 0) {
                list.requestScrollToItem(index + (if (state.historyCursor != null) 1 else 0), -anchor.offset)
            }
            historyAnchor = null
        }
    }
    val executionActive = state.executionActive
    val currentActivity = (timeline.lastOrNull() as? NativeTimelineRow.Activities)
        ?.takeIf { executionActive && it.messages.lastOrNull()?.turnId == snapshot?.activeTurnId }
    val modelChangeRows = remember(timeline, snapshot?.turnSettings) {
        val changes = nativeModelChanges(snapshot?.turnSettings.orEmpty())
        val seen = mutableSetOf<String>()
        timeline.mapNotNull { row ->
            val turnId = when (row) { is NativeTimelineRow.Message -> row.message.turnId; is NativeTimelineRow.Activities -> row.messages.first().turnId; is NativeTimelineRow.Notice -> row.turn.turnId }
            if (seen.add(turnId)) changes[turnId]?.let { row.key to it } else null
        }.toMap()
    }
    val loadEarlierWithAnchor by rememberUpdatedState(newValue = {
        if (!state.loading && !state.loadingEarlier && !state.stale &&
            snapshot?.historyReady == true && state.historyCursor != null) {
            followLatest = false
            val keys = timeline.map { it.key }.toSet()
            historyAnchor = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key in keys }?.let {
                HistoryScrollAnchor(it.key as String, it.offset, timeline.firstOrNull()?.key)
            }
            onEarlier()
        }
    })
    val scrollConnection = remember(list) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) {
                if (available.y < 0f || list.firstVisibleItemIndex > 0) historyAnchor = null
                if (available.y > 0f) {
                    followLatest = false
                    scrollingTowardLatest = false
                    // Continued upward input on the paging row loads the previous page.
                    if (list.firstVisibleItemIndex == 0) loadEarlierWithAnchor()
                }
                if (available.y < 0f) scrollingTowardLatest = true
            }
            return Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (scrollingTowardLatest && consumed.y < 0f &&
                (list.layoutInfo.distanceFromBottom() ?: Int.MAX_VALUE) <= followThresholdPx) followLatest = true
            return Offset.Zero
        }
    } }
    LaunchedEffect(list, state.id, state.historyCursor, state.loading, state.loadingEarlier, state.stale, snapshot?.historyReady) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.size > 0 && !list.canScrollForward && !list.canScrollBackward }
            .collect { unfilled -> if (unfilled && !state.loading && !state.loadingEarlier && !state.stale && snapshot?.historyReady == true && state.historyCursor != null) onFillHistory() }
    }
    // A content-growth layout is not a user leaving the bottom; don't flash the jump button.
    val showLatest by remember(list) { derivedStateOf { !followLatest && list.canScrollForward } }
    val quoteText: ((String) -> Unit)? = if (!busy && !inputLocked && draftLoaded) ({ text ->
        draft = appendQuoteToDraft(draft, text); inputFocusRequest++
    }) else null
    val saveFile: (String) -> Unit = { fileAction = FileAction.SAVE; filePath = it }
    fun openSettings() { if (inputLocked) return; settingsOpen = !settingsOpen; if (settingsOpen) { focus.clearFocus(); keyboard?.hide(); onLoadModels() } }

    val mediaCache: NativeCachedDownload? = mediaClient?.let { client ->
        { path, progress ->
            if (isHttpWebLink(path) || path.startsWith("/v1/media/")) client.cached(mediaContext, path, progress)
            else requireNotNull(cachedFile)(path, progress)
        }
    } ?: cachedFile
    CompositionLocalProvider(LocalMediaAssets provides mediaAssets, LocalMediaSourceIndex provides mediaSources, LocalCodexGallery provides gallery,
        LocalMediaEdited provides { uri -> addImages(listOf(uri)) }, LocalCodexMediaClient provides mediaClient,
        LocalNativeFileCache provides mediaCache,
        LocalCodexLinkForwarder provides if (onForwardLink == null || busy || blocked) null else { link -> forwardingLink = link }) {
    Column(Modifier.fillMaxSize().imePadding().testTag("chat_page")) {
        if (pendingTitle != null) Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("back_sessions")) { Text("‹") }
            Column(Modifier.clickable(onClick = ::openSettings).testTag("conversation_status")) { Text("新会话", style = MaterialTheme.typography.titleLarge); Text(pendingTitle, style = MaterialTheme.typography.bodySmall) }
        } else NativeChatHeader(state, usage, onBack, ::openSettings, onRefresh, { developerOpen = true }, { stopConfirm = true },
            !busy && !blocked && !state.stale && canOperate, pinned, canManage, onPin, onArchive,
            onWriterAction, writerBusy || busy || blocked, takeoverBlockedReason, recoveryState, onDelete)
        // Keep a 2.dp gap below the quota bar and reserve the strip during revalidation.
        Box(Modifier.fillMaxWidth()
            .padding(horizontal = dev.rivo.relay.ui.theme.RivoSpacing.Page)
            .padding(top = 2.dp)
            .height(4.dp)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
        }
        if (pendingTitle == null && state.displayControl().state == ControlState.EXTERNAL) NativeWriterStatus(state, nodeName, writerBusy || busy || blocked, onWriterAction, takeoverBlockedReason, onRefresh)
        var bottomOverlayHeight by remember { mutableIntStateOf(0) }
        val bottomOverlayPadding = with(LocalDensity.current) { bottomOverlayHeight.toDp() }
        val trailingDiagnostics = snapshot?.reviewDiagnostics.orEmpty().filter { diagnostic ->
            diagnostic.string("status") != "verified" && snapshot?.messages.orEmpty().none {
                it.turnId == diagnostic.string("turnId") && it.type in setOf("agentMessage", "agent_message", "exitedReviewMode")
            }
        }
        val showPendingIntro = pendingTitle != null && timeline.isEmpty()
        val showEmptyIntro = !state.loading && snapshot != null && timeline.isEmpty()
        val showTimingTail = standaloneHistoricalTiming[timeline.size] != null
        val showTurnFooter = turnFooter != null && turnFooterBoundary == timeline.size
        val showExecutionStage = executionActive && currentActivity == null && turnFooter == null
        // All ordinary rows plus the keyed trailing anchor. Compute the new count before layout,
        // rather than using the previous layout's count when a tool/footer is inserted or removed.
        val lastItemIndex = timeline.size + trailingDiagnostics.size +
            listOf(showPendingIntro, state.historyCursor != null, showEmptyIntro,
                showTimingTail, showTurnFooter, showExecutionStage).count { it }
        FollowChatContent(list, followLatest, lastItemIndex,
            listOf(timeline, streamingIdentity, streamingText, turnFooter, historicalTiming,
                trailingDiagnostics, lastItemIndex, bottomOverlayHeight))
        Box(Modifier.weight(1f).fillMaxWidth()) {
        // Draw history behind the rounded composer; reserve scroll space for the final message.
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(bottom = 20.dp).nestedScroll(scrollConnection).testTag("chat_history"), contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = bottomOverlayPadding + 20.dp), verticalArrangement = Arrangement.spacedBy(chatActivitySpacing)) {
            if (showPendingIntro) item {
                Text("今天想从哪里开始？", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.historyCursor != null) item { TextButton(onClick = {
                loadEarlierWithAnchor()
            }, enabled = !state.loadingEarlier && snapshot?.historyReady == true, modifier = Modifier.fillMaxWidth().testTag("load_earlier")) { Text(if (snapshot?.historyReady != true) "历史同步中…" else if (state.loadingEarlier) "正在加载…" else "加载更早的消息") } }
            if (showEmptyIntro) item {
                Text("继续这个项目中的工作", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(timeline, key = { _, row -> row.key }, contentType = { _, row ->
                when (row) { is NativeTimelineRow.Message -> row.message.type; is NativeTimelineRow.Activities -> "activity_group"; is NativeTimelineRow.Notice -> "turn_notice" }
            }) { index, row ->
                HistoricalTurnTiming(standaloneHistoricalTiming[index].orEmpty())
                if (turnFooterBoundary == index && turnFooter != null) NativeTurnDuration(turnFooter)
                val modelChange = modelChangeRows[row.key]
                val consecutiveActivities = row is NativeTimelineRow.Activities &&
                    timeline.getOrNull(index - 1) is NativeTimelineRow.Activities && modelChange == null
                if ((index > 0 || state.historyCursor != null) && !consecutiveActivities) {
                    Spacer(Modifier.height(chatSectionSpacing - chatActivitySpacing))
                }
                modelChange?.let { label -> Text(label,
                    Modifier.fillMaxWidth().padding(bottom = chatActivitySpacing).testTag("model_change_notice"),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (row is NativeTimelineRow.Activities) NativeActivityGroup(row, isCurrent = row.key == currentActivity?.key && !state.stale, resourceItems = resourceRows[row.key].orEmpty(), agentDetails = agentDetails, onOpenAgent = onOpenAgent) { path -> nativeFilePath(path, cwd)?.let(::openFile) }
                else if (row is NativeTimelineRow.Notice) NativeTurnNotice(row.turn)
                else if (row is NativeTimelineRow.Message) {
                val message = row.message
                val user = message.type in setOf("userMessage", "user_message")
                val assistant = message.type in setOf("agentMessage", "agent_message", "exitedReviewMode")
                var expanded by remember(message.id) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                    if (user) {
                        val content = message.content
                        val attachmentImages = content.mapNotNull { nativeImageSource(it, cwd) }
                        val textParts = content.filter { nativeImageSource(it, cwd) == null }
                        if (textParts.isNotEmpty() || content.isEmpty()) Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 326.dp)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            .pendingMessageShimmer(message.raw.string("relayDelivery") in setOf("submitting", "accepted")), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (content.isEmpty()) MessageContent(message.text, cwd, onQuoteText = quoteText, onSaveFile = saveFile, inlineImage = { source ->
                                val target = if (isHttpWebLink(source)) source else nativeFilePath(source, cwd)
                                target?.let { NativeImage(it, readFile, downloadFile = downloadFile) }
                            }, onFile = ::openFile)
                            textParts.forEach { part ->
                                when (part.string("type")) {
                                "text" -> MessageContent(part.string("text"), cwd, onQuoteText = quoteText, onSaveFile = saveFile, inlineImage = { source ->
                                val target = if (isHttpWebLink(source)) source else nativeFilePath(source, cwd)
                                target?.let { NativeImage(it, readFile, downloadFile = downloadFile) }
                            }, onFile = ::openFile)
                                "image" -> part.string("url").takeIf { it.isNotBlank() }?.let { NativeImage(it, readFile, downloadFile = downloadFile) } ?: Text("图片引用：${part.string("fileId")}")
                                "localImage" -> NativeImage(nativeFilePath(part.string("path"), cwd) ?: part.string("path"), readFile, downloadFile = downloadFile)
                                "mention", "skill" -> TextButton(onClick = { nativeFilePath(part.string("path"), cwd)?.let(::openFile) }) { Text((if (part.string("type") == "skill") "技能：" else "文件：") + part.string("name")) }
                                else -> Text(part.toString(2), style = MaterialTheme.typography.bodySmall)
                            } }
                        }
                        }
                            if (attachmentImages.isNotEmpty()) Row(
                                modifier = Modifier.padding(top = if (textParts.isNotEmpty()) 6.dp else 0.dp).testTag("user_message_attachments"),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                attachmentImages.take(3).forEachIndexed { imageIndex, source ->
                                    NativeImage(source, readFile, downloadFile = downloadFile, compact = true,
                                        remaining = if (imageIndex == 2) (attachmentImages.size - 3).coerceAtLeast(0) else 0)
                                }
                            }
                            val delivery = message.raw.string("relayDelivery")
                            if (delivery in setOf("unknown", "failed")) {
                                var showDeliveryInfo by remember(message.id) { mutableStateOf(false) }
                                IconButton(onClick = { showDeliveryInfo = true }, modifier = Modifier.testTag("message_delivery_$delivery")) {
                                    Text("ⓘ", modifier = Modifier.semantics { contentDescription = if (delivery == "failed") "发送失败，查看重试方式" else "正在确认是否送达" },
                                        color = if (delivery == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (showDeliveryInfo) AlertDialog(
                                    onDismissRequest = { showDeliveryInfo = false },
                                    title = { Text(if (delivery == "failed") "发送失败" else "正在确认是否送达") },
                                    text = { Text(if (delivery == "failed") "消息已保留，可重试或删除这条消息。" else "暂时无法确认服务端是否收到消息，正在核对结果，请勿重复发送。") },
                                    confirmButton = { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        if (delivery == "failed") TextButton(
                                            modifier = Modifier.testTag("delete_failed_message"),
                                            onClick = {
                                                showDeliveryInfo = false
                                                onDismissFailedMessage(message.raw.string("clientId"))
                                            },
                                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                        Spacer(Modifier.weight(1f))
                                        if (delivery == "failed") TextButton(
                                        enabled = !busy && !inputLocked && !blocked && !state.stale && (snapshot != null || pendingTitle != null && pendingReady),
                                        onClick = {
                                            showDeliveryInfo = false
                                            val retried = { onDismissFailedMessage(message.raw.string("clientId")) }
                                            val input = if (content.isEmpty()) JSONArray().put(JSONObject().put("type", "text").put("text", message.text)) else JSONArray(content)
                                            if (onConfiguredSend != null) onConfiguredSend(input, effectiveModel, effectiveEffort, selectedMode, selectedServiceTier, JSONObject().apply {
                                                if (pendingTitle != null) {
                                                    pendingGoal?.let { put("goal", JSONObject(it)) }
                                                    selectedSandbox?.takeUnless { it == "inherit" }?.let { put("sandbox", it) }
                                                    selectedApproval?.takeUnless { it == "inherit" }?.let { put("approvalPolicy", it) }
                                                }
                                            }, retried)
                                            else if (onSendWithMode != null) onSendWithMode(input, effectiveModel, effectiveEffort, selectedMode, retried)
                                            else onSend(input, effectiveModel, effectiveEffort, retried)
                                        },
                                    ) { Text("重试") }
                                        TextButton(onClick = { showDeliveryInfo = false }) { Text("关闭") }
                                    } },
                                )
                            }
                    } else if (assistant) {
                        val isLastReply = lastReplyIds[message.turnId] == message.id
                        val timing = nativeMessageTiming(message, snapshot?.turnSettings?.firstOrNull { it.turnId == message.turnId }, isLastReply)
                        val visibleText = if (message.id == latestAssistant?.id && message.turnId == latestAssistant.turnId) streamingText else message.text
                        MessageContent(visibleText, cwd, cacheBlocks = !(snapshot?.isOngoing == true && message.turnId == snapshot.activeTurnId), onQuoteText = quoteText, onSaveFile = saveFile, inlineImage = { source ->
                                val target = if (isHttpWebLink(source)) source else nativeFilePath(source, cwd)
                                target?.let { NativeImage(it, readFile, downloadFile = downloadFile) }
                            }, onFile = ::openFile)
                        if (isLastReply && snapshot != null) {
                            ChangeSummary(snapshot.changes.firstOrNull { it.string("turnId") == message.turnId }) { developerOpen = true }
                            ReviewDiagnostic(snapshot.reviewDiagnostics.lastOrNull { it.string("turnId") == message.turnId })
                        }
                        AssistantMessageFooter(message.text, "${message.turnId}:${message.id}", timing.outputAt,
                            forkTurnId = message.turnId.takeIf { isLastReply && onFork != null &&
                                snapshot?.turnSettings?.any { it.turnId == message.turnId && it.status in setOf("completed", "interrupted", "failed") } == true },
                            forkEnabled = !busy && !blocked && !state.stale,
                            onFork = { forkTurn = message.turnId },
                            historicalTiming = inlineHistoricalTiming[message.turnId].takeIf { isLastReply })
                    }
                    else if (message.type == "imageGeneration" && (message.raw.string("savedPath").isNotBlank() || message.raw.string("result").isNotBlank())) {
                        val source = generatedImageSource(message, mediaIndex)
                        if (source != null) NativeImage(source, readFile, downloadFile = downloadFile, identity = "${message.turnId}:${message.id}")
                        else if (message.raw.optBoolean("mediaRef")) {
                            val failed = mediaIndex[NativeMediaIdentity(message.turnId, message.id)]?.string("status") in setOf("failed", "deleted")
                            Text(if (failed) "图片暂时无法读取" else "图片正在准备…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            ChatTimelineSummaryRow(onClick = { expanded = !expanded }) {
                                Text((when (message.type) { "commandExecution" -> "执行命令"; "fileChange" -> "文件变更"; "reasoning" -> "思考摘要"; else -> message.type }) + if (expanded) " ▴" else " ▾")
                            }
                            if (expanded) SelectionContainer(Modifier.padding(bottom = 8.dp)) {
                                Text(message.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                }
                val rowTurn = when (row) {
                    is NativeTimelineRow.Message -> row.message.turnId
                    is NativeTimelineRow.Activities -> row.messages.first().turnId
                    is NativeTimelineRow.Notice -> row.turn.turnId
                }
                val nextTurn = timeline.getOrNull(index + 1)?.let { next -> when (next) {
                    is NativeTimelineRow.Message -> next.message.turnId
                    is NativeTimelineRow.Activities -> next.messages.first().turnId
                    is NativeTimelineRow.Notice -> next.turn.turnId
                } }
                val terminal = snapshot?.turnSettings?.any { it.turnId == rowTurn && it.status in setOf("completed", "interrupted", "failed") } == true
                if (onFork != null && rowTurn.isNotBlank() && rowTurn != nextTurn && terminal && lastReplyIds[rowTurn] == null) {
                    AssistantMessageFooter("", rowTurn,
                        snapshot?.turnSettings?.firstOrNull { it.turnId == rowTurn }?.completedAt,
                        forkTurnId = rowTurn, forkEnabled = !busy && !blocked && !state.stale,
                        onFork = { forkTurn = rowTurn })
                }
            }
            standaloneHistoricalTiming[timeline.size]?.let { timings ->
                item(key = "historical_turn_timing_tail") { HistoricalTurnTiming(timings) }
            }
            trailingDiagnostics.forEach { diagnostic -> item(key = "review-diagnostic:${diagnostic.string("turnId")}") { ReviewDiagnostic(diagnostic) } }
            if (showTurnFooter) item(key = "turn_footer") { NativeTurnDuration(requireNotNull(turnFooter)) }
            if (showExecutionStage) item(key = "execution_stage") {
                ChatTimelineSummaryRow {
                    if (state.stale) Text("正在处理…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("execution_stage_pending"))
                    else ShimmeringStageText("正在处理…", Modifier.testTag("execution_stage_shimmer"))
                }
            }
            item(key = "relay_latest_anchor") { Spacer(Modifier.fillMaxWidth().height(1.dp).testTag("latest_message_anchor")) }
        }
        if (showLatest) SmallFloatingActionButton(
            onClick = { followLatest = true; scrollingTowardLatest = false; scope.launch { list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = bottomOverlayPadding).padding(12.dp).testTag("jump_to_latest")
                .semantics { contentDescription = "回到最新消息" },
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ) { Text("↓", style = MaterialTheme.typography.titleLarge) }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onSizeChanged { bottomOverlayHeight = it.height }) {
        if (pendingTitle == null) pendingWorkspace?.invoke()
        val asyncQuestions = remember(snapshot?.messages, snapshot?.activeTurnId, snapshot?.turnSettings, snapshot?.questionAnswerStates) {
            snapshot?.let(::pendingAsyncQuestions).orEmpty()
        }
        if (asyncQuestions.isNotEmpty() && snapshot != null) key(draftStore, state.id, snapshot.activeTurnId.ifBlank { snapshot.turnSettings.lastOrNull()?.turnId }) {
            AsyncQuestionPanel(asyncQuestions, !busy && !blocked && !writerBusy && !state.stale &&
                snapshot.canAnswerQuestions && snapshot.requests.isEmpty(),
                store = draftStore, draftKey = "question:${snapshot.id}:${snapshot.activeTurnId.ifBlank { snapshot.turnSettings.lastOrNull()?.turnId.orEmpty() }}",
                unavailableReason = if (asyncQuestions.any { snapshot.questionAnswerStates.string(it.id) in setOf("pending", "unknown") })
                    "回答结果待核对，请刷新；不会自动重发。" else if (!snapshot.canAnswerQuestions)
                    "当前节点暂不能连接问题所属会话，请刷新或在桌面回答。" else null) { input, accepted ->
                val activeTurn = snapshot.activeTurnId
                onAnswerQuestions(activeTurn.ifBlank { snapshot.turnSettings.lastOrNull()?.turnId.orEmpty() }, input, accepted)
            }
        }

        if (snapshot?.requests?.isNotEmpty() == true) Column(Modifier.fillMaxWidth().heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).testTag("native_requests_panel")) {
            snapshot.requests.forEach { request -> key(request.key) {
                NativeRequestPanel(request, !busy && !blocked && !state.stale && (canOperate || request.method.endsWith("requestUserInput"))) { onRespond(request, it) }
            } }
        }
        var goalEditing by remember(state.id) { mutableStateOf(false) }
        val goalAvailable = (pendingTitle != null && (goalCapability == true || pendingGoal != null)) || snapshot?.goalSupported == true
        val goalEnabled = !pendingReceiptUnresolved && !pendingFirstTurnSent && !busy && !blocked && !state.stale && draftLoaded && (pendingTitle != null || canOperate)
        val displayedGoal = if (pendingTitle != null) pendingGoal?.let { JSONObject(it).put("status", "pending") } else snapshot?.goal
        val goalMode = goalModeSelected || displayedGoal?.string("status") in setOf("active", "pending")
        if (goalAvailable) key(state.id) {
            NativeGoalPanel(displayedGoal, goalEnabled, { action ->
                if (action.string("operation") == "clear") goalModeSelected = false
                if (pendingTitle != null) pendingGoal = if (action.string("operation") == "clear") null else action.toString()
                else onGoal(action)
            }, goalEditing, pending = pendingTitle != null) { goalEditing = it }
        }
        if (pendingTitle != null && !pendingFirstTurnSent && goalCapability != true) Column(Modifier.padding(horizontal = 16.dp)) {
            Text(goalCapabilityError ?: if (goalCapability == false) "此节点不支持持续目标。" else "正在检查目标能力…",
                style = MaterialTheme.typography.bodySmall)
            if (pendingGoal != null) Text("草稿中的目标已保留；确认能力或移除目标后才能发送。", style = MaterialTheme.typography.bodySmall)
            if (goalCapabilityError != null) TextButton(onClick = { goalCapabilityRetry++ }, enabled = !busy && !blocked) { Text("重新检查") }
        }
        if (pendingReceiptUnresolved && !busy && !blocked) Column(Modifier.padding(horizontal = 16.dp)) {
            Text("该会话仍有未确认提交。正在自动恢复发送状态，请稍候。", style = MaterialTheme.typography.bodySmall)
            onReconcilePending?.let { TextButton(onClick = it) { Text("核对新建结果") } }
        }
        if (pendingFirstTurnSent) Column(Modifier.padding(horizontal = 16.dp)) {
            Text("首条任务已发送，持续目标尚待确认。恢复只提交原先保存的目标，不会重发消息。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onConfiguredSend?.invoke(JSONArray(), null, null, null, null, JSONObject(), {}) },
                enabled = !pendingReceiptUnresolved && !busy && !blocked && !state.stale) { Text("完成目标设置") }
            onOpenPendingThread?.let { TextButton(onClick = it) { Text("查看已发送任务") } }
        }
        if (pendingTitle != null && pendingGoalApplied) Text(
            "目标设置已提交到会话；首条任务尚待发送。重试不会重复提交相同目标。",
            modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        NativePlan(snapshot?.plan)
        if (state.canResumeQueue() && onWriterAction != null) {
            TextButton(onClick = { onWriterAction("resume") },
                enabled = !busy && !blocked && !writerBusy,
                modifier = Modifier.testTag("resume_queue")) { Text("恢复队列") }
        }
        val waitingQueue = snapshot?.queue.orEmpty().filter { it.status !in setOf("running", "dispatching") && !it.recoveryRequired && it.id != snapshot?.recoveryTask?.id }
        val displayedQueue = waitingQueue + projectedPendingQueue(snapshot, state.pendingMessages)
        if (snapshot != null && displayedQueue.isNotEmpty()) ExecutionQueuePanel(snapshot.id, displayedQueue, !busy && !blocked && !writerBusy && state.canManageQueue(),
            canSteer = canOperate && snapshot.activeTurnId.isNotBlank() && snapshot.requests.isEmpty(),
            onEdit = ::editQueuedTask, onWithdraw = { removingQueue = it },
            onSteer = { task -> onQueue(JSONObject().put("operation", "steer").put("itemId", task.id).put("revision", task.revision).put("expectedTurnId", snapshot.activeTurnId), {}) },
            onReorder = { ids, expected -> onQueue(JSONObject().put("operation", "reorder").put("ids", JSONArray(ids)).put("expectedIds", JSONArray(expected)), {}) })
        if (pendingTitle != null) {
            LaunchedEffect(state.id) { onLoadModels() }
        }
        AnimatedVisibility(
            // Measure the composer first; settings scroll within the remaining height.
            // fill=false keeps the overlay compact when the keyboard is hidden.
            modifier = Modifier.weight(1f, fill = false),
            visible = pendingTitle != null && !busy && !pendingFirstTurnSent && !pendingReceiptUnresolved,
            enter = expandVertically(animationSpec = tween(160), expandFrom = Alignment.Bottom),
            exit = shrinkVertically(animationSpec = tween(160), shrinkTowards = Alignment.Bottom),
        ) {
            NewSessionSettings(models, effectiveModel, effectiveEffort, selectedSandbox, selectedApproval,
                enabled = !pendingReceiptUnresolved && !pendingFirstTurnSent && !busy && !blocked && !state.stale && pendingReady && draftLoaded,
                permissionsEditable = pendingPermissionsEditable,
                onModel = { modelId ->
                    selectedModel = modelId
                    val capability = models.firstOrNull { it.string("model").ifBlank { it.string("id") } == modelId }
                    selectedEffort = reasoningEffortForModel(capability, selectedEffort)
                }, onEffort = { selectedEffort = it },
                onSandbox = { selectedSandbox = it ?: "inherit" },
                onApproval = { selectedApproval = it ?: "inherit" }, workspace = pendingWorkspace,
                serviceTier = selectedServiceTier, defaultSettings = defaultSettings, onSaveDefaults = onSaveDefaults)
        }
        MessageComposer(
            tokenUsage = snapshot?.tokenUsage, contextThreadId = state.id,
            text = if (hideSubmitted) "" else draft, onTextChange = { if (textEditable) draft = it }, images = if (hideSubmitted) emptyList() else images,
            onEditImage = { focus.clearFocus(); keyboard?.hide(); editingImage = it },
            onRemoveImage = { image -> images = images.filterNot { it.id == image.id } },
            files = if (hideSubmitted) emptyList() else files, onRemoveFile = { reference -> files = files.filterNot { it === reference } },
            onOpenFile = { reference -> nativeFilePath(reference.string("path"), cwd)?.let(::openFile) },
            preparing = preparing, error = attachmentError, enabled = !busy && !inputLocked && draftLoaded,
            textEditable = textEditable, attachmentsEnabled = attachmentsEnabled,
            prediction = visiblePrediction,
            canSend = (attachmentsEnabled || images.isEmpty() && files.isEmpty()) && !(goalMode && displayedGoal == null) && !pendingReceiptUnresolved && !pendingFirstTurnSent && (pendingGoal == null || goalCapability == true) && (sendText.isNotBlank() || images.isNotEmpty() || files.isNotEmpty() || pendingGoal != null) && !state.stale && (snapshot != null || pendingTitle != null && pendingReady),
            canContinue = state.canContinueTask() && !blocked && !submissionInFlight, onContinue = onContinue,
            isRunning = snapshot?.activeTurnId?.isNotBlank() == true, canStop = !busy && !blocked && !state.stale && canOperate, busy = busy,
            onAddImage = { focus.clearFocus(); keyboard?.hide(); imagePickerTarget = state.id; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onAddFile = { focus.clearFocus(); keyboard?.hide(); filePickerTarget = state.id; filePicker.launch(arrayOf("*/*")) },
            onAddVideo = { focus.clearFocus(); keyboard?.hide(); filePickerTarget = state.id; filePicker.launch(arrayOf("video/*")) },
            onAddWorkspaceFile = { focus.clearFocus(); keyboard?.hide(); workspaceFileDialog = true }, onSettings = ::openSettings,
            discussionMode = (selectedMode ?: snapshot?.settings?.optJSONObject("collaborationMode")?.string("mode")) == "plan",
            goalMode = goalMode, goalModeEnabled = goalAvailable && goalEnabled,
            onSelectMode = { mode ->
                if (mode == "goal") {
                    selectedMode = "default"; goalModeSelected = true; goalEditing = true
                } else {
                    goalModeSelected = false
                    selectedMode = mode
                    if (pendingTitle != null) pendingGoal = null
                    else if (displayedGoal?.string("status") == "active") onGoal(JSONObject().put("operation", "pause"))
                }
            },
            focusRequest = inputFocusRequest, onPasteImages = { uris ->
                if (attachmentsEnabled) pendingImageUris = ArrayList((pendingImageUris + uris.map { it.toString() }).take(10))
            },
            settingsExpanded = settingsOpen, onDismissSettings = { settingsOpen = false }, settingsContent = {
                NativeComposerSettings(snapshot?.settings, models, effectiveModel, effectiveEffort, selectedMode, !pendingReceiptUnresolved && !pendingFirstTurnSent && !busy && !blocked && !state.stale && (state.canCompose() || pendingTitle != null && pendingReady) && draftLoaded,
                    onModel = { modelId ->
                        selectedModel = modelId
                        val capability = models.firstOrNull { it.string("model").ifBlank { it.string("id") } == modelId }
                        selectedEffort = reasoningEffortForModel(capability, selectedEffort ?: snapshot?.settings?.string("reasoningEffort"))
                        if (modelId != null) onSettingsChange?.invoke(JSONObject().put("model", modelId).apply { selectedEffort?.let { put("effort", it) } })
                    }, onEffort = { selectedEffort = it; if (it != null) onSettingsChange?.invoke(JSONObject().put("effort", it)) }, onInheritMode = { selectedMode = null },
                    serviceTier = selectedServiceTier ?: snapshot?.settings?.string("serviceTier"), onServiceTier = {
                        selectedServiceTier = it; onSettingsChange?.invoke(JSONObject().put("serviceTier", it))
                    }, sandboxChoice = if (pendingTitle != null) selectedSandbox else null,
                    approvalChoice = if (pendingTitle != null) selectedApproval else null,
                    newSession = pendingTitle != null, permissionsEditable = pendingPermissionsEditable,
                    onSandbox = { value ->
                        if (pendingTitle != null) selectedSandbox = value ?: "inherit"
                        else value?.let { onSettingsChange?.invoke(JSONObject().put("sandbox", it)) }
                    }, onApproval = { value ->
                        if (pendingTitle != null) selectedApproval = value ?: "inherit"
                        else value?.let { onSettingsChange?.invoke(JSONObject().put("approvalPolicy", it)) }
                    }, savesToSession = onSettingsChange != null, defaultSettings = defaultSettings, onSaveDefaults = onSaveDefaults,
                    catalogNotice = modelCatalogNotice)
            },
            onSend = {
                val targetId = state.id
                predictionConsumed = true
                val sentDraft = currentDraft
                submittedDraft = sentDraft
                targetId?.let { onPersistDraft(it, sentDraft) }
                val accepted = { if (currentSessionId == targetId && latestDraft == sentDraft) {
                    followLatest = true; pendingGoal = null; draft = ""; images = emptyList<DraftImage>(); files = emptyList<JSONObject>()
                    targetId?.let { onPersistDraft(it, ConversationDraft(model = sentDraft.model, effort = sentDraft.effort, mode = sentDraft.mode, serviceTier = sentDraft.serviceTier, sandbox = sentDraft.sandbox, approvalPolicy = sentDraft.approvalPolicy)) }
                }
                    Unit }
                if (onConfiguredSend != null) onConfiguredSend(nativeInputs(sendText.ifBlank { pendingGoal?.let { JSONObject(it).string("objective") }.orEmpty() }, images, files), effectiveModel, effectiveEffort, selectedMode, selectedServiceTier, JSONObject().apply {
                    if (pendingTitle != null) {
                        pendingGoal?.let { put("goal", JSONObject(it)) }
                        selectedSandbox?.takeUnless { it == "inherit" }?.let { put("sandbox", it) }
                        selectedApproval?.takeUnless { it == "inherit" }?.let { put("approvalPolicy", it) }
                    }
                }, accepted)
                else if (onSendWithMode != null) onSendWithMode(nativeInputs(sendText, images, files), effectiveModel, effectiveEffort, selectedMode, accepted)
                else onSend(nativeInputs(sendText, images, files), effectiveModel, effectiveEffort, accepted)
            },
            onStop = { stopConfirm = true },
            queueing = snapshot != null && canOperate && snapshot.shouldQueueInput,
        )
        }
        }
    }
    if (developerOpen && state.id != null) DeveloperToolsDialog(state.id, readTool, submitTool, busy || blocked || !canOperate, onDismiss = { developerOpen = false },
        canCleanup = snapshot?.activeTurnId?.isBlank() == true && snapshot.requests.isEmpty() && snapshot.queue.isEmpty() && canOperate)
    editingImage?.takeUnless { inputLocked }?.let { image ->
        key(image) {
            AttachmentImageEditor(image, onDismiss = { editingImage = null }, onComplete = { edited ->
                if (images.sumOf { it.bytes.size.toLong() } - image.bytes.size + edited.bytes.size <= 40L * 1024 * 1024) images = images.map { if (it.id == image.id) edited else it }
                else attachmentError = "图片总大小不能超过 40 MiB"
                editingImage = null
            })
        }
    }
    removingQueue?.let { task -> AlertDialog(onDismissRequest = { if (!busy) removingQueue = null },
        title = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Text(if (task.recoveryRequired) "忽略待恢复记录？" else "撤回待发任务？") } },
        text = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Text(if (task.recoveryRequired) "仅移除此队列记录，不会停止或重新发送原任务。请先核对会话历史。" else "该任务将从等待队列移除，不再执行。") } },
        confirmButton = { TextButton(onClick = { onQueue(JSONObject().put("operation", if (task.recoveryRequired) "dismiss" else "remove").put("itemId", task.id).put("revision", task.revision)) { removingQueue = null } }, enabled = !busy && !blocked && !writerBusy && state.canManageQueue(), modifier = Modifier.testTag("queue_remove")) { Text(if (task.recoveryRequired) "确认忽略" else "确认撤回") } },
        dismissButton = { TextButton(onClick = { removingQueue = null }) { Text("取消") } }) }
    filePath?.let { FilePreviewDialog(it, readFile, downloadFile, initialAction = fileAction) { filePath = null; fileAction = null } }
    if (workspaceFileDialog && !inputLocked) WorkspaceFilePicker(cwd, readDirectory,
        onDismiss = { workspaceFileDialog = false }, onSelect = { file ->
            if (files.size < 16 && files.none { it.string("path") == file.string("path") }) files = files + file
            workspaceFileDialog = false
        })
    if (stopConfirm) AlertDialog(onDismissRequest = { stopConfirm = false }, title = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Text("停止当前轮次？") } },
        text = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Text("将中断当前 Codex 轮次。已产生的内容和改动会保留；这不等同于暂停或清除持续目标，队列也会保留。") } },
        confirmButton = { Button(onClick = { stopConfirm = false; onStop() }, enabled = !busy && !blocked && canOperate) { Text("停止当前轮次") } },
        dismissButton = { TextButton(onClick = { stopConfirm = false }) { Text("取消") } })
    forwardingLink?.let { link -> CodexLinkDestinationDialog(link, readTool,
        onConfirm = { projectId, threadId, source, name -> forwardingLink = null; onForwardLink?.invoke(projectId, threadId, source, name) },
        onDismiss = { forwardingLink = null }) }
    }
}

internal fun nativePermissionSummary(settings: JSONObject): String {
    val sandbox = nativeSandbox(settings)
    val access = when (sandbox?.string("type")) {
        "dangerFullAccess", "danger-full-access" -> "完整文件访问"
        "workspaceWrite", "workspace-write" -> "工作区读写"
        "readOnly", "read-only" -> "只读"
        "externalSandbox", "external-sandbox" -> "外部沙盒"
        else -> if (sandbox?.string("type").isNullOrBlank()) "文件访问权限：尚未读取" else "文件访问权限：原生自定义配置"
    }
    val approval = when (val policy = settings.opt("approvalPolicy")) {
        "never" -> "原生审批：不要求"
        "on-request" -> "原生审批：按需"
        "on-failure" -> "原生审批：失败时"
        "untrusted" -> "原生审批：不受信任操作"
        is JSONObject -> if (policy.length() > 0) "原生审批：细粒度规则" else "原生审批：尚未读取"
        is String -> if (policy.isNotBlank()) "原生审批：自定义规则" else "原生审批：尚未读取"
        else -> "原生审批：尚未读取"
    }
    return "$access\n$approval"
}
