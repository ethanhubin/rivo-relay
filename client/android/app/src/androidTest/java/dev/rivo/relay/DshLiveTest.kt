package dev.rivo.relay

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real existing DSH Host only. Pairing must come from the actual camera decoder. */
@RunWith(AndroidJUnit4::class)
class DshLiveTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(*(if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.CAMERA)))
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String, timeout: Long = 60000) = compose.waitUntil(timeout) {
        runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
    }
    @Test fun cameraPairing() {
        compose.onNodeWithTag("scan_connect").assertIsDisplayed().performClick()
        waitTag("projects_page", 240000)
        compose.onNodeWithTag("app_error").assertDoesNotExist()
    }
    @Test fun nativeApproval() {
        val threadId = requireNotNull(InstrumentationRegistry.getArguments().getString("threadId"))
        compose.waitUntil(60000) { androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java].state.value.node != null }
        compose.runOnIdle {
            requireNotNull(androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java].state.value.node).openNotificationThread(threadId)
        }
        waitTag("approve_request", 60000)
        compose.onNodeWithTag("approval_choice_0").performClick()
        compose.onNodeWithTag("approve_request").assertIsEnabled().performClick()
        compose.waitUntil(180000) { runCatching { compose.onAllNodesWithText("RIVO_DSH_APPROVAL_OK", substring = false).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.onNodeWithTag("app_error").assertDoesNotExist()
    }
    @Test fun chatAndNetworkRecovery() {
        compose.waitUntil(60000) { androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java].state.value.node != null }
        val threadId = requireNotNull(InstrumentationRegistry.getArguments().getString("threadId"))
        lateinit var node: NodeRuntime
        compose.runOnIdle {
            node = requireNotNull(androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java].state.value.node)
            node.openNotificationThread(threadId)
        }
        waitTag("chat_page")
        compose.waitUntil(60000) { !node.session.state.value.stale && node.session.state.value.snapshot != null }
        compose.onNodeWithTag("message_input").performTextInput("Reply with exactly RIVO_DSH_ANDROID_OK. Do not use tools.")
        compose.onNodeWithTag("send_message").assertIsEnabled().performClick()
        compose.waitUntil(180000) { runCatching { compose.onAllNodesWithText("RIVO_DSH_ANDROID_OK", substring = false).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.waitUntil(60000) { compose.onAllNodesWithTag("stop_turn").fetchSemanticsNodes().isEmpty() && !node.session.state.value.stale }
        val before = node.session.state.value.snapshot!!.messages.map { Triple(it.turnId, it.id, it.text) }
        val ins = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) { ins.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        val wifi = android.provider.Settings.Global.getInt(ins.targetContext.contentResolver, "wifi_on", 0) == 1
        val data = android.provider.Settings.Global.getInt(ins.targetContext.contentResolver, "mobile_data", 0) == 1
        try {
            shell("svc wifi disable"); shell("svc data disable")
            compose.waitUntil(20000) { node.session.state.value.stale }
        } finally {
            if (wifi) shell("svc wifi enable")
            if (data) shell("svc data enable")
        }
        try {
            compose.waitUntil(60000) { !node.session.state.value.stale && !node.session.state.value.loading && node.session.state.value.activityStatus == "idle" }
        } catch (error: Throwable) {
            val session = node.session.state.value
            val syncField = NodeRuntime::class.java.getDeclaredField("sync").apply { isAccessible = true }
            val activity = (syncField.get(node) as dev.rivo.relay.codex.NodeSessionSync).activity.value
            throw AssertionError("Recovery state: stale=${session.stale}, loading=${session.loading}, " +
                "activityStatus=${session.activityStatus}, generation=${activity.generation}, version=${activity.version}, " +
                "confirmed=${activity.confirmed}, currentKnown=${threadId in activity.known}, " +
                "currentUnknown=${threadId in activity.unknown}, currentExecution=${activity.execution(threadId).status}", error)
        }
        assertEquals(before, node.session.state.value.snapshot!!.messages.map { Triple(it.turnId, it.id, it.text) })
        compose.onNodeWithTag("app_error").assertDoesNotExist()
        val dir = java.io.File(ins.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        compose.onAllNodesWithText("稍后再说").fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
            compose.onNodeWithText("稍后再说").performClick()
            compose.waitForIdle()
        }
        ins.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { java.io.File(dir, "dsh-chat-recovered.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
            finally { bitmap.recycle() }
        }
    }
}
