package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.data.Todo
import dev.rivo.relay.ui.todos.TodosScreen
import dev.rivo.relay.ui.todos.TodosState
import dev.rivo.relay.ui.projects.ProjectSummary
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodoInteractionParityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun editorContinuesChecklistWithImeAndSavesBothRows() {
        var savedTitle: String? = null
        var saved: JSONObject? = null
        compose.setContent { RivoTheme { Surface { TodosScreen(TodosState(), emptyList(), false, { emptyList() },
            { _, _, _, _ -> }, {}, { _, _ -> }, { _, _, _, _ -> }, {}, {},
            onSaveDetails = { _, title, _, details, done -> savedTitle = title; saved = details; done() }) } } }
        compose.onNodeWithTag("new_todo").performClick()
        compose.onNodeWithTag("todo_subtask_input_0").performTextInput("First item")
        compose.onNodeWithTag("todo_subtask_input_0").performImeAction()
        compose.onNodeWithTag("todo_subtask_input_1").performTextInput("Second item")
        compose.onNodeWithTag("save_todo").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("待办清单", savedTitle)
            assertEquals(2, saved?.getJSONArray("subtasks")?.length())
            assertEquals("Second item", saved?.getJSONArray("subtasks")?.getJSONObject(1)?.getString("title"))
        }
    }

    @Test fun todoSelectionRequiresConfirmationAndDeletesSerially() {
        val one = Todo("one", "One", "", false, "", "")
        val two = Todo("two", "Two", "", false, "", "")
        val deleted = mutableListOf<String>()
        compose.setContent { var items by remember { mutableStateOf(listOf(one, two)) }
            RivoTheme { Surface { TodosScreen(TodosState(items), emptyList(), false, { emptyList() }, { _, _, _, _ -> }, {},
                { item, done -> deleted += item.id; items = items.filterNot { it.id == item.id }; done() }, { _, _, _, _ -> }, {}, {}) } } }
        compose.onNodeWithTag("todo_content_one").performTouchInput { longClick() }
        compose.onNodeWithTag("todo_content_two").performClick()
        compose.onNodeWithTag("todo_selection_delete").performClick()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        compose.onNodeWithTag("todo_confirm_delete").performClick()
        compose.runOnIdle { assertEquals(listOf("one", "two"), deleted) }
    }

    @Test fun searchIncludesChecklistAndAttachmentNamesAndCollapseWorks() {
        val details = JSONObject().put("subtasks", JSONArray().put(JSONObject().put("id", "sub").put("title", "Needle task").put("completed", false)))
            .put("attachments", JSONArray().put(JSONObject().put("name", "design.pdf").put("path", "/p/design.pdf")))
        val item = Todo("checklist_fixture", "Checklist", "", false, "", "", details)
        compose.setContent { RivoTheme { Surface { TodosScreen(TodosState(listOf(item)), emptyList(), false, { emptyList() },
            { _, _, _, _ -> }, {}, { _, _ -> }, { _, _, _, _ -> }, {}, {}) } } }
        compose.onNodeWithTag("todo_expand_checklist_fixture").performClick()
        compose.onNodeWithTag("subtask_sub").assertDoesNotExist()
        compose.onNodeWithTag("todo_search").performTextInput("design.pdf")
        compose.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("todo_content_checklist_fixture"))
        compose.onNodeWithTag("todo_content_checklist_fixture").assertIsDisplayed()
        compose.onNodeWithTag("todo_search").performTextReplacement("Needle")
        compose.onNodeWithTag("todo_content_checklist_fixture").assertIsDisplayed()
    }

    @Test fun startCodexWaitsForSavedTodoBeforeOpeningDestination() {
        val project = ProjectSummary("p", "Project", "/project")
        compose.setContent { var items by remember { mutableStateOf(emptyList<Todo>()) }
            RivoTheme { Surface { TodosScreen(TodosState(items, online = true), listOf(project), true, { emptyList() },
                { _, _, _, _ -> }, {}, { _, _ -> }, { _, _, _, _ -> }, {}, {},
                onSaveDetails = { _, title, notes, details, done -> items = listOf(Todo("saved", title, notes, false, "", "", details)); done() }) } } }
        compose.onNodeWithTag("new_todo").performClick()
        compose.onNodeWithTag("todo_subtask_input_0").performTextInput("Deliver this checklist")
        compose.onNodeWithTag("todo_start_codex").performScrollTo().performClick()
        compose.onNodeWithTag("dispatch_project").assertIsDisplayed()
        compose.onNodeWithTag("confirm_dispatch").assertIsEnabled()
    }
}
