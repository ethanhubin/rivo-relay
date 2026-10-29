package dev.rivo.relay

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.rivo.relay.connection.*
import dev.rivo.relay.codex.*
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.todos.TodosController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class NodeRuntime(val connection: NodeConnection, scope: CoroutineScope, vault: CredentialStore, failure: (Throwable) -> Unit) {
    val api = connection.api()
    val catalog = CatalogController(api, scope, failure)
    val session = SessionController(api, scope, failure)
    val submissions = SubmissionCoordinator(api, scope, vault, "pending:${connection.nodeId}:${connection.deviceId}:${connection.base}", failure)
    val todos = TodosController(api, scope, failure)
}
data class AppState(val node: NodeRuntime? = null, val pairing: Boolean = false, val error: String? = null, val savedNodes: List<SavedNode> = emptyList())
class AppViewModel(application: Application): AndroidViewModel(application) {
    private val vault = CredentialStore(application)
    private val pairing = PairingRepository(vault)
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private var nodeJob: Job? = null
    init { mutable.value = AppState(savedNodes = pairing.saved()); pairing.restore()?.let(::activate) }
    private fun activate(connection: NodeConnection) {
        nodeJob?.cancel()
        val job = SupervisorJob(viewModelScope.coroutineContext[Job]); nodeJob = job
        val node = NodeRuntime(connection, CoroutineScope(viewModelScope.coroutineContext + job), vault) { error ->
            if (nodeJob === job && job.isActive) fail(error)
        }
        mutable.value = AppState(node = node, savedNodes = pairing.saved())
        node.catalog.refreshProjects()
    }
    fun pair(input: String, code: String) {
        if (mutable.value.pairing) return
        mutable.update { it.copy(pairing = true, error = null) }
        viewModelScope.launch {
            try { activate(pairing.pair(input, code)) } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(error = if (e is ApiException && e.status == 401) "配对码已过期、已使用或无效，请在电脑生成新码。节点地址已保存。" else e.message ?: "连接失败，请重试。", savedNodes = pairing.saved()) }
            }
            finally { mutable.update { it.copy(pairing = false) } }
        }
    }
    fun selectNode(base: String) { pairing.select(base)?.let(::activate) }
    fun setError(message: String?) { mutable.update { it.copy(error = message) } }
    fun fail(error: Throwable) {
        if (error is CancellationException) return
        if (error is ApiException && error.status == 401) {
            nodeJob?.cancel(); pairing.invalidate(); mutable.value = AppState(error = "节点授权已失效，请重新扫码连接。", savedNodes = pairing.saved())
        } else mutable.update { it.copy(error = when (error) { is ApiException, is IllegalArgumentException -> error.message; else -> "无法连接节点，请检查网络。已提交操作不会自动重发。" }) }
    }
    fun disconnect() {
        // Clear the active selection; saved node credentials remain available for switching.
        nodeJob?.cancel(); pairing.forget(); mutable.value = AppState(savedNodes = pairing.saved())
    }
}
