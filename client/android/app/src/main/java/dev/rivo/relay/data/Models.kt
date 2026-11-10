package dev.rivo.relay.data

import org.json.JSONObject

/** Copy mutable JSON containers while sharing immutable strings (including large base64 inputs). */
fun copyJsonObject(value: JSONObject): JSONObject {
    fun copy(item: Any?): Any? = when (item) {
        is JSONObject -> JSONObject().also { result -> item.keys().forEach { key -> result.put(key, copy(item.opt(key))) } }
        is org.json.JSONArray -> org.json.JSONArray().also { result -> for (i in 0 until item.length()) result.put(copy(item.opt(i))) }
        else -> item
    }
    return copy(value) as JSONObject
}

fun sessionTitle(thread: JSONObject): String = visibleSessionText(thread.string("name")).ifBlank {
    visibleSessionText(thread.string("preview")).ifBlank { "新会话" }
}

data class ThreadSummary(val id: String, val name: String, val preview: String, val status: String, val cwd: String, val projectId: String, val pinned: Boolean = false, val updatedAt: Long = 0,
    val queueDepth: Int = 0, val recoveryRequired: Boolean = false, val nativeExecution: String = "",
    val deliveryState: String = "", val displayState: String = "", val hasOutstandingWork: Boolean = false,
    val source: String = "", val executionMode: String = "", val gitInfo: JSONObject? = null, val writerOwner: String = "", val workspace: JSONObject? = null,
    val externalWorkspace: JSONObject? = null)
fun threadSummary(json: JSONObject) = ThreadSummary(json.string("id"), sessionTitle(json), visibleSessionText(json.string("preview")), json.string("status"), json.string("cwd"), json.string("projectId"), json.optBoolean("pinned", false), json.optLong("updatedAt", 0),
    json.optInt("queueDepth").coerceAtLeast(0), json.optBoolean("recoveryRequired"), json.string("nativeExecution"),
    json.string("deliveryState"), json.string("displayState"), json.optBoolean("hasOutstandingWork"),
    json.string("source"), json.string("executionMode"), json.optJSONObject("gitInfo"), json.string("writerOwner"), json.optJSONObject("workspace"), json.optJSONObject("externalWorkspace"))
data class Todo(val id: String, val title: String, val notes: String, val completed: Boolean, val projectId: String, val threadId: String, val details: JSONObject = JSONObject())
fun todo(json: JSONObject) = Todo(json.string("id"), json.string("title"), json.string("notes"), json.optBoolean("completed"), json.string("projectId"), json.string("threadId"), json.optJSONObject("details") ?: JSONObject())
data class NativeMessage(val id: String, val turnId: String, val type: String, val text: String, val raw: JSONObject,
    val content: List<JSONObject> = emptyList(), val activity: NativeActivity? = null, val turnStatus: String = "")
data class NativeTurnSettings(val turnId: String, val model: String? = null, val effort: String? = null,
    val status: String = "", val error: JSONObject? = null,
    val startedAt: Long? = null, val completedAt: Long? = null, val durationMs: Long? = null)
data class NativeRequest(val key: String, val method: String, val params: JSONObject)
data class QueueTask(val id: String, val text: String, val status: String, val revision: Int, val error: String, val raw: JSONObject, val recoveryRequired: Boolean = false)
fun queueTask(j: JSONObject) = QueueTask(j.string("id"), j.string("text"), j.string("status"), j.optInt("revision"), j.string("error"), j, j.optBoolean("recoveryRequired"))
data class SessionSnapshot(val id: String, val name: String, val status: String, val activeTurnId: String,
    val messages: List<NativeMessage>, val requests: List<NativeRequest>, val settings: JSONObject, val historyCursor: String? = null, val readOnly: Boolean = false, val queue: List<QueueTask> = emptyList(), val plan: JSONObject? = null, val tokenUsage: JSONObject? = null, val changes: List<JSONObject> = emptyList(), val updatedAt: Long = 0, val turnSettings: List<NativeTurnSettings> = emptyList(),
    val nativeRevision: String? = null, val nativeSyncState: String = "ready", val recoveryTask: QueueTask? = null,
    val recoveryRequired: Boolean = false, val nativeExecution: String = "", val deliveryState: String = "",
    val displayState: String = "", val writerOwner: String = "", val queueDepth: Int = 0, val reviewDiagnostics: List<JSONObject> = emptyList(), val nativeRuntime: JSONObject? = null, val goal: JSONObject? = null, val goalSupported: Boolean = false, val control: JSONObject? = null, val workspaceUnavailable: Boolean = false, val persistedHistoryReady: Boolean = false, val resumeTurnId: String = "", val canAnswerQuestions: Boolean = false, val questionAnswerStates: JSONObject = JSONObject()) {
    val isOngoing: Boolean get() = activeTurnId.isNotBlank() || status in setOf("running", "waiting") || (nativeExecution.isBlank() && goal?.string("status") == "active")
    val shouldQueueInput: Boolean get() = isOngoing || requests.isNotEmpty() || (!recoveryRequired && queue.isNotEmpty())
    val nativeReady: Boolean get() = nativeSyncState == "ready"
    val historyReady: Boolean get() = nativeReady || persistedHistoryReady
}

/** Project native items by IDs, never by text matching or by duplicating a local history. */
fun sessionSnapshot(json: JSONObject): SessionSnapshot {
    val thread = json.getJSONObject("thread")
    val observedSettings = json.objects("turnSettings").associateBy { it.string("turnId") }
    val turnSettings = thread.objects("turns").map { turn ->
        val id = turn.string("id")
        val observed = observedSettings[id]
        NativeTurnSettings(id, (observed?.opt("model") as? String)?.takeIf(String::isNotBlank),
            (observed?.opt("effort") as? String)?.takeIf(String::isNotBlank), turn.string("status"), turn.optJSONObject("error"),
            nativeTimestamp(turn.opt("startedAt")), nativeTimestamp(turn.opt("completedAt")),
            (turn.opt("durationMs") as? Number)?.toLong()?.takeIf { it >= 0 })
    }
    val messages = thread.objects("turns").flatMap { turn ->
        turn.objects("items").filterNot { it.string("type").replace("_", "").lowercase() == "hookprompt" }.map { item ->
            val type = item.string("type")
            val content = if (type in setOf("userMessage", "user_message")) item.objects("content").map { part ->
                if (part.string("type") == "text") JSONObject(part.toString()).put("text", visibleUserText(part.string("text"))) else part
            } else emptyList()
            val activity = nativeActivityForTurn(item, turn.string("status"))
            val text = when (type) {
                "userMessage", "user_message" -> content.joinToString("\n\n") { part -> part.string("text").ifBlank {
                    when (part.string("type")) { "image", "localImage" -> "[图片]"; "mention", "skill" -> part.string("name"); else -> part.toString(2) }
                } }.ifBlank { visibleUserText(item.string("text")) }
                "agentMessage", "agent_message", "plan" -> item.string("text")
                "exitedReviewMode" -> item.string("review")
                "reasoning" -> nativeReasoningText(item)
                "commandExecution", "command_execution" -> listOf(item.string("command"), item.string("aggregatedOutput")).filter { it.isNotBlank() }.joinToString("\n\n")
                "fileChange", "file_change" -> activity?.detail.orEmpty()
                else -> activity?.let { listOf(it.summary, it.detail).filter(String::isNotBlank).distinct().joinToString("\n\n") }
                    ?: item.string("text").ifBlank { item.toString(2) }
            }
            NativeMessage(item.string("id"), turn.string("id"), type, text, item, content, activity, turn.string("status"))
        }
    }
    val requests = json.objects("requests").map { NativeRequest(it.string("key"), it.string("method"), it.optJSONObject("params") ?: JSONObject()) }
    return SessionSnapshot(thread.string("id"), sessionTitle(thread), json.string("status"), json.string("activeTurnId"), messages, requests, json.optJSONObject("settings") ?: JSONObject(),
        json.string("nextCursor").takeIf { it.isNotBlank() }, json.optBoolean("readOnly", false), json.objects("queue").map(::queueTask), json.optJSONObject("plan"), json.optJSONObject("tokenUsage"), json.objects("changes"), thread.optLong("updatedAt", 0), turnSettings,
        json.string("nativeRevision").takeIf(String::isNotBlank), json.optJSONObject("nativeSync")?.string("state")?.ifBlank { "ready" } ?: "ready",
        json.optJSONObject("recoveryTask")?.let(::queueTask), json.optBoolean("recoveryRequired"), json.string("nativeExecution"),
        json.string("deliveryState"), json.string("displayState"), json.string("writerOwner"), json.optInt("queueDepth").coerceAtLeast(0),
        mergeTurnDiagnostics(emptyList(), json.objects("reviewDiagnostics")), json.optJSONObject("nativeRuntime"), json.optJSONObject("goal"), json.optBoolean("goalSupported"), json.optJSONObject("control"), json.optBoolean("workspaceUnavailable"), json.optJSONObject("nativeSync")?.optBoolean("historyReady") == true, json.string("resumeTurnId"), json.optBoolean("canAnswerQuestions"), json.optJSONObject("questionAnswerStates") ?: JSONObject())
}

fun mergeTurnDiagnostics(previous: List<JSONObject>, latest: List<JSONObject>): List<JSONObject> =
    (previous + latest).filter { it.string("turnId").isNotBlank() }.associateBy { it.string("turnId") }.values.toList()

fun mergeLatestMessages(previous: List<NativeMessage>, latest: List<NativeMessage>): List<NativeMessage> {
    val firstTurn = latest.firstOrNull()?.turnId ?: return latest
    val start = previous.indexOfFirst { it.turnId == firstTurn }
    return if (start >= 0) previous.take(start) + latest else latest
}

/** Trusted live native items may lead persisted history. Merge by native IDs until ready replaces it. */
fun mergeConvergingMessages(previous: List<NativeMessage>, incoming: List<NativeMessage>): List<NativeMessage> {
    val turns = linkedMapOf<String, LinkedHashMap<String, NativeMessage>>()
    previous.forEach { turns.getOrPut(it.turnId) { linkedMapOf() }[it.id] = it }
    incoming.forEach { turns.getOrPut(it.turnId) { linkedMapOf() }[it.id] = it }
    return turns.values.flatMap { it.values }
}
fun mergeConvergingTurnSettings(previous: List<NativeTurnSettings>, incoming: List<NativeTurnSettings>): List<NativeTurnSettings> {
    val values = previous.associateByTo(linkedMapOf()) { it.turnId }
    incoming.forEach { values[it.turnId] = it }
    return values.values.toList()
}

fun prependNativePage(earlier: List<NativeMessage>, current: List<NativeMessage>): List<NativeMessage> {
    val currentIds = current.map { it.turnId to it.id }.toSet()
    return earlier.filterNot { (it.turnId to it.id) in currentIds }.distinctBy { it.turnId to it.id } + current
}

/** Unknown observations break adjacency; the current session model is never used as history. */
fun nativeModelChanges(turns: List<NativeTurnSettings>): Map<String, String> = turns.zipWithNext().mapNotNull { (previous, next) ->
    val before = previous.model
    val after = next.model
    if (before == null || after == null || before == after) null
    else next.turnId to ("已切换至 $after" + next.effort?.let { " · $it" }.orEmpty())
}.toMap()

fun mergeLatestTurnSettings(previous: List<NativeTurnSettings>, latest: List<NativeTurnSettings>): List<NativeTurnSettings> {
    val first = latest.firstOrNull()?.turnId ?: return latest
    val start = previous.indexOfFirst { it.turnId == first }
    return if (start >= 0) previous.take(start) + latest else latest
}

fun prependTurnSettings(earlier: List<NativeTurnSettings>, current: List<NativeTurnSettings>): List<NativeTurnSettings> {
    val ids = current.map { it.turnId }.toSet()
    return earlier.filterNot { it.turnId in ids }.distinctBy { it.turnId } + current
}

private data class DeltaField(val type: String, val field: String, val indexField: String? = null)
private val deltaFields = mapOf(
    "item/agentMessage/delta" to DeltaField("agentMessage", "text"),
    "item/commandExecution/outputDelta" to DeltaField("commandExecution", "aggregatedOutput"),
    "item/fileChange/outputDelta" to DeltaField("fileChange", "output"),
    "item/plan/delta" to DeltaField("plan", "text"),
    "item/reasoning/summaryTextDelta" to DeltaField("reasoning", "summary", "summaryIndex"),
    "item/reasoning/textDelta" to DeltaField("reasoning", "content", "contentIndex"),
)
fun supportsLiveDelta(method: String) = method in deltaFields

class NativeMessageIndex(messages: List<NativeMessage>) {
    internal val positions = messages.withIndex().associate { (index, message) -> (message.turnId to message.id) to index }
}

private class DeltaText(private val original: String) {
    private var builder: StringBuilder? = null
    private var codePoints = original.codePointCount(0, original.length)
    val changed get() = builder != null
    fun append(offset: Long, delta: String): Boolean {
        if (offset < 0 || offset > codePoints) return false
        val current: CharSequence = builder ?: original
        val start = if (offset == codePoints.toLong()) current.length else Character.offsetByCodePoints(current, 0, offset.toInt())
        val tailLength = current.length - start
        for (index in 0 until minOf(tailLength, delta.length)) if (current[start + index] != delta[index]) return false
        if (delta.length <= tailLength) return true // Snapshot/replay already contains this event.
        val target = builder ?: StringBuilder(original).also { builder = it }
        val joinsSurrogate = target.isNotEmpty() && target.last().isHighSurrogate() && delta[tailLength].isLowSurrogate()
        target.append(delta, tailLength, delta.length)
        codePoints += delta.codePointCount(tailLength, delta.length) - if (joinsSurrogate) 1 else 0
        return true
    }
    override fun toString() = builder?.toString() ?: original
}

/** Atomic batch: validate offsets against private builders; copy each changed item/list only once. */
fun SessionSnapshot.applyLiveDeltas(events: List<JSONObject>, index: NativeMessageIndex = NativeMessageIndex(messages)): SessionSnapshot? {
    val changes = linkedMapOf<Int, MutableMap<Pair<String, Int?>, DeltaText>>()
    for (event in events) {
        val field = deltaFields[event.string("method")] ?: return null
        val params = event.optJSONObject("params") ?: return null
        if (params.string("threadId").ifBlank { params.string("conversationId") } != id) return null
        val identity = params.string("turnId") to params.string("itemId")
        if (identity.first.isBlank() || identity.second.isBlank()) return null
        val position = index.positions[identity] ?: return null
        val old = messages.getOrNull(position) ?: return null
        if (old.turnId != identity.first || old.id != identity.second || old.type != field.type) return null
        val delta = params.opt("delta") as? String ?: return null
        val offsetValue = params.opt("relayOffset") as? Number ?: return null
        val offset = offsetValue.toLong()
        if (offsetValue.toDouble() != offset.toDouble()) return null
        val part = field.indexField?.let { key ->
            val value = params.opt(key) as? Number ?: return null
            val partIndex = value.toInt()
            if (value.toDouble() != partIndex.toDouble() || partIndex !in 0..1023) return null
            partIndex
        }
        val fields = changes.getOrPut(position) { linkedMapOf() }
        val key = field.field to part
        val text = fields[key] ?: run {
            val original = if (part == null) {
                old.raw.opt(field.field)?.takeUnless { it == JSONObject.NULL }?.let { it as? String ?: return null }.orEmpty()
            } else {
                val values = old.raw.optJSONArray(field.field)
                val size = values?.length() ?: 0
                if (part > size) return null
                if (part == size) "" else values!!.opt(part) as? String ?: return null
            }
            DeltaText(original).also { fields[key] = it }
        }
        if (!text.append(offset, delta)) return null
    }
    if (changes.values.none { fields -> fields.values.any { it.changed } }) return this
    val updated = messages.toMutableList()
    changes.forEach { (position, fields) ->
        if (fields.values.none { it.changed }) return@forEach
        val old = messages[position]
        val raw = JSONObject().also { copy -> old.raw.keys().forEach { key -> copy.put(key, old.raw.opt(key)) } }
        val arrays = mutableMapOf<String, org.json.JSONArray>()
        fields.forEach { (key, text) ->
            if (text.changed) {
                val (field, part) = key
                if (part == null) raw.put(field, text.toString())
                else arrays.getOrPut(field) {
                    val oldArray = old.raw.optJSONArray(field)
                    org.json.JSONArray().also { copy -> if (oldArray != null) for (i in 0 until oldArray.length()) copy.put(oldArray.opt(i)) }
                        .also { raw.put(field, it) }
                }.put(part, text.toString())
            }
        }
        val activity = nativeActivityForTurn(raw, old.turnStatus)
        val text = when (old.type) {
            "commandExecution" -> listOf(raw.string("command"), raw.string("aggregatedOutput")).filter(String::isNotBlank).joinToString("\n\n")
            "fileChange" -> activity?.detail.orEmpty()
            "reasoning" -> nativeReasoningText(raw)
            else -> raw.string("text")
        }
        updated[position] = old.copy(text = text, raw = raw, activity = activity)
    }
    return copy(messages = updated)
}

fun SessionSnapshot.applyLiveDelta(event: JSONObject): SessionSnapshot? = applyLiveDeltas(listOf(event))
