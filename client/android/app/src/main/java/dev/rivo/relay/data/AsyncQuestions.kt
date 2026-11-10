package dev.rivo.relay.data

import org.json.JSONArray
import org.json.JSONObject

/** Desktop's identity is JSON.stringify([tool name, native item ID, question index]). */
data class AsyncQuestion(val id: String, val title: String, val options: List<String>) {
    fun card(): JSONObject = JSONObject().put("id", id).put("question", title).put("isOther", true)
        .put("options", JSONArray(options.map { JSONObject().put("label", it) }))
}

fun pendingAsyncQuestions(snapshot: SessionSnapshot): List<AsyncQuestion> {
    val turnId = snapshot.activeTurnId.ifBlank {
        snapshot.turnSettings.lastOrNull()?.turnId ?: snapshot.messages.lastOrNull()?.turnId.orEmpty()
    }
    if (snapshot.turnSettings.lastOrNull { it.turnId == turnId }?.status in
        setOf("failed", "interrupted", "canceled", "cancelled")) return emptyList()
    val pending = linkedMapOf<String, AsyncQuestion>()
    snapshot.messages.filter { it.turnId == turnId }.forEach { message ->
        val item = message.raw
        when (message.type) {
            "agentMessage" -> if (message.id.isNotBlank()) {
                // Preserve original indices even when a malformed question is skipped.
                val questions = item.optJSONArray("questions") ?: JSONArray()
                for (index in 0 until questions.length()) {
                    val question = questions.optJSONObject(index) ?: continue
                    val title = (question.opt("title") as? String)?.takeIf(String::isNotBlank) ?: continue
                    val id = JSONArray().put("request_user_input_async").put(message.id).put(index).toString()
                    pending[id] = AsyncQuestion(id, title, (question.optJSONArray("options") ?: JSONArray()).let { options ->
                        (0 until options.length()).mapNotNull { options.opt(it) as? String }.filter(String::isNotBlank)
                    })
                }
            }
            "userMessage", "steeringUserMessage" -> {
                val accepted = message.type == "userMessage" || item.string("status") == "accepted" || item.optBoolean("steerAcknowledged")
                if (accepted) {
                    val content = item.objects(if (message.type == "userMessage") "content" else "input")
                    val text = content.filter { it.string("type") == "text" }.joinToString("\n") { it.string("text") }
                        .ifBlank { item.string("text") }
                    asyncReplyIds(text).forEach(pending::remove)
                }
            }
        }
    }
    return pending.values.filterNot { snapshot.questionAnswerStates.string(it.id) == "accepted" }
}

private fun asyncReplyIds(source: String): List<String> {
    val open = "<send_user_message_question_reply>"
    val close = "</send_user_message_question_reply>"
    val text = source.trim()
    if (!text.startsWith(open) || !text.endsWith(close)) return emptyList()
    return runCatching {
        val payload = text.substring(open.length, text.length - close.length).trim()
        val rows = if (payload.startsWith("[")) JSONArray(payload) else JSONArray().put(JSONObject(payload))
        (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.takeIf { it.opt("question") is String && it.opt("answer") is String }
                ?.opt("questionItemId") as? String
        }
    }.getOrDefault(emptyList())
}

fun asyncQuestionReply(questions: List<AsyncQuestion>, answers: Map<String, String>): JSONArray {
    require(questions.isNotEmpty() && questions.all { !answers[it.id].isNullOrBlank() })
    val replies = JSONArray(questions.map { question -> JSONObject().put("questionItemId", question.id)
        .put("question", question.title).put("answer", answers.getValue(question.id).trim()) })
    return JSONArray().put(JSONObject().put("type", "text")
        .put("text", "<send_user_message_question_reply>\n$replies\n</send_user_message_question_reply>"))
}
