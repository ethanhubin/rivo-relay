package dev.rivo.relay

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.*
import dev.rivo.relay.ui.sessions.SessionsScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QueueAndPinUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun task(id: String) = QueueTask(id, "待执行任务 $id", "waiting", 0, "", JSONObject())
    private fun evidence(name: String) {
        compose.waitForIdle()
        val ins = InstrumentationRegistry.getInstrumentation()
        ins.uiAutomation.waitForIdle(300, 5000)
        val folder = File(ins.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @Test fun longPressPinsAndUnpinsWithPinnedFirst() {
        val normal = ThreadSummary("normal", "普通会话", "", "idle", "", "project")
        val pinned = normal.copy(id = "pin", name = "置顶会话", pinned = true)
        var action: Pair<String, Boolean>? = null
        compose.setContent { RivoTheme { Surface { SessionsScreen("测试项目", listOf(normal, pinned), false, true,
            {}, {}, {}, {}, onPin = { t, pin -> action = t.id to pin }) } } }
        assertTrue(compose.onNodeWithTag("thread_pin").fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithTag("thread_normal").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithTag("thread_normal").performTouchInput { longClick() }
        compose.onNodeWithTag("pin_session").performClick()
        compose.runOnIdle { assertEquals("normal" to true, action) }
        compose.onNodeWithTag("thread_pin").performTouchInput { longClick() }
        compose.onNodeWithText("取消置顶").assertIsDisplayed()
        compose.onNodeWithTag("pin_session").performClick()
        compose.runOnIdle { assertEquals("pin" to false, action) }
        evidence("sessions-pin-0.1.4")
    }
    @Test fun queueExpandsAndLongPressReordersWaitingTasks() {
        var reordered: List<String>? = null
        var expected: List<String>? = null
        compose.setContent { RivoTheme { Surface { Column { ExecutionQueuePanel("thread", (1..5).map { task("$it") }, true, true,
            {}, {}, {}, { ids, before -> reordered = ids; expected = before }) } } } }
        compose.onNodeWithTag("queue_item_4").assertDoesNotExist()
        compose.onNodeWithTag("queue_expand").performClick()
        compose.onNodeWithTag("queue_item_4").assertExists()
        compose.onNodeWithTag("queue_item_1").performTouchInput {
            down(Offset(12f, centerY)); advanceEventTime(700); moveBy(Offset(0f, 130f)); up()
        }
        compose.runOnIdle {
            assertEquals(listOf("2", "1", "3", "4", "5"), reordered)
            assertEquals(listOf("1", "2", "3", "4", "5"), expected)
        }
    }
    @Test fun queueEditWithdrawAndSteerUseNativeTurnAndRevisions() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val snapshot = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "queue-thread").put("name", "执行队列验证").put("turns", JSONArray()))
            .put("activeTurnId", "active-turn").put("status", "running")).copy(queue = (1..4).map { task("$it") })
        val operations = mutableListOf<JSONObject>()
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.systemBarsPadding()) {
            ChatScreen(SessionState(id=snapshot.id, snapshot=snapshot), false, false, emptyList(), {},
                onSend={ _,_,_,_-> }, onStop={}, onRespond={_,_->}, onRefresh={}, onLoadModels={}, onEarlier={},
                readFile={ JSONObject() }, onQueue={ body, accepted -> operations.add(body); accepted() })
        } } } }
        compose.onNodeWithTag("queue_steer_1").performClick()
        compose.runOnIdle { assertEquals("active-turn", operations.last().getString("expectedTurnId")) }
        compose.onNodeWithTag("queue_edit_2").performClick()
        compose.onNodeWithTag("queue_edit_text").performTextReplacement("修改后的任务")
        compose.onNodeWithTag("queue_save").performClick()
        compose.runOnIdle {
            assertEquals("edit", operations.last().getString("operation")); assertEquals(0, operations.last().getInt("revision"))
            assertEquals("修改后的任务", operations.last().getString("text"))
        }
        compose.onNodeWithTag("queue_edit_3").performClick()
        compose.onNodeWithText("撤回").performClick()
        compose.onNodeWithTag("queue_remove").performClick()
        compose.runOnIdle { assertEquals("remove", operations.last().getString("operation")); assertEquals("3", operations.last().getString("itemId")) }
        evidence("execution-queue-0.1.4")
        compose.onNodeWithTag("message_input").performClick().performTextInput("继续下一项")
        compose.waitUntil(10000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        compose.onNodeWithText("↑ 排队").assertIsDisplayed()
        compose.onNodeWithTag("send_message").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("执行队列验证").assertIsDisplayed()
        evidence("execution-queue-keyboard-0.1.4")
    }
}
