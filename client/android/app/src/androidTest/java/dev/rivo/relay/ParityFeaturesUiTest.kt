package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.codex.TaskNotifications
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.chat.DeveloperToolsDialog
import dev.rivo.relay.ui.chat.NativePlan
import dev.rivo.relay.ui.common.UsageIndicator
import dev.rivo.relay.ui.projects.ProjectsScreen
import dev.rivo.relay.ui.projects.ProjectSummary
import dev.rivo.relay.ui.sessions.SessionsScreen
import dev.rivo.relay.ui.theme.RivoTheme
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Local fixtures only: no node credentials, native task, microphone, or network dependency. */
@RunWith(AndroidJUnit4::class)
class ParityFeaturesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun evidence(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(directory, "$name-0.2.0.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }

    @Test fun modelChangeNoticeRequiresTwoAdjacentObservedTurnModels() {
        val response = JSONObject("""{"thread":{"id":"fixture","name":"模型记录","turns":[{"id":"first","items":[{"id":"one","type":"agentMessage","text":"第一轮结果"}]},{"id":"second","items":[{"id":"two","type":"agentMessage","text":"第二轮结果"}]}]},"settings":{"model":"unrelated-current-setting"},"turnSettings":[{"turnId":"first","model":"model-a"},{"turnId":"second","model":"model-b","effort":"high"}],"status":"idle"}""")
        var snapshot by mutableStateOf(sessionSnapshot(response))
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) {
            ChatScreen(SessionState(id = "fixture", snapshot = snapshot), false, false, emptyList(), {},
                onSend = { _, _, _, _ -> error("This fixture must not submit") }, onStop = {}, onRespond = { _, _ -> },
                onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() })
        } } }
        compose.onNodeWithTag("model_change_notice").assertTextEquals("已切换至 model-b · high").assertIsDisplayed()
        evidence("observed-model-change")
        compose.runOnIdle { snapshot = snapshot.copy(turnSettings = listOf(NativeTurnSettings("first"), NativeTurnSettings("second", "model-b", "high"))) }
        compose.onNodeWithTag("model_change_notice").assertDoesNotExist()
        compose.onNodeWithText("第二轮结果").assertExists()
    }

    @Test fun cachedCatalogControlsEnableOnlyAfterTheirFreshRead() {
        var showProjects by mutableStateOf(true)
        var fresh by mutableStateOf(false)
        var writes = 0
        val project = ProjectSummary("p", "缓存项目", "/workspace")
        val thread = ThreadSummary("t", "缓存会话", "本地缓存", "idle", "/workspace", "p")
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) {
            if (showProjects) ProjectsScreen(listOf(project), "测试节点", false, {}, {}, {}, { writes++ }, canAdd = fresh)
            else SessionsScreen("缓存项目", listOf(thread), false, fresh, {}, {}, { writes++ }, {},
                onBatchPin = { _, _ -> writes++ }, onArchive = { _, _ -> writes++ })
        } } }
        compose.onNodeWithTag("add_project").assertIsNotEnabled()
        compose.runOnIdle { fresh = true }
        compose.onNodeWithTag("add_project").assertIsEnabled()
        compose.runOnIdle { showProjects = false; fresh = false }
        compose.onNodeWithTag("new_thread").assertIsNotEnabled()
        compose.onNodeWithTag("thread_t").performTouchInput { longClick() }
        compose.onNodeWithTag("pin_session").assertIsNotEnabled()
        compose.onNodeWithTag("archive_sessions").assertIsNotEnabled()
        evidence("cached-catalog-controls")
        compose.runOnIdle { assertEquals(0, writes); fresh = true }
        compose.onNodeWithTag("new_thread").assertIsEnabled()
        compose.onNodeWithTag("pin_session").assertIsEnabled()
        compose.onNodeWithTag("archive_sessions").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, writes) }
    }

    @Test fun draftTextAndFileReferenceSurviveLeavingAndReopeningChat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "draft-ui-${UUID.randomUUID()}")
        var store = ConversationStore(directory)
        runBlocking { store.saveDraft("fixture", ConversationDraft("恢复的草稿", files = listOf(
            JSONObject().put("type", "mention").put("name", "notes.txt").put("path", "/workspace/notes.txt")))) }
        val snapshot = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "fixture").put("name", "草稿测试").put("turns", JSONArray()))
            .put("requests", JSONArray()).put("settings", JSONObject()).put("status", "idle"))
        var shown by mutableStateOf(true)
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) {
            if (shown) ChatScreen(SessionState(id = "fixture", snapshot = snapshot), false, false, emptyList(), { shown = false },
                onSend = { _, _, _, _ -> error("This fixture must not submit") }, onStop = {}, onRespond = { _, _ -> },
                onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() },
                draftStore = store, onPersistDraft = { id, draft -> store.enqueueDraft(id, draft) })
            else Button(onClick = { store = ConversationStore(directory); shown = true }) { Text("重新打开会话") }
        } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("恢复的草稿").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("message_input").performTextReplacement("离开后仍保留")
        compose.onNodeWithTag("back_sessions").performClick()
        compose.onNodeWithText("重新打开会话").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("离开后仍保留").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("message_input").assertTextContains("离开后仍保留")
        compose.onNodeWithText("notes.txt", substring = true).assertExists()
        evidence("restored-draft")
        runBlocking {
            val restored = ConversationStore(directory).loadDraft("fixture")
            assertEquals("离开后仍保留", restored.text)
            assertEquals("/workspace/notes.txt", restored.files.single().getString("path"))
        }
        // Remove through the same writer so pending keystrokes cannot recreate this test artifact.
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        store.enqueueDraft("fixture", ConversationDraft())
    }

    @Test fun archivedTabResetsSelectionAndBatchActionsKeepTheirTargetIds() {
        val threads = listOf("a", "b").map { ThreadSummary(it, "会话 $it", "本地测试", "idle", "/tmp", "project") }
        var archived by mutableStateOf(false)
        var action: Pair<Set<String>, Boolean>? = null
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) {
            SessionsScreen("测试项目", threads, false, true, {}, {}, {}, {}, archived = archived,
                onArchivedChanged = { archived = it }, onArchive = { selected, value -> action = selected.map { it.id }.toSet() to value },
                onBatchPin = { _, _ -> }, unreadThreadIds = setOf("a"))
        } } }
        compose.onNodeWithTag("unread_thread_a", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("thread_a").performTouchInput { longClick() }
        compose.onNodeWithTag("thread_b").performClick()
        compose.onNodeWithTag("archive_sessions").performClick()
        compose.runOnIdle { assertEquals(setOf("a", "b") to true, action) }
        compose.onNodeWithTag("sessions_archived").performClick()
        compose.onNodeWithTag("sessions_selection").assertDoesNotExist()
        compose.onNodeWithTag("thread_b").performTouchInput { longClick() }
        compose.onNodeWithTag("archive_sessions").assertTextContains("恢复").performClick()
        compose.runOnIdle { assertEquals(setOf("b") to false, action) }
        evidence("archived-sessions")
    }

    @Test fun quotaContextAndPlanDetailsShowTheirAuthoritativeValues() {
        val usage = CodexUsage(listOf(CodexUsageLimit("codex", "Codex", null, CodexUsageWindow(25, 10080L, null))))
        val plan = JSONObject("""{"turnId":"turn","status":"inProgress","plan":[{"step":"读取项目","status":"completed"},{"step":"补充验证","status":"inProgress"}]}""")
        compose.setContent { RivoTheme { Column {
            UsageIndicator(usage, contextPercent = 40)
            NativePlan(plan)
        } } }
        compose.onNodeWithText("周额度剩余 75%").assertIsDisplayed()
        compose.onNodeWithTag("context_usage").assertTextEquals("上下文 40%")
        compose.onNodeWithText("执行计划 1/2 已完成", substring = true).performClick()
        compose.onNodeWithText("读取项目").assertIsDisplayed()
        compose.onNodeWithText("补充验证").assertIsDisplayed()
        compose.onNodeWithTag("codex_plan_item_in_progress").assertExists()
        evidence("native-plan")
    }

    @Test fun backgroundTaskObserversShareOneNodeEventStream() {
        val server = MockWebServer()
        val streams = AtomicInteger()
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/events")) {
                    streams.incrementAndGet()
                    return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                reads.incrementAndGet()
                val id = request.path!!.substringAfterLast('/')
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("thread", JSONObject().put("id", id).put("turns", JSONArray())).put("status", "idle").toString())
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val url = server.url("/").newBuilder().host("127.0.0.1").build().toString()
            val notifications = TaskNotifications(context, RelayApi(url), scope, url, "本地测试", {}, { throw AssertionError(it) })
            compose.runOnIdle { notifications.watch("a"); notifications.watch("b"); notifications.watch("c") }
            compose.waitUntil(10_000) { streams.get() >= 1 && reads.get() >= 3 }
            compose.runOnIdle { assertEquals(1, streams.get()) }
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun developerDiffReadsOneEndpointAndNeverSubmitsByOpening() {
        val reads = mutableListOf<String>()
        var writes = 0
        val patch = "diff --git a/main.kt b/main.kt\n--- a/main.kt\n+++ b/main.kt\n@@ -1 +1 @@\n-oldValue\n+newValue\n"
        compose.setContent { RivoTheme {
            DeveloperToolsDialog("fixture", read = { path ->
                reads += path
                JSONObject().put("diff", JSONObject().put("patch", patch).put("files", JSONArray().put(
                    JSONObject().put("path", "main.kt").put("added", 1).put("removed", 1))))
            }, submit = { _, _, _ -> writes++ }, busy = false, onDismiss = {})
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("diff_preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("diff_preview").assertIsDisplayed()
        compose.onNodeWithText("+ newValue").assertIsDisplayed()
        evidence("workspace-diff")
        compose.runOnIdle { assertEquals(listOf("/v1/threads/fixture/diff"), reads); assertEquals(0, writes) }
    }
}
