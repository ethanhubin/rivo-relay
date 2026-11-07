package dev.rivo.relay

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.sessionSnapshot
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native history fixture: no node access and no tool execution. */
@RunWith(AndroidJUnit4::class)
class ChatRenderingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun evidence(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(300, 5_000)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }

    @Test fun nativeQuestionAndConsecutiveToolsUseQiguangPresentation() {
        // Match MainActivity's real edge-to-edge and resize behavior, without starting its node session.
        compose.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val reply = JSONArray().put(JSONObject().put("questionItemId", "private-request-id")
            .put("question", "是否继续验证消息展示？").put("answer", "继续测试"))
        val wrapper = "<send_user_message_question_reply>\n$reply\n</send_user_message_question_reply>"
        val items = JSONArray().put(JSONObject().put("id", "user").put("type", "userMessage")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", wrapper))))
        repeat(8) { index -> items.put(JSONObject().put("id", "command-$index").put("type", "commandExecution")
            .put("command", "rg --files sample-$index").put("aggregatedOutput", "sample-$index/result.kt")
            .put("status", "completed").put("exitCode", 0)) }
        items.put(JSONObject().put("id", "answer").put("type", "agentMessage").put("phase", "final_answer")
            .put("text", "消息已整理。**工具活动已合并**，仅显示命令数量和状态。"))
        val snapshot = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "fixture-thread").put("name", "消息展示验证")
            .put("turns", JSONArray().put(JSONObject().put("id", "fixture-turn").put("items", items))))
            .put("requests", JSONArray()).put("settings", JSONObject()).put("status", "idle")
            .put("backwardsCursor", "reverse-only-cursor"))
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.systemBarsPadding()) {
            ChatScreen(SessionState(id = snapshot.id, snapshot = snapshot), false, false, emptyList(), {},
                onSend = { _, _, _, _ -> }, onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {},
                readFile = { JSONObject() })
        } } } }
        compose.onAllNodesWithText("send_user_message_question_reply", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("private-request-id", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("load_earlier").assertDoesNotExist()
        compose.onNodeWithTag("chat_history").performScrollToNode(hasText("答：继续测试", substring = true))
        compose.onNodeWithText("答：继续测试", substring = true).assertIsDisplayed()
        compose.onAllNodesWithTag("native_activity_group").assertCountEquals(1)
        compose.onAllNodesWithText("sample-0/result.kt", substring = true).assertCountEquals(0)
        evidence("chat-rendering-0.1.1")
        compose.onNodeWithTag("native_activity_toggle").assertDoesNotExist()
        compose.onNodeWithText("运行了 8 条命令 · 成功 8 · 失败 0").assertIsDisplayed()
        compose.onAllNodesWithText("rg --files", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("message_input").performClick().performTextInput("继续验证")
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithTag("send_message").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("消息展示验证").assertIsDisplayed()
        evidence("chat-keyboard-0.1.1")
    }
    @Test fun inspectedScreenshotLoadsOnlyAfterExplicitPreview() {
        var reads = 0
        val snapshot = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "image-tool").put("name", "工具图片验证")
            .put("turns", JSONArray().put(JSONObject().put("id", "turn").put("items", JSONArray().put(
                JSONObject().put("id", "image").put("type", "imageView").put("path", "/tmp/inspection.png")))))))
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) {
            ChatScreen(SessionState(id=snapshot.id, snapshot=snapshot), false, false, emptyList(), {},
                onSend={_,_,_,_->}, onStop={}, onRespond={_,_->}, onRefresh={}, onLoadModels={}, onEarlier={},
                readFile={ reads++; JSONObject().put("path", it).put("encoding", "utf-8").put("text", "preview") })
        } } }
        compose.onNodeWithText("查看了图片 inspection.png").assertIsDisplayed()
        compose.onNodeWithTag("native_image").assertDoesNotExist()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, reads) }
        evidence("image-tool-collapsed-0.1.6")
        compose.onNodeWithTag("native_activity_toggle").performClick()
        compose.onNodeWithTag("file_preview").assertIsDisplayed()
        compose.waitUntil(5000) { reads == 1 }
    }

    @Test fun losingPermissionLocksDraftAndRestoringPermissionPreservesIt() {
        val state = androidx.compose.runtime.mutableStateOf(SessionState(id = "control", snapshot = sessionSnapshot(
            JSONObject().put("thread", JSONObject().put("id", "control")).put("writerOwner", "relay"))))
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) {
            ChatScreen(state.value, false, false, emptyList(), {},
                onSend = { _, _, _, _ -> error("Must not send") }, onStop = {}, onRespond = { _, _ -> },
                onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() })
        } } }
        compose.onNodeWithTag("message_input").performTextInput("保留草稿")
        compose.runOnIdle { state.value = state.value.copy(snapshot = state.value.snapshot!!.copy(
            readOnly = true, writerOwner = "external", control = JSONObject().put("state", "external").put("canOperate", false).put("action", "takeover"))) }
        compose.onNodeWithText("保留草稿").assertIsDisplayed()
        for (tag in listOf("message_input", "add_attachment", "composer_settings", "codex_task_mode", "send_message"))
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(snapshot = state.value.snapshot!!.copy(
            readOnly = false, control = JSONObject().put("state", "writable").put("canOperate", true))) }
        compose.onNodeWithTag("message_input").assertIsEnabled()
        compose.onNodeWithText("保留草稿").assertIsDisplayed()
    }

    @Test fun releasedSessionAllowsDraftAndSendWithoutOwnershipButtons() {
        val state = SessionState(id = "released", snapshot = sessionSnapshot(JSONObject()
            .put("thread", JSONObject().put("id", "released")).put("readOnly", true).put("writerOwner", "none")
            .put("control", JSONObject().put("state", "released").put("canOperate", false).put("action", "resume"))))
        var sends = 0
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) {
            ChatScreen(state, false, false, emptyList(), {},
                onSend = { _, _, _, _ -> sends++ }, onStop = {}, onRespond = { _, _ -> },
                onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() })
        } } }
        compose.runOnIdle { org.junit.Assert.assertEquals(0, sends) }
        compose.onNodeWithTag("ownership_takeover").assertDoesNotExist()
        compose.onNodeWithTag("writer_action").assertDoesNotExist()
        compose.onNodeWithTag("message_input").performTextInput("发送时获取权限")
        compose.onNodeWithTag("send_message").assertIsEnabled().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, sends) }
    }

    @Test fun externallyOwnedHistoryLocksNewInputAndCannotStop() {
        val snapshot = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "external").put("name", "已有会话")
            .put("turns", JSONArray().put(JSONObject().put("id", "turn").put("items", JSONArray().put(
                JSONObject().put("id", "answer").put("type", "agentMessage").put("text", "已有历史仍可阅读"))))))
            .put("readOnly", true).put("activeTurnId", "turn"))
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) {
            ChatScreen(SessionState(id = snapshot.id, snapshot = snapshot), false, false, emptyList(), {},
                onSend = { _, _, _, _ -> error("Must not send") }, onStop = { error("Must not stop") },
                onRespond = { _, _ -> error("Must not respond") }, onRefresh = {}, onLoadModels = {}, onEarlier = {},
                readFile = { JSONObject() })
        } } }
        compose.onNodeWithTag("external_writer_notice", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("已有历史仍可阅读").assertIsDisplayed()
        compose.onNodeWithTag("message_input").assertIsNotEnabled()
        compose.onNodeWithTag("add_attachment").assertIsNotEnabled()
        compose.onNodeWithTag("composer_settings").assertIsNotEnabled()
        compose.onNodeWithTag("codex_task_mode").assertIsNotEnabled()
        compose.onNodeWithTag("stop_turn").assertIsNotEnabled()
        compose.onNodeWithTag("conversation_more_button").performClick()
        compose.onNodeWithTag("header_stop_turn").assertIsNotEnabled()
    }

}
