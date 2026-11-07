package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.ui.chat.*
import dev.rivo.relay.ui.theme.RivoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CodexVideoUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun nativeSourceSurvivesCachedPlaybackAndSpeedMenu() {
        val target = File(compose.activity.cacheDir, "video-fixture.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("media-fixture.mp4").use { input -> target.outputStream().use { input.copyTo(it) } }
        var forwarded: CodexLinkAttachment? = null
        val video = VideoViewerSource(listOf(VideoViewerItem(null, target.toURI().toString(), source = "/native/demo.mp4", displayName = "demo.mp4", mimeType = "video/mp4")), 0, Rect.Zero)
        compose.mainClock.autoAdvance = false
        compose.setContent { RivoTheme { CompositionLocalProvider(LocalCodexLinkForwarder provides { forwarded = it }) {
            FullScreenVideo(video, {}, {}, onShare = {})
        } } }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithTag("fullscreen_video_player").assertExists()
        compose.onNodeWithTag("fullscreen_video_more").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("fullscreen_video_speed").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("fullscreen_video_speed_menu").assertExists()
        compose.onNodeWithTag("fullscreen_video_speed_2.0").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("fullscreen_video_more").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.advanceTimeBy(300)
        android.os.SystemClock.sleep(250)
        val folder = File(compose.activity.getExternalFilesDir(null), "verification").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            File(folder, "codex-video-controls.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
        compose.onNodeWithTag("fullscreen_video_forward_codex").performClick()
        compose.runOnIdle { assertEquals("/native/demo.mp4", forwarded?.source) }
    }
}
