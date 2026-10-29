package dev.rivo.relay.ui.todos

import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class TodosState(val items: List<Todo> = emptyList(), val loading: Boolean = false, val mutating: Boolean = false)
class TodosController(private val api: RelayApi, private val scope: CoroutineScope, private val failure: (Throwable) -> Unit) {
    private val mutable = MutableStateFlow(TodosState())
    val state = mutable.asStateFlow()
    fun refresh() = scope.launch {
        mutable.update { it.copy(loading = true) }
        try { val items = api.call("/v1/todos").objects("todos").map(::todo); mutable.update { it.copy(items = items) } }
        catch (e: Exception) { if (e !is CancellationException) failure(e) } finally { mutable.update { it.copy(loading = false) } }
    }
    fun save(existing: Todo?, title: String, notes: String, finished: () -> Unit) = mutate {
        api.call(if (existing == null) "/v1/todos" else "/v1/todos/${encoded(existing.id)}", if (existing == null) "POST" else "PATCH", JSONObject().put("title", title.trim()).put("notes", notes))
        finished()
    }
    fun toggle(item: Todo) = mutate { api.call("/v1/todos/${encoded(item.id)}", "PATCH", JSONObject().put("completed", !item.completed)) }
    fun delete(item: Todo, finished: () -> Unit) = mutate { api.call("/v1/todos/${encoded(item.id)}", "DELETE"); finished() }
    private fun mutate(block: suspend () -> Unit) {
        if (mutable.value.mutating) return
        mutable.update { it.copy(mutating = true) }
        scope.launch {
            try { block(); refresh() } catch (e: Exception) { if (e !is CancellationException) failure(e) }
            finally { mutable.update { it.copy(mutating = false) } }
        }
    }
}
