package dev.rivo.relay.codex

import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class SessionState(val id: String? = null, val snapshot: SessionSnapshot? = null, val loading: Boolean = false, val stale: Boolean = false,
    val historyCursor: String? = null, val loadingEarlier: Boolean = false, val earlierLoaded: Boolean = false,
    val readError: String? = null, val historyError: String? = null,
    val pendingMessages: List<PendingNativeMessage> = emptyList(), val overlayRevision: Long = 0L)

/** One active thread owns reads and subscription; switch cancels both and rejects late snapshots. */
class SessionController(private val api: RelayApi, private val scope: CoroutineScope,
    private val store: ConversationStore? = null,
    private val onSnapshot: (String, org.json.JSONObject) -> Unit = { _, _ -> },
    private val onVisible: (String?) -> Unit = {},
    private val failure: (Throwable) -> Unit) {
    private val mutable = MutableStateFlow(SessionState())
    val state = mutable.asStateFlow()
    private val pendingByThread = linkedMapOf<String, List<PendingNativeMessage>>()
    private val changedPendingClients = mutableSetOf<String>()
    private val resolvedPending = mutableMapOf<String, org.json.JSONObject>()
    private var activeJob: Job? = null
    private var updates: Channel<Unit>? = null
    private var generation = 0L
    private var historyEpoch = 0L
    private var historyJob: Job? = null
    private val requestedHistoryCursors = mutableSetOf<String>()
    init {
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
        }
    }
    private fun isCurrent(id: String, targetGeneration: Long) = mutable.value.id == id && generation == targetGeneration
    private fun message(error: Exception) = if (error is ApiException) error.message else api.describeFailure(error)
    private fun invalidateHistory() { historyEpoch++; historyJob?.cancel(); historyJob = null; requestedHistoryCursors.clear() }
    fun dismissReadError() { mutable.update { it.copy(readError = null, historyError = null) } }
    fun open(id: String, online: Boolean = true) {
        pause()
        val targetGeneration = generation
        val previous = mutable.value
        mutable.value = if (previous.id == id) previous.copy(loading = true, stale = true) else SessionState(id = id, loading = true, pendingMessages = pendingByThread[id].orEmpty())
        onVisible(id)
        if (!online) {
            // Cancel the old transport immediately; retain read-only content while no route exists.
            activeJob = scope.launch {
                var cached: SessionSnapshot? = null
                try { if (mutable.value.snapshot == null) cached = store?.loadSnapshot(id)?.let(::sessionSnapshot) }
                catch (e: Exception) { if (e is CancellationException) throw e }
                if (isCurrent(id, targetGeneration)) mutable.update {
                    it.copy(snapshot = it.snapshot ?: cached, loading = false, stale = true)
                }
            }
            return
        }
        val signals = Channel<Unit>(Channel.CONFLATED); updates = signals
        activeJob = scope.launch {
            var reading = false
            var eventCursor: String? = null
            var lastSaved: String? = null
            val pendingDeltas = mutableListOf<org.json.JSONObject>()
            fun flushDeltas() {
                if (pendingDeltas.isEmpty()) return
                val batch = pendingDeltas.toList(); pendingDeltas.clear()
                val current = mutable.value
                var snapshot = current.snapshot
                if (reading || current.stale || snapshot == null) { signals.trySend(Unit); return }
                for (event in batch) {
                    snapshot = snapshot?.applyLiveDelta(event)
                    if (snapshot == null) { signals.trySend(Unit); return }
                }
                mutable.update { it.copy(snapshot = snapshot) }
            }
            // Match QiGuang's 150 ms live presentation cadence; terminal events flush immediately.
            launch { while (isActive) { delay(150); flushDeltas() } }
            // Subscribe before first snapshot. Buffered notifications coalesce into another authoritative read.
            launch {
                while (isActive) {
                    try { api.events(id, eventCursor).collect { event ->
                        if (isCurrent(id, targetGeneration)) {
                            event.cursor?.let { eventCursor = it.takeIf(String::isNotBlank) }
                            val params = event.payload.optJSONObject("params")
                            val eventThread = params?.string("threadId").orEmpty()
                            // The bus also carries other threads. They must not trigger history reads.
                            if (eventThread.isNotEmpty() && eventThread != id) return@collect
                            if (event.type == "native" && params?.has("relayOffset") == true &&
                                event.payload.string("method") in setOf("item/agentMessage/delta", "item/commandExecution/outputDelta")) {
                                pendingDeltas += event.payload
                                if (pendingDeltas.size >= 128) flushDeltas()
                                return@collect
                            }
                            val historyInvalidated = event.type == "reset" || (event.type == "native" &&
                                event.payload.string("method") == "relay/historyInvalidated")
                            if (historyInvalidated) pendingDeltas.clear() else flushDeltas()
                            if (historyInvalidated) {
                                invalidateHistory()
                                mutable.update { it.copy(earlierLoaded = false, historyCursor = null, loading = true,
                                    loadingEarlier = false, stale = true, historyError = null) }
                            }
                            signals.trySend(Unit)
                        }
                    } }
                    catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (isCurrent(id, targetGeneration)) {
                            mutable.update { it.copy(stale = true) }
                            if (e is ApiException && e.status == 401) failure(e)
                        }
                    }
                    flushDeltas()
                    if (isCurrent(id, targetGeneration)) mutable.update { it.copy(stale = true) }
                    delay(1800); signals.trySend(Unit)
                }
            }
            launch { while (isActive) { delay(5000); signals.trySend(Unit) } }
            if (mutable.value.snapshot == null) {
                try {
                    val cached = store?.loadSnapshot(id)?.let(::sessionSnapshot)
                    if (cached != null && isCurrent(id, targetGeneration)) mutable.update { it.copy(snapshot = cached, stale = true) }
                } catch (e: Exception) { if (e is CancellationException) throw e }
            }
            signals.trySend(Unit)
            for (ignored in signals) {
                val requestHistoryEpoch = historyEpoch
                try {
                    reading = true
                    val json = api.call("/v1/threads/" + encoded(id))
                    val head = sessionSnapshot(json)
                    require(head.id == id) { "节点返回了其他会话，请重新同步" }
                    val previous = mutable.value
                    val snapshot = if (head.nativeReady && previous.earlierLoaded &&
                        head.nativeRevision != null && previous.snapshot?.nativeRevision != head.nativeRevision)
                        loadHistoryDepth(id, head, previous.snapshot) else head
                    if (isCurrent(id, targetGeneration) && requestHistoryEpoch == historyEpoch) {
                        reconcilePending(id, snapshot)
                        mutable.update { current ->
                            val keepEarlier = current.earlierLoaded && (snapshot.nativeRevision == null ||
                                snapshot.nativeRevision == current.snapshot?.nativeRevision)
                            current.copy(snapshot = when {
                                !snapshot.nativeReady && current.snapshot != null -> snapshot.copy(
                                    messages = mergeConvergingMessages(current.snapshot.messages, snapshot.messages),
                                    turnSettings = mergeConvergingTurnSettings(current.snapshot.turnSettings, snapshot.turnSettings),
                                    reviewDiagnostics = mergeTurnDiagnostics(current.snapshot.reviewDiagnostics, snapshot.reviewDiagnostics),
                                    nativeRevision = current.snapshot.nativeRevision, historyCursor = current.historyCursor)
                                keepEarlier -> snapshot.copy(messages = mergeLatestMessages(current.snapshot?.messages.orEmpty(), snapshot.messages),
                                    turnSettings = mergeLatestTurnSettings(current.snapshot?.turnSettings.orEmpty(), snapshot.turnSettings),
                                    reviewDiagnostics = mergeTurnDiagnostics(current.snapshot?.reviewDiagnostics.orEmpty(), snapshot.reviewDiagnostics))
                                else -> snapshot
                            }, loading = false, stale = false, readError = null,
                                historyCursor = if (!snapshot.nativeReady || keepEarlier) current.historyCursor else snapshot.historyCursor)
                        }
                        // Fresh live control/history is authoritative even while persistence catches up.
                        onSnapshot(id, json)
                        if (snapshot.nativeReady) {
                            val serialized = json.toString()
                            if (serialized != lastSaved) {
                                try { store?.saveSnapshot(id, json); lastSaved = serialized }
                                catch (e: Exception) { if (e is CancellationException) throw e }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Transport reset cancels a Call, not this session. Only a
                    // canceled session job must terminate the refresh loop.
                    if (e is CancellationException) currentCoroutineContext().ensureActive()
                    if (isCurrent(id, targetGeneration)) {
                        if (e is ApiException && e.status == 401) {
                            mutable.update { it.copy(loading = false, stale = true) }
                            failure(e)
                        } else if (requestHistoryEpoch == historyEpoch) mutable.update { it.copy(loading = false, stale = true, readError = message(e)) }
                    }
                }
                finally { reading = false }
                delay(180)
            }
        }
    }
    fun beginSubmission(threadId: String, submissionId: String, input: org.json.JSONArray,
        kind: String = "turn", clientId: String = submissionId) {
        val parts = (0 until input.length()).mapNotNull { input.optJSONObject(it)?.let { part -> org.json.JSONObject(part.toString()) } }
        changedPendingClients += clientId
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
    fun pendingMessagesFor(threadId: String): List<PendingNativeMessage> = pendingByThread[threadId].orEmpty()
    suspend fun persistSubmission(submissionId: String) {
        pendingByThread.values.flatten().filter { it.submissionId == submissionId }.forEach { store?.savePendingMessage(it) }
    }
    private fun resolveMessage(message: PendingNativeMessage, response: org.json.JSONObject): PendingNativeMessage? = when (response.string("status")) {
        "failed" -> null
        "accepted" -> if (message.kind in setOf("queue", "create")) null else message.copy(delivery = "accepted",
            nativeTurnId = response.optJSONObject("receipt")?.string("turnId")?.takeIf(String::isNotBlank))
        else -> message.copy(delivery = "unknown")
    }
    fun resolveSubmission(submissionId: String, response: org.json.JSONObject) {
        resolvedPending[submissionId] = org.json.JSONObject(response.toString())
        store?.resolvePendingMessage(submissionId, response)
        pendingByThread.keys.toList().forEach { id ->
            pendingByThread[id] = pendingByThread[id].orEmpty().mapNotNull { message ->
                if (message.submissionId != submissionId) message
                else resolveMessage(message, response)
            }
            publishPending(id)
        }
    }
    private fun reconcilePending(id: String, snapshot: SessionSnapshot) {
        val clientIds = snapshot.messages.filter { it.type in setOf("userMessage", "user_message") }
            .map { it.raw.string("clientId") }.filter(String::isNotBlank).toSet()
        // A trusted live user item hides the overlay, but only persisted native
        // history can retire its crash-recovery receipt and input bytes.
        val persistedClients = if (snapshot.nativeReady) clientIds else emptySet()
        val queueIds = snapshot.queue.map { it.id }.toSet()
        changedPendingClients += persistedClients
        persistedClients.forEach { store?.removePendingMessage(it) }
        pendingByThread[id].orEmpty().filter { it.kind == "queue" && it.clientId in queueIds }.forEach {
            changedPendingClients += it.clientId; store?.removePendingMessage(it.clientId)
        }
        pendingByThread[id] = pendingByThread[id].orEmpty().filterNot {
            it.clientId in persistedClients || it.kind == "queue" && it.clientId in queueIds
        }
        publishPending(id)
    }
    private fun publishPending(id: String) {
        mutable.update { it.copy(pendingMessages = pendingByThread[it.id].orEmpty(), overlayRevision = it.overlayRevision + 1) }
        if (pendingByThread[id]?.isEmpty() == true) pendingByThread.remove(id)
    }
    fun refresh() { updates?.trySend(Unit) }
    /** UI calls this only when the laid-out timeline still does not fill its viewport. */
    fun ensureVisibleHistory() {
        val current = mutable.value
        if (current.loading || current.loadingEarlier || current.stale || current.snapshot?.nativeReady != true || current.historyError != null || current.historyCursor == null) return
        loadEarlier()
    }
    fun loadEarlier() {
        val current = mutable.value
        val id = current.id ?: return
        val cursor = current.historyCursor ?: return
        val requestGeneration = generation
        val requestHistoryEpoch = historyEpoch
        if (current.loadingEarlier || current.stale || current.snapshot?.nativeReady != true || !requestedHistoryCursors.add(cursor)) return
        mutable.update { it.copy(loadingEarlier = true, historyError = null) }
        historyJob = scope.launch {
            try {
                val page = sessionSnapshot(api.call(historyPagePath(id, cursor, current.snapshot?.nativeRevision)))
                require(page.id == id) { "节点返回了其他会话，请重新同步" }
                if (!page.nativeReady || current.snapshot?.nativeRevision != null && page.nativeRevision != current.snapshot.nativeRevision)
                    throw ApiException(409, "history_changed", "会话历史已更新")
                if (isCurrent(id, requestGeneration) && historyEpoch == requestHistoryEpoch) {
                    val latest = mutable.value
                    if (current.snapshot?.nativeRevision != latest.snapshot?.nativeRevision)
                        throw ApiException(409, "history_changed", "会话历史已更新")
                    reconcilePending(id, page)
                    mutable.update { it.copy(
                        snapshot = it.snapshot?.copy(messages = prependNativePage(page.messages, it.snapshot.messages),
                            turnSettings = prependTurnSettings(page.turnSettings, it.snapshot.turnSettings),
                            reviewDiagnostics = mergeTurnDiagnostics(page.reviewDiagnostics, it.snapshot.reviewDiagnostics)),
                        historyCursor = page.historyCursor?.takeUnless { it in requestedHistoryCursors }, earlierLoaded = true, loadingEarlier = false, historyError = null) }
                }
            } catch (e: Exception) {
                if (e !is CancellationException && isCurrent(id, requestGeneration) && historyEpoch == requestHistoryEpoch) {
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

    /** Rebuild already visible depth after a native revision changes; never splice revision domains. */
    private suspend fun loadHistoryDepth(id: String, head: SessionSnapshot, previous: SessionSnapshot?): SessionSnapshot {
        val previousTurns = previous?.turnSettings.orEmpty().map { it.turnId }
        val oldest = previousTurns.firstOrNull()
        var snapshot = head
        val visited = mutableSetOf<String>()
        while (snapshot.historyCursor != null && (snapshot.turnSettings.size < previousTurns.size ||
                oldest != null && snapshot.turnSettings.none { it.turnId == oldest })) {
            val cursor = snapshot.historyCursor ?: break
            if (!visited.add(cursor)) return snapshot.copy(historyCursor = null)
            val page = sessionSnapshot(api.call(historyPagePath(id, cursor, head.nativeRevision)))
            require(page.id == id) { "节点返回了其他会话，请重新同步" }
            if (!page.nativeReady || page.nativeRevision != head.nativeRevision)
                throw ApiException(409, "history_changed", "会话历史正在更新，稍后自动同步")
            snapshot = snapshot.copy(messages = prependNativePage(page.messages, snapshot.messages),
                turnSettings = prependTurnSettings(page.turnSettings, snapshot.turnSettings),
                reviewDiagnostics = mergeTurnDiagnostics(page.reviewDiagnostics, snapshot.reviewDiagnostics),
                historyCursor = page.historyCursor?.takeUnless { it in visited })
        }
        requestedHistoryCursors.clear()
        return snapshot
    }
    fun pause() {
        onVisible(null)
        generation++; activeJob?.cancel(); activeJob = null; updates?.close(); updates = null
        invalidateHistory()
        mutable.update { it.copy(stale = it.id != null, loadingEarlier = false) }
    }
    fun close() { pause(); mutable.value = SessionState() }
}
