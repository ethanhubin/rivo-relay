package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PermissionSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pendingPermissionsAreSubmittedAndSavedWithoutAModel() {
        var defaults: JSONObject? = null
        var submitted: JSONObject? = null
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            ChatScreen(SessionState(id = "pending:permissions"), false, false, emptyList(), {},
                onSend = { _, _, _, _ -> }, onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {},
                onEarlier = {}, readFile = { JSONObject() }, pendingTitle = "测试项目",
                onConfiguredSend = { _, _, _, _, _, permissions, _ -> submitted = permissions },
                onSaveDefaults = { defaults = it })
        } } }
        compose.onNodeWithTag("new_session_settings").assertExists()
        compose.onNodeWithTag("new_session_sandbox").performScrollTo().performClick()
        compose.onNodeWithTag("new_session_sandbox_workspace-write").performClick()
        compose.onNodeWithTag("new_session_approval").performScrollTo().performClick()
        compose.onNodeWithTag("new_session_approval_on-request").performClick()
        compose.onNodeWithTag("conversation_status").performClick()
        compose.onNodeWithTag("codex_set_default").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("workspace-write", defaults!!.string("sandbox"))
            assertEquals("on-request", defaults!!.string("approvalPolicy"))
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("message_input").performTextInput("检查项目")
        compose.onNodeWithTag("send_message").performClick()
        compose.runOnIdle {
            assertEquals("workspace-write", submitted!!.string("sandbox"))
            assertEquals("on-request", submitted!!.string("approvalPolicy"))
        }
    }
}
