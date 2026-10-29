package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.ui.chat.DeveloperDiffContent
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeveloperDiffUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(diff: JSONObject) {
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) { DeveloperDiffContent(diff, Modifier.fillMaxSize()) } } }
    }

    @Test fun unifiedAndFileModesKeepBinaryPlaceholderSeparateFromEmptyDiff() {
        val patch = "diff --git a/main.kt b/main.kt\n--- a/main.kt\n+++ b/main.kt\n@@ -1 +1 @@\n-oldValue\n+newValue\n"
        show(JSONObject().put("patch", patch).put("files", JSONArray()
            .put(JSONObject().put("path", "main.kt").put("added", 1).put("removed", 1))
            .put(JSONObject().put("path", "image.png").put("binary", true))))
        compose.onNodeWithTag("codex_diff_unified").assertIsDisplayed()
        compose.onNodeWithText("+ newValue").assertIsDisplayed()
        compose.onNodeWithTag("codex_diff_files").performClick()
        compose.onNodeWithTag("codex_diff_file_image.png").performClick()
        compose.onNodeWithTag("codex_diff_placeholder").assertTextEquals("二进制文件只显示元数据，不加载内容")
        compose.onNodeWithTag("codex_diff_empty").assertDoesNotExist()
        compose.onNodeWithTag("codex_diff_file_main.kt").performClick()
        compose.onNodeWithText("+ newValue").assertIsDisplayed()
        compose.onNodeWithText("− oldValue").assertIsDisplayed()
    }

    @Test fun truncatedMissingFileDoesNotClaimThereWereNoChanges() {
        show(JSONObject().put("patch", "").put("truncated", true).put("files", JSONArray()
            .put(JSONObject().put("path", "later.kt").put("added", 20))))
        compose.onNodeWithTag("codex_diff_files").performClick()
        compose.onNodeWithTag("codex_diff_placeholder").assertTextEquals("此文件的文本内容未包含在节点返回的部分差异中。")
        compose.onNodeWithTag("codex_diff_empty").assertDoesNotExist()
    }

    @Test fun emptyWorkspaceShowsTheReferenceEmptyState() {
        show(JSONObject().put("patch", "").put("files", JSONArray()))
        compose.onNodeWithTag("codex_diff_empty").assertTextEquals("当前工作空间没有未提交改动")
    }
}
