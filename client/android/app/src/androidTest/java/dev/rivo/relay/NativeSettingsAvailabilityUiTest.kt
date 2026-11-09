package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.rivo.relay.codex.SessionSynchronization
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.sessionSnapshot
import dev.rivo.relay.ui.chat.NativeComposerSettings
import dev.rivo.relay.ui.chat.NativeWriterStatus
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NativeSettingsAvailabilityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun emptySessionShowsReasonAndDoesNotReleaseUntilDurable() {
        fun session(available: Boolean) = SessionState(id = "t", snapshot = sessionSnapshot(JSONObject()
            .put("thread", JSONObject().put("id", "t"))
            .put("control", JSONObject().put("state", "writable").put("canOperate", true).put("action", "release"))
            .put("nativeRuntime", JSONObject().put("releaseAvailable", available)
                .put("releaseUnavailableReason", "首条消息尚未持久化，当前会话已保留。"))))
        val state = mutableStateOf(session(false))
        var actions = 0
        compose.setContent { RivoTheme { Surface {
            NativeWriterStatus(state.value, "节点", false, { actions++ })
        } } }
        val releaseReason = "首条消息尚未持久化，当前会话已保留。"
        compose.onNodeWithText(releaseReason).assertDoesNotExist()
        compose.onNodeWithTag("writer_action").assertIsNotEnabled()
        compose.onNodeWithTag("writer_status_details").assertTextEquals("节点 · 可操作").performClick()
        compose.onNodeWithText(releaseReason).assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertEquals(0, actions); state.value = session(true) }
        compose.onNodeWithTag("writer_action").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, actions) }
    }

    @Test fun unverifiedOwnershipHasNoActionButtons() {
        val state = SessionState(id = "t", snapshot = sessionSnapshot(JSONObject()
            .put("thread", JSONObject().put("id", "t")).put("readOnly", true)
            .put("control", JSONObject().put("state", "ownership_unverified").put("canOperate", false)
                .put("unavailableReason", "当前节点暂时无法核验所有权"))))
        var refreshed = 0
        compose.setContent { RivoTheme { Surface {
            NativeWriterStatus(state, "节点", false, { error("handoff must not run") }, onRefresh = { refreshed++ })
        } } }
        compose.onNodeWithText("节点 · 操作权限暂无法核验").assertIsDisplayed()
        compose.onNodeWithTag("ownership_takeover").assertDoesNotExist()
        compose.onNodeWithTag("writer_action").assertDoesNotExist()
        compose.onNodeWithTag("writer_recheck").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, refreshed) }
    }

    @Test fun writerDetailsScrollThroughDistinctReasonsWithoutEnablingBlockedActions() {
        val notice = (1..60).joinToString("\n") { "第 $it 条释放核验记录：仍需确认当前会话的写锁状态。" }
        val blocked = "网络不可用，请恢复节点连接后操作"
        val release = "持锁进程尚未退出，释放尚未完成"
        val ownership = "当前会话仍在等待写锁核验结果"
        val handoff = "节点暂不能核验接管条件，请稍后重试"
        val state = SessionState(id = "t", synchronization = SessionSynchronization.confirmed().copy(notice = notice), snapshot = sessionSnapshot(JSONObject()
            .put("thread", JSONObject().put("id", "t")).put("readOnly", true)
            .put("control", JSONObject().put("state", "release_pending").put("action", "release")
                .put("unavailableReason", ownership))
            .put("nativeRuntime", JSONObject().put("releaseAvailable", false).put("releaseUnavailableReason", release)
                .put("handoffAvailable", false).put("handoffUnavailableReason", handoff))))
        var actions = 0
        compose.setContent { RivoTheme { Surface {
            NativeWriterStatus(state, "节点", false, { actions++ }, blocked)
        } } }
        compose.onNodeWithTag("writer_action").assertDoesNotExist()
        compose.onNodeWithTag("ownership_takeover").assertDoesNotExist()
        val reasons = listOf(notice, blocked, release, ownership)
        reasons.forEach { reason -> compose.onNodeWithText(reason, useUnmergedTree = true).assertDoesNotExist() }
        compose.onNodeWithTag("writer_status_details").assertTextEquals("节点 · 释放待确认").performClick()
        val inDetails = hasAnyAncestor(hasTestTag("writer_runtime_details"))
        reasons.forEach { reason ->
            compose.onAllNodes(hasText(reason) and inDetails, useUnmergedTree = true).assertCountEquals(1)
            compose.onNode(hasText(reason) and inDetails, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("writer_action").assertDoesNotExist()
        compose.onNodeWithTag("ownership_takeover").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun unknownSettingsAreDisplayedWithoutCustomPolicyClaim() {
        compose.setContent { RivoTheme { Surface {
            NativeComposerSettings(JSONObject(), emptyList(), null, null, null, true,
                {}, {}, {}, savesToSession = true, onSaveDefaults = { error("unknown defaults must not save") })
        } } }
        // File access is represented by the unified permission selector.
        compose.onNodeWithTag("composer_sandbox").assertDoesNotExist()
        compose.onNodeWithTag("composer_approval").performScrollTo().assertTextContains("尚未读取审批策略", substring = true)
        compose.onNodeWithText("当前自定义权限").assertDoesNotExist()
        compose.onNodeWithText("当前自定义审批").assertDoesNotExist()
        compose.onNodeWithTag("codex_set_default").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("codex_default_unavailable").performScrollTo().assertTextContains("尚未读取", substring = true)
    }

    @Test fun newSessionCanExplicitlySaveInheritanceWithoutGuessingServiceTier() {
        var saved: JSONObject? = null
        compose.setContent { RivoTheme { Surface {
            NativeComposerSettings(null, emptyList(), null, null, null, true, {}, {}, {}, newSession = true,
                defaultSettings = JSONObject().put("serviceTier", "priority"), onSaveDefaults = { saved = it })
        } } }
        compose.onNodeWithTag("codex_set_default").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(false, saved!!.has("serviceTier"))
            assertEquals(false, saved!!.has("sandbox"))
            assertEquals(false, saved!!.has("approvalPolicy"))
        }
    }
}
