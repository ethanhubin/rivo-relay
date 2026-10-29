package dev.rivo.relay.codex

import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class SessionState(val id: String? = null, val snapshot: SessionSnapshot? = null, val loading: Boolean = false, val stale: Boolean = false,
    val historyCursor: String? = null, val loadingEarlier: Boolean = false, val earlierLoaded: Boolean = false,
    val readError: String? = null, val historyError: String? = null)

/** One active thread owns reads and subscription; switch cancels both and rejects late snapshots. */
class SessionController(private val api: RelayApi, private val scope: CoroutineScope, private val failure: (Throwable) -> Unit) {
    private val mutable = MutableStateFlow(SessionState())
    val state = mutable.asStateFlow()
    private var activeJob: Job? = null
    private var updates: Channel<Unit>? = null
    private var generation = 0L
    private var historyEpoch = 0L
    private var historyJob: Job? = null
    private fun isCurrent(id: String, targetGeneration: Long) = mutable.value.id == id && generation == targetGeneration
    private fun message(error: Exception) = if (error is ApiException) error.message else "无法读取会话，正在重新连接。"
    private fun invalidateHistory() { historyEpoch++; historyJob?.cancel(); historyJob = null }
    fun dismissReadError() { mutable.update { it.copy(readError = null, historyError = null) } }
    fun open(id: String) {
        pause()
        val targetGeneration = generation
        val previous = mutable.value
        mutable.value = if (previous.id == id) previous.copy(loading = true, stale = true) else SessionState(id = id, loading = true)
        val signals = Channel<Unit>(Channel.CONFLATED); updates = signals
        activeJob = scope.launch {
            // Subscribe before first snapshot. Buffered notifications coalesce into another authoritative read.
            launch {
                while (isActive) {
                    try { api.events(id).collect { event ->
                        if (isCurrent(id, targetGeneration)) {
                            if (event.type == "reset") {
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
                    delay(1800); signals.trySend(Unit)
                }
            }
            launch { while (isActive) { delay(5000); signals.trySend(Unit) } }
            signals.trySend(Unit)
            for (ignored in signals) {
                val requestHistoryEpoch = historyEpoch
                try {
                    val snapshot = sessionSnapshot(api.call("/v1/threads/" + encoded(id)))
                    if (isCurrent(id, targetGeneration) && requestHistoryEpoch == historyEpoch) mutable.update { current ->
                        current.copy(snapshot = if (current.earlierLoaded) snapshot.copy(messages = mergeLatestMessages(current.snapshot?.messages.orEmpty(), snapshot.messages)) else snapshot,
                            loading = false, stale = false, readError = null,
                            historyCursor = if (current.earlierLoaded) current.historyCursor else snapshot.historyCursor)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (isCurrent(id, targetGeneration)) {
                        if (e is ApiException && e.status == 401) failure(e)
                        else if (requestHistoryEpoch == historyEpoch) mutable.update { it.copy(loading = false, stale = true, readError = message(e)) }
                    }
                }
                delay(180)
            }
        }
    }
    fun refresh() { updates?.trySend(Unit) }
    fun loadEarlier() {
        val current = mutable.value
        val id = current.id ?: return
        val cursor = current.historyCursor ?: return
        val requestGeneration = generation
        val requestHistoryEpoch = historyEpoch
        if (current.loadingEarlier) return
        mutable.update { it.copy(loadingEarlier = true, historyError = null) }
        historyJob = scope.launch {
            try {
                val page = sessionSnapshot(api.call("/v1/threads/${encoded(id)}?cursor=${encoded(cursor)}&limit=30"))
                if (isCurrent(id, requestGeneration) && historyEpoch == requestHistoryEpoch) mutable.update { latest -> latest.copy(
                    snapshot = latest.snapshot?.copy(messages = prependNativePage(page.messages, latest.snapshot.messages)),
                    historyCursor = page.historyCursor?.takeUnless { it == cursor }, earlierLoaded = true, loadingEarlier = false, historyError = null) }
            } catch (e: Exception) {
                if (e !is CancellationException && isCurrent(id, requestGeneration) && historyEpoch == requestHistoryEpoch) {
                    mutable.update { it.copy(loadingEarlier = false) }
                    if (e is ApiException && e.status == 401) failure(e)
                    else if (e is ApiException && e.status == 409) {
                        historyEpoch++
                        mutable.update { it.copy(earlierLoaded = false, historyCursor = null, historyError = null) }
                        refresh()
                    } else mutable.update { it.copy(historyError = message(e)) }
                }
            }
        }
    }
    fun pause() {
        generation++; activeJob?.cancel(); activeJob = null; updates?.close(); updates = null
        invalidateHistory()
        mutable.update { it.copy(stale = it.id != null, loadingEarlier = false) }
    }
    fun close() { pause(); mutable.value = SessionState() }
}
