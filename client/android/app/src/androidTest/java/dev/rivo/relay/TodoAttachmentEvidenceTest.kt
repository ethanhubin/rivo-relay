package dev.rivo.relay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.data.Todo
import dev.rivo.relay.ui.todos.TodoDetailsEditor
import dev.rivo.relay.ui.projects.ProjectSummary
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TodoAttachmentEvidenceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        val folder = File(compose.activity.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun tapThroughWindowManager(tag: String) {
        val center = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.center
        val origin = IntArray(2)
        compose.runOnIdle { compose.activity.window.decorView.getLocationOnScreen(origin) }
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, center.x + origin[0], center.y + origin[1], 0)
        val up = android.view.MotionEvent.obtain(now, now + 20, android.view.MotionEvent.ACTION_UP, center.x + origin[0], center.y + origin[1], 0)
        try {
            down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            assertTrue(automation.injectInputEvent(down, true))
            assertTrue(automation.injectInputEvent(up, true))
        } finally { down.recycle(); up.recycle() }
        compose.waitForIdle()
    }

    @Test fun localFileOpenUsesDisplayNameMimeFallbackAndReadGrant() {
        val context = compose.activity
        val file = File(File(context.filesDir, "todo-attachments").apply { mkdirs() }, "mime-fixture.bin").apply { writeText("# Test") }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val attachment = JSONObject().put("name", "说明.md").put("path", uri.toString()).put("type", "local")
        val intent = dev.rivo.relay.ui.todos.todoAttachmentOpenIntent(context, attachment)
        assertEquals("text/markdown", intent.type)
        assertEquals(uri, intent.data)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun unsupportedFileNameTipTogglesAndDismissesOutside() {
        var opened = false
        compose.setContent { RivoTheme {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                dev.rivo.relay.ui.chat.ComposerAttachmentRow("归档资料.zip", true, true,
                    { opened = true }, {}, androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.Center).testTag("unsupported_attachment"))
                androidx.compose.material3.TextButton({}, androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 100.dp).testTag("outside_tip")) {
                    androidx.compose.material3.Text("其他区域")
                }
            }
        } }
        compose.onNodeWithTag("unsupported_attachment").performClick()
        compose.onNodeWithTag("attachment_filename_tip").assertIsDisplayed()
        compose.onNodeWithText("归档资料.zip").assertIsDisplayed()
        screenshot("todo-attachment-filename-tip")
        tapThroughWindowManager("unsupported_attachment")
        compose.onNodeWithTag("attachment_filename_tip").assertDoesNotExist()
        compose.onNodeWithTag("unsupported_attachment").performClick()
        compose.onNodeWithTag("attachment_filename_tip").assertIsDisplayed()
        tapThroughWindowManager("outside_tip")
        compose.onNodeWithTag("attachment_filename_tip").assertDoesNotExist()
        compose.runOnIdle { assertFalse(opened) }
    }

    @Test fun menuImageEditPersistenceAndRemoval() {
        val context = compose.activity
        val source = File(File(context.filesDir, "todo-attachments").apply { mkdirs() }, "test-design.png")
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(235, 240, 247))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(65, 95, 150); drawRoundRect(70f, 70f, 730f, 530f, 32f, 32f, paint)
            paint.color = Color.WHITE; paint.textSize = 48f; drawText("RIVO / ATTACHMENTS", 120f, 170f, paint)
            paint.color = Color.rgb(170, 205, 240); drawRoundRect(120f, 230f, 680f, 460f, 20f, 20f, paint)
        }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", source)
        val details = JSONObject().put("attachments", JSONArray()
            .put(JSONObject().put("name", "设计参考.png").put("path", uri.toString()).put("type", "local"))
            .put(JSONObject().put("name", "需求说明.md").put("path", "/project/需求说明.md").put("type", "file")))
        val item = Todo("evidence", "整理附件交互方案", "", false, "", "", details)
        var saved: JSONObject? = null
        var opened: String? = null
        compose.setContent { RivoTheme { Surface {
            TodoDetailsEditor(item, false, listOf(ProjectSummary("p", "Rivo Relay", "/project")),
                { JSONObject().put("entries", JSONArray()) }, {}, { _, _, value -> saved = value }, {},
                onOpenAttachment = { opened = it })
        } } }
        compose.onNodeWithTag("todo_add_attachment").performScrollTo().performClick()
        compose.onNodeWithTag("attachment_dialog").assertIsDisplayed()
        listOf("add_camera", "add_image", "add_video", "add_file", "add_workspace_file").forEach {
            compose.onNodeWithTag(it).assertIsEnabled()
        }
        screenshot("todo-attachment-menu")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("attachment_dialog").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("todo_attachment_1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("/project/需求说明.md", opened) }
        val imageTile = compose.onNodeWithTag("todo_attachment_0").getUnclippedBoundsInRoot()
        val fileTile = compose.onNodeWithTag("todo_attachment_1").getUnclippedBoundsInRoot()
        assertEquals(imageTile.top, fileTile.top)
        assertTrue(fileTile.left >= imageTile.right)
        assertEquals(imageTile.right - imageTile.left, imageTile.bottom - imageTile.top)
        compose.onNodeWithText("需求说明.md").assertDoesNotExist()
        compose.onNodeWithText("移除").assertDoesNotExist()
        screenshot("todo-attachment-cards")
        compose.onNodeWithTag("todo_attachment_0").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("attachment_editor_toolbar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("attachment_editor_tool_crop").performClick()
        screenshot("todo-attachment-editor")
        compose.onNodeWithTag("attachment_editor_done").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("attachment_editor_toolbar").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("save_todo").performScrollTo().performClick()
        compose.waitUntil(10000) { saved != null }
        val edited = saved!!.getJSONArray("attachments").getJSONObject(0).getString("path")
        assertNotEquals(uri.toString(), edited)
        assertTrue(edited.contains("/todo-attachments/"))
        context.contentResolver.openInputStream(android.net.Uri.parse(edited))!!.use {
            val cropped = requireNotNull(android.graphics.BitmapFactory.decodeStream(it))
            assertEquals(800, cropped.width)
            assertEquals(300, cropped.height)
            cropped.recycle()
        }
        compose.onNodeWithTag("todo_attachment_remove_1").performScrollTo().performClick()
        compose.onNodeWithTag("todo_attachment_1").assertDoesNotExist()
        compose.onNodeWithTag("save_todo").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, saved!!.getJSONArray("attachments").length()) }
    }
}
