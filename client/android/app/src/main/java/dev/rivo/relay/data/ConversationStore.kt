package dev.rivo.relay.data

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Private, node-scoped recovery data. Never contains node credentials or authority to write. */
data class StoredDraftImage(val id: String, val bytes: ByteArray)
data class ConversationDraft(val text: String = "", val images: List<StoredDraftImage> = emptyList(),
    val files: List<JSONObject> = emptyList(), val model: String? = null, val effort: String? = null, val mode: String? = null, val serviceTier: String? = null, val sandbox: String? = null, val approvalPolicy: String? = null) {
    val empty get() = text.isEmpty() && images.isEmpty() && files.isEmpty() && model == null && effort == null && mode == null && serviceTier == null && sandbox == null && approvalPolicy == null
}
data class PendingConversation(val id: String, val projectId: String, val draft: ConversationDraft, val createdAt: Long) {
    val draftKey get() = "pending:$id"
}

class ConversationStore(private val directory: File) {
    private val lock = Any()
    private val metadata = synchronized(lock) { read("metadata", 256 * 1024) ?: JSONObject() }

    companion object {
        fun nodeDirectory(root: File, identity: String) = File(root, digest(identity))
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private const val SNAPSHOT_LIMIT = 2 * 1024 * 1024
        private const val DRAFT_LIMIT = 64 * 1024 * 1024
        private data class DraftWrite(val store: ConversationStore, val id: String, val draft: ConversationDraft,
            val failed: (Throwable) -> Unit, val completed: List<CompletableDeferred<Unit>> = emptyList())
        private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val writerLock = Any()
        private val waiting = linkedMapOf<Pair<String, String>, DraftWrite>()
        private var currentWrite: Pair<Pair<String, String>, DraftWrite>? = null
        private var writerActive = false
    }

    /** Route/node disposal does not cancel queued saves. One writer preserves order across store instances. */
    fun enqueueDraft(threadId: String, draft: ConversationDraft, onFailure: (Throwable) -> Unit = {}) =
        enqueueDraftWrite(threadId, draft, onFailure)

    suspend fun saveDraftOrdered(threadId: String, draft: ConversationDraft) {
        val completion = CompletableDeferred<Unit>()
        enqueueDraftWrite(threadId, draft, {}, completion)
        completion.await()
    }

    private fun enqueueDraftWrite(threadId: String, draft: ConversationDraft, onFailure: (Throwable) -> Unit,
        completion: CompletableDeferred<Unit>? = null) {
        synchronized(writerLock) {
            val key = directory.absolutePath to threadId
            val observers = waiting[key]?.completed.orEmpty() + listOfNotNull(completion)
            waiting[key] = DraftWrite(this, threadId, draft, onFailure, observers)
            if (writerActive) return
            writerActive = true
        }
        writerScope.launch {
            while (true) {
                // Coalesce keystrokes and image edits before serializing private recovery data.
                delay(120)
                val next = synchronized(writerLock) {
                    val key = waiting.keys.firstOrNull()
                    if (key == null) { writerActive = false; null }
                    else (key to waiting.remove(key)!!).also { currentWrite = it }
                } ?: break
                try {
                    next.second.store.saveDraft(next.second.id, next.second.draft)
                    next.second.completed.forEach { it.complete(Unit) }
                } catch (e: Exception) {
                    next.second.completed.forEach { it.completeExceptionally(e) }
                    runCatching { next.second.failed(e) }
                }
                finally { synchronized(writerLock) { currentWrite = null } }
            }
        }
    }
    fun getMetadata(key: String): String? = synchronized(lock) { metadata.string(key).takeIf { metadata.has(key) } }
    fun putMetadata(key: String, value: String?) = synchronized(lock) {
        if (value == null) metadata.remove(key) else metadata.put(key, value)
        // Small navigation/read markers are committed before returning so a stopped process cannot lose them.
        runCatching { write("metadata", metadata.toString()) }
        Unit
    }
    private fun decodeDraft(json: JSONObject): ConversationDraft = runCatching {
        ConversationDraft(json.string("text"), json.objects("images").take(10).mapNotNull {
            val bytes = runCatching { Base64.getDecoder().decode(it.string("bytes")) }.getOrNull()
            if (bytes != null && bytes.size <= 20 * 1024 * 1024) StoredDraftImage(it.string("id"), bytes) else null
        }, json.objects("files").take(16).filter {
            when (it.string("type")) {
                "mention", "skill", "localAudio" -> it.string("path").isNotBlank()
                "audio" -> it.string("url").isNotBlank()
                else -> false
            }
        }, json.string("model").takeIf(String::isNotBlank), json.string("effort").takeIf(String::isNotBlank),
            json.string("mode").takeIf { it == "plan" || it == "default" },
            json.string("serviceTier").takeIf { it == "default" || it == "priority" },
            json.string("sandbox").takeIf(String::isNotBlank), json.string("approvalPolicy").takeIf(String::isNotBlank))
    }.getOrDefault(ConversationDraft())

    private fun latestDraft(threadId: String): ConversationDraft {
        val queued = synchronized(writerLock) {
            val key = directory.absolutePath to threadId
            waiting[key]?.draft ?: currentWrite?.takeIf { it.first == key }?.second?.draft
        }
        return queued ?: synchronized(lock) { read("draft-${digest(threadId)}", DRAFT_LIMIT)?.let(::decodeDraft) ?: ConversationDraft() }
    }
    suspend fun loadDraft(threadId: String): ConversationDraft = withContext(Dispatchers.IO) {
        if (threadId.startsWith("pending:") && getMetadata("completed:$threadId") == "true") return@withContext ConversationDraft()
        val current = latestDraft(threadId)
        val recoverable = sentDraftCheckpoints().entries.firstOrNull { it.value.string("threadId") == threadId && it.value.string("status") == "pending" }
        val draft = if (recoverable != null && !recoverable.value.optBoolean("preserveNewerDraft") &&
            (current.empty || draftFingerprint(current) == recoverable.value.string("baselineFingerprint"))) synchronized(lock) {
            read("sent-input-${digest(recoverable.key)}", DRAFT_LIMIT)?.let(::decodeDraft) ?: current
        } else current
        val accepted = sentDraftCheckpoints().values.firstOrNull { it.string("threadId") == threadId &&
            it.string("status") == "accepted" && it.string("fingerprint") == draftFingerprint(draft) }
        // A durable accepted receipt hides only the unchanged old draft while cleanup is pending.
        if (accepted == null) draft else decodeDraft(accepted.getJSONObject("settings"))
    }
    private fun draftJson(draft: ConversationDraft) = JSONObject().put("text", draft.text).put("model", draft.model)
        .put("effort", draft.effort).put("mode", draft.mode).put("serviceTier", draft.serviceTier).put("sandbox", draft.sandbox).put("approvalPolicy", draft.approvalPolicy)
        .put("files", JSONArray(draft.files.take(16)))
        .put("images", JSONArray(draft.images.take(10).filter { it.bytes.size <= 20 * 1024 * 1024 }.map {
            JSONObject().put("id", it.id).put("bytes", Base64.getEncoder().encodeToString(it.bytes))
        }))
    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonicalJson(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.opt(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun draftFingerprint(draft: ConversationDraft) = digest(canonicalJson(draftJson(draft)))

    fun captureDraft(threadId: String): ConversationDraft = decodeDraft(draftJson(latestDraft(threadId)))
    suspend fun checkpointSentDraft(submissionId: String, threadId: String, draft: ConversationDraft = captureDraft(threadId)) {
        val baseline = synchronized(lock) { read("draft-${digest(threadId)}", DRAFT_LIMIT)?.let(::decodeDraft) ?: ConversationDraft() }
        val latest = captureDraft(threadId)
        val preserveNewerDraft = !latest.empty && draftFingerprint(latest) != draftFingerprint(draft)
        // Persist the frozen input independently before awaiting the normal draft writer.
        // A newer edit may coalesce that writer, but cannot erase the submitted bytes.
        withContext(Dispatchers.IO) { synchronized(lock) {
            val frozen = draftJson(draft).toString()
            require(frozen.toByteArray().size <= DRAFT_LIMIT) { "草稿过大，无法保存在手机" }
            write("sent-input-${digest(submissionId)}", frozen)
        } }
        val settings = draftJson(ConversationDraft(model = draft.model, effort = draft.effort, mode = draft.mode, serviceTier = draft.serviceTier, sandbox = draft.sandbox, approvalPolicy = draft.approvalPolicy))
        putRecoveryMetadata("sent-draft:$submissionId", JSONObject().put("threadId", threadId)
            .put("fingerprint", draftFingerprint(draft)).put("baselineFingerprint", draftFingerprint(baseline))
            .put("preserveNewerDraft", preserveNewerDraft)
            .put("settings", settings).put("status", "pending").toString())
        // Keep any newer local edit; the immutable submitted bytes above remain recoverable.
        saveDraftOrdered(threadId, captureDraft(threadId))
    }
    fun sentDraftCheckpoints(): Map<String, JSONObject> = synchronized(lock) {
        metadata.keys().asSequence().filter { it.startsWith("sent-draft:") }.mapNotNull { key ->
            runCatching { key.removePrefix("sent-draft:") to JSONObject(metadata.getString(key)) }.getOrNull()
        }.toMap()
    }
    fun markSentDraftResolved(submissionId: String, response: JSONObject): Boolean {
        val saved = getMetadata("sent-draft:$submissionId")?.let(::JSONObject) ?: return false
        when (response.string("status")) {
            "accepted" -> putRecoveryMetadata("sent-draft:$submissionId", saved.put("status", "accepted").toString())
            "failed" -> {
                putRecoveryMetadata("sent-draft:$submissionId", null)
                synchronized(lock) { file("sent-input-${digest(submissionId)}").delete() }
            }
            else -> return false
        }
        return response.string("status") == "accepted"
    }
    suspend fun clearAcceptedSentDraft(submissionId: String) {
        val saved = getMetadata("sent-draft:$submissionId")?.let(::JSONObject) ?: return
        if (saved.string("status") != "accepted") return
        val completion = CompletableDeferred<Unit>()
        synchronized(writerLock) {
            val threadId = saved.getString("threadId")
            if (draftFingerprint(latestDraft(threadId)) == saved.string("fingerprint")) {
                enqueueDraftWrite(threadId, decodeDraft(saved.getJSONObject("settings")), {}, completion)
            } else completion.complete(Unit) // A newer draft belongs to the user and must survive.
        }
        completion.await()
        putRecoveryMetadata("sent-draft:$submissionId", null)
        withContext(Dispatchers.IO) { synchronized(lock) { file("sent-input-${digest(submissionId)}").delete() } }
    }
    suspend fun saveDraft(threadId: String, draft: ConversationDraft) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val name = "draft-${digest(threadId)}"
            if (draft.empty) { file(name).delete(); return@synchronized }
            val json = draftJson(draft)
            val text = json.toString()
            require(text.toByteArray().size <= DRAFT_LIMIT) { "草稿过大，无法保存在手机" }
            // Unsent user work is durable data, never evicted by the bounded history cache.
            write(name, text)
        }
    }
    private fun putRecoveryMetadata(key: String, value: String?) = synchronized(lock) {
        val next = JSONObject(metadata.toString())
        if (value == null) next.remove(key) else next.put(key, value)
        write("metadata", next.toString())
        if (value == null) metadata.remove(key) else metadata.put(key, value)
        Unit
    }
    fun defaultSettings(): JSONObject? = getMetadata("codex-default-settings")?.let {
        runCatching { JSONObject(it) }.getOrNull()
    }
    fun saveDefaultSettings(settings: JSONObject) {
        val model = settings.string("model").takeIf(String::isNotBlank)
        val value = JSONObject().put("model", model)
        settings.string("effort").takeIf(String::isNotBlank)?.let { value.put("effort", it) }
        settings.string("serviceTier").takeIf { it == "default" || it == "priority" }?.let { value.put("serviceTier", it) }
        settings.string("sandbox").takeIf { it in setOf("read-only", "workspace-write", "danger-full-access") }?.let { value.put("sandbox", it) }
        settings.string("approvalPolicy").takeIf { it in setOf("on-request", "untrusted", "never", "on-failure") }?.let { value.put("approvalPolicy", it) }
        putRecoveryMetadata("codex-default-settings", value.toString())
    }
    fun pendingCompleted(projectId: String) = getMetadata("completed:pending:$projectId") == "true"
    fun completedPendingThreadId(projectId: String): String? = getMetadata("pending-completed-thread:$projectId")
    fun pendingThreadId(projectId: String): String? = getMetadata("pending-thread:$projectId")
    fun pendingWorktreeRef(projectId: String): String? = getMetadata("pending-ref:$projectId")
    fun setPendingWorktreeRef(projectId: String, ref: String?) = putRecoveryMetadata("pending-ref:$projectId", ref)
    fun beginPending(projectId: String) {
        val key = "pending:$projectId"
        if (getMetadata("completed:$key") == "true") enqueueDraft(key, ConversationDraft())
        putRecoveryMetadata("completed:$key", null)
        putRecoveryMetadata("pending-completed-thread:$projectId", null)
    }
    private fun pendingEntry(id: String): JSONObject? = getMetadata("pending-entry:$id")?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun migratePending(projectId: String) {
        if (pendingEntry(projectId) != null || pendingCompleted(projectId)) return
        val draft = latestDraft("pending:$projectId")
        if (draft.text.isNotBlank() || draft.images.isNotEmpty() || draft.files.isNotEmpty() || pendingThreadId(projectId) != null) {
            putRecoveryMetadata("pending-entry:$projectId", JSONObject().put("projectId", projectId)
                .put("createdAt", System.currentTimeMillis()).toString())
        }
    }
    /** Each new chat owns a distinct draft, including multiple unsent chats in one project. */
    fun createPending(projectId: String): String {
        migratePending(projectId)
        val id = UUID.randomUUID().toString()
        putRecoveryMetadata("pending-entry:$id", JSONObject().put("projectId", projectId)
            .put("createdAt", System.currentTimeMillis()).toString())
        return id
    }
    suspend fun listPending(projectId: String): List<PendingConversation> = withContext(Dispatchers.IO) {
        migratePending(projectId)
        val ids = synchronized(lock) { metadata.keys().asSequence().filter { it.startsWith("pending-entry:") }
            .map { it.removePrefix("pending-entry:") }.toList() }
        ids.mapNotNull { id ->
            val entry = pendingEntry(id) ?: return@mapNotNull null
            if (entry.string("projectId") != projectId || pendingCompleted(id)) return@mapNotNull null
            val draft = loadDraft("pending:$id")
            if (draft.text.isBlank() && draft.images.isEmpty() && draft.files.isEmpty() && pendingThreadId(id) == null) return@mapNotNull null
            PendingConversation(id, projectId, draft, entry.optLong("createdAt"))
        }.sortedByDescending { it.createdAt }
    }
    /** Checkpoint before crossing the network boundary, so reconciliation survives process death. */
    fun checkpointPendingSubmission(projectId: String, submissionId: String, kind: String) {
        require(kind == "create" || kind == "send")
        putRecoveryMetadata("pending-submission:$submissionId", JSONObject().put("projectId", pendingEntry(projectId)?.string("projectId") ?: projectId)
            .put("pendingId", projectId).put("kind", kind).toString())
    }
    fun resolvePendingSubmission(submissionId: String, response: JSONObject): String? {
        val checkpoint = getMetadata("pending-submission:$submissionId")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val projectId = checkpoint.string("projectId")
        val pendingId = checkpoint.string("pendingId").ifBlank { projectId }
        val threadId = response.optJSONObject("receipt")?.string("threadId")?.takeIf(String::isNotBlank)
        if (checkpoint.string("kind") == "create" && threadId != null) putRecoveryMetadata("pending-thread:$pendingId", threadId)
        if (response.string("status") == "accepted") {
            if (checkpoint.string("kind") == "send") {
                val acceptedThread = threadId ?: pendingThreadId(pendingId)
                finishPending(pendingId)
                if (acceptedThread != null) putRecoveryMetadata("pending-completed-thread:$pendingId", acceptedThread)
            }
        }
        if (response.string("status") in setOf("accepted", "failed")) putRecoveryMetadata("pending-submission:$submissionId", null)
        return projectId
    }
    fun finishPending(projectId: String) {
        // Tombstone first: an old asynchronous draft write cannot resurrect accepted input.
        putRecoveryMetadata("completed:pending:$projectId", "true")
        putRecoveryMetadata("pending-thread:$projectId", null)
        putRecoveryMetadata("pending-ref:$projectId", null)
        putRecoveryMetadata("pending-entry:$projectId", null)
        enqueueDraft("pending:$projectId", ConversationDraft())
    }

    /** Only unresolved display receipts live here; canonical history remains native-owned. */
    suspend fun savePendingMessage(message: PendingNativeMessage) = withContext(Dispatchers.IO) { synchronized(lock) {
        val body = JSONObject().put("input", JSONArray(message.input)).toString()
        require(body.toByteArray().size <= DRAFT_LIMIT) { "待核对消息过大，无法保存在手机" }
        val key = digest(message.clientId)
        write("overlay-input-$key", body)
        write("overlay-state-$key", message.toJson(includeInput = false).toString())
        // This bounded visual aid is independent of durable draft/submission recovery data.
        val states = directory.listFiles().orEmpty().filter { it.name.startsWith("overlay-state-") && it.extension == "json" }
            .sortedByDescending(File::lastModified)
        var bytes = 0L
        states.forEachIndexed { index, state ->
            val input = File(directory, state.name.replace("overlay-state-", "overlay-input-"))
            bytes += input.length()
            if (index >= 64 || bytes > 64L * 1024 * 1024) { state.delete(); input.delete() }
        }
    } }
    suspend fun loadPendingMessages(): List<PendingNativeMessage> = withContext(Dispatchers.IO) { synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.name.startsWith("overlay-state-") && it.extension == "json" }
            .mapNotNull { source -> runCatching {
                val state = read(source.nameWithoutExtension, 16 * 1024) ?: return@runCatching null
                val input = read(source.nameWithoutExtension.replace("overlay-state-", "overlay-input-"), DRAFT_LIMIT)
                    ?: return@runCatching null
                pendingNativeMessage(state, input.objects("input"))
            }.getOrNull() }.sortedBy { it.createdAt }
    } }
    fun resolvePendingMessage(submissionId: String, response: JSONObject) = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.name.startsWith("overlay-state-") && it.extension == "json" }
            .forEach { source ->
                val state = read(source.nameWithoutExtension, 16 * 1024) ?: return@forEach
                if (state.string("submissionId") != submissionId) return@forEach
                val status = response.string("status")
                if (status == "failed" || status == "accepted" && state.string("kind") in setOf("queue", "create")) {
                    removePendingMessage(state.string("clientId"))
                } else {
                    state.put("delivery", if (status == "accepted") "accepted" else "unknown")
                    response.optJSONObject("receipt")?.string("turnId")?.takeIf(String::isNotBlank)?.let { state.put("nativeTurnId", it) }
                    write(source.nameWithoutExtension, state.toString())
                }
            }
    }
    fun removePendingMessage(clientId: String) = synchronized(lock) {
        val key = digest(clientId)
        file("overlay-state-$key").delete()
        file("overlay-input-$key").delete()
        Unit
    }

    suspend fun loadSnapshot(threadId: String): JSONObject? = withContext(Dispatchers.IO) {
        synchronized(lock) { read("snapshot-${digest(threadId)}", SNAPSHOT_LIMIT)?.takeIf { it.optJSONObject("thread")?.string("id") == threadId } }
    }
    suspend fun saveSnapshot(threadId: String, snapshot: JSONObject) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (snapshot.optJSONObject("thread")?.string("id") != threadId ||
                snapshot.optJSONObject("nativeSync")?.string("state")?.let { it.isNotBlank() && it != "ready" } == true) return@synchronized
            val json = JSONObject(snapshot.toString()).put("requests", JSONArray()).put("queue", JSONArray())
                .put("recoveryTask", JSONObject.NULL).put("recoveryRequired", false).put("nextCursor", JSONObject.NULL)
                .put("nativeRevision", JSONObject.NULL)
            // Approval keys/challenges belong to the live connection and never survive as offline prompts.
            val thread = json.getJSONObject("thread")
            var turns = thread.objects("turns").takeLast(30)
            thread.put("turns", JSONArray(turns))
            // Cached content is a visual seed; pagination, approvals and queue controls require a live response.
            var value = json.toString()
            while (value.toByteArray().size > SNAPSHOT_LIMIT && turns.size > 1) {
                turns = turns.drop(1); thread.put("turns", JSONArray(turns)); value = json.toString()
            }
            if (value.toByteArray().size <= SNAPSHOT_LIMIT) write("snapshot-${digest(threadId)}", value)
            else file("snapshot-${digest(threadId)}").delete()
            prune("snapshot-", 12)
        }
    }
    /** Durable offline todo edits are not a cache and must never be evicted. */
    suspend fun loadTodoState(): JSONObject? = withContext(Dispatchers.IO) { synchronized(lock) { read("todos-state", DRAFT_LIMIT) } }
    suspend fun saveTodoState(value: JSONObject) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val text = value.toString()
            require(text.toByteArray().size <= DRAFT_LIMIT) { "待办离线数据过大，请先连接节点同步" }
            write("todos-state", text)
        }
    }
    suspend fun loadCatalog(key: String): JSONObject? = withContext(Dispatchers.IO) {
        synchronized(lock) { read("catalog-${digest(key)}", SNAPSHOT_LIMIT) }
    }
    suspend fun saveCatalog(key: String, value: JSONObject) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val text = value.toString()
            if (text.toByteArray().size <= SNAPSHOT_LIMIT) write("catalog-${digest(key)}", text)
            prune("catalog-", 20)
        }
    }
    private fun file(name: String) = File(directory, "$name.json")
    private fun read(name: String, limit: Int): JSONObject? = runCatching {
        val source = file(name)
        if (!source.isFile || source.length() > limit) null else JSONObject(source.readText())
    }.getOrNull()
    private fun write(name: String, content: String) {
        check(directory.isDirectory || directory.mkdirs()) { "无法保存本机草稿" }
        val temporary = File(directory, "$name.tmp")
        temporary.outputStream().use { stream -> stream.write(content.toByteArray()); stream.fd.sync() }
        check(temporary.renameTo(file(name))) { "无法保存本机草稿" }
    }
    private fun prune(prefix: String, keep: Int) {
        directory.listFiles()?.filter { it.name.startsWith(prefix) && it.extension == "json" }
            ?.sortedByDescending(File::lastModified)?.drop(keep)?.forEach(File::delete)
    }
}
