package dev.rivo.relay.data

import org.json.JSONObject

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
    val content: List<JSONObject> = emptyList(), val activity: NativeActivity? = null)
data class NativeTurnSettings(val turnId: String, val model: String? = null, val effort: String? = null)
data class NativeRequest(val key: String, val method: String, val params: JSONObject)
data class QueueTask(val id: String, val text: String, val status: String, val revision: Int, val error: String, val raw: JSONObject, val recoveryRequired: Boolean = false)
fun queueTask(j: JSONObject) = QueueTask(j.string("id"), j.string("text"), j.string("status"), j.optInt("revision"), j.string("error"), j, j.optBoolean("recoveryRequired"))
data class SessionSnapshot(val id: String, val name: String, val status: String, val activeTurnId: String,
    val messages: List<NativeMessage>, val requests: List<NativeRequest>, val settings: JSONObject, val historyCursor: String? = null, val readOnly: Boolean = false, val queue: List<QueueTask> = emptyList(), val plan: JSONObject? = null, val tokenUsage: JSONObject? = null, val changes: List<JSONObject> = emptyList(), val updatedAt: Long = 0, val turnSettings: List<NativeTurnSettings> = emptyList(),
    val nativeRevision: String? = null, val nativeSyncState: String = "ready", val recoveryTask: QueueTask? = null,
    val recoveryRequired: Boolean = false, val nativeExecution: String = "", val deliveryState: String = "",
    val displayState: String = "", val writerOwner: String = "", val queueDepth: Int = 0, val reviewDiagnostics: List<JSONObject> = emptyList(), val nativeRuntime: JSONObject? = null, val goal: JSONObject? = null, val goalSupported: Boolean = false, val control: JSONObject? = null, val workspaceUnavailable: Boolean = false) {
    val isOngoing: Boolean get() = activeTurnId.isNotBlank() || status == "running" || goal?.string("status") == "active"
    val nativeReady: Boolean get() = nativeSyncState == "ready"
}

/** Project native items by IDs, never by text matching or by duplicating a local history. */
fun sessionSnapshot(json: JSONObject): SessionSnapshot {
    val thread = json.getJSONObject("thread")
    val observedSettings = json.objects("turnSettings").associateBy { it.string("turnId") }
    val turnSettings = thread.objects("turns").map { turn ->
        val id = turn.string("id")
        val observed = observedSettings[id]
        NativeTurnSettings(id, (observed?.opt("model") as? String)?.takeIf(String::isNotBlank),
            (observed?.opt("effort") as? String)?.takeIf(String::isNotBlank))
    }
    val messages = thread.objects("turns").flatMap { turn ->
        turn.objects("items").filterNot { it.string("type") == "hookPrompt" }.map { item ->
            val type = item.string("type")
            val content = if (type in setOf("userMessage", "user_message")) item.objects("content").map { part ->
                if (part.string("type") == "text") JSONObject(part.toString()).put("text", visibleUserText(part.string("text"))) else part
            } else emptyList()
            // The enclosing native turn owns execution lifetime. A persisted tool item may
            // still say inProgress after interruption; never keep its spinner alive forever.
            val projectedItem = if (turn.string("status") in setOf("completed", "interrupted", "failed", "canceled") &&
                item.string("status") in setOf("inProgress", "running", "started", "pending"))
                JSONObject(item.toString()).put("status", turn.string("status")) else item
            val activity = nativeActivity(projectedItem)
            val text = when (type) {
                "userMessage", "user_message" -> content.joinToString("\n\n") { part -> part.string("text").ifBlank {
                    when (part.string("type")) { "image", "localImage" -> "[图片]"; "mention", "skill" -> part.string("name"); else -> part.toString(2) }
                } }.ifBlank { visibleUserText(item.string("text")) }
                "agentMessage", "agent_message", "plan" -> item.string("text")
                "exitedReviewMode" -> item.string("review")
                "reasoning" -> nativeReasoningText(item)
                "commandExecution", "command_execution" -> listOf(item.string("command"), item.string("aggregatedOutput")).filter { it.isNotBlank() }.joinToString("\n\n")
                "fileChange", "file_change" -> item.objects("changes").joinToString("\n\n") { it.string("path") + "\n" + it.string("diff") }
                else -> item.string("text").ifBlank { item.toString(2) }
            }
            NativeMessage(item.string("id"), turn.string("id"), type, text, item, content, activity)
        }
    }
    val requests = json.objects("requests").map { NativeRequest(it.string("key"), it.string("method"), it.optJSONObject("params") ?: JSONObject()) }
    return SessionSnapshot(thread.string("id"), sessionTitle(thread), json.string("status"), json.string("activeTurnId"), messages, requests, json.optJSONObject("settings") ?: JSONObject(),
        json.string("nextCursor").takeIf { it.isNotBlank() }, json.optBoolean("readOnly", false), json.objects("queue").map(::queueTask), json.optJSONObject("plan"), json.optJSONObject("tokenUsage"), json.objects("changes"), thread.optLong("updatedAt", 0), turnSettings,
        json.string("nativeRevision").takeIf(String::isNotBlank), json.optJSONObject("nativeSync")?.string("state")?.ifBlank { "ready" } ?: "ready",
        json.optJSONObject("recoveryTask")?.let(::queueTask), json.optBoolean("recoveryRequired"), json.string("nativeExecution"),
        json.string("deliveryState"), json.string("displayState"), json.string("writerOwner"), json.optInt("queueDepth").coerceAtLeast(0),
        mergeTurnDiagnostics(emptyList(), json.objects("reviewDiagnostics")), json.optJSONObject("nativeRuntime"), json.optJSONObject("goal"), json.optBoolean("goalSupported"), json.optJSONObject("control"), json.optBoolean("workspaceUnavailable"))
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
    val turns = (previous.map { it.turnId } + incoming.map { it.turnId }).distinct()
    return turns.flatMap { turnId ->
        val merged = previous.filter { it.turnId == turnId }.toMutableList()
        incoming.filter { it.turnId == turnId }.forEach { item ->
            val index = merged.indexOfFirst { it.id == item.id }
            if (index >= 0) merged[index] = item else merged += item
        }
        merged
    }
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

/** Apply only deltas with a known baseline. Unknown/missing items require a native snapshot. */
fun SessionSnapshot.applyLiveDelta(event: JSONObject): SessionSnapshot? {
    val field = when (event.string("method")) {
        "item/agentMessage/delta" -> "text"
        "item/commandExecution/outputDelta" -> "aggregatedOutput"
        else -> return null
    }
    val params = event.optJSONObject("params") ?: return null
    if (params.string("threadId") != id) return null
    val turnId = params.string("turnId").takeIf { it.isNotBlank() } ?: return null
    val itemId = params.string("itemId").takeIf { it.isNotBlank() } ?: return null
    val delta = params.opt("delta") as? String ?: return null
    val offset = (params.opt("relayOffset") as? Number)?.toLong() ?: return null
    val index = messages.indexOfFirst { it.turnId == turnId && it.id == itemId }
    if (index < 0) return null
    val old = messages[index]
    if (old.type != if (field == "text") "agentMessage" else "commandExecution") return null
    val previous = old.raw.string(field)
    // A snapshot may already include this event (or later text). Never append twice or regress.
    if (offset < 0 || offset > previous.codePointCount(0, previous.length)) return null
    val start = previous.offsetByCodePoints(0, offset.toInt())
    val tail = previous.substring(start)
    if (tail.startsWith(delta)) return this
    if (!delta.startsWith(tail)) return null
    val raw = JSONObject(old.raw.toString()).put(field, previous.substring(0, start) + delta)
    val text = if (field == "text") raw.string("text") else
        listOf(raw.string("command"), raw.string("aggregatedOutput")).filter { it.isNotBlank() }.joinToString("\n\n")
    return copy(messages = messages.toMutableList().also {
        it[index] = old.copy(text = text, raw = raw, activity = nativeActivity(raw))
    })
}
