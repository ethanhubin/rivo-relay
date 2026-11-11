package dev.rivo.relay.ui.todos

import dev.rivo.relay.data.*
import dev.rivo.relay.codex.NodeSessionSync
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val LOCAL_TODO_ATTACHMENT = "local"

internal fun Todo.localAttachments() = details.objects("attachments").filter { it.string("type") == LOCAL_TODO_ATTACHMENT }

private fun JSONObject.withoutLocalAttachments(): JSONObject {
    val details = optJSONObject("details") ?: return this
    val attachments = details.objects("attachments")
    if (attachments.none { it.string("type") == LOCAL_TODO_ATTACHMENT }) return this
    return JSONObject(toString()).put("details", JSONObject(details.toString()).put("attachments",
        JSONArray(attachments.filterNot { it.string("type") == LOCAL_TODO_ATTACHMENT })))
}

private fun keepLocalAttachments(remote: List<Todo>, current: List<Todo>): List<Todo> {
    val localById = current.associate { it.id to it.localAttachments() }
    return remote.map { todo ->
        val local = localById[todo.id].orEmpty()
        val attachments = todo.details.objects("attachments").filterNot { it.string("type") == LOCAL_TODO_ATTACHMENT }
        if (local.isEmpty() && attachments.size == todo.details.objects("attachments").size) todo
        else todo.copy(details = JSONObject(todo.details.toString()).put("attachments", JSONArray(attachments + local)))
    }
}

data class TodosState(val items: List<Todo> = emptyList(), val loading: Boolean = false, val mutating: Boolean = false,
    val syncing: Boolean = false, val dispatching: Boolean = false, val refreshing: Boolean = false, val pendingCount: Int = 0, val online: Boolean = false, val syncError: String? = null)

private data class TodoOperation(val id: String, val todoId: String, val method: String, val body: JSONObject) {
    fun json() = JSONObject().put("id", id).put("todoId", todoId).put("method", method).put("body", body)
}

/** Todo CRUD is idempotent state assignment, kept in a durable node-scoped outbox until acknowledged.
 * Codex dispatch is deliberately excluded: it still uses SubmissionCoordinator and is never retried here.
 */
class TodosController(private val api: RelayApi, private val scope: CoroutineScope, private val failure: (Throwable) -> Unit,
    private val store: ConversationStore? = null, private val onChanged: (List<Todo>) -> Unit = {},
    private val sync: NodeSessionSync? = null) {
    private val mutable = MutableStateFlow(TodosState())
    val state = mutable.asStateFlow()
    private val stateLock = Mutex()
    private val syncLock = Mutex()
    private var loaded = false
    private var syncJob: Deferred<Boolean>? = null
    private var syncGeneration = 0L
    private var savedState: String? = null
    @Volatile private var outbox = emptyList<TodoOperation>()

    init { scope.launch { runCatching { stateLock.withLock { restore() } }.onFailure(failure) } }

    private suspend fun restore() {
        if (loaded) return
        val saved = store?.loadTodoState()
        if (saved != null) {
            require(saved.optInt("version") == 1) { "本机待办格式无法识别，未发送任何更改" }
            outbox = saved.objects("operations").map {
                val method = it.string("method")
                require(method in setOf("POST", "PATCH", "DELETE")) { "本机待办同步记录无效" }
                TodoOperation(it.string("id"), it.string("todoId"), method, it.optJSONObject("body") ?: JSONObject())
            }
            mutable.update { it.copy(items = saved.objects("items").map(::todo), pendingCount = outbox.size) }
            savedState = saved.toString()
            onChanged(mutable.value.items)
        }
        loaded = true
    }

    private suspend fun commit(items: List<Todo>, operations: List<TodoOperation>) {
        val saved = JSONObject().put("version", 1).put("items", JSONArray(items.map(::todoJson)))
            .put("operations", JSONArray(operations.map(TodoOperation::json)))
        val serialized = saved.toString()
        if (serialized == savedState) return
        store?.saveTodoState(saved) // Must succeed before either the UI accepts the edit or a network write starts.
        savedState = serialized
        outbox = operations
        mutable.update { it.copy(items = items, pendingCount = operations.size) }
        onChanged(items)
    }

    /** Readers join a single request; mutations/events require at most one dirty follow-up. */
    @Synchronized private fun requestSync(changed: Boolean = false): Deferred<Boolean> {
        if (changed) syncGeneration++
        syncJob?.takeUnless { it.isCompleted }?.let { return it }
        val job = scope.async(start = CoroutineStart.LAZY) {
            var result: Boolean
            do {
                val generation = syncGeneration
                result = synchronize()
            } while (result && generation != syncGeneration)
            result
        }
        syncJob = job
        job.start()
        return job
    }
    fun refresh(): Job = requestSync()
    fun refreshAfterChange(): Job = requestSync(changed = true)
    fun synchronizeNow() { requestSync() }
    suspend fun observe(pollIntervalMillis: Long = 60_000) = coroutineScope {
        require(pollIntervalMillis > 0)
        val events = sync?.let { source -> launch {
            source.events(onInterrupted = {}).collect { event ->
                if (event.type in setOf("connected", "reset") || event.type == "native" &&
                    event.payload.string("method") == "relay/todos") requestSync(changed = true)
            }
        } }
        try {
            while (currentCoroutineContext().isActive) {
                requestSync().await()
                delay(pollIntervalMillis)
            }
        } finally { events?.cancel() }
    }

    private suspend fun synchronize(): Boolean = syncLock.withLock {
        mutable.update { it.copy(loading = it.items.isEmpty(), refreshing = true, syncError = null) }
        try {
            stateLock.withLock { restore() }
            while (true) {
                val operation = stateLock.withLock { outbox.firstOrNull() } ?: break
                mutable.update { it.copy(syncing = true) }
                try {
                    api.call(if (operation.method == "POST") "/v1/todos" else "/v1/todos/${encoded(operation.todoId)}", operation.method, operation.body.withoutLocalAttachments())
                } catch (e: ApiException) {
                    if (!(operation.method == "DELETE" && e.status == 404)) throw e
                }
                stateLock.withLock {
                    if (outbox.firstOrNull()?.id == operation.id) commit(mutable.value.items, outbox.drop(1))
                }
            }
            val remote = api.call("/v1/todos").objects("todos").map(::todo)
            stateLock.withLock {
                var merged = remote
                outbox.forEach { merged = applyOperation(merged, it) }
                commit(keepLocalAttachments(merged, mutable.value.items), outbox)
            }
            mutable.update { it.copy(online = true, syncError = null) }
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is ApiException && e.status == 401) failure(e)
            val message = if (e is ApiException && e.status in 400..499) "节点未接受更改：${e.message}"
                else if (mutable.value.pendingCount > 0) "更改已保存在手机，联网后可继续同步"
                else "当前无法连接节点，显示本机保存的待办"
            mutable.update { it.copy(online = if (e is ApiException && e.status in setOf(401, 403)) false else it.online, syncError = message) }
            false
        } finally { mutable.update { it.copy(loading = false, syncing = false, refreshing = false) } }
    }

    fun save(existing: Todo?, title: String, notes: String, finished: () -> Unit) =
        saveDetails(existing, title, notes, existing?.details ?: JSONObject(), finished)

    fun saveDetails(existing: Todo?, title: String, notes: String, details: JSONObject, finished: () -> Unit) {
        val id = existing?.id ?: UUID.randomUUID().toString()
        val body = JSONObject().put("title", title.trim()).put("notes", notes).put("details", JSONObject(details.toString()))
        if (existing == null) body.put("id", id)
        enqueue(id, if (existing == null) "POST" else "PATCH", body, finished)
    }

    fun updateDetails(item: Todo, details: JSONObject) = enqueue(item.id, "PATCH", JSONObject().put("details", JSONObject(details.toString())))
    fun toggle(item: Todo) = enqueue(item.id, "PATCH", JSONObject().put("completed", !item.completed))
    fun delete(item: Todo, finished: () -> Unit) = enqueue(item.id, "DELETE", JSONObject(), finished)

    private fun enqueue(id: String, method: String, body: JSONObject, finished: () -> Unit = {}) {
        if (mutable.value.mutating) return
        mutable.update { it.copy(mutating = true) }
        scope.launch {
            var committed = false
            try {
                stateLock.withLock {
                    restore()
                    val operation = TodoOperation(UUID.randomUUID().toString(), id, method, body)
                    require(outbox.size < 1000) { "待同步更改过多，请先联网同步" }
                    commit(applyOperation(mutable.value.items, operation), outbox + operation)
                }
                committed = true
                requestSync(changed = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e) }
            finally { mutable.update { it.copy(mutating = false) } }
            // A successful callback may enqueue the next selected deletion.
            // Release the local mutation guard first so that next edit is not dropped.
            if (committed) finished()
        }
    }

    /** Explicit recovery for a rejected outbox; callers must confirm losing unsynchronized edits first. */
    fun discardLocalChanges() {
        val discardedIds = outbox.mapTo(mutableSetOf()) { it.id }
        scope.launch {
            syncLock.withLock {
                try {
                    val remote = api.call("/v1/todos").objects("todos").map(::todo)
                    stateLock.withLock {
                        restore()
                        val remaining = outbox.filterNot { it.id in discardedIds }
                        var merged = remote
                        remaining.forEach { merged = applyOperation(merged, it) }
                        commit(keepLocalAttachments(merged, mutable.value.items), remaining)
                    }
                    mutable.update { it.copy(online = true, syncError = null) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure(e) }
            }
        }
    }

    /** A successful online read follows the whole outbox before a native task may be dispatched. */
    fun prepareDispatch(todoId: String, ready: (Todo) -> Unit) {
        if (mutable.value.dispatching) return
        mutable.update { it.copy(dispatching = true) }
        scope.launch {
            try {
                if (!requestSync(changed = true).await()) return@launch
                val current = mutable.value
                val item = current.items.firstOrNull { it.id == todoId } ?: return@launch
                if (current.pendingCount == 0 && !current.mutating && !item.completed && item.threadId.isBlank()) ready(item)
            } finally { mutable.update { it.copy(dispatching = false) } }
        }

    }
}

private fun applyOperation(items: List<Todo>, operation: TodoOperation): List<Todo> {
    if (operation.method == "DELETE") return items.filterNot { it.id == operation.todoId }
    val current = items.firstOrNull { it.id == operation.todoId }
        ?: Todo(operation.todoId, "", "", false, "", "")
    val body = operation.body
    val updated = current.copy(title = if (body.has("title")) body.string("title") else current.title,
        notes = if (body.has("notes")) body.string("notes") else current.notes,
        completed = if (body.has("completed")) body.optBoolean("completed") else current.completed,
        details = body.optJSONObject("details") ?: current.details)
    return if (items.any { it.id == operation.todoId }) items.map { if (it.id == operation.todoId) updated else it } else listOf(updated) + items
}

private fun todoJson(item: Todo) = JSONObject().put("id", item.id).put("title", item.title).put("notes", item.notes)
    .put("completed", item.completed).put("projectId", item.projectId).put("threadId", item.threadId).put("details", item.details)
