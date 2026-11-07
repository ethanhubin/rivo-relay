package dev.rivo.relay

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.ui.chat.DeveloperToolsDialog
import dev.rivo.relay.ui.chat.ReviewDiagnostic
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewDiagnosticsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changedWorkspaceShowsWarningWithoutAttributingConcurrentChanges() {
        compose.setContent { MaterialTheme { ReviewDiagnostic(JSONObject().put("turnId", "review-turn")
            .put("status", "changed").put("readOnlyViolation", true)) } }
        compose.onNodeWithTag("review_workspace_warning").assertIsDisplayed()
        compose.onNodeWithText("审查期间工作区发生变化").assertIsDisplayed()
        compose.onNodeWithText("请核对改动来源。现有改动已保留，未自动还原。").assertIsDisplayed()
    }

    @Test fun unavailableVerificationDoesNotClaimAReadonlyViolation() {
        compose.setContent { MaterialTheme { ReviewDiagnostic(JSONObject().put("turnId", "review-turn").put("status", "unavailable")) } }
        compose.onNodeWithTag("review_workspace_unverified").assertIsDisplayed()
        compose.onNodeWithTag("review_workspace_warning").assertDoesNotExist()
    }

    @Test fun externalMissingWorktreeShowsOriginWithoutCleanupControl() {
        compose.setContent { MaterialTheme { DeveloperToolsDialog("external-thread", read = { path ->
            if (path.endsWith("/workspace")) JSONObject().put("workspace", JSONObject.NULL)
                .put("externalWorkspace", JSONObject().put("path", "/external/tree").put("projectRoot", "/project")
                    .put("commit", "01234567").put("status", "missing").put("managed", false))
            else JSONObject().put("diff", JSONObject().put("patch", ""))
        }, submit = { _, _, _ -> error("External worktrees must never be cleaned up here") }, busy = false, onDismiss = {}) } }
        compose.onNodeWithTag("developer_tab_2").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("/external/tree").assertIsDisplayed()
        compose.onNodeWithText("原工作树目录已不存在；会话仍归属此项目。").assertIsDisplayed()
        compose.onNodeWithTag("cleanup_workspace").assertDoesNotExist()
    }
}
