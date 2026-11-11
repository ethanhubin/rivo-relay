package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.settings.SettingsScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SpeechSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun saveReopenAndClearVocabularyPersistAcrossPreferenceInstances() {
        val preferences = SpeechPreferences(compose.activity)
        val original = preferences.vocabulary
        val visible = mutableStateOf(true)
        try {
            preferences.vocabulary = "默认测试词"
            compose.setContent { RivoTheme { Surface { if (visible.value) SettingsScreen() } } }
            compose.onNodeWithTag("speech_vocabulary").assertDoesNotExist()
            compose.onNodeWithTag("edit_speech_settings").performScrollTo().performClick()
            compose.onNodeWithTag("speech_vocabulary").performTextReplacement("ComfyUI、LoRA")
            compose.onNodeWithTag("save_speech_vocabulary").performClick()
            compose.onNodeWithTag("speech_vocabulary_saved").assertExists()
            compose.runOnIdle { assertEquals("ComfyUI、LoRA", SpeechPreferences(compose.activity).vocabulary); visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { visible.value = true }
            compose.onNodeWithTag("speech_vocabulary").assertDoesNotExist()
            compose.onNodeWithTag("edit_speech_settings").performScrollTo().performClick()
            compose.onNodeWithTag("speech_vocabulary").assertTextContains("ComfyUI、LoRA")
            compose.onNodeWithTag("clear_speech_vocabulary").performClick()
            compose.onNodeWithTag("save_speech_vocabulary").performClick()
            compose.runOnIdle { assertEquals("", SpeechPreferences(compose.activity).vocabulary) }
            compose.onNodeWithTag("speech_vocabulary").performTextReplacement("词".repeat(396))
            compose.onNodeWithTag("save_speech_vocabulary").assertIsNotEnabled()
            compose.runOnIdle { assertEquals("", SpeechPreferences(compose.activity).vocabulary) }
            compose.onNodeWithTag("close_speech_settings").performClick()
            compose.onNodeWithTag("speech_vocabulary").assertDoesNotExist()
            compose.onNodeWithTag("edit_speech_settings").performScrollTo().performClick()
            compose.onNodeWithTag("save_speech_vocabulary").assertIsNotEnabled()
        } finally { preferences.vocabulary = original }
    }
}
