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
        compose.onNodeWithText("▸ docs").performClick()
        compose.onNodeWithTag("workspace_parent").assertIsDisplayed()
        compose.onNodeWithText("文件 · report.pdf").performClick()
        compose.runOnIdle {
            assertEquals(listOf("/workspace", "/workspace/docs"), visited)
            assertEquals("/workspace/docs/report.pdf", selected?.getString("path"))
            assertEquals("mention", selected?.getString("type"))
        }
    }
}
