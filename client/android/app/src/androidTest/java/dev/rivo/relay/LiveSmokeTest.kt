package dev.rivo.relay

import android.Manifest
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue
import android.os.Build

/** Real-node tests. No mock, predecoded scan intent, or embedded credential. Run methods separately in the documented order. */
@RunWith(AndroidJUnit4::class)
class LiveSmokeTest {
    @get:Rule(order = 0) val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val projectName get() = InstrumentationRegistry.getArguments().getString("projectName") ?: "Rivo Relay Validation"

    private fun waitForTag(tag: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }
    private fun waitForEnabled(tag: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { runCatching { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }
    private fun waitForText(text: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { runCatching { compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }
    private fun waitForNoTag(tag: String) {
        compose.waitUntil(30_000) { runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }.getOrDefault(false) }
    }
    private fun scrollTodoTo(text: String) {
        compose.waitUntil(30_000) { runCatching {
            val rowText = hasText(text, substring = false) and !hasSetTextAction()
            compose.onNodeWithTag("todo_list").performScrollToNode(rowText)
            compose.onNode(rowText).assertIsDisplayed()
            true
        }.getOrDefault(false) }
    }
    private fun waitForProject() {
        waitForTag("projects_page")
        compose.waitUntil(60_000) { runCatching {
            compose.onNodeWithTag("projects_page").performScrollToNode(hasText(projectName))
            compose.onNodeWithText(projectName).assertIsDisplayed()
            true
        }.getOrDefault(false) }
    }
    private fun saveEvidence(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Compose idleness does not include system Photo Picker or keyboard transitions.
        instrumentation.uiAutomation.waitForIdle(300, 5_000)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun newValidationConversation() {
        waitForProject()
        compose.onNodeWithText(projectName).performClick()
        waitForEnabled("new_thread")
        compose.onNodeWithTag("new_thread").performClick()
        waitForTag("chat_page", 60_000)
    }

    @Test fun nativePinRoundTrip() {
        waitForProject()
        compose.onNodeWithText(projectName).performClick()
        waitForText("置顶与队列验收 0.1.4")
        compose.onNodeWithText("置顶与队列验收 0.1.4").performTouchInput { longClick() }
        compose.onNodeWithTag("pin_session").performClick()
        waitForText("置顶")
        saveEvidence("native-pin-0.1.4")
        compose.onNodeWithText("置顶与队列验收 0.1.4").performTouchInput { longClick() }
        compose.onNodeWithText("取消置顶").assertIsDisplayed()
        compose.onNodeWithTag("pin_session").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("置顶", substring=false).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("置顶与队列验收 0.1.4").performClick()
        waitForText("QUEUE_SECOND")
        compose.onNodeWithTag("app_error").assertDoesNotExist()
        saveEvidence("native-queue-history-0.1.4")
    }

    @Test fun qrCameraPairing() {
        // The host must inject the freshly generated QR into the emulator camera before this test.
        compose.onNodeWithTag("scan_connect").assertIsDisplayed().performClick()
        // Only a genuine ZXing capture result can navigate to the authenticated project screen.
        waitForTag("projects_page", 300_000)
        compose.onNodeWithText("Codex 项目").assertIsDisplayed()
        waitForProject()
        saveEvidence("qr-paired-projects")
    }

    @Test fun nativeConversationRoundTrip() {
        waitForProject()
        compose.onNodeWithText(projectName).performClick()
        waitForEnabled("new_thread")
        compose.onNodeWithTag("new_thread").performClick()
        waitForTag("chat_page", 60_000)
        compose.onNodeWithTag("message_input").performTextInput("Reply with exactly RIVO_ANDROID_OK. Do not read or modify files or use tools.")
        waitForEnabled("send_message", 60_000)
        compose.onNodeWithTag("send_message").performClick()
        waitForText("RIVO_ANDROID_OK", 180_000)
        compose.onNodeWithText("RIVO_ANDROID_OK").assertIsDisplayed()
        waitForNoTag("stop_turn")
        compose.onNodeWithTag("app_error").assertDoesNotExist()
        compose.onNodeWithTag("load_earlier").assertDoesNotExist()
        saveEvidence("native-conversation")
        compose.onNodeWithTag("message_input").performClick().performTextInput("继续验证")
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithTag("send_message").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Codex 原生会话").assertIsDisplayed()
        saveEvidence("native-conversation-keyboard")
    }

    @Test fun todoCrudAndRealDispatch() {
        val title = "Rivo Android verification ${System.currentTimeMillis()}"
        val edited = "$title edited"
        waitForTag("nav_todos")
        compose.onNodeWithTag("nav_todos").performClick()
        waitForTag("new_todo")
        compose.onNodeWithTag("new_todo").performClick()
        waitForTag("todo_title")
        compose.onNodeWithTag("todo_title").performTextInput(title)
        compose.onNodeWithTag("todo_notes").performTextInput("Reply with exactly RIVO_TODO_OK. Do not read or modify files or use tools.")
        compose.onNodeWithTag("save_todo").performClick()
        waitForNoTag("save_todo")
        waitForText(title)
        compose.onNodeWithTag("todo_search").performTextReplacement(title)
        compose.onNodeWithTag("todo_search").performImeAction()
        scrollTodoTo(title)
        compose.onNode(hasText(title, substring = false) and !hasSetTextAction()).assertHasClickAction().performClick()
        waitForTag("todo_title")
        compose.onNodeWithTag("todo_title").performTextReplacement(edited)
        compose.onNodeWithTag("save_todo").performClick()
        waitForNoTag("save_todo")
        waitForText(edited)
        compose.onNodeWithTag("todo_search").performTextInput(edited)
        compose.onNodeWithTag("todo_search").performImeAction()
        scrollTodoTo("交给 Codex")
        compose.onNodeWithText("交给 Codex").performClick()
        compose.onNodeWithTag("dispatch_project").performClick()
        val projectChoice = hasText(projectName, substring = false) and hasAnyAncestor(hasTestTag("dispatch_project_menu"))
        compose.waitUntil(30_000) { compose.onAllNodes(projectChoice).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(projectChoice).performScrollTo().performClick()
        waitForEnabled("confirm_dispatch")
        compose.onNodeWithTag("confirm_dispatch").performClick()
        waitForTag("chat_page", 60_000)
        waitForText("RIVO_TODO_OK", 180_000)
        saveEvidence("todo-native-dispatch")
        compose.onNodeWithText("‹ 返回").performClick()
        waitForTag("todo_search")
        compose.onNodeWithTag("todo_search").performTextReplacement(edited)
        compose.onNodeWithTag("todo_search").performImeAction()
        scrollTodoTo(edited)
        waitForText(edited)
        compose.onNode(isToggleable() and isOff()).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已完成", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("已完成", substring = true).performClick()
        scrollTodoTo(edited)
        compose.onNode(hasText(edited, substring = false) and !hasSetTextAction()).assertHasClickAction().performClick()
        waitForTag("todo_title")
        compose.onNodeWithText("删除待办").performClick()
        waitForText("删除这条待办？")
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(edited, substring = false) and !hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun systemPhotoPickerAndNativeImageRoundTrip() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val fixture = createRedPhoto()
        try {
            newValidationConversation()
            compose.onNodeWithTag("message_input").performTextInput("What is the dominant color of the attached image? Reply with exactly RED if it is red. Do not read or modify files or use tools.")
            compose.onNodeWithTag("add_attachment").performClick()
            waitForTag("add_image")
            compose.onNodeWithTag("add_image").performClick()
            try { compose.waitUntil(30_000) { clickVisibleRedPickerPhoto() } }
            catch (e: Throwable) { savePickerEvidence(); throw e }
            compose.waitUntil(30_000) {
                val attached = runCatching { compose.onAllNodesWithTag("draft_image").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
                if (!attached) confirmPickerSelectionIfPresent()
                attached
            }
            compose.onNodeWithTag("draft_image").assertIsDisplayed()
            waitForEnabled("send_message", 60_000)
            saveEvidence("system-photo-selected")
            compose.onNodeWithTag("send_message").performClick()
            waitForText("RED", 180_000)
            compose.onNodeWithText("RED").assertIsDisplayed()
            saveEvidence("native-image-reply")
        } finally { resolver.delete(fixture, null, null) }
    }

    @Test fun nativeFilePreview() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("previewPath") ?: "/Users/e2/Documents/Rivo-Relay-Validation-20261029/preview.txt"
        // This fixture text was confirmed through the native host's fs/readFile, not inferred from the filename.
        val expected = args.getString("previewExpected") ?: "RIVO_NATIVE_FILE_PREVIEW"
        newValidationConversation()
        compose.onNodeWithTag("add_attachment").performClick()
        waitForTag("add_workspace_file")
        compose.onNodeWithTag("add_workspace_file").performClick()
        waitForTag("workspace_files")
        // previewPath must be a direct child of the validation workspace.
        val fileLabel = "文件 · ${path.substringAfterLast('/')}"
        compose.onNodeWithTag("workspace_files").performScrollToNode(hasText(fileLabel))
        compose.onNodeWithText(fileLabel).performClick()
        compose.onNodeWithTag("message_input").performTextInput("Reply with exactly FILE_READY. Do not read or modify files or use tools.")
        waitForEnabled("send_message", 60_000)
        compose.onNodeWithTag("send_message").performClick()
        waitForText("FILE_READY", 180_000)
        val label = "文件：${path.substringAfterLast('/')}"
        compose.onNodeWithTag("chat_history").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
        waitForTag("file_preview")
        compose.waitUntil(30_000) { compose.onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(expected, substring = true).assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("file_preview"))).assertCountEquals(0)
        saveEvidence("native-file-preview")
    }

    @Test fun offlineHomeKeepsLogin() {
        // The host stops only its isolated Relay before launching this method.
        waitForTag("projects_page")
        waitForTag("app_error", 60_000)
        compose.onNodeWithTag("scan_connect").assertDoesNotExist()
        compose.onNodeWithText("Codex 项目").assertIsDisplayed()
        saveEvidence("offline-home")
    }

    @Test fun revokedConnectionReturnsToLogin() {
        // The host revokes this test connection before launching this method.
        waitForTag("scan_connect", 60_000)
        waitForText("节点授权已失效，请重新扫码连接。")
        compose.onNodeWithTag("projects_page").assertDoesNotExist()
        saveEvidence("revoked-login")
    }

    @Test fun existingTodoAssociationAfterRestart() {
        val title = requireNotNull(InstrumentationRegistry.getArguments().getString("todoTitle")) { "todoTitle is required" }
        waitForTag("nav_todos")
        compose.onNodeWithTag("nav_todos").performClick()
        waitForTag("todo_search")
        compose.onNodeWithTag("todo_search").performTextReplacement(title)
        compose.onNodeWithTag("todo_search").performImeAction()
        scrollTodoTo("查看会话")
        compose.onNodeWithText("交给 Codex").assertDoesNotExist()
        compose.onNodeWithText("查看会话").performClick()
        waitForTag("chat_page", 60_000)
        waitForText("RIVO_TODO_OK", 60_000)
        compose.onNodeWithText("RIVO_TODO_OK").assertIsDisplayed()
        saveEvidence("todo-association-restored")
    }

    @Test fun savedConnectionSwitchAndRetry() {
        val address = requireNotNull(InstrumentationRegistry.getArguments().getString("nodeAddress"))
        waitForTag("projects_page")
        compose.onNodeWithTag("node_settings").performClick()
        waitForText("切换节点")
        compose.onNodeWithText("切换节点").performClick()
        waitForTag("scan_connect")
        compose.onNodeWithTag("saved_node_$address").performScrollTo().assertIsDisplayed()
        saveEvidence("saved-nodes")
        compose.onNodeWithTag("saved_node_$address").performClick()
        waitForTag("projects_page")
        compose.onNodeWithText("Codex 项目").assertIsDisplayed()
        compose.onNodeWithTag("app_error").assertDoesNotExist()
        saveEvidence("saved-node-reconnected")
    }

    @Test fun manualPairingFromInstrumentationArguments() {
        // Arguments are provided transiently by the host. They must not be logged or saved in source/results.
        val args = InstrumentationRegistry.getArguments()
        val address = requireNotNull(args.getString("nodeAddress")) { "nodeAddress is required" }
        val code = requireNotNull(args.getString("pairingCode")) { "pairingCode is required" }
        waitForTag("manual_entry", 60_000)
        compose.onNodeWithTag("manual_entry").performClick()
        compose.onNodeWithTag("pairing_address").performTextInput(address)
        compose.onNodeWithTag("pairing_code").performTextInput(code)
        compose.onNodeWithTag("manual_connect").performClick()
        waitForTag("projects_page", 60_000)
        compose.onNodeWithText("Codex 项目").assertIsDisplayed()
        waitForProject()
        saveEvidence("manual-paired-projects")
    }
}
