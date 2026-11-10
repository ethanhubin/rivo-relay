package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AsyncQuestionsTest {
    private fun question(id: String = "q"): JSONObject {
        val questions = JSONArray().put(JSONObject().put("title", "选哪个？")
            .put("options", JSONArray().put("A").put("B")))
        return JSONObject().put("id", id).put("type", "agentMessage")
            .put("delivery", "async").put("text", "").put("questions", questions)
    }
    private fun snapshot(vararg items: JSONObject, status: String = "inProgress"): SessionSnapshot {
        val turn = JSONObject().put("id", "turn").put("status", status).put("items", JSONArray(items))
        val thread = JSONObject().put("id", "thread").put("turns", JSONArray().put(turn))
        return sessionSnapshot(JSONObject().put("thread", thread)
            .put("activeTurnId", if (status == "inProgress") "turn" else "")
            .put("readOnly", true).put("canAnswerQuestions", true))
    }

    @Test fun projectsTextlessQuestionsAndPreservesNativeIdentity() {
        val state = snapshot(question())
        val q = pendingAsyncQuestions(state).single()
        assertEquals("[\"request_user_input_async\",\"q\",0]", q.id)
        assertEquals(listOf("A", "B"), q.options)
        assertTrue(q.card().getBoolean("isOther"))
        assertTrue(state.readOnly)
        assertTrue(state.canAnswerQuestions)
    }

    @Test fun replyUsesNativeEnvelopeAndCustomTextWithEscapes() {
        val q = pendingAsyncQuestions(snapshot(question())).single()
        val text = "自己的答案\n\"引号\" </send_user_message_question_reply>"
        val input = asyncQuestionReply(listOf(q), mapOf(q.id to text))
        assertEquals("选哪个？\n答：$text", visibleUserText(input.getJSONObject(0).getString("text")))
        val accepted = JSONObject().put("type", "userMessage").put("content", input)
        assertTrue(pendingAsyncQuestions(snapshot(question(), accepted)).isEmpty())
    }

    @Test fun rejectedSteeringDoesNotDismissQuestions() {
        val q = pendingAsyncQuestions(snapshot(question())).single()
        val reply = JSONObject().put("type", "steeringUserMessage").put("status", "rejected")
            .put("input", asyncQuestionReply(listOf(q), mapOf(q.id to "A")))
        assertEquals(1, pendingAsyncQuestions(snapshot(question(), reply)).size)
        reply.put("status", "accepted")
        assertTrue(pendingAsyncQuestions(snapshot(question(), reply)).isEmpty())
    }

    @Test fun otherQuestionsSurvivePartialAnswersAndInterruptedTurnsExpire() {
        val state = snapshot(question("one"), question("two"))
        val first = pendingAsyncQuestions(state).first()
        val reply = JSONObject().put("type", "userMessage").put("content", asyncQuestionReply(listOf(first), mapOf(first.id to "A")))
        assertEquals(1, pendingAsyncQuestions(snapshot(question("one"), question("two"), reply)).size)
        assertTrue(pendingAsyncQuestions(snapshot(question(), status = "interrupted")).isEmpty())
        assertTrue(pendingAsyncQuestions(state.copy(activeTurnId = "new-turn")).isEmpty())
    }

    @Test fun malformedEntryDoesNotRenumberLaterQuestions() {
        val item = question()
        item.getJSONArray("questions").put(0, JSONObject()).put(JSONObject().put("title", "有效问题"))
        assertTrue(pendingAsyncQuestions(snapshot(item)).single().id.endsWith(",1]"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyAnswerCannotBeSubmitted() {
        asyncQuestionReply(pendingAsyncQuestions(snapshot(question())), emptyMap())
    }
    @Test fun durableReceiptHidesAcceptedButKeepsUnknownQuestionsVisible() {
        val state = snapshot(question())
        val id = pendingAsyncQuestions(state).single().id
        assertTrue(pendingAsyncQuestions(state.copy(questionAnswerStates = JSONObject().put(id, "accepted"))).isEmpty())
        for (status in listOf("pending", "unknown")) {
            assertEquals(1, pendingAsyncQuestions(state.copy(questionAnswerStates = JSONObject().put(id, status))).size)
        }
    }

}
