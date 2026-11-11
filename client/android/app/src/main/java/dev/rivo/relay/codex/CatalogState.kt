package dev.rivo.relay.codex

import dev.rivo.relay.data.*
import dev.rivo.relay.ui.projects.ProjectSummary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject

private const val PINNED_PROJECTS = "pinnedProjectIds"

data class CatalogState(
    val projects: List<ProjectSummary> = emptyList(), val threads: List<ThreadSummary> = emptyList(),
    val selectedProject: ProjectSummary? = null, val projectsLoading: Boolean = false, val threadsLoading: Boolean = false,
    val models: List<JSONObject> = emptyList(), val archived: Boolean = false,
    val unreadThreadIds: Set<String> = emptySet(), val usage: CodexUsage? = null,
    val usageLoading: Boolean = false, val usageError: String? = null,
    val projectsFresh: Boolean = false, val threadsFresh: Boolean = false, val threadsComplete: Boolean = true, val threadsSummaryFresh: Boolean = false,
    val modelsSource: String = "", val modelsStale: Boolean = false, val modelsError: String? = null,
    val pinnedProjectIds: List<String> = emptyList(),
) { val loading: Boolean get() = projectsLoading || threadsLoading }

internal fun CatalogState.withProjectActivity(activity: NodeActivity): CatalogState {
    fun projectState(project: ProjectSummary) = project.copy(
        isRunning = project.id in activity.runningProjectIds)
    return copy(projects = projects.map(::projectState), selectedProject = selectedProject?.let(::projectState))
}

/** Unverified activity must not overwrite a freshly reconciled detail status. */
internal fun CatalogState.withNodeActivity(activity: NodeActivity): CatalogState =
    withProjectActivity(activity).copy(
        threads = threads.map { thread ->
            val task = activity.execution(thread.id)
            if (archived) thread else thread.copy(status = task.status,
                nativeExecution = task.nativeExecution, hasOutstandingWork = task.hasOutstandingWork,
                queueDepth = task.queueDepth, deliveryState = task.deliveryState,
                displayState = task.displayState, recoveryRequired = task.recoveryRequired)
        })

/** Separate catalog/list owner; stale requests cannot overwrite another project or archive filter. */
class CatalogController(private val api: RelayApi, private val scope: CoroutineScope, private val failure: (Throwable) -> Unit,
    private val store: ConversationStore? = null,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sync: NodeSessionSync? = null) {
    private val mutable = MutableStateFlow(CatalogState(pinnedProjectIds = readProjectPins()))
    private val readRevision = MutableStateFlow(0L)
    val state: StateFlow<CatalogState> = if (sync == null) mutable.asStateFlow() else
        combine(mutable, sync.activity, readRevision) { current, activity, _ ->
            current.withNodeActivity(activity).let { projected ->
                projected.copy(unreadThreadIds = unread(projected.threads))
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(0), mutable.value)
    private val runtimeThreads = mutableMapOf<Pair<String, Boolean>, List<ThreadSummary>>()
    private fun scopeKey() = mutable.value.selectedProject?.id?.let { it to mutable.value.archived }
    private var projectsJob: Job? = null
    private var listJob: Job? = null
    private var listKey: String? = null
    private var listReadPending = false
    private var listReadDirty = false
    private var summaryJob: Job? = null
    private var summaryDirty = false
    private var summarySupported: Boolean? = null
    private var summarySequence = 0L
    private var appliedSummarySequence = 0L
    private var usageJob: Job? = null
    private var usageUpdatedAt = 0L
    private var generation = 0
    private data class CreatedThread(val summary: ThreadSummary, val expiresAt: Long)
    private val createdThreads = mutableMapOf<String, CreatedThread>()
    private val readVersions = mutableMapOf<String, Long>()
    private val detailVersions = mutableMapOf<String, Long>()

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
        val displayed = catalogRows(response, "projects").map {
            ProjectSummary(it.string("id"), it.string("name"), it.string("path"))
        }
        val selectedId = mutable.value.selectedProject?.id ?: store?.getMetadata("selectedProjectId")
        // A catalog refresh is not a navigation action. A temporarily missing
        // project must not erase the selected conversation list or cancel its read.
        val selected = displayed.firstOrNull { it.id == selectedId } ?: mutable.value.selectedProject
        val changedProject = mutable.value.selectedProject?.id != selected?.id
        if (changedProject) { listJob?.cancel(); generation++ }
        mutable.update { current -> current.copy(projects = displayed, selectedProject = selected, projectsFresh = fresh,
            threads = if (changedProject) emptyList() else current.threads,
            threadsFresh = if (changedProject) false else current.threadsFresh,
            threadsComplete = if (changedProject) false else current.threadsComplete,
            threadsSummaryFresh = if (changedProject) false else current.threadsSummaryFresh,
            threadsLoading = if (changedProject) false else current.threadsLoading,
            unreadThreadIds = if (changedProject) emptySet() else current.unreadThreadIds) }
    }

    /** Call only while the project home is STARTED; cancellation ends polling. */
    suspend fun observeProjects(pollIntervalMillis: Long = 5_000) = coroutineScope {
        require(pollIntervalMillis > 0)
        val signals = Channel<Unit>(Channel.CONFLATED)
        val events = sync?.let { source -> launch {
            source.events(onInterrupted = { signals.trySend(Unit) }).collect { event ->
                if (catalogLifecycleEvent(event)) signals.trySend(Unit)
            }
        } }
        try {
            while (currentCoroutineContext().isActive) {
                refreshProjects().join()
                withTimeoutOrNull(pollIntervalMillis) { signals.receive() }
            }
        } finally { events?.cancel(); signals.close() }
    }

    fun project(project: ProjectSummary) {
        if (mutable.value.selectedProject?.id == project.id && !mutable.value.archived) {
            refreshThreads()
            return
        }
        store?.putMetadata("selectedProjectId", project.id)
        mutable.update { it.copy(selectedProject = project, threads = runtimeThreads[project.id to false].orEmpty(), archived = false, unreadThreadIds = emptySet(), threadsFresh = false, threadsComplete = false, threadsSummaryFresh = false) }
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
        listReadDirty = false
        val targetGeneration = ++generation
        listJob = scope.launch {
            mutable.update { it.copy(threadsLoading = true, threadsSummaryFresh = false) }
            try {
                // Rapid project/filter navigation only sends the final selection.
                if (coalesceSwitch) delay(120)
                if ((project.id to archived) !in runtimeThreads) try { store?.loadCatalog(cacheKey)?.let {
                    if (generation == targetGeneration) applyThreads(it.objects("threads").map(::threadSummary), fresh = false)
                } } catch (e: Exception) { if (e is CancellationException) throw e }
                var readDetailVersions = detailVersions.toMap()
                val summaryVersion = ++summarySequence
                var response = api.call(threadsPath(project.id, archived) + "&view=summary")
                summarySupported = !response.optBoolean("catalogComplete", true)
                if (summarySupported == true) {
                    if (generation == targetGeneration && summaryVersion >= appliedSummarySequence) {
                        appliedSummarySequence = summaryVersion
                        applySummary(response)
                    }
                    readDetailVersions = detailVersions.toMap()
                    response = api.call(threadsPath(project.id, archived))
                }
                if (generation == targetGeneration) listReadPending = false
                if (generation == targetGeneration) {
                    val existing = mutable.value.threads.associateBy { it.id }
                    val merged = catalogRows(response, "threads").map(::threadSummary).map { row ->
                        val live = existing[row.id]
                        val metadata = if (live != null && live.updatedAt > row.updatedAt)
                            row.copy(name = live.name, preview = live.preview, updatedAt = live.updatedAt) else row
                        if (live != null && detailVersions[row.id] != readDetailVersions[row.id])
                            metadata.withExecution(live).copy(name = live.name, updatedAt = maxOf(metadata.updatedAt, live.updatedAt))
                        else metadata
                    }
                    // A lifecycle event/new summary may have found rows after
                    // this full scan started. Keep them until the dirty follow-up.
                    val ids = merged.mapTo(mutableSetOf()) { it.id }
                    val extras = if (listReadDirty) mutable.value.threads.filterNot { it.id in ids } else emptyList()
                    applyThreads(merged + extras, fresh = false)
                    // Persist reconciled fields rather than the stale full response
                    // that lost a race to a detail or metadata observation.
                    val reconciled = merged.associateBy { it.id }
                    catalogRows(response, "threads").forEach { row -> reconciled[row.string("id")]?.let { live ->
                        row.put("name", live.name).put("preview", live.preview).put("updatedAt", live.updatedAt)
                            .put("status", live.status).put("nativeExecution", live.nativeExecution)
                            .put("deliveryState", live.deliveryState).put("displayState", live.displayState)
                            .put("queueDepth", live.queueDepth).put("recoveryRequired", live.recoveryRequired)
                            .put("hasOutstandingWork", live.hasOutstandingWork).put("writerOwner", live.writerOwner)
                    } }
                    mutable.update { it.copy(threadsFresh = !listReadDirty, threadsComplete = !listReadDirty, threadsSummaryFresh = listReadDirty) }
                }
                if (generation == targetGeneration && !listReadDirty) try { store?.saveCatalog(cacheKey, response) } catch (e: Exception) { if (e is CancellationException) throw e }
            } catch (e: Exception) { if (e !is CancellationException && generation == targetGeneration) {
                mutable.update { it.copy(threadsFresh = false) }; failure(e)
            } }
            finally { if (generation == targetGeneration) {
                listReadPending = false
                mutable.update { it.copy(threadsLoading = false) }
                if (listReadDirty && currentCoroutineContext().isActive) {
                    listReadDirty = false
                    listJob = null
                    refreshThreads()
                }
            } }
        }
        return listJob
    }

    /** Shared SSE and a small metadata poll discover Desktop changes without waiting for full scans. */
    suspend fun observeThreads(activeIntervalMillis: Long = 3_000, idleIntervalMillis: Long = 5_000,
                               summaryIntervalMillis: Long = 3_000) = coroutineScope {
        require(activeIntervalMillis > 0 && idleIntervalMillis > 0 && summaryIntervalMillis > 0)
        val signals = Channel<Unit>(Channel.CONFLATED)
        val events = sync?.let { source -> launch {
            source.events(onInterrupted = { signals.trySend(Unit) }).collect { event ->
                if (catalogLifecycleEvent(event)) {
                    if (listReadPending) {
                        if (catalogMembershipEvent(event)) listReadDirty = true
                        refreshThreadSummary()
                    } else signals.trySend(Unit)
                }
            }
        } }
        val metadata = launch {
            while (isActive) {
                delay(summaryIntervalMillis)
                refreshThreadSummary()?.join()
            }
        }
        try {
            refreshThreads()?.join()
            while (currentCoroutineContext().isActive) {
                val interval = if (threadCatalogNeedsFastRefresh(mutable.value)) activeIntervalMillis else idleIntervalMillis
                withTimeoutOrNull(interval) { signals.receive() }
                refreshThreads()?.join()
            }
        } finally { events?.cancel(); metadata.cancel(); signals.close() }
    }

    private fun applySummary(response: JSONObject): Boolean {
        val summary = catalogRows(response, "threads").map(::threadSummary)
        val existing = mutable.value.threads.associateBy { it.id }
        val changed = summary.any { it.id !in existing }
        val metadata = summary.map { row -> existing[row.id]?.let { old ->
            row.withExecution(old).copy(executionMode = old.executionMode, workspace = old.workspace,
                externalWorkspace = old.externalWorkspace,
                name = if (row.updatedAt >= old.updatedAt) row.name else old.name,
                updatedAt = maxOf(row.updatedAt, old.updatedAt))
        } ?: row }
        val combined = (mutable.value.threads + metadata).associateBy { it.id }.values.toList()
        applyThreads(combined, fresh = mutable.value.threadsFresh && !changed, confirmCreated = false)
        mutable.update { it.copy(threadsComplete = it.threadsComplete && !changed, threadsSummaryFresh = true) }
        return changed
    }

    private fun refreshThreadSummary(): Job? {
        if (summarySupported == false) return null // Older nodes ignore view and would scan everything.
        val project = mutable.value.selectedProject ?: return null
        val archived = mutable.value.archived
        summaryJob?.takeIf { it.isActive }?.let { summaryDirty = true; return it }
        val targetGeneration = generation
        summaryDirty = false
        val version = ++summarySequence
        summaryJob = scope.launch {
            try {
                val response = api.call(threadsPath(project.id, archived) + "&view=summary")
                if (targetGeneration != generation || version < appliedSummarySequence) return@launch
                summarySupported = !response.optBoolean("catalogComplete", true)
                if (summarySupported == true) {
                    appliedSummarySequence = version
                    val changed = applySummary(response)
                    if (changed) {
                        if (listReadPending) listReadDirty = true else refreshThreads()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Full status read owns visible failure/retry policy. */ }
            finally {
                summaryJob = null
                if (summaryDirty && targetGeneration == generation && currentCoroutineContext().isActive) {
                    summaryDirty = false
                    refreshThreadSummary()
                }
            }
        }
        return summaryJob
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

    /** Detail reads update metadata only; execution is owned by NodeActivity. */
    fun reconcileSession(snapshot: SessionSnapshot) {
        if (!snapshot.nativeReady) return
        val current = mutable.value
        val prior = current.threads.firstOrNull { it.id == snapshot.id } ?: return
        // Native updatedAt is metadata recency, not execution-event ordering.
        // The session controller has already rejected obsolete detail responses.
        detailVersions[snapshot.id] = (detailVersions[snapshot.id] ?: 0) + 1
        val updated = prior.copy(name = if (snapshot.updatedAt >= prior.updatedAt) snapshot.name.ifBlank { prior.name } else prior.name,
            updatedAt = maxOf(snapshot.updatedAt, prior.updatedAt))
        createdThreads[snapshot.id]?.let { createdThreads[snapshot.id] = it.copy(summary = updated) }
        if (updated == prior) return
        // Keep the catalog request alive so other rows and legacy membership
        // still finish. Its captured per-thread revision protects this state.
        val threads = current.threads.map { if (it.id == updated.id) updated else it }
        scopeKey()?.let { runtimeThreads[it] = threads }
        mutable.update { it.copy(threads = stableThreadOrder(threads, current.threads.map { t -> t.id }),
            unreadThreadIds = unread(threads)) }
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
    fun markRead(threadId: String, updatedAt: Long) {
        // Only acknowledge the history actually displayed, never a newer catalog row.
        val version = updatedAt
        val prior = readVersions[threadId] ?: store?.getMetadata("read:$threadId")?.toLongOrNull() ?: 0L
        if (version <= prior) return
        readVersions[threadId] = maxOf(prior, version)
        store?.putMetadata("read:$threadId", readVersions.getValue(threadId).toString())
        mutable.update { it.copy(unreadThreadIds = unread(it.threads)) }
        readRevision.update { it + 1 }
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
/** Only current execution determines the list's active group. */
fun threadIsActive(thread: ThreadSummary): Boolean = threadIsExecuting(thread)

fun threadIsExecuting(thread: ThreadSummary): Boolean =
    thread.status != "waiting" && (thread.nativeExecution == "running" ||
        (thread.nativeExecution.isBlank() && thread.status in setOf("running", "inProgress", "active", "starting")))

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
    if ((!state.threadsFresh && !state.threadsSummaryFresh) || state.archived) emptySet()
    else state.threads.asSequence().filter { it.pinned }.take(12).map { it.id }.toSet()

/** Cached data during a normal refresh is not evidence that the node is offline. */
internal fun catalogCacheNoticeVisible(route: String, state: CatalogState, online: Boolean): Boolean = when (route) {
    "projects" -> state.projects.isNotEmpty() && !state.projectsFresh && (!online || !state.projectsLoading)
    "sessions" -> state.threads.isNotEmpty() && !state.threadsFresh && (!online || (!state.threadsLoading && !state.threadsSummaryFresh))
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

/** Summary metadata is never authoritative execution/queue evidence. */
private fun ThreadSummary.withExecution(state: ThreadSummary): ThreadSummary = copy(
    status = state.status, nativeExecution = state.nativeExecution, deliveryState = state.deliveryState,
    displayState = state.displayState, queueDepth = state.queueDepth, recoveryRequired = state.recoveryRequired,
    hasOutstandingWork = state.hasOutstandingWork, writerOwner = state.writerOwner)

internal fun threadCatalogNeedsFastRefresh(state: CatalogState): Boolean =
    !state.threadsFresh || !state.threadsComplete || state.threads.any { threadIsActive(it) || it.status == "unknown" || it.nativeExecution == "unknown" }

/** Only lifecycle/queue events invalidate catalog metadata; streaming tokens never do. */
internal fun catalogLifecycleEvent(event: RelayEvent): Boolean {
    if (event.type in setOf("reset", "connected", "revoked", "queue", "submission")) return true
    if (event.type != "native") return false
    val method = event.payload.string("method")
    return (method.startsWith("thread/") && method != "thread/tokenUsage/updated" && !method.endsWith("/delta")) ||
        method.startsWith("project/") || method.startsWith("goal/") || method.startsWith("queue/") ||
        method in setOf("turn/started", "turn/completed", "turn/failed", "turn/interrupted", "serverRequest/resolved",
            "relay/queueUpdated", "relay/queueChanged", "relay/historyInvalidated", "relay/ownershipChanged", "relay/writerDisconnected", "relay/nativeDisconnected") ||
        (method.startsWith("item/") && (method.endsWith("/requestApproval") || method.endsWith("/requestUserInput")))
}

/** Execution notifications do not invalidate the membership of an in-flight list. */
internal fun catalogMembershipEvent(event: RelayEvent): Boolean {
    if (event.type in setOf("reset", "connected", "revoked")) return true
    if (event.type != "native") return false
    val method = event.payload.string("method")
    return method.startsWith("project/") || method in setOf("thread/started", "thread/deleted", "thread/archived", "thread/unarchived")
}

/** Destructive actions require a known idle thread with no pending work. */
fun canDeleteThread(thread: ThreadSummary): Boolean =
    thread.status in setOf("idle", "completed", "failed", "interrupted") &&
        thread.nativeExecution in setOf("", "idle") && !threadIsExecuting(thread) &&
        thread.queueDepth == 0 && !thread.hasOutstandingWork && !thread.recoveryRequired
