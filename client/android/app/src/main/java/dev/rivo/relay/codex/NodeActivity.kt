package dev.rivo.relay.codex
import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The only execution projection consumed by project, list and conversation views. */
data class NodeActivity(val tasks: Map<String, ThreadSummary> = emptyMap(), val unknown: Set<String> = emptySet(), val confirmed: Boolean = false, val known: Set<String> = emptySet(), val generation: String = "", val version: Long = -1, val retiredGenerations: Set<String> = emptySet()) {
    fun status(id: String): String? = if (!confirmed || id in unknown || id !in known) null else tasks[id]?.status ?: "idle"
    fun execution(id: String): ThreadSummary {
        val status = status(id) ?: "unknown"
        return tasks[id]?.takeIf { status != "unknown" } ?: ThreadSummary(id, "", "", status, "", "",
            nativeExecution = status, deliveryState = "idle", displayState = status)
    }
    val runningProjectIds: Set<String> get() = tasks.values.filter {
        status(it.id) == "running"
    }.map { it.projectId }.filter(String::isNotBlank).toSet()
}

/** Old HTTP responses cannot roll back a newer node snapshot from a detail read. */
internal fun NodeActivity.accept(response: org.json.JSONObject): NodeActivity {
    require(response.optBoolean("complete", false) && response.string("generation").isNotBlank())
    require(response.optJSONArray("tasks") != null && response.optJSONArray("knownThreadIds") != null &&
        response.optJSONArray("unknownThreadIds") != null)
    if (response.string("generation") in retiredGenerations) return this
    val nextVersion = response.optLong("version", -1)
    if (generation == response.string("generation") && version >= 0 && nextVersion <= version) return this
    fun ids(key: String) = response.getJSONArray(key).let { values ->
        (0 until values.length()).map { values.getString(it) }.toSet()
    }
    val known = ids("knownThreadIds")
    val unknown = ids("unknownThreadIds")
    val tasks = response.objects("tasks").map { row ->
        threadSummary(org.json.JSONObject(row.toString()).put("id", row.string("threadId")))
    }.filter { it.id in known && it.id !in unknown }.associateBy { it.id }
    return NodeActivity(tasks, unknown, true, known, response.string("generation"), nextVersion,
        retiredGenerations + setOf(generation).filter { it.isNotBlank() && it != response.string("generation") })
}

/** null means node-wide uncertainty; an empty set changes no execution state. */
internal fun activityInvalidatedThreads(event: RelayEvent): Set<String>? {
    if (!catalogLifecycleEvent(event)) return emptySet()
    if (event.type in setOf("reset", "connected", "revoked")) return null
    val method = event.payload.string("method")
    if (method == "relay/nativeDisconnected") return null
    // Project metadata can require a catalog refresh without invalidating execution.
    if (method.startsWith("project/")) return emptySet()
    val params = event.payload.optJSONObject("params") ?: return null
    params.optJSONArray("threadIds")?.let { ids ->
        return (0 until ids.length()).map { ids.optString(it) }.filter(String::isNotBlank).toSet()
    }
    val id = params.string("threadId").ifBlank { params.string("conversationId") }
        .ifBlank { params.optJSONObject("thread")?.string("id").orEmpty() }
    return id.takeIf(String::isNotBlank)?.let { setOf(it) }
}

fun nodeActivity(api: RelayApi, sync: NodeSessionSync, scope: CoroutineScope): StateFlow<NodeActivity> = channelFlow {
    var previous = NodeActivity()
    var epoch = 0L
    val lock = Mutex()
    coroutineScope {
        val signals = Channel<Unit>(Channel.CONFLATED)
        val events = launch {
            suspend fun invalidate(threads: Set<String>? = null) {
                if (threads == null || threads.isNotEmpty()) lock.withLock {
                    epoch++
                    previous = if (threads == null) previous.copy(confirmed = false)
                        else previous.copy(unknown = previous.unknown + threads)
                    send(previous)
                }
                signals.trySend(Unit)
            }
            sync.events(onInterrupted = { invalidate() }).collect {
                if (catalogLifecycleEvent(it)) invalidate(activityInvalidatedThreads(it))
            }
        }
        val snapshots = launch {
            sync.activitySnapshots.collect { response ->
                try {
                    lock.withLock {
                        val next = previous.accept(response)
                        if (next !== previous) {
                            previous = next
                            epoch++
                            send(previous)
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { signals.trySend(Unit) }
            }
        }
        try {
            while (currentCoroutineContext().isActive) {
                val readEpoch = lock.withLock { epoch }
                try {
                    val response = api.call("/v1/activity")
                    lock.withLock {
                        if (readEpoch == epoch && response.optBoolean("complete", false)) {
                            previous = previous.accept(response)
                        } else if (readEpoch == epoch) previous = previous.copy(confirmed = false)
                        send(previous)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { lock.withLock {
                    if (readEpoch == epoch) previous = previous.copy(confirmed = false)
                    send(previous)
                } }
                withTimeoutOrNull(5000) { signals.receive() }
            }
        } finally { events.cancel(); snapshots.cancel(); signals.close() }
    }
}.stateIn(scope, SharingStarted.WhileSubscribed(0), NodeActivity())
