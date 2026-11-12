package dev.rivo.relay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusManager
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

    @Test fun doubleTapPredictionFillsEditableDraftWithoutSending() {
        var draft by mutableStateOf("")
        var sends = 0
        var editable by mutableStateOf(true)
        val prediction = "继续补充回归测试"
        compose.setContent { RivoTheme {
            MessageComposer(draft, { draft = it }, emptyList(), {}, emptyList(), {},
                preparing = false, error = null, enabled = true, canSend = true,
                isRunning = false, canStop = false, busy = false,
                onAddImage = {}, onAddWorkspaceFile = {}, onSettings = {},
                onSend = { sends++ }, onStop = {}, prediction = prediction, textEditable = editable)
        } }
        val input = compose.onNodeWithTag("message_input")
        input.performTouchInput { click() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("", draft); assertEquals(0, sends) }
        input.performTouchInput { doubleClick() }
        input.assertTextEquals(prediction).assertIsFocused()
        input.performTextInput("，包括异常情况")
        compose.runOnIdle { assertEquals(prediction + "，包括异常情况", draft); assertEquals(0, sends) }
        input.performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(prediction + "，包括异常情况", draft); assertEquals(0, sends) }
        input.performTextClearance()
        input.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.runOnIdle { editable = false }
        input.performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals("", draft); assertEquals(0, sends) }
    }

    private fun captureComposer(name: String) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val folder = java.io.File(context.getExternalFilesDir(null), "composer-evidence").apply { mkdirs() }
        val bitmap = compose.onNodeWithTag("composer_surface").captureToImage().asAndroidBitmap()
        java.io.File(folder, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

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

    @Test fun emptyComposerIsCompactAndCollapsedInputGrowsToTwoLines() {
        var density = 1f
        lateinit var focusManager: FocusManager
        compose.setContent {
            focusManager = LocalFocusManager.current
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
        compose.onNodeWithTag("message_input").performClick()
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        val focusedHeight = compose.onNodeWithTag("composer_surface").fetchSemanticsNode().boundsInRoot.height / density
        assertEquals("Empty input should return to compact height after blur", emptyHeight, focusedHeight, 1f)
        compose.onNodeWithTag("message_input").performTextInput("第一行\n第二行")
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        val twoLineHeight = compose.onNodeWithTag("composer_surface").fetchSemanticsNode().boundsInRoot.height / density
        assertTrue("Two lines must not shrink the composer below its controls", twoLineHeight >= focusedHeight)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("message_input").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount)
        val inputHeight = compose.onNodeWithTag("message_input").fetchSemanticsNode().boundsInRoot.height
        captureComposer("two-lines-collapsed")
        assertTrue("Two complete text lines must fit inside the input: bottom=${layouts.single().getLineBottom(1)}, height=$inputHeight", layouts.single().getLineBottom(1) <= inputHeight + 1f)
        compose.onNodeWithTag("message_input").performTextReplacement("第一行\n第二行\n第三行\n第四行\n第五行")
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        val fiveLineHeight = compose.onNodeWithTag("composer_surface").fetchSemanticsNode().boundsInRoot.height / density
        assertEquals("Long drafts should scroll within two visible lines", twoLineHeight, fiveLineHeight, 1f)
        compose.onNodeWithTag("send_message").assertIsEnabled()
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        val blurredHeight = compose.onNodeWithTag("composer_surface").fetchSemanticsNode().boundsInRoot.height / density
        assertEquals("Blur must preserve two visible lines for a long draft", twoLineHeight, blurredHeight, 1f)
        compose.onNodeWithTag("message_input").assertTextEquals("第一行\n第二行\n第三行\n第四行\n第五行")
        captureComposer("long-draft-blurred")
        compose.onNodeWithTag("message_input").performClick().performTextClearance()
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        val clearedHeight = compose.onNodeWithTag("composer_surface").fetchSemanticsNode().boundsInRoot.height / density
        assertEquals("An empty unfocused composer should return to its compact height", emptyHeight, clearedHeight, 1f)
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
        compose.onNodeWithTag("add_workspace_file").assertIsDisplayed().performClick()
        compose.onNodeWithTag("composer_settings").assertDoesNotExist()
        compose.onNodeWithTag("codex_task_mode").assertIsDisplayed().performClick()
        compose.onNodeWithTag("composer_mode_plan").performClick()
        compose.onNodeWithText("计划模式").assertIsDisplayed()
        compose.onNodeWithTag("stop_turn").assertDoesNotExist()
        compose.onNodeWithTag("send_message").assertIsDisplayed().performClick()
        compose.runOnIdle { draft = "" }
        compose.onNodeWithTag("send_message").assertDoesNotExist()
        compose.onNodeWithTag("stop_turn").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf("image", "file", "video", "workspace", "send", "stop"), calls)
            enabled = false
        }
        compose.onNodeWithTag("message_input").assertIsNotEnabled()
        compose.onNodeWithTag("add_attachment").assertIsNotEnabled()
        compose.onNodeWithTag("codex_task_mode").assertIsNotEnabled()
        compose.onNodeWithTag("stop_turn").assertIsNotEnabled()
    }
}
