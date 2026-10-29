package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.data.NativeRequest
import dev.rivo.relay.data.QueueTask
import dev.rivo.relay.ui.chat.ExecutionQueuePanel
import dev.rivo.relay.ui.chat.NativePlan
import dev.rivo.relay.ui.chat.NativeRequestPanel
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePanelsParityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun questionPagingAndCollapsingKeepAnswersBoundToTheirNativeIds() {
        val params = JSONObject("""{"questions":[{"id":"choice","header":"方案","question":"选择方案","options":[{"label":"继续","description":"执行下一步"}]},{"id":"details","header":"补充","question":"补充要求","isOther":true}]}""")
        var result: JSONObject? = null
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) { Column {
            NativeRequestPanel(NativeRequest("native:42", "item/tool/requestUserInput", params), true) { result = it }
        } } } }
        compose.onNodeWithTag("question_progress").assertTextEquals("1/2")
        compose.onNodeWithTag("question_option_choice_0").performClick()
        compose.onNodeWithTag("question_progress").assertTextEquals("2/2")
        compose.onNodeWithTag("question_answer_details").performTextInput("保留原文件")
        compose.onNodeWithTag("toggle_native_request").performClick()
        compose.onNodeWithTag("native_question_card").assertDoesNotExist()
        compose.onNodeWithTag("toggle_native_request").performClick()
        compose.onNodeWithTag("question_answer_details").assertTextContains("保留原文件", substring = true)
        compose.onNodeWithTag("previous_question").performClick()
        compose.onNodeWithTag("question_option_choice_0").assertIsSelected()
        compose.runOnIdle { assertNull(result) }
        compose.onNodeWithTag("answer_questions").performClick()
        compose.runOnIdle {
            val answers = result!!.getJSONObject("answers")
            assertEquals("继续", answers.getJSONObject("choice").getJSONArray("answers").getString(0))
            assertEquals("保留原文件", answers.getJSONObject("details").getJSONArray("answers").getString(0))
        }
    }

    @Test fun planUsesAnchoredStatusRowsAndPreservesFailureAndInterruption() {
        val plan = JSONObject("""{"turnId":"turn","status":"inProgress","plan":[{"step":"已读取项目","status":"completed"},{"step":"执行验证","status":"failed"},{"step":"整理结果","status":"interrupted"}]}""")
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) { Column(verticalArrangement = Arrangement.Bottom) { NativePlan(plan) } } } }
        compose.onNodeWithTag("codex_plan_progress", useUnmergedTree = true).assertTextEquals("执行计划 1/3 已完成")
        compose.onNodeWithTag("codex_plan_header").performClick()
        compose.onNodeWithTag("codex_plan_popup").assertIsDisplayed()
        compose.onNodeWithTag("codex_plan_item_completed").assertExists()
        compose.onNodeWithTag("codex_plan_item_failed").assertExists()
        compose.onNodeWithTag("codex_plan_item_interrupted").assertExists()
        compose.onNodeWithText("失败").assertIsDisplayed()
        compose.onNodeWithText("已中断").assertIsDisplayed()
    }

    @Test fun discussionAndReviewQueueItemsCannotBeSteeredIntoAnExecutingTask() {
        fun task(id: String, raw: JSONObject) = QueueTask(id, "待办 $id", "waiting", 3, "", raw)
        val tasks = listOf(task("plan", JSONObject().put("mode", "plan")),
            task("review", JSONObject().put("review", JSONObject().put("type", "uncommittedChanges"))), task("normal", JSONObject()))
        var steered: String? = null
        compose.setContent { RivoTheme { Surface { ExecutionQueuePanel("thread", tasks, true, true,
            onEdit = {}, onWithdraw = {}, onSteer = { steered = it.id }, onReorder = { _, _ -> }) } } }
        compose.onNodeWithTag("queue_steer_plan").assertIsNotEnabled()
        compose.onNodeWithTag("queue_steer_review").assertIsNotEnabled()
        compose.onNodeWithTag("queue_steer_normal").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("normal", steered) }
    }
}
