package dev.rivo.relay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.rivo.relay.ui.chat.WorkspaceFilePicker
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkspaceFilePickerUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun browseSubdirectoryAndSelectNativeFile() {
        val visited = mutableListOf<String>()
        var selected: JSONObject? = null
        compose.setContent { RivoTheme {
            WorkspaceFilePicker("/workspace", { path ->
                visited += path
                JSONObject().put("entries", JSONArray().put(JSONObject()
                    .put("fileName", if (path == "/workspace") "docs" else "report.pdf")
                    .put("isDirectory", path == "/workspace").put("isFile", path != "/workspace")))
            }, {}, { selected = it })
        } }
        compose.onNodeWithTag("attachment_entry_/workspace/docs").performClick()
        compose.onNodeWithTag("attachment_source_back").assertIsDisplayed()
        compose.onNodeWithTag("attachment_entry_/workspace/docs/report.pdf").performClick()
        compose.runOnIdle {
            assertEquals(listOf("/workspace", "/workspace/docs"), visited)
            assertEquals("/workspace/docs/report.pdf", selected?.getString("path"))
            assertEquals("mention", selected?.getString("type"))
        }
    }
    @Test fun browserBackNavigatesBeforeClosing() {
        var dismissed = false
        compose.setContent { RivoTheme {
            WorkspaceFilePicker("/workspace", { path -> JSONObject().put("entries", JSONArray().apply {
                if (path == "/workspace") put(JSONObject().put("fileName", "docs").put("isDirectory", true))
            }) }, { dismissed = true }, {})
        } }
        compose.onNodeWithTag("attachment_source_browser").assertIsDisplayed()
        compose.onNodeWithTag("attachment_entry_/workspace/docs").performClick()
        compose.onNodeWithText("这里还没有可附加的文件").assertIsDisplayed()
        compose.onNodeWithTag("attachment_source_back").performClick()
        compose.onNodeWithTag("attachment_entry_/workspace/docs").assertIsDisplayed()
        compose.runOnIdle { assertFalse(dismissed) }
        compose.onNodeWithTag("attachment_source_back").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun failedDirectoryCanRetryAndUnsafeEntriesAreNotSelectable() {
        var attempts = 0
        compose.setContent { RivoTheme {
            WorkspaceFilePicker("/workspace", {
                attempts++
                if (attempts == 1) error("目录读取暂时失败")
                JSONObject().put("entries", JSONArray()
                    .put(JSONObject().put("fileName", "../escape").put("isFile", true))
                    .put(JSONObject().put("fileName", "report.txt").put("isFile", true).put("size", 512)))
            }, {}, {})
        } }
        compose.onNodeWithText("目录读取暂时失败").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithTag("attachment_entry_/workspace/report.txt").assertIsDisplayed()
        compose.onNodeWithText("text/plain · 512 B").assertIsDisplayed()
        compose.onNodeWithText("../escape").assertDoesNotExist()
    }
}
