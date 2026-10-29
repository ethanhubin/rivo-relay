package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.NativeActivityGroup
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityFailureUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun color(tag: String): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.color
    }
    @Test fun failureDoesNotColorSuccessfulCommandsOrGroupTitle() {
        val items = JSONArray()
        for ((id, failed) in listOf("success" to false, "failure" to true)) items.put(JSONObject()
            .put("id", id).put("type", "commandExecution").put("command", if (failed) "cat missing.txt" else "cat existing.txt")
            .put("status", if (failed) "failed" else "completed").put("exitCode", if (failed) 1 else 0)
            .put("aggregatedOutput", if (failed) "No such file or directory" else "file content"))
        val state = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "thread").put("turns", JSONArray().put(JSONObject().put("id", "turn").put("items", items)))))
        val row = nativeTimelineRows(state.messages).single() as NativeTimelineRow.Activities
        var neutral = Color.Unspecified
        var failure = Color.Unspecified
        compose.setContent { RivoTheme {
            neutral = MaterialTheme.colorScheme.onSurfaceVariant
            failure = MaterialTheme.colorScheme.error
            Surface { NativeActivityGroup(row) {} }
        } }
        compose.onNodeWithText("1 项未完成").assertIsDisplayed()
        assertEquals(neutral, color("activity_group_summary"))
        compose.onNodeWithText("No such file or directory").assertDoesNotExist()
        compose.onNodeWithTag("native_activity_toggle").performClick()
        assertEquals(neutral, color("activity_summary_success"))
        assertEquals(failure, color("activity_summary_failure"))
        assertEquals(neutral, color("activity_group_summary"))
        compose.onNodeWithText("退出码 0").assertIsDisplayed()
        compose.onNodeWithText("执行未完成 · 退出码 1").assertIsDisplayed()
        compose.onNodeWithText("No such file or directory").assertIsDisplayed()
    }
}
