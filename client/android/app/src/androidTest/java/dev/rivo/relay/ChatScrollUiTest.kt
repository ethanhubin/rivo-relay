package dev.rivo.relay

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.sessionSnapshot
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScrollUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun snapshot(count: Int, tail: String = "最新消息", id: String = "scroll-test") = sessionSnapshot(
        JSONObject().put("thread", JSONObject().put("id", id).put("name", "滚动验证").put("turns", JSONArray().put(
            JSONObject().put("id", "turn").put("items", JSONArray().apply {
                repeat(count) { put(JSONObject().put("id", "message-$it").put("type", "agentMessage")
                    .put("text", if (it == count - 1) tail else "历史消息 $it\n\n这是用于检查滚动位置的正文。")) }
            })))).put("status", "idle"))
    private val current = mutableStateOf(SessionState(id = "scroll-test", loading = true))
    private fun show() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.systemBarsPadding()) {
            ChatScreen(current.value, false, false, emptyList(), {}, { _,_,_,_-> }, {}, {_,_->}, {}, {}, {}, { JSONObject() })
        } } } }
    }
    private fun evidence(name: String) {
        compose.waitForIdle()
        val ins = InstrumentationRegistry.getInstrumentation()
        ins.uiAutomation.waitForIdle(300, 5000)
        val dir = File(ins.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun atLatest() {
        compose.waitUntil(10000) { compose.onAllNodesWithTag("jump_to_latest").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("latest_message_anchor").assertIsDisplayed()
    }
    @Test fun initialLoadAndStreamingLongLastMessageFollowActualBottom() {
        show()
        compose.runOnIdle { current.value = SessionState(id="scroll-test", snapshot=snapshot(30, (1..40).joinToString("\n\n") { "长消息第 $it 段" })) }
        atLatest()
        compose.runOnIdle { current.value = current.value.copy(snapshot=snapshot(30, (1..60).joinToString("\n\n") { "长消息第 $it 段" })) }
        atLatest()
        compose.onNodeWithText("长消息第 60 段", substring = true).assertIsDisplayed()
        evidence("chat-scroll-long-message-0.1.5")
    }
    @Test fun readingHistoryStaysPutUntilFloatingButtonRestoresFollowing() {
        current.value = SessionState(id="scroll-test", snapshot=snapshot(30)); show(); atLatest()
        compose.onNodeWithTag("chat_history").performTouchInput { swipeDown() }
        compose.onNodeWithTag("jump_to_latest").assertIsDisplayed()
        evidence("chat-scroll-button-0.1.5")
        val before = compose.onNodeWithTag("chat_history").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle { current.value = current.value.copy(snapshot=snapshot(31, "追加的新消息")) }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("chat_history").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before, after, 0.01f)
        compose.onNodeWithTag("jump_to_latest").performClick(); atLatest()
        compose.onNodeWithText("追加的新消息").assertIsDisplayed()
        compose.runOnIdle { current.value = current.value.copy(snapshot=snapshot(32, "恢复自动跟随")) }
        atLatest(); compose.onNodeWithText("恢复自动跟随").assertIsDisplayed()
        evidence("chat-scroll-latest-0.1.5")
    }
    @Test fun switchingThreadsResetsHistoryReadingPosition() {
        current.value = SessionState(id="scroll-test", snapshot=snapshot(30)); show(); atLatest()
        compose.onNodeWithTag("chat_history").performTouchInput { swipeDown() }
        compose.onNodeWithTag("jump_to_latest").assertIsDisplayed()
        compose.runOnIdle { current.value = SessionState(id="other", snapshot=snapshot(40, "另一会话最新消息", "other")) }
        atLatest(); compose.onNodeWithText("另一会话最新消息").assertIsDisplayed()
    }
}
