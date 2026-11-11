package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.ConversationDraft
import dev.rivo.relay.data.string
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NewSessionDefaultsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sol = JSONObject("""{"model":"gpt-6.1-sol","displayName":"GPT-6.1 Sol","isDefault":true,
        "defaultReasoningEffort":"high","supportedReasoningEfforts":[{"reasoningEffort":"high"}]}""")

    @Test fun catalogLoadedAfterOpeningIsDisplayedSavedAndSubmittedWithoutManualSelection() {
        val models = mutableStateOf(emptyList<JSONObject>())
        val defaults = mutableStateOf<JSONObject?>(null)
        var draft: ConversationDraft? = null
        var submittedModel: String? = null
        var submittedEffort: String? = null
        var submissions = 0
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            ChatScreen(SessionState(id = "pending:default"), false, false, models.value, {},
                onSend = { _, _, _, _ -> }, onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {},
                onEarlier = {}, readFile = { JSONObject() }, pendingTitle = "测试项目",
                onPersistDraft = { _, value -> draft = value },
                onConfiguredSend = { _, model, effort, _, _, _, _ -> submissions++; submittedModel = model; submittedEffort = effort },
                defaultSettings = defaults.value, onSaveDefaults = { defaults.value = it })
        } } }
        compose.onNodeWithTag("new_session_model").assertTextContains("沿用节点默认")
        compose.runOnIdle { models.value = listOf(sol) }
        compose.onNodeWithTag("new_session_model").assertTextContains("GPT-6.1 Sol")
        compose.onNodeWithTag("new_session_effort").assertTextContains("high")
        compose.onNodeWithTag("new_session_set_default").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("new_session_set_default").assertTextEquals("已是默认").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals("gpt-6.1-sol", defaults.value!!.string("model"))
            assertEquals("high", defaults.value!!.string("effort"))
            assertEquals("gpt-6.1-sol", draft!!.model)
            assertEquals("high", draft!!.effort)
        }
        compose.onNodeWithTag("message_input").performTextInput("检查项目")
        // Invoke the button action independently of the soft keyboard's resize animation.
        compose.onNodeWithTag("send_message").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(1, submissions)
            assertEquals("gpt-6.1-sol", submittedModel)
            assertEquals("high", submittedEffort)
        }
    }

    @Test fun savedDefaultsOverrideCatalogAndInlineButtonSavesPermissionChanges() {
        val defaults = mutableStateOf(JSONObject().put("model", "gpt-6-astra").put("effort", "medium")
            .put("serviceTier", "priority").put("approvalPolicy", "preset-request"))
        var submittedModel: String? = null
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            ChatScreen(SessionState(id = "pending:saved"), false, false, listOf(sol), {},
                onSend = { _, _, _, _ -> }, onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {},
                onEarlier = {}, readFile = { JSONObject() }, pendingTitle = "测试项目",
                onConfiguredSend = { _, model, _, _, _, _, _ -> submittedModel = model },
                defaultSettings = defaults.value, onSaveDefaults = { defaults.value = it })
        } } }
        compose.onNodeWithTag("new_session_model").assertTextContains("gpt-6-astra")
        compose.onNodeWithTag("new_session_set_default").assertIsNotEnabled()
        compose.onNodeWithTag("new_session_approval").performScrollTo().performClick()
        compose.onNodeWithTag("new_session_approval_preset-auto").performClick()
        compose.onNodeWithTag("new_session_set_default").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("gpt-6-astra", defaults.value.string("model"))
            assertEquals("medium", defaults.value.string("effort"))
            assertEquals("priority", defaults.value.string("serviceTier"))
            assertEquals("preset-auto", defaults.value.string("approvalPolicy"))
            assertFalse(defaults.value.has("sandbox"))
        }
        compose.onNodeWithTag("message_input").performTextInput("检查项目")
        compose.onNodeWithTag("send_message").performClick()
        compose.runOnIdle { assertEquals("gpt-6-astra", submittedModel) }
    }
}
