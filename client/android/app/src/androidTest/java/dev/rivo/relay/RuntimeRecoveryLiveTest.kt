package dev.rivo.relay

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rivo.relay.codex.TaskNotificationTarget
import dev.rivo.relay.data.InteractionChoice
import dev.rivo.relay.data.NativeMessage
import dev.rivo.relay.data.NativeRequest
import dev.rivo.relay.data.SessionSnapshot
import dev.rivo.relay.ui.chat.approvalDecisions
import dev.rivo.relay.ui.chat.approvalLabel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Opt-in existing-runtime recovery checks. The host prepares one dedicated session and input;
 * these tests never pair, clear APP data, send another prompt, or restart a native runtime.
 *
 * Required instrumentation arguments: nodeAddress, threadId, submissionId, completionMarker,
 * runId (letters/digits/_/-). Optional: timeoutMs (default 180000), offlineMillis (default 2000).
 * checkpointBeforeProcessRestart additionally accepts checkpointState=approval|running.
 * verifyAfterProcessRestart accepts restartResolution=app|other-controller (default app).
 * observeOtherControllerResolution requires controllerKind=native-desktop|bridge-client|relay-client.
 *
 * Host coordination/evidence: external-files/verification/runtime-recovery/<runId>/<stage>.json.
 * Read ready.json before answering from another controller. For process recovery, run the
 * checkpoint test, force-stop dev.rivo.relay outside instrumentation, then run the verify test
 * with the same arguments. ActivityScenario.recreate alone is not a process-restart test.
 * Full interaction identities are hashed; no pairing credentials or executable request keys are saved.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeRecoveryLiveTest {
    @get:Rule(order = 0) val permission = GrantPermissionRule.grant(*(if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()))
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private fun required(name: String) = requireNotNull(arguments.getString(name)?.takeIf(String::isNotBlank)) {
        "Explicit live test argument required: $name"
    }
    private val base by lazy { required("nodeAddress").trimEnd('/') }
    private val threadId by lazy { required("threadId") }
    private val submissionId by lazy { required("submissionId") }
    private val marker by lazy { required("completionMarker") }
    private val timeout by lazy { (arguments.getString("timeoutMs")?.toLong() ?: 180_000L).coerceIn(10_000L, 600_000L) }
    private val folder by lazy {
        val runId = required("runId")
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid runId" }
        File(instrumentation.targetContext.getExternalFilesDir(null), "verification/runtime-recovery/$runId").apply { mkdirs() }
    }
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var vm: AppViewModel
    private val node get() = requireNotNull(vm.state.value.node)
    private val state get() = node.session.state.value
    private val snapshot get() = requireNotNull(state.snapshot)
    private val fresh get() = !state.stale && !state.loading && state.snapshot?.id == threadId

    private fun withSession(block: () -> Unit) {
        // Validate required coordination data before touching the APP or network.
        folder; submissionId; marker
        scenario = ActivityScenario.launch(TaskNotificationTarget(base, threadId).intent(instrumentation.targetContext))
        try {
            bindViewModel()
            compose.waitUntil(60_000) {
                val selected = vm.state.value.node
                selected?.connection?.base?.trimEnd('/') == base && selected.session.state.value.id == threadId
            }
            awaitFresh()
            compose.waitUntil(30_000) { present("chat_page") }
            dismissUpdate()
            block()
        } finally {
            scenario.close()
        }
    }

    private fun bindViewModel() = scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
    private fun awaitFresh() {
        try {
            compose.waitUntil(timeout) { fresh }
        } catch (error: Throwable) {
            val current = state
            val detail = JSONObject().put("stale", current.stale).put("loading", current.loading)
                .put("offline", current.offline).put("readError", current.readError)
                .put("controlNotice", current.controlNotice).put("activityStatus", current.activityStatus)
                .put("connectionRecovery", node.recovery.state.value.toString())
                .put("network", node.network.value?.toString()).put("synchronization", current.synchronization.toString())
                .put("snapshotStatus", current.snapshot?.status).put("runtimeSessionValid", current.snapshot?.runtimeSessionValid)
            File(folder, "sync-failure.json").writeText(detail.toString(2))
            throw AssertionError("Session did not revalidate: $detail", error)
        }
        assertEquals("Live runtime must provide a valid shared session contract", true, snapshot.runtimeSessionValid)
    }
    private fun present(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun dismissUpdate() {
        if (compose.onAllNodesWithText("稍后再说").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("稍后再说").performClick()
        }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun requestDigest(request: NativeRequest): String {
        val identity = requireNotNull(request.interaction?.identity) { "Shared interaction identity is required" }
        return digest(JSONArray(listOf(identity.sessionId, identity.runtimeGeneration, identity.id, identity.revision)).toString())
    }
    private fun identity(message: NativeMessage) = "${message.turnId}/${message.id}"
    private fun input(view: SessionSnapshot = snapshot): NativeMessage {
        val matches = view.messages.filter {
            it.type in setOf("userMessage", "user_message") && it.raw.optString("clientId") == submissionId
        }
        assertEquals("Exactly one accepted native input must match submissionId", 1, matches.size)
        return matches.single()
    }
    private fun assertInput(expectedIdentity: String) {
        assertEquals("Recovery must preserve the accepted input identity", expectedIdentity, identity(input()))
        val ids = snapshot.messages.map(::identity)
        assertEquals("Recovered messages must not contain duplicate native identities", ids.size, ids.toSet().size)
    }
    private fun pendingApproval(): NativeRequest {
        assertEquals("Prepare one pending approval in the dedicated session", 1, snapshot.requests.size)
        return snapshot.requests.single().also {
            assertTrue("Pending request must be an approval", it.interaction?.kind == "approval" || it.method in setOf(
                "runtime/approval", "item/commandExecution/requestApproval", "item/fileChange/requestApproval",
                "execCommandApproval", "applyPatchApproval"))
        }
    }
    private fun assertApprovalIdentity(expectedDigest: String) {
        assertEquals("Recovery must preserve session, runtime generation, request and revision", expectedDigest, requestDigest(pendingApproval()))
    }
    private fun assertActionsBlocked() {
        assertTrue("Disconnected session must require revalidation", state.stale)
        listOf("approve_request", "send_message", "stop_turn", "answer_questions").forEach { tag ->
            assertTrue("$tag must not be actionable before revalidation",
                compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isEmpty())
        }
    }

    private fun allowOnce(expectedDigest: String) {
        awaitFresh()
        dismissUpdate()
        assertApprovalIdentity(expectedDigest)
        val request = pendingApproval()
        val choices: List<Any> = (request.interaction?.choices ?: approvalDecisions(request)).filter { approvalLabel(it) != null }
        val index = choices.indexOfFirst { it == "accept" || it == "approved" || (it as? InteractionChoice)?.effect == "allow-once" }
        assertTrue("The runtime must explicitly offer a one-time approval", index >= 0)
        if (!present("approve_request")) compose.onNodeWithTag("toggle_native_request").performClick()
        compose.waitUntil(30_000) { present("approve_request") }
        compose.onNodeWithTag("approval_choice_$index").assertIsEnabled().performClick()
        assertApprovalIdentity(expectedDigest)
        compose.onNodeWithTag("approve_request").assertIsEnabled().performClick()
    }

    private fun awaitCompleted(expectedInput: String, resolvedRequest: String? = null) {
        compose.waitUntil(timeout) {
            fresh && !snapshot.isOngoing && snapshot.requests.isEmpty() && state.activityStatus == "idle" &&
                snapshot.messages.any { it.type in setOf("agentMessage", "agent_message") && it.text.contains(marker) }
        }
        assertInput(expectedInput)
        if (resolvedRequest != null) assertFalse("Resolved approval must not survive in the shared snapshot",
            snapshot.requests.any { requestDigest(it) == resolvedRequest })
        compose.onNodeWithTag("approve_request").assertDoesNotExist()
        compose.onNodeWithTag("app_error").assertDoesNotExist()
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }
    private fun disconnected(block: () -> Unit) {
        val resolver = instrumentation.targetContext.contentResolver
        val wifi = Settings.Global.getInt(resolver, "wifi_on", 0) == 1
        val data = Settings.Global.getInt(resolver, "mobile_data", 0) == 1
        try {
            shell("svc wifi disable")
            shell("svc data disable")
            compose.waitUntil(30_000) { state.stale && state.offline }
            assertActionsBlocked()
            block()
            val until = SystemClock.elapsedRealtime() + (arguments.getString("offlineMillis")?.toLong() ?: 2_000L).coerceIn(500L, 120_000L)
            compose.waitUntil(125_000) { SystemClock.elapsedRealtime() >= until }
            assertActionsBlocked()
        } finally {
            shell("svc wifi ${if (wifi) "enable" else "disable"}")
            shell("svc data ${if (data) "enable" else "disable"}")
        }
    }

    private fun evidence(stage: String, extra: JSONObject = JSONObject()): JSONObject {
        val current = state
        val view = requireNotNull(current.snapshot)
        val value = JSONObject().put("stage", stage).put("nodeAddress", base).put("threadId", threadId)
            .put("submissionId", submissionId).put("completionMarker", marker)
            .put("pid", Process.myPid()).put("processStartElapsedRealtime", Process.getStartElapsedRealtime())
            .put("elapsedRealtime", SystemClock.elapsedRealtime()).put("stale", current.stale)
            .put("offline", current.offline).put("status", view.status).put("activityStatus", current.activityStatus)
            .put("activeTurnId", view.activeTurnId).put("inputIdentity", identity(input(view)))
            .put("runtimeSessionValid", view.runtimeSessionValid)
            .put("requestDigests", JSONArray(view.requests.map(::requestDigest)))
            .put("messages", JSONArray(view.messages.map { JSONObject().put("identity", identity(it))
                .put("type", it.type).put("textDigest", digest(it.text)) }))
        extra.keys().forEach { key -> value.put(key, extra.get(key)) }
        val target = File(folder, "$stage.json")
        val temporary = File(folder, "$stage.json.tmp")
        temporary.writeText(value.toString(2))
        check(temporary.renameTo(target)) { "Cannot persist test checkpoint" }
        return value
    }
    private fun finishEvidence(extra: JSONObject = JSONObject()) {
        dismissUpdate()
        // Completion can be followed by the final activity/history invalidation.
        // Require a quiet confirmed window before calling the screenshot a recovered state.
        var confirmedSince = 0L
        compose.waitUntil(timeout) {
            val ready = fresh && !snapshot.isOngoing && snapshot.requests.isEmpty() && state.activityStatus == "idle"
            val now = SystemClock.elapsedRealtime()
            if (!ready) confirmedSince = 0L else if (confirmedSince == 0L) confirmedSince = now
            ready && now - confirmedSince >= 1_000L
        }
        compose.waitForIdle()
        assertTrue("Final evidence must be confirmed and idle", fresh && state.activityStatus == "idle")
        evidence("completed", extra)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(folder, "completed.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }

    @Test fun executionNetworkRecovery() = withSession {
        compose.waitUntil(timeout) { fresh && snapshot.isOngoing && snapshot.requests.isEmpty() && state.activityStatus == "running" }
        val acceptedInput = identity(input())
        evidence("before-disconnect")
        disconnected {
            assertInput(acceptedInput)
            evidence("offline")
        }
        awaitCompleted(acceptedInput)
        finishEvidence()
    }

    @Test fun approvalNetworkRecoveryAndActivityRecreate() = withSession {
        compose.waitUntil(timeout) { fresh && snapshot.requests.isNotEmpty() }
        val acceptedInput = identity(input())
        val requestDigest = requestDigest(pendingApproval())
        val processStart = Process.getStartElapsedRealtime()
        evidence("before-disconnect")
        disconnected {
            assertInput(acceptedInput)
            assertApprovalIdentity(requestDigest)
            evidence("offline")
        }
        awaitFresh()
        assertApprovalIdentity(requestDigest)
        evidence("network-recovered")
        scenario.recreate()
        bindViewModel()
        awaitFresh()
        dismissUpdate()
        assertEquals("Activity recreation must stay in the same process", processStart, Process.getStartElapsedRealtime())
        assertInput(acceptedInput)
        assertApprovalIdentity(requestDigest)
        evidence("activity-recreated")
        allowOnce(requestDigest)
        awaitCompleted(acceptedInput, requestDigest)
        finishEvidence()
    }

    @Test fun checkpointBeforeProcessRestart() = withSession {
        val expected = arguments.getString("checkpointState") ?: "approval"
        require(expected in setOf("approval", "running")) { "checkpointState must be approval or running" }
        compose.waitUntil(timeout) { fresh && if (expected == "approval") snapshot.requests.isNotEmpty()
            else snapshot.isOngoing && snapshot.requests.isEmpty() && state.activityStatus == "running" }
        if (expected == "approval") pendingApproval()
        evidence("checkpoint", JSONObject().put("checkpointState", expected))
        evidence("ready", JSONObject().put("nextAction", "host-force-stop-then-run-verifyAfterProcessRestart"))
    }

    @Test fun verifyAfterProcessRestart() = withSession {
        val checkpoint = JSONObject(File(folder, "checkpoint.json").readText())
        assertEquals(base, checkpoint.getString("nodeAddress"))
        assertEquals(threadId, checkpoint.getString("threadId"))
        assertEquals(submissionId, checkpoint.getString("submissionId"))
        assertEquals(marker, checkpoint.getString("completionMarker"))
        assertTrue("Host must force-stop and relaunch the APP process between phases",
            checkpoint.getInt("pid") != Process.myPid() ||
                checkpoint.getLong("processStartElapsedRealtime") != Process.getStartElapsedRealtime())
        val acceptedInput = checkpoint.getString("inputIdentity")
        assertInput(acceptedInput)
        val mode = arguments.getString("restartResolution") ?: "app"
        require(mode in setOf("app", "other-controller")) { "Invalid restartResolution" }
        val requestDigest = checkpoint.getJSONArray("requestDigests").optString(0).takeIf(String::isNotBlank)
        evidence("process-restarted", JSONObject().put("previousPid", checkpoint.getInt("pid")))
        if (requestDigest != null && mode == "app") allowOnce(requestDigest)
        awaitCompleted(acceptedInput, requestDigest)
        finishEvidence(JSONObject().put("restartResolution", mode))
    }

    @Test fun observeOtherControllerResolution() = withSession {
        val controller = required("controllerKind")
        require(controller in setOf("native-desktop", "bridge-client", "relay-client")) { "State the actual other controller" }
        compose.waitUntil(timeout) { fresh && snapshot.requests.isNotEmpty() }
        val acceptedInput = identity(input())
        val requestDigest = requestDigest(pendingApproval())
        compose.waitUntil(30_000) { present("approve_request") }
        evidence("ready", JSONObject().put("controllerKind", controller)
            .put("nextAction", "host-answer-current-request-on-other-controller"))
        // Deliberately no APP response: the host answers the exact current request elsewhere.
        awaitCompleted(acceptedInput, requestDigest)
        finishEvidence(JSONObject().put("controllerKind", controller))
    }

    /** Read-only evidence repair when a late invalidation raced an earlier terminal screenshot. */
    @Test fun captureCompletedSession() = withSession {
        val acceptedInput = identity(input())
        awaitCompleted(acceptedInput)
        finishEvidence(JSONObject().put("readOnlyRecapture", true))
    }
}
