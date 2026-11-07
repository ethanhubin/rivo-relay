package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.rivo.relay.data.*
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.ui.chat.*
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test

class MessagePerformanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tableStartsWithOneBatchButCopyQuoteAndExpandedRowsRetainTheFullSource() {
        val source = "A | B\n---|---\n" + (0..MARKDOWN_TABLE_BATCH_SIZE).joinToString("\n") { "$it | value" }
        val table = parseChatMarkdownBlocks(source).single() as ChatMarkdownBlock.Table
        var copied = ""; var quoted = ""
        compose.setContent { RivoTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            ChatMarkdownTable(table, "test", 0, { copied = it }, { quoted = it }, {}, {})
        } } } }
        compose.onNodeWithTag("markdown_table_test_0_row_$MARKDOWN_TABLE_BATCH_SIZE").assertDoesNotExist()
        compose.onNodeWithTag("markdown_table_copy_all").performClick()
        compose.onNodeWithTag("markdown_table_quote_all").performClick()
        compose.runOnIdle { assertEquals(source, copied); assertEquals(source, quoted) }
        compose.onNodeWithTag("markdown_table_show_more").performScrollTo().performClick()
        compose.onNodeWithTag("markdown_table_test_0_row_$MARKDOWN_TABLE_BATCH_SIZE").assertExists()
        compose.onNodeWithTag("markdown_table_show_more").assertDoesNotExist()
    }

    @Test fun activityExpansionKeepsItsBatchWhenTheSameGroupReceivesAnotherItem() {
        fun message(index: Int) = NativeMessage("tool-$index", "turn", "mcpToolCall", "", JSONObject(),
            activity = NativeActivity("tool", "工具", "结果 $index", "", "completed"))
        val messages = mutableStateOf((0..NATIVE_ACTIVITY_BATCH_SIZE).map(::message))
        compose.setContent { RivoTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            NativeActivityGroup(NativeTimelineRow.Activities(messages.value)) {}
        } } } }
        compose.onNodeWithTag("native_activity_toggle").performClick()
        compose.onNodeWithTag("activity_summary_tool-$NATIVE_ACTIVITY_BATCH_SIZE").assertDoesNotExist()
        compose.onNodeWithTag("activity_show_more").performScrollTo().performClick()
        compose.onNodeWithTag("activity_summary_tool-$NATIVE_ACTIVITY_BATCH_SIZE").assertExists()
        compose.runOnIdle { messages.value = messages.value + message(NATIVE_ACTIVITY_BATCH_SIZE + 1) }
        compose.onNodeWithTag("activity_summary_tool-$NATIVE_ACTIVITY_BATCH_SIZE").assertExists()
        compose.onNodeWithTag("activity_show_more").assertExists()
    }

    @Test fun aCompletedBackgroundParseCannotReplaceANewerMessageRevision() {
        val markdown = mutableStateOf("[x](".repeat(32768) + "tail")
        compose.setContent { RivoTheme { Surface { MessageContent(markdown.value) } } }
        compose.runOnIdle { markdown.value = "**当前回复**" }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("当前回复").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("message_text").assertTextContains("当前回复")
        compose.onAllNodesWithText("tail", substring = true).assertCountEquals(0)
    }

    @Test fun textOnlyNativeRevisionsDoNotRequestTheMediaLibrary() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/v1/media/capabilities" -> MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"media":{"proxy":true,"library":true},"speech":{"streaming":false,"state":"unconfigured"}}""")
                "/v1/media/library" -> MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[]}""")
                else -> MockResponse().setResponseCode(404).setBody("""{"error":{"code":"unexpected_test_request"}}""")
            }
        }
        server.start()
        try {
            val initial = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "text-only")))
            val state = mutableStateOf(SessionState(id = initial.id, snapshot = initial))
            val api = RelayApi(server.url("/").toString(), "test-token")
            compose.setContent { RivoTheme { Surface {
                ChatScreen(state.value, false, false, emptyList(), {}, onSend = { _, _, _, _ -> }, onStop = {},
                    onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() }, mediaApi = api)
            } } }
            // Chat startup legitimately reads speech/media capabilities. Wait for that request
            // before exercising revisions, then inspect actual paths rather than banning all HTTP.
            compose.waitUntil(10_000) { server.requestCount > 0 }
            repeat(3) { revision -> compose.runOnIdle {
                state.value = state.value.copy(snapshot = initial.copy(nativeRevision = "$revision",
                    messages = listOf(NativeMessage("answer", "turn", "agentMessage", "reply $revision", JSONObject()))))
            } }
            compose.waitForIdle()
            val requests = mutableListOf<RecordedRequest>()
            while (true) {
                val request = server.takeRequest(250, TimeUnit.MILLISECONDS) ?: break
                requests += request
            }
            assertTrue(requests.any { it.method == "GET" && it.requestUrl?.encodedPath == "/v1/media/capabilities" })
            assertTrue("Text-only revisions must not list media: ${requests.map { it.path }}",
                requests.none { it.requestUrl?.encodedPath == "/v1/media/library" })
        } finally { server.shutdown() }
    }
}
