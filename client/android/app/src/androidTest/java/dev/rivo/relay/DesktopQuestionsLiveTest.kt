package dev.rivo.relay

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rivo.relay.codex.TaskNotificationTarget
import dev.rivo.relay.connection.CredentialStore
import dev.rivo.relay.connection.PairingRepository
import dev.rivo.relay.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real Desktop -> production MainActivity -> Relay -> Desktop test. No mocked data or send callbacks. */
@RunWith(AndroidJUnit4::class)
class DesktopQuestionsLiveTest {
    @get:Rule(order = 0) val permission = GrantPermissionRule.grant(*(if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()))
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Test fun answersTwoDesktopQuestionsWithoutTakingWriter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixtureFile = File(context.getExternalFilesDir(null), "desktop-question-fixture.json")
        assumeTrue("Explicit live Desktop fixture required", fixtureFile.isFile)
        val fixture = JSONObject(fixtureFile.readText())
        val threadId = fixture.getString("threadId")
        val base = fixture.getString("base")
        require(base == "http://10.0.2.2:18876")
        val store = CredentialStore(context)
        val previous = listOf("connection", "nodes").associateWith(store::read)
        var scenario: ActivityScenario<MainActivity>? = null
        val folder = File(context.getExternalFilesDir(null), "desktop-question-evidence").apply { mkdirs() }
        try {
            val connection = runBlocking(Dispatchers.IO) { PairingRepository(store).pair(base, fixture.getString("code")) }
            fixtureFile.delete() // One-use code only; no token is written into the fixture/evidence.
            val api = connection.api()
            fun snapshot(): SessionSnapshot = runBlocking(Dispatchers.IO) {
                sessionSnapshot(api.call("/v1/threads/${encoded(threadId)}"))
            }
            fun save(name: String) {
                val raw = runBlocking(Dispatchers.IO) { api.call("/v1/threads/${encoded(threadId)}") }
                File(folder, "$name.json").writeText(raw.toString(2))
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            val before = snapshot()
            save("before")
            assertTrue("Must be Desktop-owned, not a Relay writer", before.readOnly)
            assertEquals("external", before.writerOwner)
            assertTrue("Original Desktop writer must be reachable", before.canAnswerQuestions)
            scenario = ActivityScenario.launch(TaskNotificationTarget(base, threadId).intent(context))
            fun answer(value: String) {
                compose.waitUntil(60_000) { compose.onAllNodesWithTag("async_questions_panel").fetchSemanticsNodes().isNotEmpty() }
                val question = pendingAsyncQuestions(snapshot()).single()
                compose.onNodeWithTag("question_answer_${question.id}").performTextInput(value)
                compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("answer_questions") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("answer_questions").performClick()
            }
            answer("RIVO_E2E_ALPHA\n请逐字复述 RIVO_E2E_ALPHA，然后使用 request_user_input_async 提问：请输入第二个验收标记。收到回答后逐字复述标记。不修改文件，不使用其他工具。")
            compose.waitUntil(120_000) {
                val head = snapshot()
                head.messages.any { it.type == "agentMessage" && it.text.contains("RIVO_E2E_ALPHA") } &&
                    pendingAsyncQuestions(head).any { it.title.contains("第二个验收标记") }
            }
            save("first-answer-confirmed")
            scenario.recreate()
            answer("RIVO_E2E_BETA_20261110\n请逐字复述 RIVO_E2E_BETA_20261110，然后结束验证，不再提问。")
            compose.waitUntil(120_000) {
                val head = snapshot()
                head.activeTurnId.isBlank() && head.messages.any {
                    it.type == "agentMessage" && it.text.contains("RIVO_E2E_BETA_20261110")
                }
            }
            save("second-answer-confirmed")
            scenario.recreate()
            compose.waitUntil(60_000) { compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().isNotEmpty() }
            val after = snapshot()
            assertTrue(after.readOnly)
            assertEquals("external", after.writerOwner)
            assertTrue(pendingAsyncQuestions(after).isEmpty())
            compose.onNodeWithTag("async_questions_panel").assertDoesNotExist()
            val replies = after.messages.filter { it.type == "userMessage" && it.raw.toString().contains("send_user_message_question_reply") }
            assertEquals("Each APP answer must be persisted exactly once", 2, replies.size)
            save("after-reopen")
        } finally {
            scenario?.close()
            previous.forEach { (key, value) -> if (value == null) store.remove(key) else store.save(key, value) }
        }
    }
}
