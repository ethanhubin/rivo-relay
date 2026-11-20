package dev.rivo.relay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live node setup; keeps previously paired nodes intact. */
@RunWith(AndroidJUnit4::class)
class RuntimeLiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private fun present(tag: String) = runCatching {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)
    private fun waitTag(tag: String) = compose.waitUntil(60000) { present(tag) }
    private fun dismissUpdate() {
        if (compose.onAllNodesWithText("稍后再说").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("稍后再说").performClick()
            compose.waitForIdle()
        }
    }
    private fun address() = requireNotNull(InstrumentationRegistry.getArguments().getString("nodeAddress")).trimEnd('/')
    private fun awaitSelected(address: String) {
        compose.waitUntil(60000) { vm.state.value.node?.connection?.base?.trimEnd('/') == address }
        waitTag("projects_page")
        compose.onNodeWithTag("app_error").assertDoesNotExist()
    }
    @Test fun addNodeFromSettings() {
        val target = address()
        val code = requireNotNull(InstrumentationRegistry.getArguments().getString("pairingCode"))
        compose.waitUntil(60000) { vm.state.value.node != null }
        compose.waitForIdle()
        dismissUpdate()
        if (present("back_sessions")) compose.onNodeWithTag("back_sessions").performClick()
        compose.waitForIdle()
        if (present("sessions_back_home")) compose.onNodeWithTag("sessions_back_home").performClick()
        waitTag("nav_settings")
        compose.onNodeWithTag("nav_settings").performClick()
        waitTag("settings_add_node")
        dismissUpdate()
        compose.onNodeWithTag("settings_add_node").performClick()
        waitTag("manual_entry")
        compose.onNodeWithTag("manual_entry").performClick()
        compose.onNodeWithTag("pairing_address").performTextReplacement(target)
        compose.onNodeWithTag("pairing_code").performTextReplacement(code)
        compose.onNodeWithTag("manual_connect").performClick()
        awaitSelected(target)
    }
    @Test fun selectSavedNode() {
        val target = address()
        compose.waitUntil(60000) { vm.state.value.node != null }
        compose.runOnIdle { vm.selectNode(target) }
        awaitSelected(target)
        dismissUpdate()
    }
}
