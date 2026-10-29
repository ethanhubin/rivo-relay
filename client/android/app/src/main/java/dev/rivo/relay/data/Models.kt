package dev.rivo.relay.data

import org.json.JSONObject

data class ThreadSummary(val id: String, val name: String, val preview: String, val status: String, val cwd: String, val projectId: String, val pinned: Boolean = false, val updatedAt: Long = 0)
fun threadSummary(json: JSONObject) = ThreadSummary(json.string("id"), visibleSessionText(json.string("name")).ifBlank { "新会话" }, visibleSessionText(json.string("preview")), json.string("status"), json.string("cwd"), json.string("projectId"), json.optBoolean("pinned", false), json.optLong("updatedAt", 0))
data class Todo(val id: String, val title: String, val notes: String, val completed: Boolean, val projectId: String, val threadId: String, val details: JSONObject = JSONObject())
fun todo(json: JSONObject) = Todo(json.string("id"), json.string("title"), json.string("notes"), json.optBoolean("completed"), json.string("projectId"), json.string("threadId"), json.optJSONObject("details") ?: JSONObject())
data class NativeMessage(val id: String, val turnId: String, val type: String, val text: String, val raw: JSONObject,
    val content: List<JSONObject> = emptyList(), val activity: NativeActivity? = null)
data class NativeRequest(val key: String, val method: String, val params: JSONObject)
data class QueueTask(val id: String, val text: String, val status: String, val revision: Int, val error: String, val raw: JSONObject)
fun queueTask(j: JSONObject) = QueueTask(j.string("id"), j.string("text"), j.string("status"), j.optInt("revision"), j.string("error"), j)
data class SessionSnapshot(val id: String, val name: String, val status: String, val activeTurnId: String,
    val messages: List<NativeMessage>, val requests: List<NativeRequest>, val settings: JSONObject, val historyCursor: String? = null, val readOnly: Boolean = false, val queue: List<QueueTask> = emptyList(), val plan: JSONObject? = null, val tokenUsage: JSONObject? = null, val changes: List<JSONObject> = emptyList())

/** Project native items by IDs, never by text matching or by duplicating a local history. */
fun sessionSnapshot(json: JSONObject): SessionSnapshot {
    val thread = json.getJSONObject("thread")
    val messages = thread.objects("turns").flatMap { turn ->
        turn.objects("items").filterNot { it.string("type") == "hookPrompt" }.map { item ->
            val type = item.string("type")
            val content = if (type in setOf("userMessage", "user_message")) item.objects("content").map { part ->
                if (part.string("type") == "text") JSONObject(part.toString()).put("text", visibleUserText(part.string("text"))) else part
            } else emptyList()
            val activity = nativeActivity(item)
            val text = when (type) {
                "userMessage", "user_message" -> content.joinToString("\n\n") { part -> part.string("text").ifBlank {
                    when (part.string("type")) { "image", "localImage" -> "[图片]"; "mention", "skill" -> part.string("name"); else -> part.toString(2) }
                } }.ifBlank { visibleUserText(item.string("text")) }
                "agentMessage", "agent_message", "plan" -> item.string("text")
                "exitedReviewMode" -> item.string("review")
                "reasoning" -> item.optJSONArray("summary")?.let { a -> (0 until a.length()).joinToString("\n") { a.optString(it) } }.orEmpty().ifBlank { item.string("text") }
                "commandExecution", "command_execution" -> listOf(item.string("command"), item.string("aggregatedOutput")).filter { it.isNotBlank() }.joinToString("\n\n")
                "fileChange", "file_change" -> item.objects("changes").joinToString("\n\n") { it.string("path") + "\n" + it.string("diff") }
                else -> item.string("text").ifBlank { item.toString(2) }
            }
            NativeMessage(item.string("id"), turn.string("id"), type, text, item, content, activity)
        }
    }
    val requests = json.objects("requests").map { NativeRequest(it.string("key"), it.string("method"), it.optJSONObject("params") ?: JSONObject()) }
    return SessionSnapshot(thread.string("id"), visibleSessionText(thread.string("name")).ifBlank { "新会话" }, json.string("status"), json.string("activeTurnId"), messages, requests, json.optJSONObject("settings") ?: JSONObject(),
        json.string("nextCursor").takeIf { it.isNotBlank() }, json.optBoolean("readOnly", false), json.objects("queue").map(::queueTask), json.optJSONObject("plan"), json.optJSONObject("tokenUsage"), json.objects("changes"))
}

fun mergeLatestMessages(previous: List<NativeMessage>, latest: List<NativeMessage>): List<NativeMessage> {
    val firstTurn = latest.firstOrNull()?.turnId ?: return latest
    val start = previous.indexOfFirst { it.turnId == firstTurn }
    return if (start >= 0) previous.take(start) + latest else latest
}

fun prependNativePage(earlier: List<NativeMessage>, current: List<NativeMessage>): List<NativeMessage> {
    val currentIds = current.map { it.turnId to it.id }.toSet()
    return earlier.filterNot { (it.turnId to it.id) in currentIds }.distinctBy { it.turnId to it.id } + current
}
