package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.rivo.relay.codex.SessionSynchronization
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.codex.control
import dev.rivo.relay.data.sessionSnapshot
import dev.rivo.relay.ui.chat.NativeChatHeader
import dev.rivo.relay.ui.chat.NativeWriterStatus
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TakeoverUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun releaseMenuTracksAvailabilityAndDispatchesExistingAction() {
        val ready = SessionState(id = "t", snapshot = sessionSnapshot(JSONObject()
            .put("thread", JSONObject().put("id", "t")).put("writerOwner", "relay")))
        val state = mutableStateOf(ready)
        val busy = mutableStateOf(false)
        val blocked = mutableStateOf<String?>(null)
        var action = ""
        compose.setContent { RivoTheme { Surface {
            NativeChatHeader(state.value, null, {}, {}, {}, {}, {}, false, false, false, null, null,
                onWriterAction = { action = it }, writerBusy = busy.value, writerBlockedReason = blocked.value)
        } } }
        compose.onNodeWithTag("conversation_more_button").performClick()
        compose.onNodeWithTag("release_session_permission").assertIsEnabled()
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("release_session_permission").assertIsNotEnabled()
        compose.runOnIdle { busy.value = false; blocked.value = "等待核对" }
        compose.onNodeWithTag("release_session_permission").assertIsNotEnabled()
        compose.runOnIdle { blocked.value = null; state.value = ready.copy(synchronization = SessionSynchronization.pending()) }
        compose.onNodeWithTag("release_session_permission").assertIsNotEnabled()
        compose.runOnIdle { state.value = ready.copy(snapshot = sessionSnapshot(JSONObject()
            .put("thread", JSONObject().put("id", "t")).put("writerOwner", "relay")
            .put("nativeRuntime", JSONObject().put("releaseAvailable", false)))) }
        compose.onNodeWithTag("release_session_permission").assertIsNotEnabled()
        compose.runOnIdle { state.value = ready }
        compose.onNodeWithTag("release_session_permission").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("release", action) }
        compose.onNodeWithTag("release_session_permission").assertDoesNotExist()
    }

    @Test fun takeoverRequiresFreshExternalOwnership() {
        val reason = mutableStateOf<String?>("网络不可用，请恢复节点连接后接管")
        val snapshot = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "t"))
            .put("readOnly", true).put("writerOwner", "external"))
        val stale = mutableStateOf(true)
        var action = ""
        compose.setContent { RivoTheme { Surface {
            NativeWriterStatus(SessionState(id = "t", snapshot = snapshot, synchronization = if (stale.value) SessionSynchronization.pending() else SessionSynchronization.confirmed(), lastConfirmedControl = SessionState(snapshot = snapshot).control()), "节点", false,
                { action = it }, reason.value)
        } } }
        compose.onNodeWithTag("ownership_takeover").assertDoesNotExist()
        compose.runOnIdle { reason.value = null; stale.value = false }
        compose.onNodeWithTag("writer_status_details").assertTextEquals("节点 · 外部 Codex 占用")
        compose.onNodeWithTag("ownership_takeover").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("takeover", action) }
    }
    @Test fun initialLoadAndWritableRefreshNeverFlashTakeover() {
        val state = mutableStateOf(SessionState(id = "t", synchronization = SessionSynchronization.opening()))
        compose.setContent { RivoTheme { Surface {
            NativeWriterStatus(state.value, "节点", false, {})
        } } }
        compose.onNodeWithTag("ownership_takeover").assertDoesNotExist()
        val ready = SessionState(id = "t", snapshot = sessionSnapshot(JSONObject()
            .put("thread", JSONObject().put("id", "t")).put("writerOwner", "relay")))
        compose.runOnIdle { state.value = ready }
        compose.onNodeWithTag("writer_status_details").assertTextEquals("节点 · 可操作").assertIsDisplayed()
        compose.onNodeWithText("释放权限").assertIsEnabled()
        compose.runOnIdle { state.value = ready.copy(synchronization = SessionSynchronization.pending(), lastConfirmedControl = ready.control()) }
        compose.onNodeWithTag("ownership_takeover").assertDoesNotExist()
        compose.onNodeWithText("释放权限").assertDoesNotExist()
        compose.runOnIdle { state.value = ready }
        compose.onNodeWithText("释放权限").assertIsEnabled()
        compose.onNodeWithTag("ownership_takeover").assertDoesNotExist()
        compose.runOnIdle { state.value = SessionState(id = "other", synchronization = SessionSynchronization.opening()) }
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
        val desktopReason = "共享 Desktop 不支持结束进程接管"
        compose.onNodeWithText(desktopReason, substring = true).assertDoesNotExist()
        compose.onNodeWithTag("writer_status_details").assertTextEquals("节点 · 外部 Codex 占用").performClick()
        compose.onNodeWithText(desktopReason, substring = true).assertIsDisplayed()
    }
    @Test fun releasedAndUncertainStatesHaveNoOwnershipButtons() {
        val state = mutableStateOf(SessionState())
        compose.setContent { RivoTheme { Surface {
            NativeWriterStatus(state.value, "节点", false, { error("No action expected") })
        } } }
        val statuses = mapOf("released" to "已释放权限", "release_pending" to "释放待确认",
            "workspace_unavailable" to "工作目录不可用", "ownership_unverified" to "操作权限暂无法核验")
        for ((name, label) in statuses) {
            compose.runOnIdle { state.value = SessionState(id = "t", snapshot = sessionSnapshot(JSONObject()
                .put("thread", JSONObject().put("id", "t")).put("readOnly", true)
                .put("control", JSONObject().put("state", name).put("action", "resume")))) }
            compose.onNodeWithTag("ownership_takeover").assertDoesNotExist()
            compose.onNodeWithTag("writer_action").assertDoesNotExist()
            compose.onNodeWithTag("writer_recheck").assertDoesNotExist()
            compose.onNodeWithText("恢复操作").assertDoesNotExist()
            compose.onNodeWithTag("writer_status_details").assertTextEquals("节点 · $label")
        }
    }

}
