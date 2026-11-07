package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rivo.relay.ui.chat.*
import dev.rivo.relay.ui.theme.RivoTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Silent generated PCM fixtures; never opens a node, microphone or user file. */
class CodexAudioUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class PreviewLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun wav(name: String): File {
        val sampleRate = 16000
        val dataSize = sampleRate * 2 * 12
        val output = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize)
        output.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        output.putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
        output.putShort(2).putShort(16).put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
        return File(compose.activity.cacheDir, name).apply { writeBytes(output.array()) }
    }

    private fun awaitPlaying(playing: Boolean) {
        val label = if (playing) "暂停" else "播放"
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("audio_play_pause").assertIsEnabled()
    }

    @Test fun autoPlayPauseSeekBackgroundAndActionsKeepNativeSource() {
        val file = wav("codex-audio-controls.wav")
        val visible = mutableStateOf(true)
        val actions = mutableListOf<FileAction>()
        var forwarded: CodexLinkAttachment? = null
        lateinit var owner: PreviewLifecycle
        compose.runOnUiThread {
            owner = PreviewLifecycle()
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        try {
            compose.setContent { RivoTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner,
                    LocalCodexLinkForwarder provides { forwarded = it }) {
                    if (visible.value) MediaFilePreview("fixture.wav", false, file, null, {},
                        { actions += it }, { visible.value = false }, source = "/native/fixture.wav")
                }
            } }
            awaitPlaying(true)
            compose.onNodeWithTag("audio_play_pause").performClick()
            awaitPlaying(false)
            compose.onNodeWithTag("audio_progress").performSemanticsAction(SemanticsActions.SetProgress) { it(3000f) }
            compose.waitUntil(5_000) {
                compose.onNodeWithTag("audio_progress").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current >= 2900f
            }
            compose.onNodeWithTag("audio_play_pause").performClick()
            awaitPlaying(true)
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
            awaitPlaying(false)
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.onNodeWithContentDescription("播放").assertExists()
            compose.onNodeWithText("分享").performClick()
            compose.onNodeWithText("保存到本机").performClick()
            compose.runOnIdle { assertEquals(listOf(FileAction.SHARE, FileAction.SAVE), actions) }
            compose.onNodeWithTag("forward_link_to_codex").performClick()
            compose.runOnIdle { assertEquals("/native/fixture.wav", forwarded?.source) }
            compose.onNodeWithTag("audio_preview").assertDoesNotExist()
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            file.delete()
        }
    }

    @Test fun decodeFailureCanRetryWithFreshFileAndClose() {
        val invalid = File(compose.activity.cacheDir, "codex-audio-invalid.wav").apply { writeText("not a media container") }
        val valid = wav("codex-audio-retry.wav")
        val selected = mutableStateOf(invalid)
        val visible = mutableStateOf(true)
        var retries = 0
        try {
            compose.setContent { RivoTheme {
                if (visible.value) MediaFilePreview("fixture.wav", false, selected.value, null,
                    { retries++; selected.value = valid }, {}, { visible.value = false })
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("audio_retry").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("用其他应用打开").assertExists()
            compose.onNodeWithTag("audio_retry").performClick()
            awaitPlaying(true)
            compose.runOnIdle { assertEquals(1, retries) }
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("audio_preview").assertDoesNotExist()
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            invalid.delete(); valid.delete()
        }
    }
}
