package dev.rivo.relay

import android.graphics.Bitmap
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
import dev.rivo.relay.ui.chat.*
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CodexLatestParityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun snapshot() = sessionSnapshot(JSONObject("""{"thread":{"id":"latest","name":"Codex 交互","turns":[]},"settings":{"model":"model","reasoningEffort":"high"},"writerOwner":"relay"}"""))
    private fun evidence(name: String) {
        compose.waitForIdle()
        val ins = InstrumentationRegistry.getInstrumentation()
        val folder = File(ins.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @Test fun clarificationLocksOrdinaryInputAndKeepsItsDraft() {
        val state = mutableStateOf(SessionState(id = "latest", snapshot = snapshot()))
        var response: JSONObject? = null
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) { ChatScreen(state.value, false, false, emptyList(), {},
            onSend = { _, _, _, _ -> error("must not submit") }, onStop = {}, onRespond = { _, value -> response = value },
            onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() }) } } }
        compose.onNodeWithTag("message_input").performTextInput("尚未发送的草稿")
        compose.runOnIdle { state.value = state.value.copy(snapshot = snapshot().copy(requests = listOf(NativeRequest("question", "item/tool/requestUserInput",
            JSONObject("""{"questions":[{"id":"q","question":"是否继续？","options":[{"label":"继续"}]}]}"""))))) }
        compose.onNodeWithTag("message_input").assertIsNotEnabled().assertTextContains("尚未发送的草稿", substring = true)
        compose.onNodeWithTag("send_message").assertIsNotEnabled()
        compose.onNodeWithTag("native_requests_panel").assertIsDisplayed()
        compose.onNodeWithTag("answer_with_assumptions").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("请按合理假设继续。", response!!.getJSONObject("answers").getJSONObject("q").getJSONArray("answers").getString(0)) }
        evidence("codex-latest-clarification")
        compose.runOnIdle { state.value = state.value.copy(snapshot = snapshot()) }
        compose.onNodeWithTag("message_input").assertIsEnabled().assertTextContains("尚未发送的草稿", substring = true)
    }
    @Test fun interruptedTaskAllowsInputWithoutRecoveryPanel() {
        val recovery = queueTask(JSONObject("""{"id":"recovery","text":"完成剩余的验证工作","status":"unknown","revision":3,"error":"连接中断","recoveryRequired":true}"""))
        val current = snapshot().copy(recoveryTask = recovery, queue = listOf(recovery, QueueTask("waiting", "后续工作", "waiting", 1, "", JSONObject())))
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) { ChatScreen(SessionState(id = current.id, snapshot = current), false, false, emptyList(), {},
            onSend = { _, _, _, _ -> Unit }, onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {},
            readFile = { JSONObject() }) } } }
        compose.onNodeWithTag("codex_recovery_panel").assertDoesNotExist()
        compose.onNodeWithTag("queue_item_recovery").assertDoesNotExist()
        compose.onNodeWithTag("queue_item_waiting").assertIsDisplayed()
        compose.onNodeWithTag("message_input").assertIsEnabled()
        evidence("codex-latest-recovery")
        compose.onNodeWithTag("codex_recovery_continue").assertDoesNotExist()
        compose.onNodeWithTag("codex_recovery_dismiss").assertDoesNotExist()
    }
    @Test fun fastModeAndModelSwitchSaveWithoutLosingSharedEffort() {
        val models = listOf(JSONObject("""{"id":"second","supportedReasoningEfforts":[{"reasoningEffort":"medium"},{"reasoningEffort":"high"}],"defaultReasoningEffort":"medium"}"""))
        val edits = mutableListOf<JSONObject>()
        var defaults: JSONObject? = null
        // Saving presets requires known native permissions; missing values must
        // remain disabled rather than being silently converted into defaults.
        val known = snapshot().let { it.copy(settings = JSONObject(it.settings.toString())
            .put("sandbox", JSONObject().put("type", "workspaceWrite")).put("approvalPolicy", "on-request")) }
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) { ChatScreen(SessionState(id = "latest", snapshot = known), false, false, models, {},
            onSend = { _, _, _, _ -> }, onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() },
            onSettingsChange = { edits += it }, onSaveDefaults = { defaults = it }, modelCatalogNotice = "当前使用最近可用选项") } } }
        compose.onNodeWithTag("composer_settings").performClick()
        compose.onNodeWithTag("composer_model").performClick()
        compose.onNodeWithTag("composer_model_second").performClick()
        compose.runOnIdle { assertEquals("high", edits.last().getString("effort")) }
        compose.onNodeWithTag("codex_fast_mode_switch").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("priority", edits.last().getString("serviceTier")) }
        compose.onNodeWithTag("codex_set_default").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("second", defaults!!.getString("model")); assertEquals("priority", defaults!!.getString("serviceTier")) }
        evidence("codex-latest-settings")
    }
    @Test fun forwardingChoosesDestinationWithoutSubmittingOrDownloading() {
        var destination: List<String?>? = null
        val paths = mutableListOf<String>()
        compose.setContent { RivoTheme { CodexLinkDestinationDialog(CodexLinkAttachment("/project/result.md", "result.md"), read = { path ->
            paths += path
            if (path == "/v1/projects") JSONObject("""{"projects":[{"id":"p","name":"项目"}]}""")
            else JSONObject("""{"threads":[{"id":"target","name":"接收会话"}]}""")
        }, onConfirm = { project, thread, source, name -> destination = listOf(project, thread, source, name) }, onDismiss = {}) } }
        compose.onNodeWithTag("codex_link_session_target").performClick()
        compose.onNodeWithTag("confirm_forward_link").performClick()
        compose.runOnIdle {
            assertEquals(listOf("p", "target", "/project/result.md", "result.md"), destination)
            assertTrue(paths.all { it == "/v1/projects" || it.startsWith("/v1/threads?projectId=p") })
        }
        evidence("codex-latest-forward")
    }
    @Test fun diffRevisionConflictRefreshesAndRetainsSelectedPath() {
        fun diff(revision: String, id: String) = JSONObject().put("schemaVersion", 1).put("revision", revision)
            .put("files", JSONArray().put(JSONObject().put("id", id).put("path", "src.kt")))
            .put("lines", JSONArray().put(JSONObject().put("kind", "addition").put("text", "new-$revision").put("newLine", 1)))
            .put("nextOffset", JSONObject.NULL)
        val paths = mutableListOf<String>()
        var conflict = true
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) { StructuredDeveloperDiffContent("latest", diff("old", "old-id"), read = { path ->
            paths += path
            if (path.contains("fileId=") && path.contains("revision=old") && conflict) { conflict = false; throw ApiException(409, "changed", "changed") }
            JSONObject().put("diff", if (!path.contains('?') || path.contains("revision=new")) diff("new", "new-id") else diff("old", "old-id"))
        }, modifier = Modifier.fillMaxSize()) } } }
        compose.onNodeWithTag("codex_diff_files").performClick()
        compose.waitUntil { paths.any { it.contains("revision=new") && it.contains("fileId=new-id") } }
        compose.onNodeWithTag("codex_diff_file_src.kt").assertIsSelected()
        compose.onNodeWithText("+ new-new").assertIsDisplayed()
        evidence("codex-latest-diff")
    }
}
