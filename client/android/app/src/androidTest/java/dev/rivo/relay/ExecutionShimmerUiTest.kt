package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExecutionShimmerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun onlyLiveExecutionShimmersAndStopsForCompletionOfflineOrApproval() {
        val snapshot = SessionSnapshot("thread", "扫光验证", "running", "turn", emptyList(), emptyList(), JSONObject())
        val state = mutableStateOf(SessionState(id = snapshot.id, snapshot = snapshot))
        // The Compose test InfiniteAnimationPolicy cancels infinite effects while
        // autoAdvance is enabled. Control the clock before creating the effect.
        compose.mainClock.autoAdvance = false
        compose.setContent { RivoTheme { Box(Modifier.systemBarsPadding()) {
            ChatScreen(state.value, false, false, emptyList(), {}, onSend = { _, _, _, _ -> },
                onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() })
        } } }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("execution_stage_shimmer").assertIsDisplayed()
        compose.mainClock.advanceTimeByFrame()
        val first = compose.onNodeWithTag("execution_stage_shimmer").captureToImage().asAndroidBitmap()
        // The gradient spends part of its cycle outside the glyphs. Comparing just
        // two arbitrary frames can sample that same flat colour on both frames.
        var changed = false
        repeat(10) {
            compose.mainClock.advanceTimeBy(160)
            val frame = compose.onNodeWithTag("execution_stage_shimmer").captureToImage().asAndroidBitmap()
            changed = changed || !first.sameAs(frame)
            frame.recycle()
        }
        first.recycle()
        assertFalse("一个完整扫光周期应改变文字像素", !changed)
        val raw = JSONObject().put("type", "commandExecution").put("command", "rg --files").put("status", "inProgress")
        val activity = NativeMessage("command", "turn", "commandExecution", "rg --files", raw,
            activity = nativeActivity(raw))
        compose.runOnIdle { state.value = state.value.copy(snapshot = snapshot.copy(messages = listOf(activity))) }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val evidence = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(evidence, "shimmer-layout.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        // The activity row is clickable and merges its children's semantics.
        // Inspect the text node itself so both presence and absence checks are real.
        compose.onNodeWithTag("activity_group_shimmer", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("execution_stage_shimmer").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(stale = true) }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithTag("activity_group_shimmer", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("activity_group_summary", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(stale = false,
            snapshot = snapshot.copy(status = "idle", activeTurnId = "", messages = listOf(activity))) }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithTag("activity_group_shimmer", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("execution_stage_shimmer").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(snapshot = snapshot.copy(requests = listOf(NativeRequest("request", "unknown", JSONObject())))) }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithTag("execution_stage_shimmer").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }
}
