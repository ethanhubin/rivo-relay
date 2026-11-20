package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Goal panel screenshot acceptance using local data, with no node or task execution. */
@RunWith(AndroidJUnit4::class)
class GoalPanelUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun snapshot() = sessionSnapshot(JSONObject("""{"thread":{"id":"fixture","name":"目标面板验收","turns":[{"id":"first","items":[{"id":"answer","type":"agentMessage","text":"已完成页面布局调整，接下来检查交互细节与截图效果。"}]}]},"settings":{"model":"model-a","reasoningEffort":"high","cwd":"/workspace","collaborationMode":{"mode":"default"}},"status":"idle"}"""))
    private fun evidence(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }

    @Test fun goalModeButtonDirectlyEditsExistingGoal() {
        val snapshot = snapshot().copy(queue = listOf(
            QueueTask("one", "检查页面布局与标准边距", "waiting", 0, "", JSONObject()),
            QueueTask("two", "补充交互验证与截图", "pending_unknown", 0, "", JSONObject()),
            QueueTask("three", "核对任务执行结果", "unknown", 0, "", JSONObject())
        ), goalSupported = true, goal = JSONObject()
            .put("status", "active").put("objective", "完成完整复刻，并做 Playmode 截图验收。")
            .put("tokensUsed", 12000).put("timeUsedSeconds", 262))
        val showQueue = mutableStateOf(true)
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            ChatScreen(SessionState(id = snapshot.id, snapshot = snapshot.copy(queue = if (showQueue.value) snapshot.queue else emptyList())), false, false, emptyList(), {},
                onSend = { _, _, _, _ -> error("Screenshot fixture must not send") },
                onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {},
                onEarlier = {}, readFile = { JSONObject() })
        } } }
        compose.onNodeWithTag("native_goal").assertIsDisplayed()
        compose.onNodeWithTag("edit_goal").assertDoesNotExist()
        compose.onNodeWithText("编辑持续目标").assertDoesNotExist()
        compose.onNodeWithTag("execution_queue").assertIsDisplayed()
        compose.onNodeWithText("结果待核对").assertDoesNotExist()
        compose.onNodeWithTag("queue_pending_two").assertIsDisplayed()
        compose.onNodeWithTag("queue_pending_three").assertIsDisplayed()
        compose.onNodeWithText("执行队列").assertDoesNotExist()
        compose.onNodeWithText("1 项等待").assertDoesNotExist()
        val rowHeights = listOf("queue_item_one", "queue_item_two", "queue_item_three")
            .map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.height }
        org.junit.Assert.assertTrue("Queue task rows should have equal heights: $rowHeights",
            rowHeights.max() - rowHeights.min() <= 1f)
        evidence("queue-panel-aligned")
        compose.runOnIdle { showQueue.value = false }
        compose.onNodeWithTag("execution_queue").assertDoesNotExist()
        compose.onNodeWithTag("native_goal").assertIsDisplayed()
        evidence("goal-panel-aligned")
        compose.onNodeWithTag("codex_task_mode").assertIsEnabled().performClick()
        compose.onNodeWithText("持续目标").assertIsDisplayed()
        compose.onNodeWithText("保存并启用").assertIsDisplayed()
        compose.onNodeWithTag("composer_mode_menu").assertDoesNotExist()
        evidence("goal-edit-dialog")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("native_goal").assertIsDisplayed()
    }
    @Test fun cancellingFirstGoalKeepsNormalModeAndSendingAvailable() {
        val snapshot = snapshot().copy(goalSupported = true)
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            ChatScreen(SessionState(id = snapshot.id, snapshot = snapshot), false, false, emptyList(), {},
                onSend = { _, _, _, _ -> error("Fixture must not send") },
                onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {},
                onEarlier = {}, readFile = { JSONObject() })
        } } }
        compose.onNodeWithTag("codex_task_mode").performClick()
        compose.onNodeWithTag("composer_mode_goal").performClick()
        compose.onNodeWithText("持续目标").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("普通模式").assertIsDisplayed()
        compose.onNodeWithTag("message_input").performTextInput("继续普通任务")
        compose.onNodeWithTag("send_message").assertIsEnabled()
    }

}
