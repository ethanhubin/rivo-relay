package dev.rivo.relay.codex

import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

/** One node event subscription; shared reads are only concurrent, never a stale-response cache. */
class NodeSessionSync(private val api: RelayApi, private val scope: CoroutineScope) {
    private data class Observation(val event: RelayEvent? = null, val error: Throwable? = null)
    private data class Read(val result: Deferred<JSONObject>, val startedNanos: Long, var users: Int = 0)
    private val lock = Any()
    private var epoch = 0L
    private val reads = mutableMapOf<Pair<String, Long>, Read>()
    fun invalidate() { synchronized(lock) { epoch++ } }
    private val observations = flow {
        var cursor: String? = null
        while (currentCoroutineContext().isActive) {
            try {
                api.events("", cursor).collect { event ->
                    event.cursor?.let { cursor = it.takeIf(String::isNotBlank) }
                    invalidate()
                    emit(Observation(event = event))
                }
                throw java.io.IOException("实时连接已结束")
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                invalidate()
                emit(Observation(error = error))
            }
            delay(1800)
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), replay = 0)

    fun events(): Flow<RelayEvent> = observations.map { observation ->
        observation.error?.let { throw it }
        checkNotNull(observation.event)
    }

    suspend fun read(threadId: String, freshAfterNanos: Long? = null): JSONObject {
        val key: Pair<String, Long>
        val read: Read
        synchronized(lock) {
            key = threadId to epoch
            // Control reads own their timeout window; an older notification request
            // must not acquire a new freshness deadline when the chat opens.
            read = reads[key]?.takeUnless { it.result.isCompleted || freshAfterNanos != null && it.startedNanos < freshAfterNanos } ?: Read(scope.async(start = CoroutineStart.LAZY) {
                api.call("/v1/threads/${encoded(threadId)}")
            }, System.nanoTime()).also { reads[key] = it }
            read.users++
        }
        try { return read.result.await() }
        finally {
            synchronized(lock) {
                read.users--
                if (read.users == 0) {
                    if (reads[key] === read) reads.remove(key)
                    if (!read.result.isCompleted) read.result.cancel()
                }
            }
        }
    }
}
