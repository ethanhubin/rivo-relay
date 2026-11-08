package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.ConversationDraft
import dev.rivo.relay.data.ConversationStore
import dev.rivo.relay.data.PendingConversation
import dev.rivo.relay.data.PendingNativeMessage
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.projects.PendingWorkspaceSelector
import dev.rivo.relay.ui.projects.ProjectSummary
import dev.rivo.relay.ui.sessions.SessionsScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class PendingConversationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun switchingPendingIdentitySavesTheDepartingDraftToItsOwnKey() {
        val directory = File(compose.activity.cacheDir, "pending-identity-${System.nanoTime()}")
        val store = ConversationStore(directory)
        val current = mutableStateOf("pending:first")
        val writes = mutableListOf<Pair<String, String>>()
        runBlocking {
            store.saveDraft("pending:first", ConversationDraft("第一份"))
            store.saveDraft("pending:second", ConversationDraft("第二份"))
        }
        try {
            compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                val target = current.value
                ChatScreen(SessionState(id = target), false, false, emptyList(), {}, onSend = { _, _, _, _ -> },
                    onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {},
                    readFile = { JSONObject() }, pendingTitle = "项目", draftStore = store,
                    onPersistDraft = { id, draft ->
                        // The callback itself is scoped to the rendered identity, as in App.
                        assertEquals(target, id)
                        writes += id to draft.text
                    })
            } } }
            compose.waitUntil { compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().any {
                it.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "第一份"
            } }
            compose.onNodeWithTag("message_input").performTextReplacement("第一份新增内容")
            compose.runOnIdle { writes.clear(); current.value = "pending:second" }
            compose.waitUntil { compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().any {
                it.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "第二份"
            } }
            compose.runOnIdle {
                assertTrue(writes.contains("pending:first" to "第一份新增内容"))
                assertFalse(writes.any { it.first == "pending:first" && it.second != "第一份新增内容" })
                assertFalse(writes.any { it.first == "pending:second" && it.second != "第二份" })
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun multiplePendingChatsResumeAndDeleteTheirOwnDraft() {
        val first = PendingConversation("draft-one", "project", ConversationDraft("分析日志"), 1L)
        val second = PendingConversation("draft-two", "project", ConversationDraft("修复界面"), 2L)
        val drafts = mutableStateOf(listOf(second, first))
        var resumed: String? = null
        var removed: String? = null
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            SessionsScreen("同一项目", emptyList(), false, true, {}, {}, {}, {},
                pendingConversations = drafts.value, onResumePending = { resumed = it },
                onDiscardPending = { id -> removed = id; drafts.value = drafts.value.filterNot { it.id == id } })
        } } }
        compose.onNodeWithText("最近会话 2").assertIsDisplayed()
        compose.onNodeWithTag("pending_conversation_draft-one").performClick()
        compose.runOnIdle { assertEquals(first.id, resumed) }
        compose.onNodeWithTag("search_sessions").performTextInput("修复")
        compose.onNodeWithTag("pending_conversation_draft-one").assertDoesNotExist()
        compose.onNodeWithTag("pending_conversation_draft-two").assertIsDisplayed()
        compose.onNodeWithTag("delete_pending_conversation_draft-two").performClick()
        compose.onNodeWithTag("confirm_delete_pending_conversation").performClick()
        compose.runOnIdle { assertEquals(second.id, removed); assertEquals(listOf(first), drafts.value) }
        compose.onNodeWithTag("session_search_clear").performClick()
        compose.onNodeWithTag("pending_conversation_draft-one").assertIsDisplayed()
        compose.onNodeWithText("最近会话 1").assertIsDisplayed()
    }

    @Test fun firstSubmissionKeepsDisabledDraftAndFailureRestoresEditing() {
        val pending = mutableStateOf(emptyList<PendingNativeMessage>())
        val busy = mutableStateOf(false)
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            ChatScreen(SessionState(id = "pending:draft", pendingMessages = pending.value), busy.value, false, emptyList(), {},
                onSend = { input, _, _, _ ->
                    busy.value = true
                    pending.value = listOf(PendingNativeMessage("create", "first-send", "pending:draft",
                        (0 until input.length()).map { input.getJSONObject(it) }, kind = "create"))
                }, onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {},
                readFile = { JSONObject() }, pendingTitle = "项目",
                pendingReceiptUnresolved = pending.value.any { it.delivery == "unknown" })
        } } }
        compose.onNodeWithTag("message_input").performTextInput("先检查项目")
        compose.onNodeWithTag("send_message").performClick()
        compose.onNodeWithTag("send_progress").assertIsDisplayed()
        compose.onNodeWithTag("message_input").assertTextEquals("先检查项目").assertIsNotEnabled()
        compose.onNodeWithText("发送中…").assertDoesNotExist()
        compose.onNodeWithText("今天想从哪里开始？").assertIsDisplayed()
        compose.onNodeWithTag("send_message").assertIsNotEnabled()
        compose.onNodeWithTag("new_session_model").assertIsNotEnabled()
        compose.onNodeWithTag("new_session_effort").assertIsNotEnabled()
        compose.runOnIdle { pending.value = pending.value.map { it.copy(delivery = "unknown") }; busy.value = false }
        compose.onNodeWithTag("send_progress").assertDoesNotExist()
        compose.onNodeWithTag("message_input").assertTextEquals("先检查项目").assertIsNotEnabled()
        compose.onNodeWithTag("send_message").assertIsNotEnabled()
        compose.runOnIdle { pending.value = emptyList() }
        compose.onNodeWithTag("send_progress").assertDoesNotExist()
        compose.onNodeWithTag("message_input").assertTextEquals("先检查项目")
        compose.onNodeWithTag("send_message").assertIsEnabled()
    }

    @Test fun localWorkspaceDoesNotReadGitAndWorktreeFailureCanBeLeft() {
        var reads = 0
        var ready = false
        compose.setContent { RivoTheme {
            PendingWorkspaceSelector(ProjectSummary("p", "Project", "/project"),
                readGit = { reads++; error("Git unavailable") }, enabled = true,
                onSelection = { _, valid -> ready = valid })
        } }
        compose.runOnIdle { assertEquals(0, reads); assertTrue(ready) }
        compose.onNodeWithTag("new_conversation_workspace").performClick()
        compose.onNodeWithText("工作树").performClick()
        compose.onNodeWithText("Git unavailable").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, reads); assertFalse(ready) }
        compose.onNodeWithTag("new_conversation_workspace").performClick()
        compose.onNodeWithText("本地目录").performClick()
        compose.runOnIdle { assertTrue(ready) }
    }

    @Test fun pendingChatSendsOnlyOnTapAndKeepsDraftUntilAccepted() {
        var sends = 0
        var accept: (() -> Unit)? = null
        var back = false
        compose.setContent { RivoTheme {
            ChatScreen(SessionState(), false, false, emptyList(), { back = true },
                onSend = { input, _, _, accepted ->
                    assertEquals("Hello", input.getJSONObject(0).getString("text"))
                    sends++; accept = accepted
                }, onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {},
                onEarlier = {}, readFile = { JSONObject() }, pendingTitle = "Project")
        } }
        compose.onNodeWithText("新会话").assertIsDisplayed()
        compose.onNodeWithTag("send_message").assertIsNotEnabled()
        compose.onNodeWithTag("message_input").performTextInput("Hello")
        compose.runOnIdle { assertEquals(0, sends) }
        compose.onNodeWithTag("send_message").performClick()
        compose.onNodeWithTag("message_input").assertTextEquals("Hello")
        compose.runOnIdle { assertEquals(1, sends); accept!!.invoke() }
        compose.onNodeWithTag("message_input").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithTag("back_sessions").performClick()
        compose.runOnIdle { assertTrue(back) }
    }
    @Test fun pendingChatStagesPhoneAndNodeFilesWithoutCreatingAThread() {
        val context = compose.activity
        val file = File(File(context.cacheDir, "previews").apply { mkdirs() }, "pending-attachment.txt").apply { writeText("pending file contents") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, android.app.Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        var uploads = 0
        var sent: JSONArray? = null
        try {
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                ChatScreen(SessionState(id = "pending:p"), false, false, emptyList(), {}, onSend = { input, _, _, _ -> sent = input },
                    onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() },
                    pendingTitle = "Project", pendingCwd = "/project", uploadFile = { target, name, staged ->
                        assertEquals("pending:p", target); assertEquals("pending file contents", staged.readText()); uploads++
                        JSONObject().put("name", name).put("path", "/project/.rivo-attachments/id/$name")
                    }, readDirectory = { path ->
                        assertEquals("/project", path)
                        JSONObject("""{"entries":[{"fileName":"source.kt","isFile":true}]}""")
                    })
            } } } }
            compose.onNodeWithTag("add_attachment").performClick()
            compose.onNodeWithTag("add_file").performClick()
            compose.waitUntil { uploads == 1 }
            compose.onNodeWithTag("draft_file_pending-attachment.txt").assertExists()
            compose.onNodeWithTag("add_attachment").performClick()
            compose.onNodeWithTag("attachment_tab_node").performClick()
            compose.onNodeWithTag("add_workspace_file").performClick()
            compose.onNodeWithTag("attachment_entry_/project/source.kt").performClick()
            compose.onNodeWithTag("draft_file_source.kt").assertExists()
            compose.runOnIdle { assertNull(sent) }
            compose.onNodeWithTag("send_message").performClick()
            compose.runOnIdle {
                assertEquals(3, sent!!.length())
                assertEquals("/project/.rivo-attachments/id/pending-attachment.txt", sent!!.getJSONObject(1).getString("path"))
                assertEquals("/project/source.kt", sent!!.getJSONObject(2).getString("path"))
            }
        } finally { file.delete() }
    }
}
