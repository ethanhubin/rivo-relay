package dev.rivo.relay

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.ui.chat.*
import dev.rivo.relay.ui.theme.RivoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CodexVoiceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun microphoneIsLeftOfDraftAndUnavailableServiceDoesNotLoseInput() {
        var text by mutableStateOf("已有草稿")
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            MessageComposer(text, { text = it }, emptyList(), {}, emptyList(), {}, false, null, true, true, false, false, false,
                {}, {}, {}, {}, {})
        } } } }
        compose.onNodeWithTag("voice_input_button").assertIsNotEnabled()
        val mic = compose.onNodeWithTag("voice_input_button").fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithTag("message_input").fetchSemanticsNode().boundsInRoot
        assertTrue(mic.right <= field.left)
        compose.onNodeWithTag("message_input").assertTextContains("已有草稿")
        compose.onNodeWithTag("message_input").performTextInput("补充")
        assertEquals("已有草稿补充", text)
        compose.onNodeWithTag("send_message").assertIsEnabled()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        android.os.SystemClock.sleep(250)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(compose.activity.getExternalFilesDir(null), "verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { image -> File(folder, "codex-voice-composer.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle() }
    }
    @Test fun activeMicrophoneRemainsCancellableWhenAvailabilityChanges() {
        var recording by mutableStateOf(true)
        var transcribing by mutableStateOf(false)
        var taps = 0
        compose.setContent { RivoTheme { VoiceInputButton(recording, transcribing, null, false, "连接断开", .5f, { taps++ }) } }
        compose.onNodeWithContentDescription("停止录音").assertIsEnabled().performClick()
        compose.runOnIdle { recording = false; transcribing = true }
        compose.onNodeWithContentDescription("取消正在转写的语音").assertIsEnabled().performClick()
        assertEquals(2, taps)
    }
}
