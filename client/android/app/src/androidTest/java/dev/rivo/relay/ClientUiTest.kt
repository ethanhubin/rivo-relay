package dev.rivo.relay

import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.connection.CredentialStore
import dev.rivo.relay.data.NativeRequest
import dev.rivo.relay.ui.chat.NativeRequestPanel
import dev.rivo.relay.ui.chat.FilePreviewDialog
import dev.rivo.relay.ui.chat.MessageContent
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.codex.SubmissionCoordinator
import dev.rivo.relay.data.RelayApi
import dev.rivo.relay.ui.login.LoginScreen
import dev.rivo.relay.ui.projects.ProjectSummary
import dev.rivo.relay.ui.projects.ProjectsScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

@RunWith(AndroidJUnit4::class)
class ClientUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun manualLoginSubmitsOnePairingInputWithoutExtraPermissionScreen() {
        var received: Pair<String, String>? = null
        compose.setContent { RivoTheme { LoginScreen(false, null, { address, code -> received = address to code }, {}) } }
        compose.onNodeWithTag("scan_connect").assertIsDisplayed()
        compose.onNodeWithTag("manual_entry").performClick()
        compose.onNodeWithTag("pairing_address").performTextInput("https://node.example")
        compose.onNodeWithTag("pairing_code").performTextInput("test-code")
        compose.onNodeWithTag("manual_connect").performClick()
        compose.runOnIdle { assertEquals("https://node.example" to "test-code", received) }
    }

    @Test fun loggedInHomeIsProjectListAndOpensChosenProject() {
        val project = ProjectSummary("p", "隔离项目", "/test/project", 2)
        var chosen: ProjectSummary? = null
        compose.setContent { RivoTheme { ProjectsScreen(listOf(project), "测试节点", false, { chosen = it }, {}, {}) } }
        compose.onNodeWithText("Codex 项目").assertIsDisplayed()
        compose.onNodeWithTag("project_p").performClick()
        compose.runOnIdle { assertEquals(project, chosen) }
    }

    @Test fun nativeApprovalHasNoImplicitAnswerAndPreservesNativeDecision() {
        var response: JSONObject? = null
        val request = NativeRequest("runtime:request-1", "item/commandExecution/requestApproval", JSONObject().put("command", "printf test").put("reason", "原生测试审批"))
        compose.setContent { RivoTheme { NativeRequestPanel(request, true) { response = it } } }
        compose.runOnIdle { assertNull(response) }
        compose.onNodeWithTag("decline_request").performClick()
        compose.runOnIdle { assertEquals("decline", response?.getString("decision")) }
    }

    @Test fun keystoreStorageSurvivesStoreRecreationAndContainsNoPlaintext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "instrumentation-only-${UUID.randomUUID()}"
        val secret = "fixture-secret-${UUID.randomUUID()}"
        val store = CredentialStore(context)
        try {
            store.save(key, secret)
            val encrypted = context.getSharedPreferences("rivo_secure", 0).getString(key, null)
            assertNotNull(encrypted); assertFalse(encrypted!!.contains(secret))
            assertEquals(secret, CredentialStore(context).read(key))
        } finally { store.remove(key) }
    }

    @Test fun nativeSecretQuestionUsesPasswordSemantics() {
        val request = NativeRequest("secret-request", "item/tool/requestUserInput", JSONObject("""{"questions":[{"id":"secret","question":"输入验证值","isSecret":true,"isOther":true,"options":null}]}"""))
        compose.setContent { RivoTheme { NativeRequestPanel(request, true) {} } }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertExists()
    }

    @Test fun nativePermissionGrantPreservesRequestedScopeAndFields() {
        var response: JSONObject? = null
        val request = NativeRequest("permission-request", "item/permissions/requestApproval", JSONObject("""{"reason":"原生请求","permissions":{"network":{"enabled":true},"fileSystem":{"read":["/test"],"write":null}}}"""))
        compose.setContent { RivoTheme { NativeRequestPanel(request, true) { response = it } } }
        compose.onNodeWithTag("grant_permissions").performClick()
        compose.runOnIdle { assertEquals("turn", response?.getString("scope")); assertTrue(response!!.getJSONObject("permissions").getJSONObject("network").getBoolean("enabled")) }
    }

    @Test fun fileCitationLoadsNodeReadOnlyPreview() {
        var path: String? = null
        compose.setContent { RivoTheme { MessageContent("[design.md](/project/design.md:12)", "/project") { path = it } } }
        compose.onNodeWithTag("message_text").performTouchInput { click(Offset(18f, 18f)) }
        compose.runOnIdle { assertEquals("/project/design.md:12", path) }
    }

    @Test fun apkOffersDownloadWithoutTryingInlinePreview() {
        compose.setContent { RivoTheme { FilePreviewDialog("/project/app.apk", { error("APK should not be previewed") }, { _, _, _ -> }, onDismiss = {}) } }
        compose.onNodeWithTag("save_file").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("选择保存到本机或打开 APK。").assertIsDisplayed()
    }

    @Test fun filePreviewShowsNativeFileTextWithoutAnEditor() {
        var requested: String? = null
        compose.setContent { RivoTheme { FilePreviewDialog("/project/design.md", { path ->
            requested = path; JSONObject().put("path", path).put("encoding", "utf-8").put("text", "Native file preview").put("mimeType", "text/markdown").put("size", 19)
        }, { _, _, _ -> }, onDismiss = {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Native file preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Native file preview").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle { assertEquals("/project/design.md", requested) }
    }

    @Test fun pendingSubmissionFreezesDraftUntilItsResultIsKnown() {
        compose.setContent { RivoTheme { ChatScreen(SessionState(id = "pending-thread"), false, true, emptyList(), {},
            onSend = { _, _, _, _ -> }, onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() }) } }
        compose.onNodeWithTag("message_input").assertIsNotEnabled()
        compose.onNodeWithTag("add_attachment").assertIsNotEnabled()
        compose.onNodeWithTag("send_message").assertIsNotEnabled()
    }

    @Test fun pendingWriteIsReconciledOnceWithoutReplayingThePost() = runBlocking {
        val server = MockWebServer()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = CredentialStore(context)
        val key = "instrumentation-submission-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val accepted = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"status":"unknown"}"""))
            val coordinator = SubmissionCoordinator(RelayApi(server.url("/").toString()), scope, vault, key) { failure.set(it) }
            coordinator.submit("/v1/threads/test/turns", JSONObject().put("text", "fixture"), "测试提交") { accepted.incrementAndGet() }
            withTimeout(10_000) { while (coordinator.state.value.busy) delay(10) }
            val pending = requireNotNull(coordinator.state.value.pending)
            assertEquals(0, accepted.get())
            server.enqueue(MockResponse().setBody("""{"status":"accepted","receipt":{"turnId":"fixture-turn"}}"""))
            coordinator.reconcile()
            withTimeout(10_000) { while (coordinator.state.value.busy) delay(10) }
            assertNull(coordinator.state.value.pending)
            assertNull(failure.get())
            assertEquals(1, accepted.get())
            assertEquals(2, server.requestCount)
            assertEquals("POST", server.takeRequest().method)
            val lookup = server.takeRequest()
            assertEquals("GET", lookup.method)
            assertEquals("/v1/submissions/${pending.id}", lookup.path)
        } finally { scope.cancel(); vault.remove(key); server.shutdown() }
    }

    @Test fun endingReconciliationOnlyClearsLocalWaitAndNeverInvokesOldCompletion() = runBlocking {
        val server = MockWebServer()
        val vault = CredentialStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val key = "instrumentation-end-wait-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val accepted = AtomicInteger()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"status":"unknown","receipt":{"threadId":"created-before-disconnect"}}"""))
            val coordinator = SubmissionCoordinator(RelayApi(server.url("/").toString()), scope, vault, key) {}
            coordinator.submit("/v1/threads", JSONObject().put("projectId", "fixture"), "测试新建") { accepted.incrementAndGet() }
            withTimeout(10_000) { while (coordinator.state.value.busy) delay(10) }
            val pending = requireNotNull(coordinator.state.value.pending)
            assertEquals("created-before-disconnect", pending.threadId)
            coordinator.finishReconciliation("different-operation")
            assertNotNull(coordinator.state.value.pending)
            coordinator.finishReconciliation(pending.id)
            coordinator.reconcile()
            assertNull(coordinator.state.value.pending)
            assertNull(vault.read(key))
            assertEquals(0, accepted.get())
            assertEquals(1, server.requestCount)
            assertEquals("POST", server.takeRequest().method)
        } finally { scope.cancel(); vault.remove(key); server.shutdown() }
    }
}
