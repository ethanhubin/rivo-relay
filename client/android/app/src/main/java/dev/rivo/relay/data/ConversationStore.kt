package dev.rivo.relay.data

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** Private, node-scoped recovery data. Never contains node credentials or authority to write. */
data class StoredDraftImage(val id: String, val bytes: ByteArray)
data class ConversationDraft(val text: String = "", val images: List<StoredDraftImage> = emptyList(),
    val files: List<JSONObject> = emptyList(), val model: String? = null, val effort: String? = null, val mode: String? = null) {
    val empty get() = text.isEmpty() && images.isEmpty() && files.isEmpty() && model == null && effort == null && mode == null
}

class ConversationStore(private val directory: File) {
    private val lock = Any()
    private val metadata = synchronized(lock) { read("metadata", 256 * 1024) ?: JSONObject() }

    companion object {
        fun nodeDirectory(root: File, identity: String) = File(root, digest(identity))
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private const val SNAPSHOT_LIMIT = 2 * 1024 * 1024
        private const val DRAFT_LIMIT = 5 * 1024 * 1024
        private data class DraftWrite(val store: ConversationStore, val id: String, val draft: ConversationDraft, val failed: (Throwable) -> Unit)
        private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val writerLock = Any()
        private val waiting = linkedMapOf<Pair<String, String>, DraftWrite>()
        private var currentWrite: Pair<Pair<String, String>, DraftWrite>? = null
        private var writerActive = false
    }

    /** Route/node disposal does not cancel queued saves. One writer preserves order across store instances. */
    fun enqueueDraft(threadId: String, draft: ConversationDraft, onFailure: (Throwable) -> Unit = {}) {
        synchronized(writerLock) {
            waiting[directory.absolutePath to threadId] = DraftWrite(this, threadId, draft, onFailure)
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
                try { next.second.store.saveDraft(next.second.id, next.second.draft) }
                catch (e: Exception) { runCatching { next.second.failed(e) } }
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
    suspend fun loadDraft(threadId: String): ConversationDraft = withContext(Dispatchers.IO) {
        val queued = synchronized(writerLock) {
            val key = directory.absolutePath to threadId
            waiting[key]?.draft ?: currentWrite?.takeIf { it.first == key }?.second?.draft
        }
        if (queued != null) return@withContext queued
        synchronized(lock) {
            val json = read("draft-${digest(threadId)}", DRAFT_LIMIT) ?: return@synchronized ConversationDraft()
            runCatching { ConversationDraft(json.string("text"), json.objects("images").take(3).mapNotNull {
                val bytes = runCatching { Base64.getDecoder().decode(it.string("bytes")) }.getOrNull()
                if (bytes != null && bytes.size <= 1024 * 1024) StoredDraftImage(it.string("id"), bytes) else null
            }, json.objects("files").take(16).filter {
                when (it.string("type")) {
                    "mention", "skill", "localAudio" -> it.string("path").isNotBlank()
                    "audio" -> it.string("url").isNotBlank()
                    else -> false
                }
            },
                json.string("model").takeIf(String::isNotBlank), json.string("effort").takeIf(String::isNotBlank),
                json.string("mode").takeIf { it == "plan" || it == "default" }) }.getOrDefault(ConversationDraft())
        }
    }
    suspend fun saveDraft(threadId: String, draft: ConversationDraft) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val name = "draft-${digest(threadId)}"
            if (draft.empty) { file(name).delete(); return@synchronized }
            val json = JSONObject().put("text", draft.text).put("model", draft.model).put("effort", draft.effort).put("mode", draft.mode)
                .put("files", JSONArray(draft.files.take(16)))
                .put("images", JSONArray(draft.images.take(3).filter { it.bytes.size <= 1024 * 1024 }.map {
                    JSONObject().put("id", it.id).put("bytes", Base64.getEncoder().encodeToString(it.bytes))
                }))
            val text = json.toString()
            require(text.toByteArray().size <= DRAFT_LIMIT) { "草稿过大，无法保存在手机" }
            // Unsent user work is durable data, never evicted by the bounded history cache.
            write(name, text)
        }
    }
    suspend fun loadSnapshot(threadId: String): JSONObject? = withContext(Dispatchers.IO) {
        synchronized(lock) { read("snapshot-${digest(threadId)}", SNAPSHOT_LIMIT)?.takeIf { it.optJSONObject("thread")?.string("id") == threadId } }
    }
    suspend fun saveSnapshot(threadId: String, snapshot: JSONObject) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (snapshot.optJSONObject("thread")?.string("id") != threadId) return@synchronized
            val json = JSONObject(snapshot.toString()).put("requests", JSONArray())
            // Approval keys/challenges belong to the live connection and never survive as offline prompts.
            val thread = json.getJSONObject("thread")
            var turns = thread.objects("turns").takeLast(30)
            thread.put("turns", JSONArray(turns))
            // Keep original cursors only for display; SessionController never paginates a stale cache.
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
