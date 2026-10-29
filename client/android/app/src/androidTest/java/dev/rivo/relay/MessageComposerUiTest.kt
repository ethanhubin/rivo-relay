package dev.rivo.relay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.ui.chat.MessageComposer
import dev.rivo.relay.ui.theme.RivoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local Compose fixtures only; never connects to a node or dispatches native work. */
@RunWith(AndroidJUnit4::class)
class MessageComposerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyComposerIsCompactAndInputGrowsToThreeLines() {
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            var draft by remember { mutableStateOf("") }
            RivoTheme {
                Box(Modifier.width(360.dp)) {
                    MessageComposer(draft, { draft = it }, emptyList(), {}, emptyList(), {},
                        preparing = false, error = null, enabled = true, canSend = draft.isNotBlank(),
                        isRunning = false, canStop = false, busy = false,
                        onAddImage = {}, onAddWorkspaceFile = {}, onSettings = {}, onSend = {}, onStop = {})
                }
            }
        }
        val emptyHeight = compose.onNodeWithTag("composer_surface").fetchSemanticsNode().boundsInRoot.height / density
        assertTrue("Empty composer should use one input row and one toolbar", emptyHeight <= 116f)
        compose.onNodeWithTag("add_image").assertDoesNotExist()
        compose.onNodeWithTag("add_workspace_file").assertDoesNotExist()
        compose.onNodeWithTag("message_input").performTextInput("第一行\n第二行\n第三行")
        val threeLineHeight = compose.onNodeWithTag("composer_surface").fetchSemanticsNode().boundsInRoot.height / density
        assertTrue("Multiline input must grow from the single-line height", threeLineHeight > emptyHeight)
        compose.onNodeWithTag("message_input").performTextReplacement("第一行\n第二行\n第三行\n第四行\n第五行")
        val fiveLineHeight = compose.onNodeWithTag("composer_surface").fetchSemanticsNode().boundsInRoot.height / density
        assertEquals("Long drafts should scroll within three visible lines", threeLineHeight, fiveLineHeight, 1f)
        compose.onNodeWithTag("send_message").assertIsEnabled()
    }

    @Test fun attachmentMenuAndRunningActionsRemainAvailableWithoutExtraRows() {
        val calls = mutableListOf<String>()
        var enabled by mutableStateOf(true)
        compose.setContent {
            RivoTheme {
                Box(Modifier.width(320.dp)) {
                    MessageComposer("补充内容", {}, emptyList(), {}, emptyList(), {},
                        preparing = false, error = null, enabled = enabled, canSend = enabled,
                        isRunning = true, canStop = enabled, busy = false,
                        onAddFile = { calls += "file" }, onAddImage = { calls += "image" }, onAddWorkspaceFile = { calls += "workspace" },
                        onSettings = { calls += "settings" }, onSend = { calls += "send" }, onStop = { calls += "stop" })
                }
            }
        }
        compose.onNodeWithTag("add_attachment").performClick()
        compose.onNodeWithTag("add_image").assertIsDisplayed().performClick()
        compose.onNodeWithTag("add_workspace_file").assertDoesNotExist()
        compose.onNodeWithTag("add_attachment").performClick()
        compose.onNodeWithText("引用").assertDoesNotExist()
        compose.onNodeWithTag("add_file").assertIsDisplayed().performClick()
        compose.onNodeWithTag("add_attachment").performClick()
        compose.onNodeWithTag("add_workspace_file").assertIsDisplayed().performClick()
        compose.onNodeWithTag("composer_settings").assertIsDisplayed().performClick()
        compose.onNodeWithTag("stop_turn").assertIsDisplayed().performClick()
        compose.onNodeWithTag("send_message").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf("image", "file", "workspace", "settings", "stop", "send"), calls)
            enabled = false
        }
        compose.onNodeWithTag("message_input").assertIsNotEnabled()
        compose.onNodeWithTag("add_attachment").assertIsNotEnabled()
        compose.onNodeWithTag("send_message").assertIsNotEnabled()
        compose.onNodeWithTag("stop_turn").assertIsNotEnabled()
    }
}
