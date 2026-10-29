package dev.rivo.relay.codex

import dev.rivo.relay.data.*
import dev.rivo.relay.ui.projects.ProjectSummary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class CatalogState(
    val projects: List<ProjectSummary> = emptyList(), val threads: List<ThreadSummary> = emptyList(),
    val selectedProject: ProjectSummary? = null, val projectsLoading: Boolean = false, val threadsLoading: Boolean = false,
    val models: List<JSONObject> = emptyList(), val archived: Boolean = false,
    val unreadThreadIds: Set<String> = emptySet(), val usage: CodexUsage? = null,
    val usageLoading: Boolean = false, val usageError: String? = null,
    val projectsFresh: Boolean = false, val threadsFresh: Boolean = false,
) { val loading: Boolean get() = projectsLoading || threadsLoading }

/** Separate catalog/list owner; stale requests cannot overwrite another project or archive filter. */
class CatalogController(private val api: RelayApi, private val scope: CoroutineScope, private val failure: (Throwable) -> Unit,
    private val store: ConversationStore? = null) {
    private val mutable = MutableStateFlow(CatalogState())
    val state = mutable.asStateFlow()
    private var projectsJob: Job? = null
    private var listJob: Job? = null
    private var usageJob: Job? = null
    private var usageUpdatedAt = 0L
    private var generation = 0
    private val readVersions = mutableMapOf<String, Long>()

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

    private fun applyProjects(response: JSONObject, fresh: Boolean) {
        val projects = response.objects("projects").map {
            ProjectSummary(it.string("id"), it.string("name"), it.string("path"), it.optInt("threadCount"),
                outstandingWorkCount = it.optInt("outstandingWorkCount", 0).coerceAtLeast(0))
        }
        val selectedId = mutable.value.selectedProject?.id ?: store?.getMetadata("selectedProjectId")
        val selected = projects.firstOrNull { it.id == selectedId }
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
        store?.putMetadata("selectedProjectId", project.id)
        mutable.update { it.copy(selectedProject = project, threads = emptyList(), archived = false, unreadThreadIds = emptySet(), threadsFresh = false) }
        refreshThreads()
    }

    fun setArchived(archived: Boolean) {
        if (mutable.value.archived == archived) return
        mutable.update { it.copy(archived = archived, threads = emptyList(), unreadThreadIds = emptySet(), threadsFresh = false) }
        refreshThreads()
    }

    fun refreshThreads() {
        val project = mutable.value.selectedProject ?: return
        val archived = mutable.value.archived
        val cacheKey = "threads:${project.id}:$archived"
        listJob?.cancel()
        val targetGeneration = ++generation
        listJob = scope.launch {
            mutable.update { it.copy(threadsLoading = true) }
            try {
                if (mutable.value.threads.isEmpty()) try { store?.loadCatalog(cacheKey)?.let {
                    if (generation == targetGeneration) applyThreads(it.objects("threads").map(::threadSummary), fresh = false)
                } } catch (e: Exception) { if (e is CancellationException) throw e }
                val response = api.call(threadsPath(project.id, archived))
                if (generation == targetGeneration) applyThreads(response.objects("threads").map(::threadSummary), fresh = true)
                try { store?.saveCatalog(cacheKey, response) } catch (e: Exception) { if (e is CancellationException) throw e }
            } catch (e: Exception) { if (e !is CancellationException && generation == targetGeneration) {
                mutable.update { it.copy(threadsFresh = false) }; failure(e)
            } }
            finally { if (generation == targetGeneration) mutable.update { it.copy(threadsLoading = false) } }
        }
    }

    private fun applyThreads(threads: List<ThreadSummary>, fresh: Boolean) {
        mutable.update { it.copy(threads = threads, unreadThreadIds = unread(threads), threadsFresh = fresh) }
    }

    private fun unread(threads: List<ThreadSummary>) = threads.filter { thread ->
        val readAt = readVersions.getOrPut(thread.id) { store?.getMetadata("read:${thread.id}")?.toLongOrNull() ?: 0L }
        thread.updatedAt > readAt && !threadIsActive(thread)
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

    suspend fun threadsFor(projectId: String): List<ThreadSummary> =
        api.call(threadsPath(projectId, false)).objects("threads").map(::threadSummary)

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
        try { val models = api.call("/v1/models").objects("models"); mutable.update { it.copy(models = models) } }
        catch (e: Exception) { if (e !is CancellationException) failure(e) }
    }
}

private fun threadsPath(projectId: String, archived: Boolean) = "/v1/threads?projectId=${encoded(projectId)}&archived=$archived"
fun threadIsActive(thread: ThreadSummary): Boolean = thread.status in setOf("running", "inProgress", "active", "waiting", "queued", "starting")

/** Only current, non-archived server results may restore background observations. */
fun pinnedThreadsToObserve(state: CatalogState): Set<String> =
    if (!state.threadsFresh || state.archived) emptySet()
    else state.threads.asSequence().filter { it.pinned }.take(12).map { it.id }.toSet()
