package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.data.ThreadSummary
import dev.rivo.relay.ui.projects.ProjectDirectoryDialog
import dev.rivo.relay.ui.projects.NewConversationDialog
import dev.rivo.relay.ui.projects.ProjectSummary
import dev.rivo.relay.ui.sessions.SessionsScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogManagementUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun multipleSessionsArchiveTogetherAndUnreadIsVisible() {
        val threads = listOf("one", "two").map { ThreadSummary(it, "会话 $it", "", "idle", "/project", "p") }
        var archived: List<String>? = null
        compose.setContent { RivoTheme { Surface { SessionsScreen("项目", threads, false, true, {}, {}, {}, {},
            onArchivedChanged = {}, onArchive = { selected, archive -> if (archive) archived = selected.map { it.id } }, unreadThreadIds = setOf("one")) } } }
        compose.onNodeWithTag("unread_thread_one", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("thread_one").performTouchInput { longClick() }
        compose.onNodeWithTag("thread_two").performClick()
        compose.onNodeWithText("已选择 2 项").assertIsDisplayed()
        compose.onNodeWithTag("archive_sessions").performClick()
        compose.runOnIdle { assertEquals(listOf("one", "two"), archived) }
    }

    @Test fun runningSessionCannotBeArchived() {
        val thread = ThreadSummary("running", "执行中", "", "running", "/project", "p")
        compose.setContent { RivoTheme { Surface { SessionsScreen("项目", listOf(thread), false, true, {}, {}, {}, {}, onArchive = { _, _ -> }) } } }
        compose.onNodeWithTag("thread_running").performTouchInput { longClick() }
        compose.onNodeWithTag("archive_sessions").assertIsNotEnabled()
    }

    @Test fun directoryBrowserUsesOpenedDirectoryAndConfirmsNewFolderSeparately() {
        var selected: String? = null
        var folder: String? = null
        compose.setContent { RivoTheme { ProjectDirectoryDialog(readDirectory = { requested ->
            JSONObject().put("path", requested.ifBlank { "/home" }).put("parentPath", "/home")
                .put("entries", if (requested.isBlank()) JSONArray().put(JSONObject().put("fileName", "work").put("isDirectory", true)) else JSONArray())
        }, writable = true, onDismiss = {}, onAdd = { path, name -> selected = path; folder = name }) } }
        compose.waitUntil { compose.onAllNodesWithTag("confirm_add_project").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project_directory_work").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("project_directory_path").assertTextEquals("/home/work")
        compose.onNodeWithTag("project_new_folder").performClick()
        compose.onNodeWithTag("project_folder_name").performTextInput("new-project")
        compose.runOnIdle { assertNull(selected) }
        compose.onNodeWithTag("confirm_create_project_folder").performClick()
        compose.runOnIdle { assertEquals("/home/work", selected); assertEquals("new-project", folder) }
    }

    @Test fun sessionsUseCompactRowsAndSearchClearsHiddenSelection() {
        val threads = listOf(ThreadSummary("one", "One", "Preview must stay hidden", "idle", "/project", "p"),
            ThreadSummary("two", "Two", "Other preview", "idle", "/project", "p"))
        compose.setContent { RivoTheme { Surface { SessionsScreen("项目", threads, false, true, {}, {}, {}, {}, onArchive = { _, _ -> }) } } }
        compose.onNodeWithText("Preview must stay hidden").assertDoesNotExist()
        compose.onNodeWithText("刷新会话").assertDoesNotExist()
        compose.onNodeWithText("会话").assertExists()
        compose.onNodeWithTag("thread_one").performTouchInput { longClick() }
        compose.onNodeWithTag("search_sessions").performTextInput("Two")
        compose.onNodeWithTag("sessions_selection").assertDoesNotExist()
        compose.onNodeWithTag("new_thread").assertIsDisplayed()
    }

    @Test fun archivedSessionTapDoesNotAcquireAWriter() {
        var opened = false
        val thread = ThreadSummary("archived", "Archived", "", "idle", "/project", "p")
        compose.setContent { RivoTheme { Surface { SessionsScreen("项目", listOf(thread), false, true, {}, { opened = true }, {}, {}, archived = true,
            onArchive = { _, _ -> }) } } }
        compose.onNodeWithTag("thread_archived").performClick()
        compose.runOnIdle { assertFalse(opened) }
        compose.onNodeWithTag("thread_archived").performTouchInput { longClick() }
        compose.onNodeWithTag("archive_sessions").assertIsEnabled()
    }

    @Test fun directoryReadErrorCanRetryAndUncertainWriteCanBeDismissed() {
        var attempts = 0
        var dismissed = false
        compose.setContent { RivoTheme { ProjectDirectoryDialog(readDirectory = {
            if (++attempts == 1) error("Directory unavailable")
            JSONObject().put("path", "/").put("parentPath", "/").put("entries", JSONArray())
        }, writable = false, onDismiss = { dismissed = true }, onAdd = { _, _ -> }, submissionError = "结果待核对") } }
        compose.onNodeWithText("Directory unavailable").assertIsDisplayed()
        compose.onNodeWithTag("confirm_add_project").assertIsNotEnabled()
        compose.onNodeWithText("重试").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("project_directory_path").assertTextEquals("/")
        compose.onNodeWithTag("project_create_error").assertTextEquals("结果待核对")
        compose.onNodeWithTag("project_directory_parent").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun newConversationShowsSubmissionErrorAndAllowsLeavingBlockedWrite() {
        var dismissed = false
        compose.setContent { RivoTheme { NewConversationDialog(ProjectSummary("p", "Project", "/project"),
            readGit = { JSONObject().put("git", JSONObject().put("available", false)) }, enabled = false,
            onCreate = {}, onDismiss = { dismissed = true }, submissionError = "需要核对上一次提交") } }
        compose.onNodeWithTag("new_conversation_error").assertTextEquals("需要核对上一次提交")
        compose.onNodeWithTag("confirm_new_conversation").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
