package dev.rivo.relay.codex

import dev.rivo.relay.data.*
import dev.rivo.relay.ui.projects.ProjectSummary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class CatalogState(val projects: List<ProjectSummary> = emptyList(), val threads: List<ThreadSummary> = emptyList(),
    val selectedProject: ProjectSummary? = null, val loading: Boolean = false, val models: List<org.json.JSONObject> = emptyList())

/** Separate catalog/list owner, adapted from QiGuang state holders; stale targets never overwrite a new project. */
class CatalogController(private val api: RelayApi, private val scope: CoroutineScope, private val failure: (Throwable) -> Unit) {
    private val mutable = MutableStateFlow(CatalogState())
    val state = mutable.asStateFlow()
    private var projectsJob: Job? = null
    private var listJob: Job? = null
    private var generation = 0

    /** Manual refresh, node startup and visible-home polling share one read. */
    @Synchronized
    fun refreshProjects(): Job {
        projectsJob?.takeUnless { it.isCompleted }?.let { return it }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            mutable.update { it.copy(loading = true) }
            try {
                val projects = api.call("/v1/projects").objects("projects").map {
                    ProjectSummary(it.string("id"), it.string("name"), it.string("path"), it.optInt("threadCount"),
                        outstandingWorkCount = it.optInt("outstandingWorkCount", 0).coerceAtLeast(0))
                }
                mutable.update { it.copy(projects = projects) }
            } catch (e: Exception) {
                if (e !is CancellationException) failure(e)
            } finally { mutable.update { it.copy(loading = false) } }
        }
        projectsJob = job
        job.start()
        return job
    }

    /** Call only while the project home is STARTED; cancellation ends polling.
     * A shared in-flight read may finish, but no subsequent poll is scheduled.
     */
    suspend fun observeProjects(pollIntervalMillis: Long = 5_000) {
        require(pollIntervalMillis > 0)
        while (currentCoroutineContext().isActive) {
            refreshProjects().join()
            delay(pollIntervalMillis)
        }
    }
    fun project(project: ProjectSummary) { mutable.update { it.copy(selectedProject = project, threads = emptyList()) }; refreshThreads() }
    fun refreshThreads() {
        val project = mutable.value.selectedProject ?: return
        listJob?.cancel(); val targetGeneration = ++generation
        listJob = scope.launch {
            mutable.update { it.copy(loading = true) }
            try {
                val threads = threadsFor(project.id)
                if (generation == targetGeneration) mutable.update { it.copy(threads = threads) }
            } catch (e: Exception) { if (e !is CancellationException) failure(e) }
            finally { if (generation == targetGeneration) mutable.update { it.copy(loading = false) } }
        }
    }
    suspend fun threadsFor(projectId: String) = api.call("/v1/threads?projectId=" + encoded(projectId)).objects("threads").map(::threadSummary)
    fun loadModels() = scope.launch { try { val models = api.call("/v1/models").objects("models"); mutable.update { it.copy(models = models) } } catch (e: Exception) { if (e !is CancellationException) failure(e) } }
}
