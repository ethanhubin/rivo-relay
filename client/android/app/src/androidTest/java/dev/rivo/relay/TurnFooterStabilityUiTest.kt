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
        compose.onNodeWithTag("turn_duration_t").assertTextContains("已用时", substring = true)
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
    @Test fun previousRoundKeepsTimingWithoutCompletedLabelWhenNextRoundStarts() {
        val started = System.currentTimeMillis() / 1000 - 100
        val first = NativeTurnSettings("first", status = "completed", startedAt = started, completedAt = started + 63, durationMs = 63000)
        val next = NativeTurnSettings("next", status = "inProgress", startedAt = started + 70)
        val messages = listOf(NativeMessage("a", "first", "agentMessage", "第一轮回答", JSONObject()),
            NativeMessage("tool-first", "first", "commandExecution", "", JSONObject().put("command", "false").put("status", "completed").put("exitCode", 1), activity = NativeActivity("command", "命令执行", "false", "", "completed", exitCode = 1)),
            NativeMessage("b", "next", "agentMessage", "第二轮回答", JSONObject()),
            NativeMessage("tool-next", "next", "commandExecution", "", JSONObject().put("command", "true").put("status", "completed").put("exitCode", 0), activity = NativeActivity("command", "命令执行", "true", "", "completed", exitCode = 0)))
        val live = SessionSnapshot("history", "历史用时", "running", "next", messages, emptyList(), JSONObject(), turnSettings = listOf(first, next))
        val state = mutableStateOf(SessionState(id = "history", snapshot = live, activityStatus = "running"))
        compose.mainClock.autoAdvance = false
        compose.setContent { RivoTheme { Box(Modifier.systemBarsPadding()) {
            ChatScreen(state.value, false, false, emptyList(), {}, onSend = { _, _, _, _ -> },
                onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() })
        } } }
        compose.mainClock.advanceTimeBy(64)
        compose.onAllNodesWithTag("historical_turn_timing_first").assertCountEquals(1)
        compose.onNodeWithTag("historical_turn_timing_first").assertIsDisplayed().assertTextContains("耗时 1m 3s", substring = true)
        assertTrue(!compose.onNodeWithTag("historical_turn_timing_first").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString().contains("已完成"))
        val timingBounds = compose.onNodeWithTag("historical_turn_timing_first").fetchSemanticsNode().boundsInRoot
        val copyBounds = compose.onNodeWithTag("assistant_copy_first:a").fetchSemanticsNode().boundsInRoot
        assertTrue(timingBounds.top >= copyBounds.bottom)
        assertTrue(timingBounds.top >= compose.onNodeWithText("运行了 1 条命令 · 失败 1").fetchSemanticsNode().boundsInRoot.bottom)
        compose.onAllNodesWithTag("message_output_time")[0].assertTextContains("-", substring = true)
        compose.onNodeWithTag("turn_duration_next").assertIsDisplayed()
        compose.onNodeWithTag("turn_duration_spinner").assertIsDisplayed()
        compose.onNodeWithTag("session_completion").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(snapshot = live.copy(status = "idle", activeTurnId = "",
            turnSettings = listOf(first, next.copy(status = "completed", completedAt = started + 90, durationMs = 20000)))) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("historical_turn_timing_first").assertIsDisplayed()
        compose.onNodeWithTag("session_completion").assertIsDisplayed().assertTextContains("已完成", substring = true)
        val completedBounds = compose.onNodeWithTag("session_completion").fetchSemanticsNode().boundsInRoot
        val completedCopyBounds = compose.onNodeWithTag("assistant_copy_next:b").fetchSemanticsNode().boundsInRoot
        assertTrue(completedBounds.top >= completedCopyBounds.bottom)
        assertTrue(completedBounds.top >= compose.onNodeWithText("运行了 1 条命令").fetchSemanticsNode().boundsInRoot.bottom)
        compose.onNodeWithTag("session_completion").assertTextContains("-", substring = true)
        compose.onNodeWithTag("historical_turn_timing_next").assertDoesNotExist()
        compose.onNodeWithTag("turn_duration_spinner").assertDoesNotExist()
        // Reopen the same native history with a new conversation-owned display cache.
        val completed = state.value
        compose.runOnIdle { state.value = SessionState(id = "other") }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { state.value = completed }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("historical_turn_timing_first").assertIsDisplayed().assertTextContains("耗时 1m 3s", substring = true)
        compose.onAllNodesWithTag("session_completion").assertCountEquals(1)
    }

    @Test fun interruptedContinuationNeverKeepsOldDurationBelowNewAnswer() {
        val start = System.currentTimeMillis() / 1000 - 600
        val old = NativeTurnSettings("old", status = "interrupted", startedAt = start, completedAt = start + 111, durationMs = 111000)
        val resumed = NativeTurnSettings("resumed", status = "completed", startedAt = start + 400, completedAt = start + 421, durationMs = 21000)
        val oldReply = NativeMessage("a", "old", "agentMessage", "中断前回答", JSONObject())
        val newReply = NativeMessage("b", "resumed", "agentMessage", "继续后回答", JSONObject())
        val stopped = SessionSnapshot("resume", "继续计时", "idle", "", listOf(oldReply), emptyList(), JSONObject(), turnSettings = listOf(old))
        val state = mutableStateOf(SessionState(id = "resume", snapshot = stopped))
        compose.mainClock.autoAdvance = false
        compose.setContent { RivoTheme { Box(Modifier.systemBarsPadding()) {
            ChatScreen(state.value, false, false, emptyList(), {}, onSend = { _, _, _, _ -> },
                onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() })
        } } }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("session_completion").assertTextContains("已中断", substring = true)
        // Reconnect can deliver the new final answer before the activity ID converges.
        compose.runOnIdle { state.value = state.value.copy(snapshot = stopped.copy(activeTurnId = "old",
            messages = listOf(oldReply, newReply), turnSettings = listOf(old, resumed)))
            .transition(SessionSyncEvent.StreamInterrupted) }
        compose.mainClock.advanceTimeBy(64)
        compose.onAllNodesWithTag("session_completion").assertCountEquals(1)
        compose.onNodeWithTag("session_completion").assertIsDisplayed().assertTextContains("耗时 21s", substring = true)
        compose.onNodeWithTag("historical_turn_timing_old").assertIsDisplayed().assertTextContains("耗时 1m 51s", substring = true)
        compose.onNodeWithTag("historical_turn_timing_resumed").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(snapshot = state.value.snapshot!!.copy(activeTurnId = ""))
            .transition(SessionSyncEvent.ReadSucceeded) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("session_completion").assertTextContains("已完成", substring = true).assertTextContains("耗时 21s", substring = true)
    }

    @Test fun localSecondsKeepAdvancingDuringLongSynchronization() {
        var ticks = 1000L
        val footer = mutableStateOf(NativeTurnFooter(NativeTurnSettings("clock", status = "inProgress"),
            observedAt = 1, clientStartedAtMillis = 1000))
        compose.mainClock.autoAdvance = false
        compose.setContent { RivoTheme {
            dev.rivo.relay.ui.chat.NativeTurnDuration(footer.value, elapsedRealtime = { ticks })
        } }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { footer.value = footer.value.copy(synchronizing = true); ticks = 6000 }
        compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithTag("turn_duration_clock").assertTextEquals("已用时 5 秒")
        compose.runOnIdle { ticks = 66000 }
        compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithTag("turn_duration_clock").assertTextEquals("已用时 1 分5 秒")
        compose.runOnIdle { footer.value = footer.value.copy(label = "已完成 耗时 1m 5s"); ticks = 126000 }
        compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithTag("session_completion").assertTextEquals("已完成 耗时 1m 5s")
    }

}
