package dev.rivo.relay

import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.*
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeHistoryTest {
    @Test fun backwardsAnchorIsNotAnEarlierHistoryPage() {
        val response = JSONObject("""{"thread":{"id":"t","turns":[]},"nextCursor":null,"backwardsCursor":"reverse-sort-anchor"}""")
        assertNull(sessionSnapshot(response).historyCursor)
        response.put("nextCursor", "earlier-page")
        assertEquals("earlier-page", sessionSnapshot(response).historyCursor)
    }
    @Test fun olderPageAndRefreshedTailUseNativeIds() {
        fun item(id: String, turn: String, text: String) = NativeMessage(id, turn, "agentMessage", text, JSONObject())
        val first = item("a", "old", "old result")
        val second = item("b", "new", "stream")
        val updated = second.copy(text = "stream complete")
        assertEquals(listOf(first, updated), mergeLatestMessages(listOf(first, second), listOf(updated)))
        assertEquals(listOf(first, second), prependNativePage(listOf(first, second), listOf(second)))
        assertEquals(listOf(first, updated), prependNativePage(listOf(first, second), listOf(updated)))
    }
    @Test fun identicalTextWithDifferentNativeIdsRemainsTwoItems() {
        val snapshot = sessionSnapshot(JSONObject("""{"thread":{"id":"t","name":"Test","turns":[{"id":"turn","items":[{"id":"one","type":"agentMessage","text":"same"},{"id":"two","type":"agentMessage","text":"same"}]}]},"requests":[],"settings":{},"status":"idle"}"""))
        assertEquals(listOf("one", "two"), snapshot.messages.map { it.id })
        assertTrue(snapshot.messages.all { it.turnId == "turn" })
    }
    @Test fun nativeQuestionIdentityAndFieldsSurviveProjection() {
        val snapshot = sessionSnapshot(JSONObject("""{"thread":{"id":"t","turns":[]},"requests":[{"key":"connection:42","method":"item/tool/requestUserInput","params":{"questions":[{"id":"q","question":"Choose"}]}}]}"""))
        assertEquals("connection:42", snapshot.requests.single().key)
        assertEquals("q", snapshot.requests.single().params.objects("questions").single().string("id"))
    }
    @Test fun migratedMarkdownRetainsCodeAndNativeFileCitation() {
        val value = renderChatMarkdown("**Done**\n`code`\n:codex-file-citation{path=\"/tmp/result.txt\"}", Color.Blue, Color.Gray, Color.Gray)
        assertTrue(value.text.contains("Done")); assertTrue(value.text.contains("code")); assertTrue(value.text.contains("result.txt"))
        assertEquals("/tmp/result.txt", markdownLinkAt(value, value.text.indexOf("result.txt")))
    }
}
