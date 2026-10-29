package dev.rivo.relay.ui.todos

import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TodosState(val items: List<Todo> = emptyList(), val loading: Boolean = false, val mutating: Boolean = false,
    val syncing: Boolean = false, val pendingCount: Int = 0, val online: Boolean = false, val syncError: String? = null)

private data class TodoOperation(val id: String, val todoId: String, val method: String, val body: JSONObject) {
    fun json() = JSONObject().put("id", id).put("todoId", todoId).put("method", method).put("body", body)
}

/** Todo CRUD is idempotent state assignment, kept in a durable node-scoped outbox until acknowledged.
 * Codex dispatch is deliberately excluded: it still uses SubmissionCoordinator and is never retried here.
 */
class TodosController(private val api: RelayApi, private val scope: CoroutineScope, private val failure: (Throwable) -> Unit,
    private val store: ConversationStore? = null, private val onChanged: (List<Todo>) -> Unit = {}) {
    private val mutable = MutableStateFlow(TodosState())
    val state = mutable.asStateFlow()
    private val stateLock = Mutex()
    private val syncLock = Mutex()
    private var loaded = false
    private var outbox = emptyList<TodoOperation>()

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
            onChanged(mutable.value.items)
        }
        loaded = true
    }

    private suspend fun commit(items: List<Todo>, operations: List<TodoOperation>) {
        val saved = JSONObject().put("version", 1).put("items", JSONArray(items.map(::todoJson)))
            .put("operations", JSONArray(operations.map(TodoOperation::json)))
        store?.saveTodoState(saved) // Must succeed before either the UI accepts the edit or a network write starts.
        outbox = operations
        mutable.update { it.copy(items = items, pendingCount = operations.size) }
        onChanged(items)
    }

    fun refresh() = scope.launch { synchronize() }
    fun synchronizeNow() { scope.launch { synchronize() } }

    private suspend fun synchronize(): Boolean = syncLock.withLock {
        mutable.update { it.copy(loading = it.items.isEmpty(), syncing = true, syncError = null) }
        try {
            stateLock.withLock { restore() }
            while (true) {
                val operation = stateLock.withLock { outbox.firstOrNull() } ?: break
                try {
                    api.call(if (operation.method == "POST") "/v1/todos" else "/v1/todos/${encoded(operation.todoId)}", operation.method, operation.body)
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
                commit(merged, outbox)
            }
            mutable.update { it.copy(online = true, syncError = null) }
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is ApiException && e.status == 401) failure(e)
            val message = if (e is ApiException && e.status in 400..499) "节点未接受更改：${e.message}"
                else if (mutable.value.pendingCount > 0) "更改已保存在手机，联网后可继续同步"
                else "当前无法连接节点，显示本机保存的待办"
            mutable.update { it.copy(online = false, syncError = message) }
            false
        } finally { mutable.update { it.copy(loading = false, syncing = false) } }
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
            try {
                stateLock.withLock {
                    restore()
                    val operation = TodoOperation(UUID.randomUUID().toString(), id, method, body)
                    require(outbox.size < 1000) { "待同步更改过多，请先联网同步" }
                    commit(applyOperation(mutable.value.items, operation), outbox + operation)
                }
                finished()
                scope.launch { synchronize() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e) }
            finally { mutable.update { it.copy(mutating = false) } }
        }
    }

    /** Explicit recovery for a rejected outbox; callers must confirm losing unsynchronized edits first. */
    fun discardLocalChanges() {
        scope.launch {
            syncLock.withLock {
                try {
                    val remote = api.call("/v1/todos").objects("todos").map(::todo)
                    stateLock.withLock { restore(); commit(remote, emptyList()) }
                    mutable.update { it.copy(online = true, syncError = null) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure(e) }
            }
        }
    }

    /** A successful online read follows the whole outbox before a native task may be dispatched. */
    fun prepareDispatch(todoId: String, ready: (Todo) -> Unit) {
        scope.launch {
            if (!synchronize()) return@launch
            val item = mutable.value.items.firstOrNull { it.id == todoId } ?: return@launch
            if (mutable.value.pendingCount == 0 && !item.completed) ready(item)
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
