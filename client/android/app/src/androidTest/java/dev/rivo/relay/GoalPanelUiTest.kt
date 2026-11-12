package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
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
            QueueTask("two", "补充交互验证与截图", "waiting", 0, "", JSONObject()),
            QueueTask("three", "核对任务执行结果", "unknown", 0, "", JSONObject())
        ), goalSupported = true, goal = JSONObject()
            .put("status", "active").put("objective", "完成完整复刻，并做 Playmode 截图验收。")
            .put("tokensUsed", 12000).put("timeUsedSeconds", 262))
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            ChatScreen(SessionState(id = snapshot.id, snapshot = snapshot), false, false, emptyList(), {},
                onSend = { _, _, _, _ -> error("Screenshot fixture must not send") },
                onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {},
                onEarlier = {}, readFile = { JSONObject() })
        } } }
        compose.onNodeWithTag("native_goal").assertIsDisplayed()
        compose.onNodeWithTag("edit_goal").assertDoesNotExist()
        compose.onNodeWithText("编辑持续目标").assertDoesNotExist()
        compose.onNodeWithTag("execution_queue").assertIsDisplayed()
        evidence("queue-panel-aligned")
        compose.onNodeWithTag("codex_task_mode").assertIsEnabled().performClick()
        compose.onNodeWithText("持续目标").assertIsDisplayed()
        compose.onNodeWithText("保存并启用").assertIsDisplayed()
        compose.onNodeWithTag("composer_mode_menu").assertDoesNotExist()
        evidence("goal-edit-dialog")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("native_goal").assertIsDisplayed()
    }
}
