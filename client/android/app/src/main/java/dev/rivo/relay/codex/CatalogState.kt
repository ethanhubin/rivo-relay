package dev.rivo.relay.codex

import dev.rivo.relay.data.*
import dev.rivo.relay.ui.projects.ProjectSummary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

private const val PINNED_PROJECTS = "pinnedProjectIds"

data class CatalogState(
    val projects: List<ProjectSummary> = emptyList(), val threads: List<ThreadSummary> = emptyList(),
    val selectedProject: ProjectSummary? = null, val projectsLoading: Boolean = false, val threadsLoading: Boolean = false,
    val models: List<JSONObject> = emptyList(), val archived: Boolean = false,
    val unreadThreadIds: Set<String> = emptySet(), val usage: CodexUsage? = null,
    val usageLoading: Boolean = false, val usageError: String? = null,
    val projectsFresh: Boolean = false, val threadsFresh: Boolean = false,
    val modelsSource: String = "", val modelsStale: Boolean = false, val modelsError: String? = null,
    val pinnedProjectIds: List<String> = emptyList(),
) { val loading: Boolean get() = projectsLoading || threadsLoading }

/** Separate catalog/list owner; stale requests cannot overwrite another project or archive filter. */
class CatalogController(private val api: RelayApi, private val scope: CoroutineScope, private val failure: (Throwable) -> Unit,
    private val store: ConversationStore? = null,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutable = MutableStateFlow(CatalogState(pinnedProjectIds = readProjectPins()))
    val state = mutable.asStateFlow()
    private val runtimeThreads = mutableMapOf<Pair<String, Boolean>, List<ThreadSummary>>()
    private fun scopeKey() = mutable.value.selectedProject?.id?.let { it to mutable.value.archived }
    private var projectsJob: Job? = null
    private var listJob: Job? = null
    private var listKey: String? = null
    private var listReadPending = false
    private var usageJob: Job? = null
    private var usageUpdatedAt = 0L
    private var generation = 0
    private data class CreatedThread(val summary: ThreadSummary, val expiresAt: Long)
    private val createdThreads = mutableMapOf<String, CreatedThread>()
    private val readVersions = mutableMapOf<String, Long>()

    private fun readProjectPins(): List<String> {
        val saved = store?.getMetadata(PINNED_PROJECTS) ?: return emptyList()
        val ids = runCatching { JSONArray(saved) }.getOrNull() ?: return emptyList()
        return List(ids.length()) { ids.optString(it) }.filter { it.isNotBlank() }.distinct()
    }

    fun setProjectPinned(projectId: String, pinned: Boolean) {
        val current = mutable.value
        val ids = current.pinnedProjectIds
        if (current.projects.none { it.id == projectId } || (projectId in ids) == pinned) return
        val updated = if (pinned) listOf(projectId) + ids else ids - projectId
        store?.putMetadata(PINNED_PROJECTS, JSONArray(updated).toString())
        mutable.update { it.copy(pinnedProjectIds = updated) }
    }

    /** Manual refresh, node startup and visible-home polling share one read. */
    @Synchronized
    fun refreshProjects(): Job {
        projectsJob?.takeUnless { it.isCompleted }?.let { return it }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            mutable.update { it.copy(projectsLoading = true) }
            try {
                if (mutable.value.projects.isEmpty()) try {
                    store?.loadCatalog("projects")?.let { applyProjects(it, fresh = false) }
                } catch (e: Exception) { if (e is CancellationException) throw e }
                val response = api.call("/v1/projects")
                applyProjects(response, fresh = true)
                try { store?.saveCatalog("projects", response) } catch (e: Exception) { if (e is CancellationException) throw e }
            } catch (e: Exception) {
                if (e !is CancellationException) { mutable.update { it.copy(projectsFresh = false) }; failure(e) }
            } finally { mutable.update { it.copy(projectsLoading = false) } }
        }
        projectsJob = job
        job.start()
        return job
    }

    suspend fun reconnect() {
        // A transport cancellation can still be unwinding when refresh is called.
        // Do not join/reuse that obsolete job as the new foreground refresh.
        projectsJob?.cancelAndJoin()
        listJob?.cancelAndJoin()
        refreshProjects()
        refreshThreads()
    }

    private fun applyProjects(response: JSONObject, fresh: Boolean) {
        val projects = catalogRows(response, "projects").map {
            ProjectSummary(it.string("id"), it.string("name"), it.string("path"), it.optInt("threadCount"),
                outstandingWorkCount = it.optInt("outstandingWorkCount", 0).coerceAtLeast(0),
                runningTurnCount = it.optInt("runningTurnCount", 0).coerceAtLeast(0),
                activeGoalCount = it.optInt("activeGoalCount", 0).coerceAtLeast(0))
        }
        val selectedId = mutable.value.selectedProject?.id ?: store?.getMetadata("selectedProjectId")
        // A catalog refresh is not a navigation action. A temporarily missing
        // project must not erase the selected conversation list or cancel its read.
        val selected = projects.firstOrNull { it.id == selectedId } ?: mutable.value.selectedProject
        val changedProject = mutable.value.selectedProject?.id != selected?.id
        if (changedProject) { listJob?.cancel(); generation++ }
        mutable.update { current -> current.copy(projects = projects, selectedProject = selected, projectsFresh = fresh,
            threads = if (changedProject) emptyList() else current.threads,
            threadsFresh = if (changedProject) false else current.threadsFresh,
            threadsLoading = if (changedProject) false else current.threadsLoading,
            unreadThreadIds = if (changedProject) emptySet() else current.unreadThreadIds) }
    }

    /** Call only while the project home is STARTED; cancellation ends polling. */
    suspend fun observeProjects(pollIntervalMillis: Long = 5_000) {
        require(pollIntervalMillis > 0)
        while (currentCoroutineContext().isActive) {
            refreshProjects().join()
            delay(pollIntervalMillis)
        }
    }

    fun project(project: ProjectSummary) {
        if (mutable.value.selectedProject?.id == project.id && !mutable.value.archived) {
            refreshThreads()
            return
        }
        store?.putMetadata("selectedProjectId", project.id)
        mutable.update { it.copy(selectedProject = project, threads = runtimeThreads[project.id to false].orEmpty(), archived = false, unreadThreadIds = emptySet(), threadsFresh = false) }
        applyThreads(scopeKey()?.let { runtimeThreads[it] }.orEmpty(), fresh = false)
        refreshThreads(coalesceSwitch = true)
    }

    fun setArchived(archived: Boolean) {
        if (mutable.value.archived == archived) return
        mutable.update { it.copy(archived = archived, threads = runtimeThreads[it.selectedProject?.id to archived].orEmpty(), unreadThreadIds = emptySet(), threadsFresh = false) }
        applyThreads(scopeKey()?.let { runtimeThreads[it] }.orEmpty(), fresh = false)
        refreshThreads(coalesceSwitch = true)
    }

    fun refreshThreads(coalesceSwitch: Boolean = false, force: Boolean = false): Job? {
        val project = mutable.value.selectedProject ?: return null
        val archived = mutable.value.archived
        val cacheKey = "threads:${project.id}:$archived"
        if (!force && listReadPending && listKey == cacheKey) listJob?.takeIf { it.isActive }?.let { return it }
        listJob?.cancel()
        listKey = cacheKey
        listReadPending = true
        val targetGeneration = ++generation
        listJob = scope.launch {
            mutable.update { it.copy(threadsLoading = true) }
            try {
                // Rapid project/filter navigation only sends the final selection.
                if (coalesceSwitch) delay(120)
                if ((project.id to archived) !in runtimeThreads) try { store?.loadCatalog(cacheKey)?.let {
                    if (generation == targetGeneration) applyThreads(it.objects("threads").map(::threadSummary), fresh = false)
                } } catch (e: Exception) { if (e is CancellationException) throw e }
                val response = api.call(threadsPath(project.id, archived))
                if (generation == targetGeneration) listReadPending = false
                if (generation == targetGeneration) applyThreads(catalogRows(response, "threads").map(::threadSummary), fresh = true)
                if (generation == targetGeneration) try { store?.saveCatalog(cacheKey, response) } catch (e: Exception) { if (e is CancellationException) throw e }
            } catch (e: Exception) { if (e !is CancellationException && generation == targetGeneration) {
                mutable.update { it.copy(threadsFresh = false) }; failure(e)
            } }
            finally { if (generation == targetGeneration) {
                listReadPending = false
                mutable.update { it.copy(threadsLoading = false) }
            } }
        }
        return listJob
    }

    /** The visible list owns polling; background navigation must cancel this coroutine. */
    suspend fun observeThreads(activeIntervalMillis: Long = 15_000, idleIntervalMillis: Long = 60_000) {
        require(activeIntervalMillis > 0 && idleIntervalMillis > 0)
        // Reuse an in-flight project/filter read instead of canceling it on entry.
        refreshThreads()?.join()
        while (currentCoroutineContext().isActive) {
            delay(if (mutable.value.threads.any(::threadIsActive)) activeIntervalMillis else idleIntervalMillis)
            refreshThreads()?.join()
        }
    }

    fun recordCreatedThread(projectId: String, threadId: String) {
        if (projectId.isBlank() || threadId.isBlank()) return
        if (mutable.value.threads.any { it.id == threadId }) return
        // A receipt bridges indexing delay, but cannot keep a remotely archived/deleted row forever.
        createdThreads.putIfAbsent(threadId, CreatedThread(
            ThreadSummary(threadId, "新会话", "", "unknown", "", projectId), monotonicMillis() + 120_000))
        if (mutable.value.selectedProject?.id != projectId || mutable.value.archived) return
        applyThreads(mutable.value.threads, fresh = mutable.value.threadsFresh, confirmCreated = false)
        refreshThreads(force = true)
    }

    fun forgetCreatedThreads(ids: Collection<String>) {
        val removed = ids.toSet()
        removed.forEach { createdThreads.remove(it) }
        runtimeThreads.replaceAll { _, rows -> rows.filterNot { it.id in removed } }
        if (removed.isEmpty()) return
        mutable.update { current ->
            val threads = current.threads.filterNot { it.id in removed }
            current.copy(threads = threads, unreadThreadIds = current.unreadThreadIds - removed)
        }
    }

    private fun applyThreads(threads: List<ThreadSummary>, fresh: Boolean, confirmCreated: Boolean = fresh) {
        if (confirmCreated) threads.forEach { createdThreads.remove(it.id) }
        val current = mutable.value
        val expired = createdThreads.filterValues { it.expiresAt <= monotonicMillis() }.keys.toSet()
        createdThreads.keys.removeAll(expired)
        val key = scopeKey()
        // A successful response is the complete list; failures never reach this path.
        val authoritative = if (fresh) threads else threads.filterNot { it.id in expired }
        val provisional = if (current.archived) emptyList() else createdThreads.values.map { it.summary }.filter {
            it.projectId == current.selectedProject?.id && authoritative.none { thread -> thread.id == it.id }
        }
        val merged = provisional + authoritative
        if (key != null && (fresh || merged.isNotEmpty() || key in runtimeThreads)) runtimeThreads[key] = merged
        mutable.update { it.copy(threads = stableThreadOrder(merged, current.threads.map { t -> t.id }),
            unreadThreadIds = unread(merged), threadsFresh = fresh) }
    }

    /** Fresh detail reads also reconcile the list; read markers alone cannot stop its spinner. */
    fun reconcileSession(snapshot: SessionSnapshot) {
        if (!snapshot.nativeReady) return
        val current = mutable.value
        val prior = current.threads.firstOrNull { it.id == snapshot.id } ?: return
        if (snapshot.updatedAt < prior.updatedAt) return
        val updated = prior.copy(name = snapshot.name.ifBlank { prior.name },
            status = snapshot.status, nativeExecution = snapshot.nativeExecution,
            deliveryState = snapshot.deliveryState, displayState = snapshot.displayState,
            queueDepth = snapshot.queueDepth, recoveryRequired = snapshot.recoveryRequired,
            hasOutstandingWork = snapshot.isOngoing || snapshot.queue.isNotEmpty(),
            updatedAt = snapshot.updatedAt)
        createdThreads[snapshot.id]?.let { createdThreads[snapshot.id] = it.copy(summary = updated) }
        if (updated == prior) return
        // A list read started before this detail observation must not restore stale execution.
        generation++
        listJob?.cancel()
        listJob = null
        listReadPending = false
        val threads = current.threads.map { if (it.id == updated.id) updated else it }
        scopeKey()?.let { runtimeThreads[it] = threads }
        mutable.update { it.copy(threads = stableThreadOrder(threads, current.threads.map { t -> t.id }),
            threadsLoading = false, unreadThreadIds = unread(threads)) }
    }

    private fun unread(threads: List<ThreadSummary>) = threads.filter { thread ->
        val readAt = readVersions.getOrPut(thread.id) {
            // Existing server history starts read; persist the baseline across app restarts.
            store?.getMetadata("read:${thread.id}")?.toLongOrNull() ?: thread.updatedAt.also {
                store?.putMetadata("read:${thread.id}", it.toString())
            }
        }
        thread.updatedAt > readAt && !threadIsExecuting(thread)
    }.mapTo(mutableSetOf()) { it.id }

    /** Call only after the selected thread's fresh history was successfully displayed. */
    fun markRead(threadId: String, updatedAt: Long = 0) {
        val version = maxOf(updatedAt, mutable.value.threads.firstOrNull { it.id == threadId }?.updatedAt ?: 0L)
        val prior = readVersions[threadId] ?: store?.getMetadata("read:$threadId")?.toLongOrNull() ?: 0L
        if (version <= prior) return
        readVersions[threadId] = maxOf(prior, version)
        store?.putMetadata("read:$threadId", readVersions.getValue(threadId).toString())
        mutable.update { it.copy(unreadThreadIds = unread(it.threads)) }
    }

    suspend fun threadsFor(projectId: String): List<ThreadSummary> {
        val current = mutable.value
        if (current.selectedProject?.id == projectId && !current.archived && (current.threads.isNotEmpty() || current.threadsFresh))
            return current.threads
        runtimeThreads[projectId to false]?.let { return it }
        val key = "threads:$projectId:false"
        store?.loadCatalog(key)?.let { return it.objects("threads").map(::threadSummary) }
        val response = api.call(threadsPath(projectId, false))
        store?.saveCatalog(key, response)
        return response.objects("threads").map(::threadSummary)
    }

    fun refreshUsage(force: Boolean = false): Job? {
        usageJob?.takeUnless { it.isCompleted }?.let { return it }
        if (!force && usageUpdatedAt > 0 && System.currentTimeMillis() - usageUpdatedAt < 300_000) return null
        return scope.launch {
            mutable.update { it.copy(usageLoading = true, usageError = null) }
            try {
                val usage = catalogUsage(api.call("/v1/usage"))
                usageUpdatedAt = System.currentTimeMillis()
                mutable.update { it.copy(usage = usage) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is ApiException && e.status == 401) failure(e)
                mutable.update { it.copy(usageError = "暂时无法读取额度，保留上次结果") }
            } finally { mutable.update { it.copy(usageLoading = false) } }
        }.also { usageJob = it }
    }

    fun loadModels() = scope.launch {
        try {
            val response = api.call("/v1/models")
            val catalog = response.optJSONObject("catalog")
            mutable.update { it.copy(models = response.objects("models"), modelsSource = catalog?.string("source").orEmpty(),
                modelsStale = catalog?.optBoolean("stale") == true, modelsError = null) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is ApiException && e.status == 401) failure(e)
            mutable.update { it.copy(modelsStale = true, modelsError = "暂时无法更新模型目录，保留上次可用选项") }
        }
    }
}

private fun threadsPath(projectId: String, archived: Boolean) = "/v1/threads?projectId=${encoded(projectId)}&archived=$archived"
fun threadIsActive(thread: ThreadSummary): Boolean = thread.hasOutstandingWork || thread.recoveryRequired ||
    thread.queueDepth > 0 || thread.nativeExecution == "running" ||
    thread.status in setOf("running", "inProgress", "active", "waiting", "queued", "starting")

/** Queue attention and execution animation are independent. */
fun threadIsExecuting(thread: ThreadSummary): Boolean =
    thread.status != "waiting" && (thread.nativeExecution == "running" || thread.status in setOf("running", "inProgress", "active", "starting"))

fun threadPendingLabel(thread: ThreadSummary): String? = when {
    thread.status == "waiting" -> "待回答"
    thread.recoveryRequired -> "待恢复"
    thread.deliveryState == "failed" -> "失败"
    thread.queueDepth > 0 || thread.deliveryState == "queued" -> "排队中"
    thread.deliveryState == "dispatching" -> "发送中"
    thread.hasOutstandingWork -> "待处理"
    thread.nativeExecution == "unknown" || thread.status == "unknown" -> "状态待确认"
    else -> null
}

/** QiGuang ordering: pinned/active groups, stable active ranks, newest completed first. */
fun stableThreadOrder(threads: List<ThreadSummary>, previousIds: List<String> = emptyList()): List<ThreadSummary> {
    val ranks = previousIds.withIndex().associate { it.value to it.index }
    return threads.withIndex().sortedWith(compareByDescending<IndexedValue<ThreadSummary>> { it.value.pinned }
        .thenByDescending { threadIsActive(it.value) }
        .thenBy { if (threadIsActive(it.value)) ranks[it.value.id] ?: ranks.size + it.index else 0 }
        .thenByDescending { if (threadIsActive(it.value)) 0L else it.value.updatedAt }).map { it.value }
}

/** Only current, non-archived server results may restore background observations. */
fun pinnedThreadsToObserve(state: CatalogState): Set<String> =
    if (!state.threadsFresh || state.archived) emptySet()
    else state.threads.asSequence().filter { it.pinned }.take(12).map { it.id }.toSet()

/** Cached data during a normal refresh is not evidence that the node is offline. */
internal fun catalogCacheNoticeVisible(route: String, state: CatalogState, online: Boolean): Boolean = when (route) {
    "projects" -> state.projects.isNotEmpty() && !state.projectsFresh && (!online || !state.projectsLoading)
    "sessions" -> state.threads.isNotEmpty() && !state.threadsFresh && (!online || !state.threadsLoading)
    else -> false
}

/** Malformed successful responses are failures, never authoritative empty catalogs. */
private fun catalogRows(response: JSONObject, key: String): List<JSONObject> {
    val rows = response.optJSONArray(key) ?: throw java.io.IOException("列表响应缺少 $key")
    return List(rows.length()) { index ->
        val row = rows.optJSONObject(index) ?: throw java.io.IOException("列表响应包含无效条目")
        if (row.string("id").isBlank()) throw java.io.IOException("列表响应缺少条目标识")
        row
    }
}
