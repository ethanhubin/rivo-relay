package dev.rivo.relay.codex

import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One active thread owns reads and subscription; switch cancels both and rejects late snapshots. */
class SessionController(private val api: RelayApi, private val scope: CoroutineScope,
    private val store: ConversationStore? = null,
    private val onSnapshot: (String, org.json.JSONObject) -> Unit = { _, _ -> },
    private val onVisible: (String?) -> Unit = {},
    private val slowReadMillis: Long = 3000,
    private val readTimeoutMillis: Long = JSON_READ_TIMEOUT_MILLIS,
    private val sync: NodeSessionSync? = null,
    private val pollIntervalMillis: Long = 5000,
    private val failure: (Throwable) -> Unit) {
    private val eventSource = sync ?: NodeSessionSync(api, scope)
    private val mutable = MutableStateFlow(SessionState())
    val state = mutable.asStateFlow()
    // Node-owned recent views avoid disk IO and reparsing on A/B/A switches.
    // These are presentation snapshots only; every open still verifies control.
    private val recentViews = linkedMapOf<String, SessionState>()
    private val pendingByThread = linkedMapOf<String, List<PendingNativeMessage>>()
    private val changedPendingClients = mutableSetOf<String>()
    private val resolvedPending = mutableMapOf<String, org.json.JSONObject>()
    private var pendingLoaded = false
    private var activeJob: Job? = null
    private var activeRead: Deferred<org.json.JSONObject>? = null
    private var updates: Channel<Unit>? = null
    private var generation = 0L
    private var controlEpoch = 0L
    private var historyEpoch = 0L
    private var historyJob: Job? = null
    private var historyDepthTarget: SessionSnapshot? = null
    private val requestedHistoryCursors = mutableSetOf<String>()
    init {
        require(pollIntervalMillis > 0)
        scope.launch {
            try {
                store?.loadPendingMessages().orEmpty().forEach { saved ->
                    if (saved.clientId !in changedPendingClients) {
                        val response = resolvedPending[saved.submissionId]
                        val message = if (response != null) resolveMessage(saved, response) ?: return@forEach
                            else if (saved.delivery == "submitting") saved.copy(delivery = "unknown") else saved
                        pendingByThread[message.threadId] = pendingByThread[message.threadId].orEmpty() + message
                        publishPending(message.threadId)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e) }
            finally { pendingLoaded = true; changedPendingClients.clear(); resolvedPending.clear() }
        }
    }
    private fun isCurrent(id: String, targetGeneration: Long) = mutable.value.id == id && generation == targetGeneration
    private fun message(error: Exception): String? = if (error is CancellationException) null else if (error is ApiException) error.message else api.describeFailure(error)
    private fun invalidateHistory() { historyEpoch++; historyJob?.cancel(); historyJob = null; historyDepthTarget = null; requestedHistoryCursors.clear() }
    fun dismissReadError() { mutable.update { it.transition(SessionSyncEvent.DismissError).copy(historyError = null) } }
    fun open(id: String, online: Boolean = true) {
        pause()
        val targetGeneration = generation
        val previous = mutable.value
        val retained = if (previous.id == id) previous else recentViews.remove(id)
        mutable.value = (retained ?: SessionState(id = id)).transition(SessionSyncEvent.Open(online)).copy(
            loadingEarlier = false, pendingMessages = pendingByThread[id].orEmpty(), historyError = null)
        onVisible(id)
        if (!online) {
            // Cancel the old transport immediately; retain read-only content while no route exists.
            activeJob = scope.launch {
                var cached: SessionSnapshot? = null
                try { if (mutable.value.snapshot == null) cached = store?.loadSnapshot(id)?.let { withContext(Dispatchers.Default) { sessionSnapshot(it) } } }
                catch (e: Exception) { if (e is CancellationException) throw e }
                if (isCurrent(id, targetGeneration)) mutable.update {
                    it.copy(snapshot = it.snapshot ?: cached)
                }
            }
            return
        }
        val signals = Channel<Unit>(Channel.CONFLATED); updates = signals
        activeJob = scope.launch {
            var reading = false
            val headCache = SessionHeadCache()
            var cachedHistoryEpoch = historyEpoch
            val pendingDeltas = mutableListOf<org.json.JSONObject>()
            val deltaSignals = Channel<Unit>(Channel.CONFLATED)
            val deltaLock = Mutex()
            var indexedMessages: List<NativeMessage>? = null
            var messageIndex: NativeMessageIndex? = null
            suspend fun flushDeltas() = deltaLock.withLock {
                if (pendingDeltas.isEmpty()) return@withLock
                val batch = pendingDeltas.toList(); pendingDeltas.clear()
                val current = mutable.value
                val snapshot = current.snapshot
                if (reading || current.stale || snapshot == null) { signals.trySend(Unit); return@withLock }
                val capturedControl = controlEpoch
                val capturedHistory = historyEpoch
                val updated = withContext(Dispatchers.Default) {
                    if (indexedMessages !== snapshot.messages) {
                        messageIndex = NativeMessageIndex(snapshot.messages)
                        indexedMessages = snapshot.messages
                    }
                    snapshot.applyLiveDeltas(batch, messageIndex!!)
                }
                if (updated == null || !isCurrent(id, targetGeneration) || controlEpoch != capturedControl ||
                    historyEpoch != capturedHistory || mutable.value.stale || mutable.value.snapshot !== snapshot) {
                    signals.trySend(Unit); return@withLock
                }
                indexedMessages = updated.messages
                mutable.update { it.copy(snapshot = updated) }
            }
            // Match QiGuang's 150 ms live presentation cadence; terminal events flush immediately.
            launch {
                for (signal in deltaSignals) {
                    delay(150)
                    // Drain before projection; arrivals during projection retain their next wakeup.
                    deltaSignals.tryReceive()
                    flushDeltas()
                }
            }
            // Subscribe before first snapshot. Buffered notifications coalesce into another authoritative read.
            launch {
                eventSource.events(onInterrupted = { error ->
                    flushDeltas()
                    if (isCurrent(id, targetGeneration)) {
                        invalidateControl("连接已中断，正在重新确认操作权限")
                        mutable.update { it.transition(SessionSyncEvent.StreamInterrupted) }
                        if (error is ApiException && error.status == 401) failure(error)
                    }
                }).collect { event ->
                    if (isCurrent(id, targetGeneration)) {
                        val params = event.payload.optJSONObject("params")
                        val method = event.payload.string("method")
                        val eventThread = params?.string("threadId")?.ifBlank { params.string("conversationId") }.orEmpty()
                        val eventThreads = params?.optJSONArray("threadIds")
                        // Empty threadIds means a metadata-only disconnect. It must not
                        // disable an unrelated live writer or trigger its history reads.
                        if (eventThread.isNotEmpty() && eventThread != id) return@collect
                        if (eventThreads != null && (0 until eventThreads.length()).none { eventThreads.optString(it) == id }) return@collect
                        val writerDisconnected = event.type == "native" && method in setOf("relay/writerDisconnected", "relay/nativeDisconnected")
                        if (writerDisconnected) {
                            pendingDeltas.clear()
                            invalidateControl("会话 writer 已断开，正在重新核验恢复条件")
                            mutable.update { it.copy(snapshot = it.snapshot?.copy(requests = emptyList())) }
                        }
                        if (event.type == "native" && params?.has("relayOffset") == true &&
                            supportsLiveDelta(method)) {
                            pendingDeltas += event.payload
                            deltaSignals.trySend(Unit)
                            if (pendingDeltas.size >= 128) flushDeltas()
                            return@collect
                        }
                        if (event.type == "native" && event.payload.string("method") == "relay/ownershipChanged")
                            invalidateControl("会话权限已变化，正在重新确认")
                        val historyInvalidated = writerDisconnected || event.type == "reset" || (event.type == "native" &&
                            event.payload.string("method") == "relay/historyInvalidated")
                        if (historyInvalidated) pendingDeltas.clear() else flushDeltas()
                        if (historyInvalidated) {
                            invalidateHistory()
                            mutable.update { it.transition(SessionSyncEvent.HistoryInvalidated).copy(earlierLoaded = false, historyCursor = null,
                                loadingEarlier = false, historyError = null) }
                        }
                        signals.trySend(Unit)
                    }
                }
            }
            if (mutable.value.snapshot == null) {
                try {
                    val cached = store?.loadSnapshot(id)?.let { withContext(Dispatchers.Default) { sessionSnapshot(it) } }
                    if (cached != null && isCurrent(id, targetGeneration)) mutable.update { it.copy(snapshot = cached) }
                } catch (e: Exception) { if (e is CancellationException) throw e }
            }
            signals.trySend(Unit)
            while (isActive) {
                // Start the fallback interval after the previous read finishes.
                // A slow transfer must not queue its own immediate full reread.
                val signal = withTimeoutOrNull(pollIntervalMillis) { signals.receiveCatching() }
                if (signal?.isClosed == true) break
                if (cachedHistoryEpoch != historyEpoch) { headCache.clear(); cachedHistoryEpoch = historyEpoch }
                val knownHeadRevision = headCache.revision
                val requestHistoryEpoch = historyEpoch
                val requestControlEpoch = controlEpoch
                mutable.update { it.transition(SessionSyncEvent.ReadStarted) }
                val readStarted = System.nanoTime()
                val slowRead = launch {
                    delay(slowReadMillis)
                    if (isCurrent(id, targetGeneration) && requestControlEpoch == controlEpoch && requestHistoryEpoch == historyEpoch) mutable.update {
                        it.transition(SessionSyncEvent.ReadSlow)
                    }
                }
                try {
                    reading = true
                    val response = supervisorScope {
                        val read = async {
                            // Keep control confirmation and live refresh independent of old context.
                            // Two turns match the acknowledged-head protocol; unchanged polls
                            // omit item payloads. Older turns use cursor pagination/viewport fill.
                            withTimeout(readTimeoutMillis) { sync?.read(id, freshAfterNanos = readStarted, limit = 2, knownHeadRevision = knownHeadRevision)
                                ?: api.call("/v1/threads/${encoded(id)}?limit=2" +
                                    knownHeadRevision?.let { "&knownHeadRevision=${encoded(it)}" }.orEmpty()) }
                        }
                        activeRead = read
                        try { read.await() } finally { if (activeRead === read) activeRead = null }
                    }
                    val resolved = withContext(Dispatchers.Default) { headCache.resolve(response) }
                    val json = resolved.json
                    val head = resolved.snapshot
                    require(head.id == id) { "节点返回了其他会话，请重新同步" }
                    if ((System.nanoTime() - readStarted) / 1_000_000 >= readTimeoutMillis)
                        throw ApiException(408, "control_timeout", "会话同步超时，等待重新确认操作权限")
                    if (requestControlEpoch != controlEpoch) { signals.trySend(Unit); continue }
                    if (isCurrent(id, targetGeneration) && requestHistoryEpoch == historyEpoch) {
                        val previous = mutable.value
                        val revisionChanged = head.historyReady && head.nativeRevision != null &&
                            previous.snapshot?.nativeRevision != head.nativeRevision
                        val restoreTarget = if (revisionChanged) historyDepthTarget ?: previous.snapshot?.takeIf { previous.earlierLoaded } else null
                        val snapshot = head
                        val keepEarlier = previous.earlierLoaded && (snapshot.nativeRevision == null || snapshot.nativeRevision == previous.snapshot?.nativeRevision)
                        val projected = withContext(Dispatchers.Default) {
                            when {
                                !snapshot.historyReady && previous.snapshot != null -> snapshot.copy(
                                    messages = mergeConvergingMessages(previous.snapshot.messages, snapshot.messages),
                                    turnSettings = mergeConvergingTurnSettings(previous.snapshot.turnSettings, snapshot.turnSettings),
                                    reviewDiagnostics = mergeTurnDiagnostics(previous.snapshot.reviewDiagnostics, snapshot.reviewDiagnostics),
                                    nativeRevision = previous.snapshot.nativeRevision, historyCursor = previous.historyCursor)
                                keepEarlier -> snapshot.copy(messages = previous.snapshot?.let { mergeHistoryMessages(it, snapshot) } ?: snapshot.messages,
                                    turnSettings = mergeLatestTurnSettings(previous.snapshot?.turnSettings.orEmpty(), snapshot.turnSettings),
                                    reviewDiagnostics = mergeTurnDiagnostics(previous.snapshot?.reviewDiagnostics.orEmpty(), snapshot.reviewDiagnostics))
                                else -> snapshot
                            }
                        }
                        if (!isCurrent(id, targetGeneration) || requestControlEpoch != controlEpoch || requestHistoryEpoch != historyEpoch ||
                            mutable.value.snapshot !== previous.snapshot) { signals.trySend(Unit); continue }
                        slowRead.cancel()
                        reading = false
                        if (revisionChanged) invalidateHistory()
                        headCache.remember(resolved)
                        cachedHistoryEpoch = historyEpoch
                        reconcilePending(id, snapshot)
                        mutable.update { current ->
                            current.transition(SessionSyncEvent.ReadSucceeded).copy(snapshot = projected, mediaValidationEpoch = current.mediaValidationEpoch + 1,
                                earlierLoaded = if (revisionChanged) false else current.earlierLoaded,
                                loadingEarlier = if (revisionChanged) false else current.loadingEarlier,
                                historyError = if (revisionChanged) null else current.historyError,
                                lastConfirmedControl = SessionState(snapshot = snapshot).control().takeUnless { it.state == ControlState.UNKNOWN } ?: current.lastConfirmedControl,
                                historyCursor = if (!snapshot.historyReady || keepEarlier) current.historyCursor else snapshot.historyCursor)
                        }
                        // Publish current permissions before restoring old visible depth.
                        // Slow pagination must never consume the control-read timeout.
                        if (restoreTarget != null && snapshot.historyCursor != null)
                            restoreHistoryDepth(id, targetGeneration, snapshot, restoreTarget)
                        // Fresh live control/history is authoritative even while persistence catches up.
                        onSnapshot(id, json)
                        if (snapshot.nativeReady) store?.enqueueSnapshot(id, json)
                    }
                } catch (e: Exception) {
                    headCache.clear()
                    if (e is TimeoutCancellationException) api.recovery?.failed(java.net.SocketTimeoutException("Session read timed out"))
                    // Transport reset cancels a Call, not this session. Only a
                    // canceled session job must terminate the refresh loop.
                    if (e is CancellationException) currentCoroutineContext().ensureActive()
                    // A mutation deliberately cancels the obsolete read. Its
                    // failure cannot describe the new permission epoch either.
                    if (requestControlEpoch != controlEpoch) { signals.trySend(Unit); continue }
                    if (isCurrent(id, targetGeneration)) {
                        if (e is ApiException && e.status == 401) {
                            mutable.update { it.transition(SessionSyncEvent.ReadFailed(null)) }
                            failure(e)
                        } else if (requestHistoryEpoch == historyEpoch) mutable.update { it.transition(SessionSyncEvent.ReadFailed(message(e))) }
                    }
                }
                finally { slowRead.cancel(); reading = false }
                delay(180)
            }
        }
    }
    fun beginSubmission(threadId: String, submissionId: String, input: org.json.JSONArray,
        kind: String = "turn", clientId: String = submissionId) {
        val parts = (0 until input.length()).mapNotNull { input.optJSONObject(it)?.let(::copyJsonObject) }
        if (!pendingLoaded) changedPendingClients += clientId
        pendingByThread.keys.toList().filter { it != threadId }.forEach { old ->
            pendingByThread[old] = pendingByThread[old].orEmpty().filterNot { it.clientId == clientId }
            publishPending(old)
        }
        val target = mutable.value.snapshot?.takeIf { it.id == threadId }
        val message = PendingNativeMessage(submissionId, clientId, threadId, parts, kind,
            anchorItemId = target?.messages?.lastOrNull()?.id,
            startsThread = threadId.startsWith("pending:") || target?.messages?.isEmpty() == true)
        pendingByThread[threadId] = pendingByThread[threadId].orEmpty().filterNot { it.clientId == clientId } + message
        while (pendingByThread.size > 12) pendingByThread.remove(pendingByThread.keys.first())
        publishPending(threadId)
    }
    fun dismissFailedMessage(clientId: String) {
        pendingByThread.keys.toList().forEach { id ->
            pendingByThread[id] = pendingByThread[id].orEmpty().filterNot { it.clientId == clientId && it.delivery == "failed" }
            publishPending(id)
        }
        store?.enqueueRemovePendingMessages(setOf(clientId))
    }
    fun pendingMessagesFor(threadId: String): List<PendingNativeMessage> = pendingByThread[threadId].orEmpty()
    suspend fun persistSubmission(submissionId: String) {
        pendingByThread.values.flatten().filter { it.submissionId == submissionId }.forEach { store?.savePendingMessage(it) }
    }
    private fun resolveMessage(message: PendingNativeMessage, response: org.json.JSONObject): PendingNativeMessage? = when (response.string("status")) {
        "failed" -> message.copy(delivery = "failed")
        "accepted" -> if (message.kind in setOf("queue", "create")) null else message.copy(delivery = "accepted",
            nativeTurnId = response.optJSONObject("receipt")?.string("turnId")?.takeIf(String::isNotBlank))
        else -> message.copy(delivery = "unknown")
    }
    suspend fun resolveSubmissionDurably(submissionId: String, response: org.json.JSONObject) {
        withContext(Dispatchers.IO) { store?.resolvePendingMessage(submissionId, response) }
        resolveSubmission(submissionId, response, persist = false)
    }
    fun resolveSubmission(submissionId: String, response: org.json.JSONObject, persist: Boolean = true) {
        if (!pendingLoaded) resolvedPending[submissionId] = copyJsonObject(response)
        if (persist) store?.enqueueResolvePendingMessage(submissionId, response)
        pendingByThread.keys.toList().forEach { id ->
            pendingByThread[id] = pendingByThread[id].orEmpty().mapNotNull { message ->
                if (message.submissionId != submissionId) message
                else resolveMessage(message, response)
            }
            publishPending(id)
        }
    }
    private fun reconcilePending(id: String, snapshot: SessionSnapshot) {
        api.sentImages.reconcile(snapshot, pendingByThread[id].orEmpty())
        if (pendingLoaded && pendingByThread[id].isNullOrEmpty()) return
        val clientIds = snapshot.messages.filter { it.type in setOf("userMessage", "user_message") }
            .map { it.raw.string("clientId") }.filter(String::isNotBlank).toSet()
        // A trusted live user item hides the overlay, but only persisted native
        // history can retire its crash-recovery receipt and input bytes.
        val candidates = if (pendingLoaded) pendingByThread[id].orEmpty().mapTo(hashSetOf()) { it.clientId } else clientIds
        val persistedClients = if (snapshot.nativeReady) clientIds.intersect(candidates) else emptySet()
        val queueIds = snapshot.queue.map { it.id }.toSet()
        val newlyPersisted = persistedClients - changedPendingClients
        if (!pendingLoaded) changedPendingClients += persistedClients
        store?.enqueueRemovePendingMessages(newlyPersisted)
        pendingByThread[id].orEmpty().filter { it.kind == "queue" && it.clientId in queueIds }.forEach {
            if (!pendingLoaded) changedPendingClients += it.clientId
            store?.enqueueRemovePendingMessages(setOf(it.clientId))
        }
        pendingByThread[id] = pendingByThread[id].orEmpty().filterNot {
            it.clientId in persistedClients || it.kind == "queue" && it.clientId in queueIds
        }
        publishPending(id)
    }
    private fun publishPending(id: String) {
        mutable.update {
            val next = pendingByThread[it.id].orEmpty()
            if (it.pendingMessages == next) it else it.copy(pendingMessages = next, overlayRevision = it.overlayRevision + 1)
        }
        if (pendingByThread[id]?.isEmpty() == true) pendingByThread.remove(id)
    }
    /** Fence reads started before a disconnect or mutation; they cannot restore permission. */
    fun invalidateControl(notice: String = "操作结果已变化，正在重新确认会话权限") {
        controlEpoch++
        sync?.invalidate(mutable.value.id)
        // Its permission epoch is already obsolete. Waiting for this body to
        // finish can otherwise delay a fresh ownership check for two minutes.
        activeRead?.cancel()
        mutable.update { it.transition(SessionSyncEvent.Revalidate(notice)) }
        refresh()
    }
    fun refresh() { updates?.trySend(Unit) }
    /** UI calls this only when the laid-out timeline still does not fill its viewport. */
    fun ensureVisibleHistory() {
        val current = mutable.value
        if (current.loading || current.loadingEarlier || current.stale || current.snapshot?.historyReady != true || current.historyError != null || current.historyCursor == null) return
        loadEarlier()
    }
    fun loadEarlier() {
        val current = mutable.value
        val id = current.id ?: return
        val cursor = current.historyCursor ?: return
        val requestGeneration = generation
        val requestHistoryEpoch = historyEpoch
        if (current.loadingEarlier || current.stale || current.snapshot?.historyReady != true || !requestedHistoryCursors.add(cursor)) return
        mutable.update { it.copy(loadingEarlier = true, historyError = null) }
        historyJob = scope.launch {
            try {
                val json = withTimeout(readTimeoutMillis) { api.call(historyPagePath(id, cursor, current.snapshot?.nativeRevision)) }
                val page = withContext(Dispatchers.Default) { sessionSnapshot(json) }
                require(page.id == id) { "节点返回了其他会话，请重新同步" }
                if (!page.historyReady || current.snapshot?.nativeRevision != null && page.nativeRevision != current.snapshot.nativeRevision)
                    throw ApiException(409, "history_changed", "会话历史已更新")
                if (isCurrent(id, requestGeneration) && historyEpoch == requestHistoryEpoch) {
                    val latest = mutable.value
                    if (current.snapshot?.nativeRevision != latest.snapshot?.nativeRevision)
                        throw ApiException(409, "history_changed", "会话历史已更新")
                    val cursorAfter = page.historyCursor?.takeUnless { it in requestedHistoryCursors }
                    if (publishHistory(id, requestGeneration, requestHistoryEpoch, page.nativeRevision, cursorAfter, false) { base ->
                        base.copy(messages = prependNativePage(page.messages, base.messages),
                            turnSettings = prependTurnSettings(page.turnSettings, base.turnSettings),
                            reviewDiagnostics = mergeTurnDiagnostics(page.reviewDiagnostics, base.reviewDiagnostics))
                    }) reconcilePending(id, page)
                }
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                if (isCurrent(id, requestGeneration) && historyEpoch == requestHistoryEpoch) {
                    mutable.update { it.copy(loadingEarlier = false) }
                    if (e is ApiException && e.status == 401) failure(e)
                    else if (e is ApiException && e.status == 409) {
                        historyEpoch++
                        requestedHistoryCursors.clear()
                        mutable.update { it.copy(earlierLoaded = false, historyCursor = null, historyError = null) }
                        refresh()
                    } else {
                        requestedHistoryCursors.remove(cursor)
                        mutable.update { it.copy(historyError = message(e)) }
                    }
                }
            }
        }
    }
    private fun historyPagePath(id: String, cursor: String, revision: String?) =
        "/v1/threads/${encoded(id)}?cursor=${encoded(cursor)}&limit=30" +
            revision?.let { "&nativeRevision=${encoded(it)}" }.orEmpty()

    private suspend fun publishHistory(id: String, generation: Long, epoch: Long, revision: String?, cursor: String?,
        loading: Boolean? = null, project: (SessionSnapshot) -> SessionSnapshot): Boolean {
        while (isCurrent(id, generation) && historyEpoch == epoch) {
            val base = mutable.value.snapshot ?: return false
            if (base.nativeRevision != revision) return false
            val updated = withContext(Dispatchers.Default) { project(base) }
            if (!isCurrent(id, generation) || historyEpoch != epoch) return false
            if (mutable.value.snapshot !== base) continue
            mutable.update { it.copy(snapshot = updated, historyCursor = cursor, earlierLoaded = true,
                loadingEarlier = loading ?: it.loadingEarlier, historyError = null) }
            return true
        }
        return false
    }

    private fun mergeHistoryMessages(restored: SessionSnapshot, latest: SessionSnapshot): List<NativeMessage> {
        // Empty native turns still delimit the authoritative current suffix.
        // Message-only boundaries would discard restored history when that suffix has no items.
        val firstTurn = latest.turnSettings.firstOrNull()?.turnId
        val boundary = restored.turnSettings.indexOfFirst { it.turnId == firstTurn }
        if (boundary < 0) return mergeLatestMessages(restored.messages, latest.messages)
        val olderTurns = restored.turnSettings.take(boundary).mapTo(hashSetOf()) { it.turnId }
        return restored.messages.filter { it.turnId in olderTurns } + latest.messages
    }

    /** Restore old visible depth independently; merge only history, never stale control. */
    private fun restoreHistoryDepth(id: String, targetGeneration: Long, head: SessionSnapshot, previous: SessionSnapshot) {
        val targetEpoch = historyEpoch
        historyDepthTarget = previous
        mutable.update { it.copy(loadingEarlier = true, historyError = null) }
        historyJob = scope.launch {
            val previousTurns = previous.turnSettings.map { it.turnId }
            val oldest = previousTurns.firstOrNull()
            var snapshot = head
            val visited = mutableSetOf<String>()
            try {
                while (snapshot.historyCursor != null && (snapshot.turnSettings.size < previousTurns.size ||
                        oldest != null && snapshot.turnSettings.none { it.turnId == oldest })) {
                    val cursor = snapshot.historyCursor ?: break
                    if (!visited.add(cursor)) break
                    val json = withTimeout(readTimeoutMillis) { api.call(historyPagePath(id, cursor, head.nativeRevision)) }
                    val page = withContext(Dispatchers.Default) { sessionSnapshot(json) }
                    require(page.id == id) { "节点返回了其他会话，请重新同步" }
                    if (!page.historyReady || page.nativeRevision != head.nativeRevision)
                        throw ApiException(409, "history_changed", "会话历史正在更新，稍后自动同步")
                    if (!isCurrent(id, targetGeneration) || historyEpoch != targetEpoch) return@launch
                    if (mutable.value.snapshot?.nativeRevision != head.nativeRevision) return@launch
                    snapshot = withContext(Dispatchers.Default) {
                        snapshot.copy(messages = prependNativePage(page.messages, snapshot.messages),
                            turnSettings = prependTurnSettings(page.turnSettings, snapshot.turnSettings),
                            reviewDiagnostics = mergeTurnDiagnostics(page.reviewDiagnostics, snapshot.reviewDiagnostics),
                            historyCursor = page.historyCursor?.takeUnless { it in visited })
                    }
                    if (!publishHistory(id, targetGeneration, targetEpoch, head.nativeRevision, snapshot.historyCursor) { latest ->
                        latest.copy(messages = mergeHistoryMessages(snapshot, latest),
                            turnSettings = mergeLatestTurnSettings(snapshot.turnSettings, latest.turnSettings),
                            reviewDiagnostics = mergeTurnDiagnostics(snapshot.reviewDiagnostics, latest.reviewDiagnostics))
                    }) return@launch
                    reconcilePending(id, page)
                }
                if (isCurrent(id, targetGeneration) && historyEpoch == targetEpoch) {
                    historyDepthTarget = null
                    requestedHistoryCursors.clear()
                    requestedHistoryCursors.addAll(visited)
                    mutable.update { it.copy(loadingEarlier = false) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                if (isCurrent(id, targetGeneration) && historyEpoch == targetEpoch) {
                    historyDepthTarget = null
                    mutable.update { it.copy(loadingEarlier = false) }
                    if (e is ApiException && e.status == 401) failure(e)
                    else if (e is ApiException && e.status == 409) {
                        invalidateHistory()
                        mutable.update { it.copy(earlierLoaded = false, historyCursor = null, historyError = null) }
                        refresh()
                    } else mutable.update { it.copy(historyError = message(e)) }
                }
            }
        }
    }
    fun pause() {
        val current = mutable.value
        val snapshot = current.snapshot
        if (current.id != null && snapshot != null) {
            recentViews.remove(current.id)
            // Do not retain unusually deep histories as additional resident views.
            if (snapshot.messages.size <= 2000) recentViews[current.id] = current.transition(SessionSyncEvent.Pause).copy(
                snapshot = snapshot.copy(requests = emptyList()), pendingMessages = emptyList(),
                loadingEarlier = false)
            while (recentViews.size > 3) recentViews.remove(recentViews.keys.first())
        }
        onVisible(null)
        generation++; activeJob?.cancel(); activeJob = null; updates?.close(); updates = null
        invalidateHistory()
        mutable.update { it.transition(SessionSyncEvent.Pause).copy(loadingEarlier = false) }
    }
    fun close() { pause(); mutable.value = SessionState() }
}
