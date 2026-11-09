package dev.rivo.relay.data

import org.json.JSONArray
import org.json.JSONObject

/** A display-only receipt overlay. It never supplies execution, queue or canonical-history facts. */
data class PendingNativeMessage(
    val submissionId: String,
    val clientId: String,
    val threadId: String,
    val input: List<JSONObject>,
    val kind: String = "turn",
    val delivery: String = "submitting",
    val nativeTurnId: String? = null,
    val anchorItemId: String? = null,
    val startsThread: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

fun PendingNativeMessage.toJson(includeInput: Boolean = true): JSONObject = JSONObject()
    .put("submissionId", submissionId).put("clientId", clientId).put("threadId", threadId)
    .put("kind", kind).put("delivery", delivery).put("nativeTurnId", nativeTurnId)
    .put("anchorItemId", anchorItemId).put("startsThread", startsThread).put("createdAt", createdAt)
    .also { if (includeInput) it.put("input", JSONArray(input)) }

fun pendingNativeMessage(value: JSONObject, input: List<JSONObject> = value.objects("input")) = PendingNativeMessage(
    value.getString("submissionId"), value.getString("clientId"), value.getString("threadId"), input,
    value.string("kind").ifBlank { "turn" }, value.string("delivery").ifBlank { "unknown" },
    value.string("nativeTurnId").takeIf(String::isNotBlank), value.string("anchorItemId").takeIf(String::isNotBlank),
    value.optBoolean("startsThread"), value.optLong("createdAt"))

/** Only the native clientId may take over an optimistic user message; equal prose is unrelated. */
fun projectedSessionMessages(snapshot: SessionSnapshot?, pending: List<PendingNativeMessage>): List<NativeMessage> {
    val native = snapshot?.messages.orEmpty()
    val confirmed = native.asSequence().filter { it.type in setOf("userMessage", "user_message") }
        .map { it.raw.string("clientId") }.filter(String::isNotBlank).toSet()
    val result = native.toMutableList()
    pending.filterNot { it.clientId in confirmed || (it.kind == "queue" && it.delivery != "failed") }.sortedBy { it.createdAt }.forEach { message ->
        val item = JSONObject().put("id", "pending:${message.clientId}").put("type", "userMessage")
            .put("clientId", message.clientId).put("content", JSONArray(message.input)).put("relayDelivery", message.delivery)
        val turn = JSONObject().put("id", message.nativeTurnId ?: "pending:${message.clientId}")
            .put("items", JSONArray().put(item))
        val projected = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", message.threadId).put("turns", JSONArray().put(turn))))
            .messages.single()
        val turnStart = message.nativeTurnId?.let { id -> result.indexOfFirst { it.turnId == id && !it.raw.has("relayDelivery") }.takeIf { it >= 0 } }
        val anchor = message.anchorItemId?.let { id -> result.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
        // An accepted older turn may have fallen outside the loaded history page.
        // Keep its receipt for exact handoff, without moving it after newer replies.
        if (message.nativeTurnId != null && native.isNotEmpty() && turnStart == null && anchor == null) return@forEach
        var index = when {
            message.kind != "guidance" && turnStart != null -> turnStart
            anchor != null -> anchor + 1
            message.kind == "guidance" && message.nativeTurnId != null -> result.indexOfLast { it.turnId == message.nativeTurnId }.takeIf { it >= 0 }?.plus(1) ?: result.size
            message.startsThread -> result.indexOfFirst { !it.raw.has("relayDelivery") }.takeIf { it >= 0 } ?: result.size
            else -> result.size
        }
        while (index < result.size && result[index].raw.has("relayDelivery")) index++
        result.add(index, projected)
    }
    return result
}

/** Display-only queue receipts; these do not grant queue operations. */
fun projectedPendingQueue(snapshot: SessionSnapshot?, pending: List<PendingNativeMessage>): List<QueueTask> {
    val known = snapshot?.queue.orEmpty().map { it.id }.toSet() +
        snapshot?.messages.orEmpty().map { it.raw.string("clientId") }
    return pending.filter { it.kind == "queue" && it.delivery != "failed" && it.clientId !in known }
        .sortedBy { it.createdAt }.map {
            QueueTask(it.clientId, it.input.mapNotNull { part -> part.optString("text").takeIf(String::isNotBlank) }.joinToString("\n"),
                when (it.delivery) { "unknown" -> "pending_unknown"; "accepted" -> "pending_accepted"; else -> "submitting" }, 0, "", JSONObject())
        }
}
