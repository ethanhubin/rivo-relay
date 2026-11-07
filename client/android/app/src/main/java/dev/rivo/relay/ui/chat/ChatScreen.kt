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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.codex.control
import dev.rivo.relay.codex.canCompose
import dev.rivo.relay.data.*
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

private class ChatDraftPersistence(var draft: ConversationDraft, var loaded: Boolean,
    var persist: (String, ConversationDraft) -> Unit)

private val chatActivitySpacing = 6.dp
private val chatSectionSpacing = 12.dp

@Composable
fun ChatScreen(state: SessionState, busy: Boolean, blocked: Boolean, models: List<JSONObject>, onBack: () -> Unit,
    onSend: (JSONArray, String?, String?, () -> Unit) -> Unit, onStop: () -> Unit,
    onRespond: (NativeRequest, JSONObject) -> Unit, onRefresh: () -> Unit, onLoadModels: () -> Unit, onEarlier: () -> Unit,
    readFile: suspend (String) -> JSONObject,
    readDirectory: suspend (String) -> JSONObject = { error("节点不支持目录浏览") },
    uploadFile: suspend (String, String, java.io.File) -> JSONObject = { _, _, _ -> error("节点不支持文件上传") },
    downloadFile: suspend (String, java.io.OutputStream, (Long, Long) -> Unit) -> Unit = { _, _, _ -> error("节点不支持文件下载") },
    onQueue: (JSONObject, () -> Unit) -> Unit = { _, _ -> },
    onGoal: (JSONObject) -> Unit = {},
    draftStore: ConversationStore? = null, onPersistDraft: (String, ConversationDraft) -> Unit = { _, _ -> },
    onSendWithMode: ((JSONArray, String?, String?, String?, () -> Unit) -> Unit)? = null,
    readTool: suspend (String) -> JSONObject = { error("节点工具不可用") },
    submitTool: (String, JSONObject, () -> Unit) -> Unit = { _, _, _ -> },
    usage: CodexUsage? = null, onFillHistory: () -> Unit = {}, pinned: Boolean = false, canManage: Boolean = false,
    onPin: ((Boolean) -> Unit)? = null, onArchive: (() -> Unit)? = null,
    pendingTitle: String? = null, pendingReady: Boolean = true, pendingGoalApplied: Boolean = false, pendingFirstTurnSent: Boolean = false,
    pendingReceiptUnresolved: Boolean = false, onReconcilePending: (() -> Unit)? = null,
    readGoalCapability: (suspend () -> Boolean)? = null, onOpenPendingThread: (() -> Unit)? = null, pendingPermissionsEditable: Boolean = true,
    pendingWorkspace: (@Composable () -> Unit)? = null,
    onConfiguredSend: ((JSONArray, String?, String?, String?, String?, JSONObject, () -> Unit) -> Unit)? = null,
    onSettingsChange: ((JSONObject) -> Unit)? = null,
    takeoverBlockedReason: String? = null, nodeName: String = "当前节点", writerBusy: Boolean = false, onWriterAction: ((String) -> Unit)? = null,
    defaultSettings: JSONObject? = null, onSaveDefaults: ((JSONObject) -> Unit)? = null, modelCatalogNotice: String? = null,
    onForwardLink: ((String, String?, String, String) -> Unit)? = null, draftReloadKey: Long = 0, pendingCwd: String = "",
    mediaApi: dev.rivo.relay.data.RelayApi? = null,
    cachedFile: (suspend (String, (Long, Long) -> Unit) -> java.io.File)? = null) {
    val mediaContext = androidx.compose.ui.platform.LocalContext.current
    val mediaClient = remember(mediaApi, state.id) { mediaApi?.let { dev.rivo.relay.data.CodexMediaClient(it, state.id.orEmpty(), mediaContext) } }
    DisposableEffect(mediaClient) { onDispose { mediaClient?.close() } }
    val snapshot = state.snapshot
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
            focus.clearFocus(); keyboard?.hide(); settingsOpen = false
            editingImage = null; workspaceFileDialog = false; removingQueue = null; stopConfirm = false
        }
    }
    fun openFile(path: String) { fileAction = null; filePath = path }
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
            selectedSandbox = selectedSandbox ?: defaultSettings?.string("sandbox")?.takeIf(String::isNotBlank) ?: "inherit"
            selectedApproval = selectedApproval ?: defaultSettings?.string("approvalPolicy")?.takeIf(String::isNotBlank) ?: "inherit"
            selectedModel = selectedModel ?: defaultSettings?.string("model")?.takeIf(String::isNotBlank)
            selectedEffort = selectedEffort ?: defaultSettings?.string("effort")?.takeIf(String::isNotBlank)
            selectedServiceTier = selectedServiceTier ?: defaultSettings?.string("serviceTier")?.takeIf(String::isNotBlank)
        }
        draftLoaded = true
    }
    val currentDraft = ConversationDraft(draft, images.map { StoredDraftImage(it.id, it.bytes) }, files, selectedModel, selectedEffort, selectedMode, selectedServiceTier, selectedSandbox, selectedApproval, pendingGoal)
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
    LaunchedEffect(state.id, draftLoaded, draft, images, files, selectedModel, selectedEffort, selectedMode, selectedServiceTier) {
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
    val timeline = remember(snapshot?.messages, snapshot?.turnSettings, state.pendingMessages, snapshot?.activeTurnId, state.stale) {
        nativeTimelineRows(projectedSessionMessages(snapshot, state.pendingMessages), snapshot?.activeTurnId?.takeUnless { state.stale }, snapshot?.turnSettings.orEmpty())
    }
    val executionActive = !state.stale && snapshot?.isOngoing == true && snapshot.status != "waiting" && snapshot.requests.isEmpty()
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
    LaunchedEffect(list, state.id, state.historyCursor, state.loading, state.loadingEarlier, state.stale, snapshot?.historyReady) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.size > 0 && !list.canScrollForward && !list.canScrollBackward }
            .collect { unfilled -> if (unfilled && !state.loading && !state.loadingEarlier && !state.stale && snapshot?.historyReady == true && state.historyCursor != null) onFillHistory() }
    }
    val showLatest by remember(list) { derivedStateOf { list.canScrollForward } }
    val quoteText: ((String) -> Unit)? = if (!busy && !inputLocked && draftLoaded) ({ text ->
        draft = appendQuoteToDraft(draft, text); inputFocusRequest++
    }) else null
    val saveFile: (String) -> Unit = { fileAction = FileAction.SAVE; filePath = it }
    fun openSettings() { if (inputLocked) return; settingsOpen = !settingsOpen; if (settingsOpen) onLoadModels() }
    val mediaFavorite: (suspend (String, Boolean?) -> dev.rivo.relay.data.MediaFavoriteResult)? = mediaClient?.let { client ->
        { source, desired -> client.favorite(source, desired) }
    }
    val mediaCache: NativeCachedDownload? = mediaClient?.let { client ->
        { path, progress ->
            if (isHttpWebLink(path) || path.startsWith("/v1/media/")) client.cached(mediaContext, path, progress)
            else requireNotNull(cachedFile)(path, progress)
        }
    } ?: cachedFile
    val mediaAttach: suspend (java.io.File, String, String) -> Boolean = attach@{ file, name, mime ->
        val target = state.id ?: return@attach false
        if (busy || inputLocked || preparing || !draftLoaded) return@attach false
        if (mime.startsWith("image/")) {
            val image = prepareImage(context, android.net.Uri.fromFile(file))
            if (currentSessionId != target || latestInputLocked) return@attach false
            require(images.size < 10 && images.sumOf { it.bytes.size.toLong() } + image.bytes.size <= 40L * 1024 * 1024) { "最多 10 张图片，合计 40 MiB" }
            images = images + image
        } else {
            require(files.size < 16) { "最多 16 个附件" }
            val uploaded = uploadFile(target, name, file)
            if (currentSessionId != target || latestInputLocked) return@attach false
            files = files + JSONObject().put("type", "mention").put("name", uploaded.getString("name")).put("path", uploaded.getString("path"))
        }
        true
    }
    CompositionLocalProvider(LocalMediaAttachment provides mediaAttach, LocalMediaAssets provides mediaAssets, LocalMediaSourceIndex provides mediaSources, LocalCodexGallery provides gallery,
        LocalMediaEdited provides { uri -> addImages(listOf(uri)) }, LocalCodexMediaClient provides mediaClient,
        LocalImageFavorite provides mediaFavorite, LocalNativeFileCache provides mediaCache,
        LocalCodexLinkForwarder provides if (onForwardLink == null || busy || blocked) null else { link -> forwardingLink = link }) {
    Column(Modifier.fillMaxSize().imePadding().testTag("chat_page")) {
        if (pendingTitle != null) Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("back_sessions")) { Text("‹") }
            Column { Text("新会话", style = MaterialTheme.typography.titleLarge); Text(pendingTitle, style = MaterialTheme.typography.bodySmall) }
        } else NativeChatHeader(state, usage, onBack, ::openSettings, onRefresh, { developerOpen = true }, { stopConfirm = true },
            !busy && !blocked && !state.stale && canOperate, pinned, canManage, onPin, onArchive)
        if (pendingTitle == null) NativeWriterStatus(state, nodeName, writerBusy || busy || blocked, onWriterAction, takeoverBlockedReason, onRefresh)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection).testTag("chat_history"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(chatActivitySpacing)) {
            if (pendingTitle != null && timeline.isEmpty()) item {
                Text("今天想从哪里开始？", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.historyCursor != null) item { TextButton(onClick = { followLatest = false; onEarlier() }, enabled = !state.loadingEarlier && snapshot?.historyReady == true, modifier = Modifier.fillMaxWidth().testTag("load_earlier")) { Text(if (snapshot?.historyReady != true) "历史同步中…" else if (state.loadingEarlier) "正在加载…" else "加载更早的消息") } }
            if (!state.loading && snapshot != null && timeline.isEmpty()) item {
                Text("继续这个项目中的工作", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(timeline, key = { _, row -> row.key }) { index, row ->
                val modelChange = modelChangeRows[row.key]
                val consecutiveActivities = row is NativeTimelineRow.Activities &&
                    timeline.getOrNull(index - 1) is NativeTimelineRow.Activities && modelChange == null
                if ((index > 0 || state.historyCursor != null) && !consecutiveActivities) {
                    Spacer(Modifier.height(chatSectionSpacing - chatActivitySpacing))
                }
                modelChange?.let { label -> Text(label,
                    Modifier.fillMaxWidth().padding(bottom = chatActivitySpacing).testTag("model_change_notice"),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (row is NativeTimelineRow.Activities) NativeActivityGroup(row, isCurrent = row.key == currentActivity?.key) { path -> nativeFilePath(path, cwd)?.let(::openFile) }
                else if (row is NativeTimelineRow.Notice) NativeTurnNotice(row.turn)
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
                            if (content.isEmpty()) MessageContent(message.text, cwd, onQuoteText = quoteText, onSaveFile = saveFile, inlineImage = { source ->
                                val target = if (isHttpWebLink(source)) source else nativeFilePath(source, cwd)
                                target?.let { NativeImage(it, readFile, downloadFile = downloadFile) }
                            }, onFile = ::openFile)
                            content.forEach { part -> val imageSource = nativeImageSource(part, cwd)
                                if (imageSource != null) NativeImage(imageSource, readFile, downloadFile = downloadFile) else when (part.string("type")) {
                                "text" -> MessageContent(part.string("text"), cwd, onQuoteText = quoteText, onSaveFile = saveFile, inlineImage = { source ->
                                val target = if (isHttpWebLink(source)) source else nativeFilePath(source, cwd)
                                target?.let { NativeImage(it, readFile, downloadFile = downloadFile) }
                            }, onFile = ::openFile)
                                "image" -> part.string("url").takeIf { it.isNotBlank() }?.let { NativeImage(it, readFile, downloadFile = downloadFile) } ?: Text("图片引用：${part.string("fileId")}")
                                "localImage" -> NativeImage(nativeFilePath(part.string("path"), cwd) ?: part.string("path"), readFile, downloadFile = downloadFile)
                                "mention", "skill" -> TextButton(onClick = { nativeFilePath(part.string("path"), cwd)?.let(::openFile) }) { Text((if (part.string("type") == "skill") "技能：" else "文件：") + part.string("name")) }
                                else -> Text(part.toString(2), style = MaterialTheme.typography.bodySmall)
                            } }
                            message.raw.string("relayDelivery").takeIf(String::isNotBlank)?.let { delivery ->
                                Text(when (delivery) { "submitting" -> "发送中…"; "accepted" -> "已送达，等待同步"; else -> "正在确认是否送达" },
                                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("message_delivery_$delivery"))
                            }
                        }
                    } else if (assistant) {
                        val isLastReply = snapshot?.messages?.lastOrNull {
                            it.turnId == message.turnId && it.type in setOf("agentMessage", "agent_message", "exitedReviewMode")
                        }?.id == message.id
                        val timing = nativeMessageTiming(message, snapshot?.turnSettings?.firstOrNull { it.turnId == message.turnId }, isLastReply)
                        timing.elapsedSeconds?.let { seconds ->
                            Text("已思考 ${formatNativeThinkingDuration(seconds)}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("message_thinking_time"))
                        }
                        MessageContent(message.text, cwd, onQuoteText = quoteText, onSaveFile = saveFile, inlineImage = { source ->
                                val target = if (isHttpWebLink(source)) source else nativeFilePath(source, cwd)
                                target?.let { NativeImage(it, readFile, downloadFile = downloadFile) }
                            }, onFile = ::openFile)
                        if (snapshot?.messages?.lastOrNull { it.turnId == message.turnId && it.type in setOf("agentMessage", "agent_message", "exitedReviewMode") }?.id == message.id) {
                            ChangeSummary(snapshot.changes.firstOrNull { it.string("turnId") == message.turnId }) { developerOpen = true }
                            ReviewDiagnostic(snapshot.reviewDiagnostics.lastOrNull { it.string("turnId") == message.turnId })
                        }
                        AssistantMessageFooter(message.text, "${message.turnId}:${message.id}", timing.outputAt)
                    }
                    else if (message.type == "imageGeneration" && (message.raw.string("savedPath").isNotBlank() || message.raw.string("result").isNotBlank())) {
                        generatedImageSource(message, mediaIndex)?.let { NativeImage(it, readFile, downloadFile = downloadFile, identity = "${message.turnId}:${message.id}") }
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
            }
            snapshot?.reviewDiagnostics?.filter { diagnostic ->
                diagnostic.string("status") != "verified" && snapshot.messages.none {
                    it.turnId == diagnostic.string("turnId") && it.type in setOf("agentMessage", "agent_message", "exitedReviewMode")
                }
            }?.forEach { diagnostic -> item(key = "review-diagnostic:${diagnostic.string("turnId")}") { ReviewDiagnostic(diagnostic) } }
            if (executionActive && currentActivity == null) item(key = "execution_stage") {
                ChatTimelineSummaryRow {
                    ShimmeringStageText("正在处理…", Modifier.testTag("execution_stage_shimmer"))
                }
            }
            item(key = "relay_latest_anchor") { Spacer(Modifier.fillMaxWidth().height(1.dp).testTag("latest_message_anchor")) }
        }
        if (showLatest) SmallFloatingActionButton(
            onClick = { followLatest = true; scope.launch { list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("jump_to_latest")
                .semantics { contentDescription = "回到最新消息" },
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ) { Text("↓", style = MaterialTheme.typography.titleLarge) }
        }
        pendingWorkspace?.invoke()
        if (snapshot?.requests?.isNotEmpty() == true) Column(Modifier.fillMaxWidth().heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).testTag("native_requests_panel")) {
            snapshot.requests.forEach { request -> key(request.key) {
                NativeRequestPanel(request, !busy && !blocked && !state.stale && canOperate) { onRespond(request, it) }
            } }
        }
        var goalEditing by remember(state.id) { mutableStateOf(false) }
        val goalAvailable = (pendingTitle != null && (goalCapability == true || pendingGoal != null)) || snapshot?.goalSupported == true
        val goalEnabled = !pendingReceiptUnresolved && !pendingFirstTurnSent && !busy && !blocked && !state.stale && draftLoaded && (pendingTitle != null || canOperate)
        val displayedGoal = if (pendingTitle != null) pendingGoal?.let { JSONObject(it).put("status", "pending") } else snapshot?.goal
        if (goalAvailable) key(state.id) {
            NativeGoalPanel(displayedGoal, goalEnabled, { action ->
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
            Text("该会话仍有未确认提交。结束手机等待不会取消原操作，请先核对结果。", style = MaterialTheme.typography.bodySmall)
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
        snapshot?.recoveryTask?.let { task ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("queue_recovery_panel")) {
                Text("任务待恢复", style = MaterialTheme.typography.titleSmall)
                Text(task.text, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text("检查后继续会先核对历史和当前状态；忽略仅移除此待处理记录。", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { onQueue(JSONObject().put("operation", "recover").put("itemId", task.id).put("revision", task.revision), {}) },
                        enabled = !busy && !blocked && !state.stale && canOperate && snapshot.status == "idle" && !snapshot.isOngoing && snapshot.requests.isEmpty(),
                        modifier = Modifier.testTag("recover_queue_task")) { Text("检查后继续") }
                    TextButton(onClick = { removingQueue = task }, enabled = !busy && !blocked && !state.stale,
                        modifier = Modifier.testTag("dismiss_recovery_task")) { Text("忽略记录") }
                }
            }
        }
        val waitingQueue = snapshot?.queue.orEmpty().filter { it.status !in setOf("running", "dispatching") && !it.recoveryRequired && it.id != snapshot?.recoveryTask?.id }
        if (snapshot != null && waitingQueue.isNotEmpty()) ExecutionQueuePanel(snapshot.id, waitingQueue, !busy && !blocked && canOperate,
            canSteer = canOperate && snapshot.activeTurnId.isNotBlank() && snapshot.requests.isEmpty(),
            onEdit = ::editQueuedTask, onWithdraw = { removingQueue = it },
            onSteer = { task -> onQueue(JSONObject().put("operation", "steer").put("itemId", task.id).put("revision", task.revision).put("expectedTurnId", snapshot.activeTurnId), {}) },
            onReorder = { ids, expected -> onQueue(JSONObject().put("operation", "reorder").put("ids", JSONArray(ids)).put("expectedIds", JSONArray(expected)), {}) })
        MessageComposer(
            text = draft, onTextChange = { draft = it }, images = images,
            onEditImage = { focus.clearFocus(); keyboard?.hide(); editingImage = it },
            onRemoveImage = { image -> images = images.filterNot { it.id == image.id } },
            files = files, onRemoveFile = { reference -> files = files.filterNot { it === reference } },
            onOpenFile = { reference -> nativeFilePath(reference.string("path"), cwd)?.let(::openFile) },
            preparing = preparing, error = attachmentError, enabled = !busy && !inputLocked && draftLoaded,
            canSend = !pendingReceiptUnresolved && !pendingFirstTurnSent && (pendingGoal == null || goalCapability == true) && (draft.isNotBlank() || images.isNotEmpty() || files.isNotEmpty() || pendingGoal != null) && !state.stale && (snapshot != null || pendingTitle != null && pendingReady),
            isRunning = snapshot?.activeTurnId?.isNotBlank() == true, canStop = !busy && !blocked && !state.stale && canOperate, busy = busy,
            onAddImage = { focus.clearFocus(); keyboard?.hide(); imagePickerTarget = state.id; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onAddFile = { focus.clearFocus(); keyboard?.hide(); filePickerTarget = state.id; filePicker.launch(arrayOf("*/*")) },
            onAddVideo = { focus.clearFocus(); keyboard?.hide(); filePickerTarget = state.id; filePicker.launch(arrayOf("video/*")) },
            onAddWorkspaceFile = { focus.clearFocus(); keyboard?.hide(); workspaceFileDialog = true }, onSettings = ::openSettings,
            discussionMode = (selectedMode ?: snapshot?.settings?.optJSONObject("collaborationMode")?.string("mode")) == "plan",
            onToggleMode = { selectedMode = if ((selectedMode ?: snapshot?.settings?.optJSONObject("collaborationMode")?.string("mode")) == "plan") "default" else "plan" },
            goalControl = if (goalAvailable) { {
                IconButton(onClick = { goalEditing = true },
                    enabled = goalEnabled,
                    modifier = Modifier.size(44.dp).testTag("create_goal")
                        .semantics { contentDescription = if (displayedGoal == null) "设置持续目标" else "编辑持续目标" }) {
                    val color = if (goalEnabled && displayedGoal != null && displayedGoal.string("status") in setOf("active", "pending")) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                        drawCircle(color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        drawCircle(color, radius = size.minDimension * 0.28f, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        drawCircle(color, radius = 2.dp.toPx())
                    }
                }
            } } else null,
            focusRequest = inputFocusRequest, onPasteImages = ::addImages,
            settingsExpanded = settingsOpen, onDismissSettings = { settingsOpen = false }, settingsContent = {
                NativeComposerSettings(snapshot?.settings, models, selectedModel, selectedEffort, selectedMode, !pendingReceiptUnresolved && !pendingFirstTurnSent && !busy && !blocked && !state.stale && (state.canCompose() || pendingTitle != null && pendingReady) && draftLoaded,
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
                val sentDraft = currentDraft
                targetId?.let { onPersistDraft(it, sentDraft) }
                val accepted = { if (currentSessionId == targetId && latestDraft == sentDraft) {
                    followLatest = true; pendingGoal = null; draft = ""; images = emptyList<DraftImage>(); files = emptyList<JSONObject>()
                    targetId?.let { onPersistDraft(it, ConversationDraft(model = sentDraft.model, effort = sentDraft.effort, mode = sentDraft.mode, serviceTier = sentDraft.serviceTier, sandbox = sentDraft.sandbox, approvalPolicy = sentDraft.approvalPolicy)) }
                }
                    Unit }
                if (onConfiguredSend != null) onConfiguredSend(nativeInputs(draft.ifBlank { pendingGoal?.let { JSONObject(it).string("objective") }.orEmpty() }, images, files), selectedModel, selectedEffort, selectedMode, selectedServiceTier, JSONObject().apply {
                    if (pendingTitle != null) {
                        pendingGoal?.let { put("goal", JSONObject(it)) }
                        selectedSandbox?.takeUnless { it == "inherit" }?.let { put("sandbox", it) }
                        selectedApproval?.takeUnless { it == "inherit" }?.let { put("approvalPolicy", it) }
                    }
                }, accepted)
                else if (onSendWithMode != null) onSendWithMode(nativeInputs(draft, images, files), selectedModel, selectedEffort, selectedMode, accepted)
                else onSend(nativeInputs(draft, images, files), selectedModel, selectedEffort, accepted)
            },
            onStop = { stopConfirm = true },
            queueing = snapshot != null && canOperate && snapshot.shouldQueueInput,
        )
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
        title = { Text(if (task.recoveryRequired) "忽略待恢复记录？" else "撤回待发任务？") },
        text = { Text(if (task.recoveryRequired) "仅移除此队列记录，不会停止或重新发送原任务。请先核对会话历史。" else "该任务将从等待队列移除，不再执行。") },
        confirmButton = { TextButton(onClick = { onQueue(JSONObject().put("operation", if (task.recoveryRequired) "dismiss" else "remove").put("itemId", task.id).put("revision", task.revision)) { removingQueue = null } }, enabled = !busy && !blocked && !state.stale && (canOperate || task.recoveryRequired), modifier = Modifier.testTag("queue_remove")) { Text(if (task.recoveryRequired) "确认忽略" else "确认撤回") } },
        dismissButton = { TextButton(onClick = { removingQueue = null }) { Text("取消") } }) }
    filePath?.let { FilePreviewDialog(it, readFile, downloadFile, initialAction = fileAction) { filePath = null; fileAction = null } }
    if (workspaceFileDialog && !inputLocked) WorkspaceFilePicker(cwd, readDirectory,
        onDismiss = { workspaceFileDialog = false }, onSelect = { file ->
            if (files.size < 16 && files.none { it.string("path") == file.string("path") }) files = files + file
            workspaceFileDialog = false
        })
    if (stopConfirm) AlertDialog(onDismissRequest = { stopConfirm = false }, title = { Text("停止当前轮次？") },
        text = { Text("将中断当前 Codex 轮次。已产生的内容和改动会保留；这不等同于暂停或清除持续目标，队列也会保留。") },
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
