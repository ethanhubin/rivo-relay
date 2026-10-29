package dev.rivo.relay

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import dev.rivo.relay.connection.SavedNode
import dev.rivo.relay.ui.login.LoginScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SavedNodesUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun savedNodeCanReconnectOrPrefillAuthorizationWithoutLosingAddress() {
        var selected = ""
        var submitted = ""
        compose.setContent { RivoTheme {
            LoginScreen(false, null, { address, _ -> submitted = address }, {},
                listOf(SavedNode("https://a.example", "Node A", true), SavedNode("https://b.example", "Node B", false)),
                { selected = it })
        } }
        compose.onNodeWithTag("saved_node_https://a.example").performScrollTo().performClick()
        assertEquals("https://a.example", selected)
        compose.onNodeWithTag("saved_node_https://b.example").performScrollTo().performClick()
        compose.onNodeWithTag("pairing_address").assertTextContains("https://b.example")
        compose.onNodeWithTag("pairing_code").performTextInput("fresh-code")
        compose.onNodeWithTag("manual_connect").performClick()
        assertEquals("https://b.example", submitted)
        compose.onNodeWithTag("manual_entry").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("pairing_address").assertTextContains("https://b.example")
        assertEquals("", compose.onNodeWithTag("pairing_code").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
}
