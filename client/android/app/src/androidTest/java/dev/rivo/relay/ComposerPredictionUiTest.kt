package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerPredictionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val snapshot = sessionSnapshot(JSONObject("""{"thread":{"id":"test","turns":[{"id":"turn","status":"completed","items":[]}]},"status":"idle","control":{"state":"writable","canOperate":true}}"""))
    private val state = mutableStateOf(SessionState(id = "test", snapshot = snapshot))
    private var sent: String? = null
    private fun show() {
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            ChatScreen(state.value, false, false, emptyList(), {},
                onSend = { input, _, _, accepted -> sent = input.getJSONObject(0).getString("text"); accepted() }, onStop = {},
                onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() },
                predictMessage = { _, turn -> JSONObject().put("sourceTurnId", turn).put("text", "继续补充测试") })
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("继续补充测试").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun emptySendUsesPrediction() {
        show()
        compose.onNodeWithTag("send_message").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("继续补充测试", sent) }
        compose.onNodeWithText("继续补充测试").assertDoesNotExist()
    }
    @Test fun userDraftWins() {
        show()
        compose.onNodeWithTag("message_input").performTextInput("我的指令")
        compose.onNodeWithText("继续补充测试").assertDoesNotExist()
        compose.onNodeWithTag("send_message").performClick()
        compose.runOnIdle { assertEquals("我的指令", sent) }
    }
    @Test fun startingTurnClearsPrediction() {
        show()
        compose.runOnIdle { state.value = state.value.copy(snapshot = snapshot.copy(activeTurnId = "new", status = "running")) }
        compose.onNodeWithText("继续补充测试").assertDoesNotExist()
        compose.onNodeWithTag("send_message").assertDoesNotExist()
        compose.onNodeWithTag("stop_turn").assertExists()
    }
}
