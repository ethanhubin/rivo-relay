package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.rivo.relay.codex.*
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TurnFooterStabilityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun footerSurvivesWaitingRevalidationMissingTimingAndCompletion() {
        val started = System.currentTimeMillis() / 1000 - 60
        val turn = NativeTurnSettings("t", status = "inProgress", startedAt = started)
        val reply = NativeMessage("a", "t", "agentMessage", "正在检查文件", JSONObject())
        val live = SessionSnapshot("s", "计时稳定性", "running", "t", listOf(reply), emptyList(), JSONObject(), turnSettings = listOf(turn))
        val state = mutableStateOf(SessionState(id = "s", snapshot = live, activityStatus = "running"))
        compose.mainClock.autoAdvance = false
        compose.setContent { RivoTheme { Box(Modifier.systemBarsPadding()) {
            ChatScreen(state.value, false, false, emptyList(), {}, onSend = { _, _, _, _ -> },
                onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() })
        } } }
        fun visible() {
            compose.mainClock.advanceTimeBy(64)
            compose.onAllNodesWithTag("turn_duration_t").assertCountEquals(1)
            compose.onNodeWithTag("turn_duration_t").assertIsDisplayed()
        }
        visible()
        for (activity in listOf("waiting", "unknown", "idle", "running")) {
            compose.runOnIdle { state.value = state.value.copy(activityStatus = activity) }
            visible()
        }
        compose.runOnIdle { state.value = state.value.transition(SessionSyncEvent.StreamInterrupted) }
        visible()
        compose.onNodeWithTag("turn_duration_t").assertTextContains("同步中", substring = true)
        compose.runOnIdle { state.value = state.value.copy(snapshot = live.copy(activeTurnId = "", turnSettings = listOf(turn.copy(startedAt = null))))
            .transition(SessionSyncEvent.ReadSucceeded) }
        visible()
        compose.onNodeWithTag("turn_duration_t").assertTextContains("已用时", substring = true)
        compose.runOnIdle { state.value = state.value.copy(snapshot = live.copy(status = "idle", activeTurnId = "",
            turnSettings = listOf(turn.copy(status = "completed", completedAt = started + 63, durationMs = 63000)))) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("turn_duration_t").assertDoesNotExist()
        compose.onAllNodesWithTag("session_completion").assertCountEquals(1)
        compose.onNodeWithTag("session_completion").assertIsDisplayed().assertTextContains("耗时 1m 3s", substring = true)
        assertTrue(compose.onNodeWithTag("session_completion").fetchSemanticsNode().boundsInRoot.top >=
            compose.onNodeWithText("正在检查文件").fetchSemanticsNode().boundsInRoot.bottom)
        compose.runOnIdle { state.value = state.value.transition(SessionSyncEvent.HistoryInvalidated) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("session_completion").assertIsDisplayed().assertTextContains("耗时 1m 3s", substring = true)
        // Consecutive null snapshots must not reuse the previous conversation's projection.
        compose.runOnIdle { state.value = state.value.copy(snapshot = null) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("session_completion").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(id = "another-conversation") }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("session_completion").assertDoesNotExist()
        compose.onNodeWithTag("turn_duration_t").assertDoesNotExist()
    }
}
