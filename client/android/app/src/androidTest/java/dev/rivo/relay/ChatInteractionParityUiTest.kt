package dev.rivo.relay

import android.content.ClipboardManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** QiGuang interaction parity against local native-history fixtures, with no node or task execution. */
@RunWith(AndroidJUnit4::class)
class ChatInteractionParityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun snapshot() = sessionSnapshot(JSONObject("""{"thread":{"id":"fixture","name":"聊天交互验证","turns":[{"id":"first","items":[{"id":"answer","type":"agentMessage","text":"可引用的回答"}]}]},"settings":{"model":"model-a","reasoningEffort":"high","cwd":"/workspace","collaborationMode":{"mode":"default"}},"status":"idle"}"""))
    private fun evidence(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }

    @Test fun permanentModeAndAnchoredSettingsOnlyChangeTheNextTask() {
        val snapshot = snapshot()
        val models = listOf(
            JSONObject("""{"model":"model-a","displayName":"模型 A","supportedReasoningEfforts":[{"reasoningEffort":"low"},{"reasoningEffort":"high"}]}"""),
            JSONObject("""{"model":"model-b","displayName":"模型 B","supportedReasoningEfforts":[{"reasoningEffort":"medium"}]}"""))
        var submitted: List<String?>? = null
        var loads = 0
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) {
            ChatScreen(SessionState(id = snapshot.id, snapshot = snapshot), false, false, models, {},
                onSend = { _, _, _, _ -> error("Mode-aware callback must be used") }, onStop = {}, onRespond = { _, _ -> },
                onRefresh = {}, onLoadModels = { loads++ }, onEarlier = {}, readFile = { JSONObject() },
                onSendWithMode = { _, model, effort, mode, accepted -> submitted = listOf(model, effort, mode); accepted() })
        } } }
        compose.onNodeWithTag("conversation_configuration", useUnmergedTree = true).assertTextEquals("model-a · high")
        compose.onNodeWithTag("codex_task_mode").performClick()
        compose.onNodeWithText("讨论模式").assertIsDisplayed()
        compose.onNodeWithText("输入想讨论的方案或问题").assertIsDisplayed()
        compose.onNodeWithTag("composer_settings").performClick()
        compose.onNodeWithTag("composer_settings_panel").assertIsDisplayed()
        compose.onNodeWithTag("composer_model").performClick()
        compose.onNodeWithTag("composer_model_model-b").performClick()
        compose.onNodeWithTag("composer_effort").performClick()
        compose.onNodeWithTag("composer_effort_medium").performClick()
        evidence("chat-settings-popup")
        // Compose's node gesture injection targets its owner directly. Use WindowManager dispatch
        // so the separate non-focusable Popup window receives ACTION_OUTSIDE as a real tap would.
        val titleCenter = compose.onNodeWithTag("conversation_title").fetchSemanticsNode().boundsInWindow.center
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, titleCenter.x, titleCenter.y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 20, MotionEvent.ACTION_UP, titleCenter.x, titleCenter.y, 0)
        try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN; up.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(down, true))
            assertTrue(automation.injectInputEvent(up, true))
        } finally { down.recycle(); up.recycle() }
        compose.waitForIdle()
        compose.onNodeWithTag("composer_settings_panel").assertDoesNotExist()
        compose.onNodeWithTag("conversation_configuration", useUnmergedTree = true).assertTextEquals("model-a · high")
        compose.onNodeWithTag("message_input").performTextInput("先讨论方案")
        compose.onNodeWithTag("send_message").performClick()
        compose.runOnIdle { assertEquals(listOf("model-b", "medium", "plan"), submitted); assertEquals(1, loads) }
        compose.onNodeWithTag("message_input").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun quoteAppendsToDraftAndHeaderMenuPreservesNativeIdentity() {
        val snapshot = snapshot()
        var pinned by mutableStateOf(false)
        var archives = 0
        var refreshes = 0
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) {
            ChatScreen(SessionState(id = snapshot.id, snapshot = snapshot), false, false, emptyList(), {},
                onSend = { _, _, _, _ -> error("This fixture must not send") }, onStop = {}, onRespond = { _, _ -> },
                onRefresh = { refreshes++ }, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() },
                pinned = pinned, canManage = true, onPin = { pinned = it }, onArchive = { archives++ })
        } } }
        compose.onNodeWithTag("message_text").performTouchInput { longClick() }
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("引用").performClick()
        compose.onNodeWithTag("message_input").assertTextContains("> 可引用的回答", substring = true).assertIsFocused()
        compose.onNodeWithTag("conversation_more_button").performClick()
        compose.onNodeWithTag("set_session_pinned").performClick()
        compose.runOnIdle { assertTrue(pinned) }
        compose.onNodeWithTag("conversation_more_button").performClick()
        compose.onNodeWithTag("set_session_pinned").assertTextContains("取消置顶")
        compose.onNodeWithTag("copy_session_id").performClick()
        compose.runOnIdle { assertEquals("fixture", compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()) }
        compose.onNodeWithTag("conversation_more_button").performClick()
        compose.onNodeWithTag("refresh_conversation").performClick()
        compose.runOnIdle { assertEquals(1, refreshes); assertEquals(0, archives) }
        compose.onNodeWithTag("message_input").assertTextContains("> 可引用的回答", substring = true)
        evidence("chat-quoted-draft")
    }
}
