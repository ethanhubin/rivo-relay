package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.codex.control
import dev.rivo.relay.data.sessionSnapshot
import dev.rivo.relay.ui.chat.NativeWriterStatus
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TakeoverUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun takeoverRemainsVisibleWithReasonAndReenablesAfterReconnect() {
        val reason = mutableStateOf<String?>("网络不可用，请恢复节点连接后接管")
        val snapshot = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "t"))
            .put("readOnly", true).put("writerOwner", "external"))
        var action = ""
        compose.setContent { RivoTheme { Surface {
            NativeWriterStatus(SessionState(id = "t", snapshot = snapshot, stale = true, lastConfirmedControl = SessionState(snapshot = snapshot).control()), "节点", false,
                { action = it }, reason.value)
        } } }
        compose.onNodeWithTag("ownership_takeover").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("takeover_reason").assertTextContains("网络不可用", substring = true)
        compose.runOnIdle { reason.value = null }
        compose.onNodeWithTag("ownership_takeover").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("takeover", action) }
    }
    @Test fun initialLoadAndWritableRefreshNeverFlashTakeover() {
        val state = mutableStateOf(SessionState(id = "t", loading = true))
        compose.setContent { RivoTheme { Surface {
            NativeWriterStatus(state.value, "节点", false, {})
        } } }
        compose.onNodeWithTag("ownership_takeover").assertDoesNotExist()
        val ready = SessionState(id = "t", snapshot = sessionSnapshot(JSONObject()
            .put("thread", JSONObject().put("id", "t")).put("writerOwner", "relay")))
        compose.runOnIdle { state.value = ready }
        compose.onNodeWithText("释放权限").assertIsEnabled()
        compose.runOnIdle { state.value = ready.copy(stale = true, lastConfirmedControl = ready.control()) }
        compose.onNodeWithTag("ownership_takeover").assertDoesNotExist()
        compose.onNodeWithText("释放权限").assertIsNotEnabled()
        compose.runOnIdle { state.value = ready }
        compose.onNodeWithText("释放权限").assertIsEnabled()
        compose.onNodeWithTag("ownership_takeover").assertDoesNotExist()
        compose.runOnIdle { state.value = SessionState(id = "other", loading = true) }
        compose.onNodeWithTag("ownership_takeover").assertDoesNotExist()
        compose.onNodeWithTag("writer_action").assertDoesNotExist()
    }

    @Test fun unsupportedDesktopShowsDisabledTakeoverInsteadOfHidingIt() {
        val snapshot = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "t"))
            .put("readOnly", true).put("nativeRuntime", JSONObject().put("desktopShared", true)))
        compose.setContent { RivoTheme { Surface {
            NativeWriterStatus(SessionState(id = "t", snapshot = snapshot), "节点", false, { error("disabled") })
        } } }
        compose.onNodeWithTag("ownership_takeover").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("takeover_reason").assertTextContains("Desktop", substring = true)
    }
}
