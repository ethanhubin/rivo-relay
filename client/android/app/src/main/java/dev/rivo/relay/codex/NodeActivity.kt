package dev.rivo.relay.codex
import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NodeActivity(val tasks: Map<String, ThreadSummary> = emptyMap(), val unknown: Set<String> = emptySet(), val confirmed: Boolean = false, val known: Set<String> = emptySet(), val generation: String = "") {
    fun status(id: String): String? = if (!confirmed || id in unknown || id !in known) null else tasks[id]?.status ?: "idle"
}
fun nodeActivity(api: RelayApi, sync: NodeSessionSync, scope: CoroutineScope): StateFlow<NodeActivity> = channelFlow {
    var previous = NodeActivity()
    var epoch = 0L
    val lock = Mutex()
    coroutineScope {
        val signals = Channel<Unit>(Channel.CONFLATED)
        val events = launch {
            suspend fun invalidate() {
                lock.withLock {
                    epoch++
                    previous = previous.copy(confirmed = false)
                    send(previous)
                }
                signals.trySend(Unit)
            }
            sync.events(onInterrupted = { invalidate() }).collect {
                if (catalogLifecycleEvent(it)) invalidate()
            }
        }
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    val readEpoch = lock.withLock { epoch }
                    val response = api.call("/v1/activity")
                    lock.withLock {
                        if (readEpoch == epoch && response.optBoolean("complete", false)) {
                            require(response.string("generation").isNotBlank())
                            require(response.optJSONArray("tasks") != null && response.optJSONArray("knownThreadIds") != null &&
                                response.optJSONArray("unknownThreadIds") != null)
                            val tasks = response.objects("tasks").map { row -> row.put("id", row.string("threadId")); threadSummary(row) }.associateBy { it.id }.toMutableMap()
                            val unknown = response.optJSONArray("unknownThreadIds")
                            if (previous.generation == response.string("generation") && unknown != null) (0 until unknown.length()).forEach { index ->
                                val id = unknown.optString(index)
                                previous.tasks[id]?.let { tasks.putIfAbsent(id, it) }
                            }
                            val known = response.optJSONArray("knownThreadIds")
                            previous = NodeActivity(tasks, if (unknown == null) emptySet() else (0 until unknown.length()).map { unknown.optString(it) }.toSet(), true, if (known == null) tasks.keys else (0 until known.length()).map { known.optString(it) }.toSet(), response.string("generation"))
                        } else previous = previous.copy(confirmed = false)
                        send(previous)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { lock.withLock {
                    previous = previous.copy(confirmed = false)
                    send(previous)
                } }
                withTimeoutOrNull(5000) { signals.receive() }
            }
        } finally { events.cancel(); signals.close() }
    }
}.stateIn(scope, SharingStarted.WhileSubscribed(0), NodeActivity())
