package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AsyncQuestionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun snapshot() = sessionSnapshot(JSONObject("""{
        "thread":{"id":"desktop","turns":[{"id":"turn","status":"inProgress","items":[
            {"id":"question","type":"agentMessage","text":"","delivery":"async","questions":[
                {"title":"选择检查范围","options":["界面","服务端"]},{"title":"补充说明"}]}]}]},
        "activeTurnId":"turn","readOnly":true,"writerOwner":"external","canAnswerQuestions":true,
        "control":{"state":"external","canOperate":false}
    }"""))

    @Test fun desktopOwnedConversationAcceptsOptionsAndFreeTextWithoutOrdinaryInput() {
        var sent: JSONArray? = null
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            ChatScreen(SessionState(id = "desktop", snapshot = snapshot()), false, false, emptyList(), {},
                onSend = { _, _, _, _ -> error("ordinary send must stay locked") }, onStop = {},
                onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() },
                onAnswerQuestions = { turn, input, accepted -> assertEquals("turn", turn); sent = input; accepted() })
        } } }
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithTag("toggle_async_questions").performClick()
        compose.onNodeWithTag("message_input").performTextInput("保留原草稿")
        compose.onNodeWithTag("send_message").assertIsNotEnabled()
        compose.onNodeWithTag("toggle_async_questions").performClick()
        compose.onNodeWithText("界面", useUnmergedTree = true).performClick()
        val second = "[\"request_user_input_async\",\"question\",1]"
        compose.onNodeWithTag("question_answer_$second").performTextInput("请检查多题回答")
        compose.onNodeWithTag("answer_questions").assertIsDisplayed().assertIsEnabled()
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "question-dialog.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithTag("answer_questions").performClick()
        compose.runOnIdle {
            val text = sent!!.getJSONObject(0).getString("text")
            assertTrue(text.contains("界面")); assertTrue(text.contains("请检查多题回答"))
        }
        compose.onNodeWithTag("async_questions_panel").assertDoesNotExist()
        compose.onNodeWithTag("message_input").assertTextContains("保留原草稿")
        compose.onNodeWithTag("send_message").assertIsNotEnabled()
    }

    @Test fun unavailableDesktopRouteAndStaleStateDisableSubmission() {
        val state = mutableStateOf(SessionState(id = "desktop", snapshot = snapshot().copy(canAnswerQuestions = false)))
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            ChatScreen(state.value, false, false, emptyList(), {}, onSend = { _, _, _, _ -> }, onStop = {},
                onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() },
                onAnswerQuestions = { _, _, _ -> error("unavailable route must not submit") })
        } } }
        compose.onNodeWithTag("answer_questions").assertIsNotEnabled()
        compose.onNodeWithText("当前节点暂不能连接问题所属会话，请刷新或在桌面回答。").assertExists()
        compose.runOnIdle { state.value = state.value.copy(snapshot = snapshot(), synchronization = dev.rivo.relay.codex.SessionSynchronization.pending()) }
        compose.onNodeWithTag("answer_questions").assertIsNotEnabled()
        val ids = pendingAsyncQuestions(snapshot()).map { it.id }
        compose.runOnIdle {
            state.value = SessionState(id = "desktop", snapshot = snapshot().copy(canAnswerQuestions = false,
                questionAnswerStates = JSONObject().apply { ids.forEach { put(it, "unknown") } }))
        }
        compose.onNodeWithText("回答结果待核对，请刷新；不会自动重发。").assertExists()
        compose.runOnIdle {
            state.value = state.value.copy(snapshot = state.value.snapshot!!.copy(
                questionAnswerStates = JSONObject().apply { ids.forEach { put(it, "accepted") } }))
        }
        compose.onNodeWithTag("async_questions_panel").assertDoesNotExist()
    }
    @Test fun offlineDraftSurvivesLeavingPanelAndNewStoreInstance() {
        val directory = java.io.File(compose.activity.cacheDir, "question-recovery-" + System.nanoTime())
        val store = ConversationStore(directory)
        val currentStore = mutableStateOf(store)
        val visible = mutableStateOf(true)
        val online = mutableStateOf(false)
        val id = "[\"request_user_input_async\",\"question\",1]"
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) {
            if (visible.value) dev.rivo.relay.ui.chat.AsyncQuestionPanel(
                pendingAsyncQuestions(snapshot()).takeLast(1), online.value, currentStore.value, "question:desktop:turn",
                onSubmit = { _, _ -> error("offline draft must not submit") })
        } } }
        compose.waitUntil { compose.onAllNodesWithTag("question_answer_$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("question_answer_$id").performTextInput("离线保留")
        compose.onNodeWithTag("answer_questions").assertIsNotEnabled()
        compose.runOnIdle { visible.value = false }
        kotlinx.coroutines.runBlocking {
            store.saveDraftOrdered("question:desktop:turn", store.loadDraft("question:desktop:turn"))
        }
        compose.runOnIdle {
            currentStore.value = ConversationStore(directory)
            visible.value = true
        }
        compose.waitUntil {
            compose.onAllNodesWithText("离线保留", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("question_answer_$id").assertTextContains("离线保留")
        compose.onNodeWithTag("answer_questions").assertIsNotEnabled()
    }

}
