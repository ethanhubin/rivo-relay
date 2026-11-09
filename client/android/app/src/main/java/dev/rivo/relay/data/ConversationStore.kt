package dev.rivo.relay.data

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Private, node-scoped recovery data. Never contains node credentials or authority to write. */
data class StoredDraftImage(val id: String, val bytes: ByteArray)
data class ConversationDraft(val text: String = "", val images: List<StoredDraftImage> = emptyList(),
    val files: List<JSONObject> = emptyList(), val model: String? = null, val effort: String? = null, val mode: String? = null, val serviceTier: String? = null, val sandbox: String? = null, val approvalPolicy: String? = null, val pendingGoal: String? = null) {
    val hasContent get() = text.isNotBlank() || images.isNotEmpty() || files.isNotEmpty() || pendingGoal != null
    val empty get() = text.isEmpty() && images.isEmpty() && files.isEmpty() && model == null && effort == null && mode == null && serviceTier == null && sandbox == null && approvalPolicy == null && pendingGoal == null
}
data class PendingConversation(val id: String, val projectId: String, val draft: ConversationDraft, val createdAt: Long, val firstTurnSent: Boolean = false) {
    val draftKey get() = "pending:$id"
}

class ConversationStore(private val directory: File, private val onWrite: (String) -> Unit = {}) {
    private val lock = directoryLocks.getOrPut(directory.absolutePath) { Any() }
    private val metadataLock = Any()
    private var metadata = synchronized(lock) { readMetadataStrict() }
    private val pendingMetadata = linkedMapOf<String, String?>()
    private var metadataWriterActive = false
    private val savedSnapshots = mutableMapOf<String, String>()
    private val latestDrafts = linkedMapOf<String, ConversationDraft>()

    companion object {
        fun nodeDirectory(root: File, identity: String) = File(root, digest(identity))
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private const val SNAPSHOT_LIMIT = 2 * 1024 * 1024
        private const val DRAFT_LIMIT = 64 * 1024 * 1024
        private data class DraftWrite(val store: ConversationStore, val id: String, val draft: ConversationDraft,
            val failed: (Throwable) -> Unit, val completed: List<CompletableDeferred<Unit>> = emptyList())
        private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val directoryLocks = ConcurrentHashMap<String, Any>()
        private val writerLock = Any()
        private val waiting = linkedMapOf<Pair<String, String>, DraftWrite>()
        private var currentWrite: Pair<Pair<String, String>, DraftWrite>? = null
        private var writerActive = false
        private val draftVersions = mutableMapOf<Pair<String, String>, Long>()
        private data class SnapshotWrite(val store: ConversationStore, val threadId: String, val snapshot: JSONObject)
        private val snapshots = linkedMapOf<Pair<String, String>, SnapshotWrite>()
        private var snapshotWriterActive = false
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
            draftVersions[key] = (draftVersions[key] ?: 0) + 1
            rememberDraft(threadId, draft)
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
    fun getMetadata(key: String): String? = synchronized(metadataLock) { metadata.string(key).takeIf { metadata.has(key) } }
    /** Navigation/read markers may coalesce. Recovery checkpoints use the synchronous IO commit below. */
    fun putMetadata(key: String, value: String?) {
        synchronized(metadataLock) {
            if (getMetadata(key) == value && pendingMetadata.isEmpty()) return
            if (value == null) metadata.remove(key) else metadata.put(key, value)
            pendingMetadata[key] = value
            if (metadataWriterActive) return
            metadataWriterActive = true
        }
        writerScope.launch {
            while (true) {
                delay(120)
                val success = runCatching { flushMetadata() }.isSuccess
                val done = synchronized(metadataLock) {
                    if (!success || pendingMetadata.isEmpty()) { metadataWriterActive = false; true } else false
                }
                if (done) break
            }
        }
    }
    suspend fun flushMetadata() = withContext(Dispatchers.IO) { commitMetadata(emptyMap()) }
    private fun readMetadataStrict(): JSONObject {
        val source = file("metadata")
        if (java.nio.file.Files.notExists(source.toPath())) return JSONObject()
        return try {
            check(source.isFile && source.length() <= DRAFT_LIMIT)
            JSONObject(source.readText())
        } catch (error: Exception) {
            throw IllegalArgumentException("无法读取手机恢复记录，原记录已保留，请检查存储后重试", error)
        }
    }
    private fun commitMetadata(changes: Map<String, String?>) = synchronized(lock) {
        val queued = synchronized(metadataLock) { pendingMetadata.toMap() }
        if (queued.isEmpty() && changes.isEmpty()) return@synchronized
        // Read under the directory lock: a delayed old-node navigation save cannot erase a newer receipt.
        val next = readMetadataStrict() // Existing unreadable/corrupt receipts must never become an empty store.
        (queued + changes).forEach { (key, value) ->
            val previous = next.string(key).toLongOrNull()
            val incoming = value?.toLongOrNull()
            if (key.startsWith("read:") && previous != null && incoming != null) next.put(key, maxOf(previous, incoming).toString())
            else if (value == null) next.remove(key) else next.put(key, value)
        }
        write("metadata", next.toString())
        synchronized(metadataLock) {
            queued.forEach { (key, value) -> if (pendingMetadata.containsKey(key) && pendingMetadata[key] == value) pendingMetadata.remove(key) }
            metadata = next
            pendingMetadata.forEach { (key, value) -> if (value == null) metadata.remove(key) else metadata.put(key, value) }
        }
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
            json.string("sandbox").takeIf(String::isNotBlank), json.string("approvalPolicy").takeIf(String::isNotBlank), json.string("pendingGoal").takeIf(String::isNotBlank))
    }.getOrDefault(ConversationDraft())

    private fun latestDraft(threadId: String): ConversationDraft {
        val queued = synchronized(writerLock) {
            val key = directory.absolutePath to threadId
            waiting[key]?.draft ?: currentWrite?.takeIf { it.first == key }?.second?.draft
        }
        return queued ?: synchronized(lock) { read("draft-${digest(threadId)}", DRAFT_LIMIT)?.let(::decodeDraft) ?: ConversationDraft() }
    }
    private fun rememberDraft(threadId: String, draft: ConversationDraft) = synchronized(writerLock) {
        latestDrafts.remove(threadId); latestDrafts[threadId] = draft
        var bytes = latestDrafts.values.sumOf { value -> value.images.sumOf { it.bytes.size.toLong() } }
        while (latestDrafts.size > 12 || bytes > 64L * 1024 * 1024 && latestDrafts.size > 1) {
            latestDrafts.remove(latestDrafts.keys.first())?.let { removed -> bytes -= removed.images.sumOf { it.bytes.size.toLong() } }
        }
    }
    fun currentDraft(threadId: String): ConversationDraft? = synchronized(writerLock) {
        val key = directory.absolutePath to threadId
        waiting[key]?.draft ?: currentWrite?.takeIf { it.first == key }?.second?.draft ?: latestDrafts[threadId]
    }
    suspend fun loadDraft(threadId: String): ConversationDraft = withContext(Dispatchers.IO) {
        if (threadId.startsWith("pending:") && getMetadata("completed:$threadId") == "true") return@withContext ConversationDraft()
        val current = latestDraft(threadId)
        val recoverable = sentDraftCheckpoints().entries.firstOrNull { it.value.string("threadId") == threadId && it.value.string("status") == "pending" }
        val draft = if (recoverable != null && !recoverable.value.optBoolean("preserveNewerDraft") &&
            (current.empty || matchesFingerprint(current, recoverable.value.string("baselineFingerprint")))) synchronized(lock) {
            read("sent-input-${digest(recoverable.key)}", DRAFT_LIMIT)?.let(::decodeDraft) ?: current
        } else current
        val accepted = sentDraftCheckpoints().values.firstOrNull { it.string("threadId") == threadId &&
            it.string("status") == "accepted" && matchesFingerprint(draft, it.string("fingerprint")) }
        // A durable accepted receipt hides only the unchanged old draft while cleanup is pending.
        (if (accepted == null) draft else decodeDraft(accepted.getJSONObject("settings"))).also { rememberDraft(threadId, it) }
    }
    private fun draftJson(draft: ConversationDraft) = JSONObject().put("text", draft.text).put("model", draft.model)
        .put("effort", draft.effort).put("mode", draft.mode).put("serviceTier", draft.serviceTier).put("sandbox", draft.sandbox).put("approvalPolicy", draft.approvalPolicy)
        .put("pendingGoal", draft.pendingGoal)
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
    private fun draftFingerprint(draft: ConversationDraft): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun add(bytes: ByteArray) { hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes) }
        add(canonicalJson(draftJson(draft.copy(images = emptyList()))).toByteArray())
        draft.images.take(10).filter { it.bytes.size <= 20 * 1024 * 1024 }.forEach { add(it.id.toByteArray()); add(it.bytes) }
        return "v2:" + hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun matchesFingerprint(draft: ConversationDraft, expected: String) =
        if (expected.startsWith("v2:")) draftFingerprint(draft) == expected else digest(canonicalJson(draftJson(draft))) == expected

    private fun freezeDraft(draft: ConversationDraft) = draft.copy(
        images = draft.images.take(10).filter { it.bytes.size <= 20 * 1024 * 1024 }.map { it.copy(bytes = it.bytes.copyOf()) },
        files = draft.files.take(16).map { JSONObject(it.toString()) })
    /** Capture the queued object before yielding; image/file cloning and fallback disk reads run on IO. */
    fun captureDraftAsync(threadId: String): Deferred<ConversationDraft> {
        val queued = currentDraft(threadId)
        return writerScope.async { freezeDraft(queued ?: latestDraft(threadId)) }
    }
    fun captureDraft(threadId: String): ConversationDraft = freezeDraft(latestDraft(threadId))
    suspend fun checkpointSentDraft(submissionId: String, threadId: String, draft: ConversationDraft? = null) = withContext(Dispatchers.IO) {
        val frozenDraft = draft ?: captureDraft(threadId)
        val baseline = synchronized(lock) { read("draft-${digest(threadId)}", DRAFT_LIMIT)?.let(::decodeDraft) ?: ConversationDraft() }
        val latest = captureDraft(threadId)
        val fingerprint = draftFingerprint(frozenDraft)
        val preserveNewerDraft = !latest.empty && draftFingerprint(latest) != fingerprint
        // Persist the frozen input independently before awaiting the normal draft writer.
        // A newer edit may coalesce that writer, but cannot erase the submitted bytes.
        withContext(Dispatchers.IO) { synchronized(lock) {
            val frozen = draftJson(frozenDraft).toString()
            require(frozen.toByteArray().size <= DRAFT_LIMIT) { "草稿过大，无法保存在手机" }
            write("sent-input-${digest(submissionId)}", frozen)
        } }
        val settings = draftJson(frozenDraft.copy(text = "", images = emptyList(), files = emptyList(), pendingGoal = null))
        putRecoveryMetadata("sent-draft:$submissionId", JSONObject().put("threadId", threadId)
            .put("fingerprint", fingerprint).put("baselineFingerprint", draftFingerprint(baseline))
            .put("preserveNewerDraft", preserveNewerDraft)
            .put("settings", settings).put("status", "pending").toString())
        // Keep any newer local edit; the immutable submitted bytes above remain recoverable.
        saveDraftOrdered(threadId, captureDraft(threadId))
    }
    fun sentDraftCheckpoints(): Map<String, JSONObject> = synchronized(metadataLock) {
        metadata.keys().asSequence().filter { it.startsWith("sent-draft:") }.mapNotNull { key ->
            runCatching { key.removePrefix("sent-draft:") to JSONObject(metadata.getString(key)) }.getOrNull()
        }.toMap()
    }
    /** Include overlays left behind if the process died between receipt and cleanup. */
    suspend fun unresolvedSubmissionIds(): Set<String> = withContext(Dispatchers.IO) {
        sentDraftCheckpoints().keys + pendingSubmissionCheckpoints().keys +
            loadPendingMessages().filter { it.delivery !in setOf("accepted", "failed") }.map { it.submissionId }
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
    suspend fun clearAcceptedSentDraft(submissionId: String) = withContext(Dispatchers.IO) {
        val saved = getMetadata("sent-draft:$submissionId")?.let(::JSONObject) ?: return@withContext
        if (saved.string("status") != "accepted") return@withContext
        val completion = CompletableDeferred<Unit>()
        val threadId = saved.getString("threadId")
        val key = directory.absolutePath to threadId
        val version = synchronized(writerLock) { draftVersions[key] ?: 0 }
        val unchanged = matchesFingerprint(latestDraft(threadId), saved.string("fingerprint"))
        synchronized(writerLock) {
            if (unchanged && (draftVersions[key] ?: 0) == version) {
                enqueueDraftWrite(threadId, decodeDraft(saved.getJSONObject("settings")), {}, completion)
            } else completion.complete(Unit) // A newer draft belongs to the user and must survive.
        }
        completion.await()
        putRecoveryMetadata("sent-draft:$submissionId", null)
        withContext(Dispatchers.IO) { synchronized(lock) { file("sent-input-${digest(submissionId)}").delete() } }
    }
    suspend fun saveDraft(threadId: String, draft: ConversationDraft) = withContext(Dispatchers.IO) {
        synchronized(writerLock) {
            val key = directory.absolutePath to threadId
            if (currentWrite?.first != key || currentWrite?.second?.draft !== draft) {
                draftVersions[key] = (draftVersions[key] ?: 0) + 1
                rememberDraft(threadId, draft)
            }
        }
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
    private fun putRecoveryMetadata(key: String, value: String?) = commitMetadata(mapOf(key to value))
    fun defaultSettings(): JSONObject? = getMetadata("codex-default-settings")?.let {
        runCatching { JSONObject(it) }.getOrNull()
    }
    fun saveDefaultSettings(settings: JSONObject) {
        val model = settings.string("model").takeIf(String::isNotBlank)
        val value = JSONObject().put("model", model)
        settings.string("effort").takeIf(String::isNotBlank)?.let { value.put("effort", it) }
        settings.string("serviceTier").takeIf { it == "default" || it == "priority" }?.let { value.put("serviceTier", it) }
        settings.string("sandbox").takeIf { it in setOf("read-only", "workspace-write", "danger-full-access") }?.let { value.put("sandbox", it) }
        settings.string("approvalPolicy").takeIf { it in setOf("preset-request", "preset-auto", "preset-full") }?.let { value.put("approvalPolicy", it) }
        putRecoveryMetadata("codex-default-settings", value.toString())
    }
    fun pendingCompleted(projectId: String) = getMetadata("completed:pending:$projectId") == "true"
    fun completedPendingThreadId(projectId: String): String? = getMetadata("pending-completed-thread:$projectId")
    fun pendingThreadId(projectId: String): String? = getMetadata("pending-thread:$projectId")
    fun pendingGoalContinuation(id: String): JSONObject? = getMetadata("pending-goal-continuation:$id")?.let(::JSONObject)
    fun pendingAppliedGoal(id: String): String? = getMetadata("pending-applied-goal:$id")
    fun pendingWorktreeRef(projectId: String): String? = getMetadata("pending-ref:$projectId")
    fun setPendingWorktreeRef(projectId: String, ref: String?) = putMetadata("pending-ref:$projectId", ref)
    fun beginPending(projectId: String) {
        val key = "pending:$projectId"
        if (getMetadata("completed:$key") == "true") enqueueDraft(key, ConversationDraft())
        commitMetadata(mapOf("completed:$key" to null, "pending-completed-thread:$projectId" to null))
    }
    private fun pendingEntry(id: String): JSONObject? = getMetadata("pending-entry:$id")?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun migratePending(projectId: String) {
        if (pendingEntry(projectId) != null || pendingCompleted(projectId)) return
        val draft = latestDraft("pending:$projectId")
        if (draft.hasContent || pendingThreadId(projectId) != null) {
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
        val ids = synchronized(metadataLock) { metadata.keys().asSequence().filter { it.startsWith("pending-entry:") }
            .map { it.removePrefix("pending-entry:") }.toList() }
        ids.mapNotNull { id ->
            val entry = pendingEntry(id) ?: return@mapNotNull null
            if (entry.string("projectId") != projectId || pendingCompleted(id)) return@mapNotNull null
            val draft = loadDraft("pending:$id")
            if (!draft.hasContent && pendingThreadId(id) == null) return@mapNotNull null
            PendingConversation(id, projectId, draft, entry.optLong("createdAt"), pendingGoalContinuation(id) != null)
        }.sortedByDescending { it.createdAt }
    }
    /** Checkpoint before crossing the network boundary, so reconciliation survives process death. */
    fun checkpointPendingSubmission(projectId: String, submissionId: String, kind: String, goalAction: String? = null) {
        require(kind in setOf("create", "send", "goal", "sendWithGoal", "finishGoal"))
        require(kind !in setOf("goal", "sendWithGoal", "finishGoal") || goalAction != null)
        putRecoveryMetadata("pending-submission:$submissionId", JSONObject().put("projectId", pendingEntry(projectId)?.string("projectId") ?: projectId)
            .put("pendingId", projectId).put("kind", kind).put("goalAction", goalAction).toString())
    }
    fun pendingHasUnresolvedSubmission(id: String): Boolean = synchronized(metadataLock) {
        if (metadata.optString("completed:pending:$id") == "true") return@synchronized false
        metadata.keys().asSequence().filter { it.startsWith("pending-submission:") }.any { key ->
            val checkpoint = JSONObject(metadata.getString(key))
            checkpoint.string("pendingId").ifBlank { checkpoint.string("projectId") } == id
        }
    }
    fun pendingSubmissionCheckpoints(): Map<String, JSONObject> = synchronized(lock) {
        val disk = readMetadataStrict()
        disk.keys().asSequence().filter { it.startsWith("pending-submission:") }.associate { key ->
            key.removePrefix("pending-submission:") to JSONObject(disk.getString(key))
        }
    }
    fun resolvePendingSubmission(submissionId: String, response: JSONObject): String? {
        var clearDraft: String? = null
        val project = synchronized(lock) {
            // Read the durable state under the shared directory lock. Another store
            // instance may have discarded this draft while the receipt was in flight.
            val disk = readMetadataStrict()
            val key = "pending-submission:$submissionId"
            val checkpoint = disk.optString(key).takeIf(String::isNotBlank)?.let(::JSONObject) ?: return@synchronized null
            val projectId = checkpoint.string("projectId")
            val pendingId = checkpoint.string("pendingId").ifBlank { projectId }
            val status = response.string("status")
            val changes = linkedMapOf<String, String?>()
            if (status in setOf("accepted", "failed")) changes[key] = null
            if (disk.optString("completed:pending:$pendingId") != "true") {
                val threadId = response.optJSONObject("receipt")?.string("threadId")?.takeIf(String::isNotBlank)
                val kind = checkpoint.string("kind")
                if (kind == "create" && threadId != null) changes["pending-thread:$pendingId"] = threadId
                if (status == "accepted") {
                    if (kind == "goal") changes["pending-applied-goal:$pendingId"] = checkpoint.getString("goalAction")
                    if (kind == "sendWithGoal") {
                        val receipt = response.getJSONObject("receipt")
                        require(receipt.string("threadId").isNotBlank() && receipt.string("turnId").isNotBlank())
                        changes["pending-goal-continuation:$pendingId"] = JSONObject()
                            .put("receipt", receipt).put("goalAction", checkpoint.getString("goalAction")).toString()
                    }
                    if (kind in setOf("send", "finishGoal")) {
                        val acceptedThread = threadId ?: disk.optString("pending-thread:$pendingId").takeIf(String::isNotBlank)
                        changes.putAll(completedPendingMetadata(pendingId, acceptedThread))
                        clearDraft = pendingId
                    }
                }
            }
            // Stage advancement and receipt retirement share one atomic metadata write.
            commitMetadata(changes)
            projectId
        }
        clearDraft?.let { enqueueDraft("pending:$it", ConversationDraft()) }
        return project
    }
    private fun completedPendingMetadata(id: String, threadId: String?) = mapOf(
        "completed:pending:$id" to "true", "pending-completed-thread:$id" to threadId,
        "pending-thread:$id" to null, "pending-ref:$id" to null, "pending-entry:$id" to null,
        "pending-applied-goal:$id" to null, "pending-goal-continuation:$id" to null)

    fun finishPending(projectId: String, completedThreadId: String? = null) {
        // Tombstone first: late receipts and old draft writes cannot resurrect it.
        commitMetadata(completedPendingMetadata(projectId, completedThreadId))
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
                if (status == "accepted" && state.string("kind") == "create") {
                    removePendingMessage(state.string("clientId"))
                } else {
                    state.put("delivery", when (status) { "accepted", "failed" -> status; else -> "unknown" })
                    response.optJSONObject("receipt")?.string("turnId")?.takeIf(String::isNotBlank)?.let { state.put("nativeTurnId", it) }
                    write(source.nameWithoutExtension, state.toString())
                }
            }
    }
    fun enqueueResolvePendingMessage(submissionId: String, response: JSONObject) {
        writerScope.launch { runCatching { resolvePendingMessage(submissionId, response) } }
    }
    fun enqueueRemovePendingMessages(clientIds: Set<String>) {
        if (clientIds.isEmpty()) return
        writerScope.launch { clientIds.forEach { runCatching { removePendingMessage(it) } } }
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
    fun enqueueSnapshot(threadId: String, snapshot: JSONObject) {
        synchronized(writerLock) {
            snapshots[directory.absolutePath to threadId] = SnapshotWrite(this, threadId, snapshot)
            if (snapshotWriterActive) return
            snapshotWriterActive = true
        }
        writerScope.launch {
            while (true) {
                delay(350)
                val next = synchronized(writerLock) {
                    val key = snapshots.keys.firstOrNull()
                    if (key == null) { snapshotWriterActive = false; null } else snapshots.remove(key)
                } ?: break
                runCatching { next.store.saveSnapshot(next.threadId, next.snapshot) }
            }
        }
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun encodedFields(json: JSONObject, excluded: Set<String>) = json.keys().asSequence()
        .filterNot { it in excluded }.associateWithTo(linkedMapOf()) { jsonValue(json.opt(it)) }
    private fun encodedObject(fields: Map<String, String>) = fields.entries.joinToString(",", "{", "}") { JSONObject.quote(it.key) + ":" + it.value }

    /** Serialize every turn once, size its UTF-8 bytes, and join the bounded suffix without JSON round trips. */
    internal fun snapshotCachePayload(threadId: String, snapshot: JSONObject): String? {
        val thread = snapshot.optJSONObject("thread") ?: return null
        if (thread.string("id") != threadId || snapshot.optJSONObject("nativeSync")?.string("state")?.let { it.isNotBlank() && it != "ready" } == true) return null
        val root = encodedFields(snapshot, setOf("thread", "requests", "queue", "recoveryTask", "recoveryRequired", "nextCursor", "nativeRevision"))
        root.putAll(mapOf("requests" to "[]", "queue" to "[]", "recoveryTask" to "null", "recoveryRequired" to "false", "nextCursor" to "null", "nativeRevision" to "null"))
        val fields = encodedFields(thread, setOf("turns"))
        val turns = thread.objects("turns").takeLast(30).map { it.toString() }
        val sizes = turns.map { it.toByteArray().size }
        fields["turns"] = "[]"; root["thread"] = encodedObject(fields)
        val fixed = encodedObject(root).toByteArray().size
        var start = 0
        var bytes = fixed + sizes.sum() + (turns.size - 1).coerceAtLeast(0)
        while (bytes > SNAPSHOT_LIMIT && start < turns.size - 1) {
            bytes -= sizes[start++] + 1
        }
        if (bytes > SNAPSHOT_LIMIT) return null
        fields["turns"] = turns.drop(start).joinToString(",", "[", "]")
        root["thread"] = encodedObject(fields)
        return encodedObject(root)
    }
    suspend fun saveSnapshot(threadId: String, snapshot: JSONObject) = withContext(Dispatchers.IO) {
        if (snapshot.optJSONObject("thread")?.string("id") != threadId ||
            snapshot.optJSONObject("nativeSync")?.string("state")?.let { it.isNotBlank() && it != "ready" } == true) return@withContext
        val value = snapshotCachePayload(threadId, snapshot)
        synchronized(lock) {
            if (value == null) { savedSnapshots.remove(threadId); file("snapshot-${digest(threadId)}").delete(); return@synchronized }
            if (savedSnapshots[threadId] == value && file("snapshot-${digest(threadId)}").isFile) return@synchronized
            write("snapshot-${digest(threadId)}", value)
            savedSnapshots[threadId] = value
            while (savedSnapshots.size > 12) savedSnapshots.remove(savedSnapshots.keys.first())
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
        onWrite(name)
    }
    private fun prune(prefix: String, keep: Int) {
        directory.listFiles()?.filter { it.name.startsWith(prefix) && it.extension == "json" }
            ?.sortedByDescending(File::lastModified)?.drop(keep)?.forEach(File::delete)
    }
}
