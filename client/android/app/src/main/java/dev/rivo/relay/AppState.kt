package dev.rivo.relay

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

class NodeRuntime(val connection: NodeConnection, private val scope: CoroutineScope, vault: CredentialStore, context: Context,
    requestNotificationPermission: () -> Unit, private val failure: (Throwable) -> Unit) {
    val api = connection.api()
    val conversations = ConversationStore(ConversationStore.nodeDirectory(File(context.filesDir, "conversations"),
        "${connection.nodeId}:${connection.deviceId}:${connection.base}"))
    val notifications = TaskNotifications(context.applicationContext, api, scope, connection.base, connection.name, requestNotificationPermission, failure)
    val catalog = CatalogController(api, scope, failure, store = conversations)
    val session = SessionController(api, scope, conversations, notifications::observe, notifications::setVisibleThread, failure)
    val submissions = SubmissionCoordinator(api, scope, vault, "pending:${connection.nodeId}:${connection.deviceId}:${connection.base}", failure)
    private val todoReminders = TodoReminderScheduler(context.applicationContext, "${connection.nodeId}:${connection.deviceId}:${connection.base}", connection.base)
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
    fun persistDraft(threadId: String, draft: ConversationDraft) {
        conversations.enqueueDraft(threadId, draft) { error ->
            scope.launch { failure(IllegalArgumentException(error.message ?: "无法保存手机草稿，请检查可用空间")) }
        }
    }
}
data class AppState(val node: NodeRuntime? = null, val pairing: Boolean = false, val error: String? = null,
    val savedNodes: List<SavedNode> = emptyList(), val requestNotificationPermission: Boolean = false)
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
        val node = NodeRuntime(connection, CoroutineScope(viewModelScope.coroutineContext + job), vault, getApplication(), ::requestTaskNotifications) { error ->
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
    fun openNotification(target: TaskNotificationTarget) {
        if (mutable.value.node?.connection?.base != target.base) {
            val connection = pairing.select(target.base)
            if (connection == null) { setError("此通知的节点尚未登录，请先连接节点。"); return }
            activate(connection)
        }
        mutable.value.node?.openNotificationThread(target.threadId)
    }
    fun openTodoNotification(base: String) {
        if (base.isBlank()) return
        if (mutable.value.node?.connection?.base != base) {
            val connection = pairing.select(base)
            if (connection == null) { setError("此提醒的节点尚未登录，请先连接节点。"); return }
            activate(connection)
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
        } else mutable.update { it.copy(error = when (error) { is ApiException, is IllegalArgumentException -> error.message; else -> "无法连接节点，请检查网络。已提交操作不会自动重发。" }) }
    }
    fun disconnect() {
        // Clear the active selection; saved node credentials remain available for switching.
        nodeJob?.cancel(); pairing.forget(); mutable.value = AppState(savedNodes = pairing.saved())
    }
}
