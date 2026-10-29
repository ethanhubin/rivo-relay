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

    @Test fun attachmentCardOpensWithoutRemovingAndHasSeparateRemoval() {
        val file = org.json.JSONObject().put("type", "mention").put("name", "report.pdf").put("path", "/workspace/report.pdf")
        var opens = 0
        var removes = 0
        compose.setContent { RivoTheme {
            MessageComposer("", {}, emptyList(), {}, listOf(file), { removes++ },
                preparing = false, error = null, enabled = true, canSend = true, isRunning = false, canStop = false, busy = false,
                onAddImage = {}, onAddWorkspaceFile = {}, onSettings = {}, onSend = {}, onStop = {}, onOpenFile = { opens++ })
        } }
        compose.onNodeWithTag("draft_file_report.pdf").performClick()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(0, removes) }
        compose.onNodeWithContentDescription("移除文件：report.pdf").performClick()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(1, removes) }
    }

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
        var draft by mutableStateOf("补充内容")
        var discussion by mutableStateOf(false)
        compose.setContent {
            RivoTheme {
                Box(Modifier.width(320.dp)) {
                    MessageComposer(draft, { draft = it }, emptyList(), {}, emptyList(), {},
                        preparing = false, error = null, enabled = enabled, canSend = enabled,
                        isRunning = true, canStop = enabled, busy = false,
                        onAddFile = { calls += "file" }, onAddImage = { calls += "image" }, onAddWorkspaceFile = { calls += "workspace" },
                        onAddVideo = { calls += "video" },
                        onSettings = { calls += "settings" }, onSend = { calls += "send" }, onStop = { calls += "stop" },
                        discussionMode = discussion, onToggleMode = { discussion = !discussion })
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
        compose.onNodeWithTag("add_video").assertIsDisplayed().performClick()
        compose.onNodeWithTag("add_attachment").performClick()
        compose.onNodeWithTag("attachment_tab_node").performClick()
        compose.onNodeWithTag("add_workspace_file").assertIsDisplayed().performClick()
        compose.onNodeWithTag("composer_settings").assertIsDisplayed().performClick()
        compose.onNodeWithTag("codex_task_mode").assertIsDisplayed().performClick()
        compose.onNodeWithText("讨论模式").assertIsDisplayed()
        compose.onNodeWithTag("stop_turn").assertDoesNotExist()
        compose.onNodeWithTag("send_message").assertIsDisplayed().performClick()
        compose.runOnIdle { draft = "" }
        compose.onNodeWithTag("send_message").assertDoesNotExist()
        compose.onNodeWithTag("stop_turn").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf("image", "file", "video", "workspace", "settings", "send", "stop"), calls)
            enabled = false
        }
        compose.onNodeWithTag("message_input").assertIsNotEnabled()
        compose.onNodeWithTag("add_attachment").assertIsNotEnabled()
        compose.onNodeWithTag("codex_task_mode").assertIsNotEnabled()
        compose.onNodeWithTag("stop_turn").assertIsNotEnabled()
    }
}
