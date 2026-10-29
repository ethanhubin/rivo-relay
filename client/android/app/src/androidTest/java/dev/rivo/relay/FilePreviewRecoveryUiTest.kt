package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.ui.chat.FilePreviewDialog
import dev.rivo.relay.ui.theme.RivoTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FilePreviewRecoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedMediaRetryHidesTheOldPlayerWhileTheNewFileLoads() {
        val fixtureName = "retry-fixture-${UUID.randomUUID()}.mp3"
        var attempts by mutableIntStateOf(0)
        var visible by mutableStateOf(true)
        try {
            compose.setContent { RivoTheme {
                if (visible) FilePreviewDialog("/project/$fixtureName", { error("Media uses cached transfer") }, { _, output, _ ->
                    attempts++
                    if (attempts == 1) output.write("invalid media fixture".toByteArray()) else awaitCancellation()
                }) { visible = false }
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("重试").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("重试").performClick()
            compose.waitUntil(5_000) { attempts >= 2 }
            compose.onNodeWithText("正在载入媒体…").assertIsDisplayed()
            compose.onNodeWithContentDescription("播放").assertDoesNotExist()
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            java.io.File(compose.activity.cacheDir, "previews").listFiles()?.filter { it.name.endsWith(fixtureName) }?.forEach { it.delete() }
        }
    }
}
