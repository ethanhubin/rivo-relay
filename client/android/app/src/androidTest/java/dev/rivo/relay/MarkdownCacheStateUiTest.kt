package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.ui.chat.MessageContent
import dev.rivo.relay.ui.theme.RivoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownCacheStateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun streamingCompletionReplacementAndSessionSwitchKeepLatestText() {
        val text = mutableStateOf("stream start")
        val retain = mutableStateOf(false)
        val session = mutableStateOf("one")
        compose.setContent {
            RivoTheme {
                key(session.value) {
                    LazyColumn(Modifier.fillMaxSize().testTag("history")) {
                        item { MessageContent(text.value, cacheBlocks = retain.value) }
                    }
                }
            }
        }
        compose.onNodeWithTag("message_text").assertTextContains("stream start")
        compose.runOnIdle { text.value = "stream start extended" }
        compose.onNodeWithTag("message_text").assertTextContains("stream start extended")
        // Completion changes only retention, not text.
        compose.runOnIdle { retain.value = true }
        compose.onNodeWithTag("message_text").assertTextContains("stream start extended")
        // Resume and replace, rather than append to, the existing text.
        compose.runOnIdle { retain.value = false; text.value = "replacement" }
        compose.onNodeWithTag("message_text").assertTextContains("replacement")
        compose.onNodeWithText("stream start extended").assertDoesNotExist()
        compose.runOnIdle { session.value = "two"; text.value = "other session"; retain.value = true }
        compose.onNodeWithTag("message_text").assertTextContains("other session")
        compose.onNodeWithText("replacement").assertDoesNotExist()
    }
}
