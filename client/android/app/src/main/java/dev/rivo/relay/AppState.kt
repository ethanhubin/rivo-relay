package dev.rivo.relay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.rivo.relay.connection.*
import dev.rivo.relay.codex.*
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.todos.TodosController
import dev.rivo.relay.ui.todos.TodoReminderScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class NodeRuntime(connection: NodeConnection, private val scope: CoroutineScope, vault: CredentialStore, context: Context,
    val conversations: ConversationStore,
    requestNotificationPermission: () -> Unit, private val failure: (Throwable) -> Unit) {
    var connection by mutableStateOf(connection)
        private set
    fun updateName(updated: NodeConnection) {
        connection = updated
        notifications.nodeName = updated.name
    }
    val api = connection.api()
    private var transportNetwork = defaultNetwork(context)
    val network = observeDefaultNetwork(context).onEach {
        if (it != transportNetwork) { transportNetwork = it; api.resetTransport() }
    }.stateIn(scope, SharingStarted.Eagerly, transportNetwork)
    private val sync = NodeSessionSync(api, scope)
    val notifications = TaskNotifications(context.applicationContext, api, scope, connection.base, connection.name, requestNotificationPermission, failure, sync)
    val catalog = CatalogController(api, scope, failure, store = conversations, sync = sync)
    val session = SessionController(api, scope, conversations, notifications::observe, notifications::setVisibleThread, sync = sync, failure = failure)
    private val pendingRevisionMutable = MutableStateFlow(0L)
    val pendingRevision = pendingRevisionMutable.asStateFlow()
    val submissions = SubmissionCoordinator(api, scope, vault, "pending:${connection.nodeId}:${connection.deviceId}:${connection.storageBase}",
        onResolved = { pending, response ->
            session.invalidateControl()
            session.resolveSubmissionDurably(pending.id, response)
            if (response.string("status") == "accepted" && (pending.path.endsWith("/archive") || pending.path.endsWith("/delete"))) {
                // Per-operation reconciliation also runs after process restart and partial batches.
                pending.threadId?.let { catalog.forgetCreatedThreads(listOf(it)) }
                catalog.refreshThreads(force = true)
            }
            val (acceptedDraft, pendingChanged) = withContext(Dispatchers.IO) {
                val changed = conversations.resolvePendingSubmission(pending.id, response)
                conversations.markSentDraftResolved(pending.id, response) to changed
            }
            if (acceptedDraft) scope.launch {
                try { conversations.clearAcceptedSentDraft(pending.id) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    failure(e)
                    reconcilePendingReceipts()
                }
            }
            if (pendingChanged != null) {
                if (response.string("status") == "accepted" && pending.path == "/v1/threads")
                    catalog.recordCreatedThread(pendingChanged, response.optJSONObject("receipt")?.string("threadId").orEmpty())
                pendingRevisionMutable.value++
            }
        }, failure = failure)
    private var pendingRecoveryJob: Job? = null
    fun reconcilePendingReceipts() {
        if (pendingRecoveryJob?.isActive == true) return
        // The coordinator owns its callback and durable ID; do not resolve only
        // the draft checkpoint and leave its global submission lock behind.
        submissions.reconcile()
        // Never repeat native mutations; missing receipts are fenced by the node.
        pendingRecoveryJob = scope.launch {
            recoverPendingCheckpoints(
                read = { withContext(Dispatchers.IO) { conversations.unresolvedSubmissionIds() } },
                resolve = recover@{ id ->
                    // The active coordinator owns callbacks and its durable submission lock.
                    if (id == submissions.state.value.pending?.id) return@recover
                    val checkpoint = conversations.sentDraftCheckpoints()[id]
                    val pendingCheckpoint = withContext(Dispatchers.IO) { conversations.pendingSubmissionCheckpoints()[id] }
                    val needsPendingResolution = pendingCheckpoint != null
                    val accepted = if (checkpoint?.string("status") == "accepted" && !needsPendingResolution) true else {
                        val response = api.reconcileSubmission(id)
                        val (pendingChanged, acceptedDraft) = withContext(Dispatchers.IO) {
                            conversations.resolvePendingSubmission(id, response) to conversations.markSentDraftResolved(id, response)
                        }
                        session.resolveSubmissionDurably(id, response)
                        if (pendingChanged != null) {
                            if (response.string("status") == "accepted" && pendingCheckpoint?.string("kind") == "create")
                                catalog.recordCreatedThread(pendingChanged, response.optJSONObject("receipt")?.string("threadId").orEmpty())
                            pendingRevisionMutable.value++
                        }
                        acceptedDraft
                    }
                    if (accepted) conversations.clearAcceptedSentDraft(id)
                }, failure = failure)
        }
    }
    init { reconcilePendingReceipts() }
    private val todoReminders = TodoReminderScheduler(context.applicationContext, "${connection.nodeId}:${connection.deviceId}:${connection.storageBase}", connection.base)
    val todos = TodosController(api, scope, failure, store = conversations, onChanged = { items ->
        todoReminders.update(items)
        if (items.any { !it.completed && it.details.optLong("reminderAt", 0) > System.currentTimeMillis() }) requestNotificationPermission()
    })
    private val navigationMutable = MutableStateFlow<String?>(null)
    val navigation = navigationMutable.asStateFlow()
    private val todoNavigationMutable = MutableStateFlow(false)
    val todoNavigation = todoNavigationMutable.asStateFlow()
    fun openNotificationThread(threadId: String) { todoNavigationMutable.value = false; navigationMutable.value = threadId }
    fun openNotificationTodos() { navigationMutable.value = null; todoNavigationMutable.value = true }
    fun consumeTodoNavigation() { todoNavigationMutable.value = false }
    fun consumeNavigation() { navigationMutable.value = null }
    val recovery = ConnectionRecovery(scope,
        probe = { api.call("/v1/node", reconnectProbe = true) },
        reset = { api.resetTransport() },
        restored = {
            session.refresh()
            catalog.reconnect()
            todos.refresh()
            reconcilePendingReceipts()
        }, rejected = failure, synchronizedRead = {
            session.refresh()
            catalog.refreshProjects()
            catalog.refreshThreads()
            todos.refresh()
            reconcilePendingReceipts()
        })
    init { api.recovery = recovery }
    fun persistDraft(threadId: String, draft: ConversationDraft) {
        conversations.enqueueDraft(threadId, draft) { error ->
            scope.launch { failure(IllegalArgumentException(error.message ?: "无法保存手机草稿，请检查可用空间")) }
        }
        if (threadId.startsWith("pending:")) pendingRevisionMutable.value++
    }
}
data class AppState(val node: NodeRuntime? = null, val pairing: Boolean = false, val error: String? = null,
    val savedNodes: List<SavedNode> = emptyList(), val requestNotificationPermission: Boolean = false, val reconnecting: Boolean = false)
class AppViewModel(application: Application): AndroidViewModel(application) {
    private val vault = CredentialStore(application)
    private val pairing = PairingRepository(vault)
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private var nodeJob: Job? = null
    private val nodeHandoff = NodeJobHandoff()
    private var lastStore: ConversationStore? = null
    init { mutable.value = AppState(savedNodes = pairing.saved()); pairing.restore()?.let { activate(it) } }
    private fun activate(connection: NodeConnection, openThread: String? = null, openTodos: Boolean = false, openProjects: Boolean = false) {
        val previousStore = lastStore
        val predecessors = nodeHandoff.retire(nodeJob)
        val job = SupervisorJob(viewModelScope.coroutineContext[Job]); nodeJob = job
        val scope = CoroutineScope(viewModelScope.coroutineContext + job)
        mutable.value = AppState(savedNodes = pairing.saved(), reconnecting = true)
        scope.launch {
            try {
                // Cancellation may still be committing a submission outcome. Keep
                // every predecessor across rapid switches, including cancelled activations.
                predecessors.joinAll()
                val store = withContext(Dispatchers.IO) {
                    runCatching { previousStore?.flushMetadata() }
                    ConversationStore(ConversationStore.nodeDirectory(File(getApplication<Application>().filesDir, "conversations"),
                        "${connection.nodeId}:${connection.deviceId}:${connection.storageBase}"))
                }
                if (nodeJob !== job) return@launch
                if (openProjects) store.putMetadata("lastRoute", "projects")
                lastStore = store
                val node = NodeRuntime(connection, scope, vault, getApplication(), store, ::requestTaskNotifications) { error ->
                    if (nodeJob === job && job.isActive) fail(error)
                }
                mutable.value = AppState(node = node, savedNodes = pairing.saved())
                openThread?.let(node::openNotificationThread)
                if (openTodos) node.openNotificationTodos()
                syncNodeName(node, scope)
                node.catalog.refreshProjects()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (nodeJob === job) { mutable.update { it.copy(reconnecting = false) }; fail(e) }
            }
        }
    }
    private fun syncNodeName(node: NodeRuntime, scope: CoroutineScope) {
        scope.launch {
            try {
                val updated = node.connection.withNodeInfo(node.api.call("/v1/node"))
                if (mutable.value.node !== node || updated === node.connection) return@launch
                pairing.updateName(updated)
                node.updateName(updated)
                mutable.update { it.copy(savedNodes = pairing.saved()) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Cosmetic metadata must not block offline use or reconnect. */ }
        }
    }
    fun pair(input: String, code: String) {
        if (mutable.value.pairing) return
        mutable.update { it.copy(pairing = true, error = null) }
        viewModelScope.launch {
            try { activate(pairing.pair(input, code), openProjects = true) } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(error = if (e is ApiException && e.status == 401) "配对码已过期、已使用或无效，请在电脑生成新码。节点地址已保存。" else e.message ?: "连接失败，请重试。", savedNodes = pairing.saved()) }
            }
            finally { mutable.update { it.copy(pairing = false) } }
        }
    }
    fun setConnectionMode(mode: dev.rivo.relay.connection.ConnectionMode) { pairing.setMode(mode)?.let { activate(it) } }
    fun selectNode(base: String) { pairing.select(base)?.let { activate(it, openProjects = true) } }
    fun settingsConnection(base: String): NodeConnection? = pairing.connection(base)
    fun setNodeConnectionMode(base: String, mode: ConnectionMode): NodeConnection? {
        val updated = pairing.setMode(base, mode) ?: return null
        if (mutable.value.node?.connection?.base == base) activate(updated)
        return updated
    }
    fun openNotification(target: TaskNotificationTarget) {
        if (mutable.value.node?.connection?.base != target.base) {
            val connection = pairing.select(target.base)
            if (connection == null) { setError("此通知的节点尚未登录，请先连接节点。"); return }
            activate(connection, openThread = target.threadId)
            return
        }
        mutable.value.node?.openNotificationThread(target.threadId)
    }
    fun openTodoNotification(base: String) {
        if (base.isBlank()) return
        if (mutable.value.node?.connection?.base != base) {
            val connection = pairing.select(base)
            if (connection == null) { setError("此提醒的节点尚未登录，请先连接节点。"); return }
            activate(connection, openTodos = true)
            return
        }
        mutable.value.node?.openNotificationTodos()
    }
    fun requestTaskNotifications() {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (!app.getSharedPreferences("notifications", Context.MODE_PRIVATE).getBoolean("permissionAsked", false))
            mutable.update { it.copy(requestNotificationPermission = true) }
    }
    fun notificationPermissionLaunched() {
        getApplication<Application>().getSharedPreferences("notifications", Context.MODE_PRIVATE).edit().putBoolean("permissionAsked", true).apply()
        mutable.update { it.copy(requestNotificationPermission = false) }
    }
    fun setError(message: String?) { mutable.update { it.copy(error = message) } }
    fun fail(error: Throwable) {
        if (error is CancellationException) return
        if (error is ApiException && error.status == 401) {
            nodeJob?.cancel(); pairing.invalidate(); mutable.value = AppState(error = "节点授权已失效，请重新扫码连接。", savedNodes = pairing.saved())
        } else if (isConnectionFailure(error) && mutable.value.node != null) {
            mutable.value.node?.recovery?.failed(error)
        } else mutable.update { it.copy(error = when (error) { is ApiException, is IllegalArgumentException -> error.message; else -> mutable.value.node?.api?.describeFailure(error) ?: connectionFailure(error) }) }
    }
    private var wasBackgrounded = false
    fun onBackground() { wasBackgrounded = true }
    fun onForeground() {
        if (!wasBackgrounded) return
        wasBackgrounded = false
        mutable.value.node?.recovery?.foreground()
    }
    fun retryConnection() {
        mutable.update { it.copy(error = null) }
        mutable.value.node?.recovery?.reconnect(manual = true)
    }
    fun disconnect() {
        // Clear the active selection; saved node credentials remain available for switching.
        nodeJob?.cancel(); pairing.forget(); mutable.value = AppState(savedNodes = pairing.saved())
    }
}
