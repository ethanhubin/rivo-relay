package dev.rivo.relay

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import dev.rivo.relay.ui.login.LoginScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SavedNodesUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun loginOnlyOffersPairingAndRetainsManualAddress() {
        var submitted = ""
        compose.setContent { RivoTheme {
            LoginScreen(false, null, { address, _ -> submitted = address }, {})
        } }
        compose.onNodeWithTag("scan_connect").assertExists()
        compose.onNodeWithTag("photo_connect").assertExists()
        compose.onNodeWithTag("login_settings").assertDoesNotExist()
        compose.onNodeWithTag("login_back").assertDoesNotExist()
        compose.onNodeWithText("连接 / 重试").assertDoesNotExist()
        compose.onNodeWithText("重新授权").assertDoesNotExist()
        compose.onNodeWithTag("manual_entry").performScrollTo().performClick()
        compose.onNodeWithTag("pairing_address").performTextInput("https://b.example")
        compose.onNodeWithTag("pairing_code").performTextInput("fresh-code")
        compose.onNodeWithTag("manual_connect").performClick()
        assertEquals("https://b.example", submitted)
        compose.onNodeWithTag("manual_entry").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("pairing_address").assertTextContains("https://b.example")
        assertEquals("", compose.onNodeWithTag("pairing_code").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    @Test fun addNodeOffersBackWithoutSubmittingPairing() {
        var returns = 0
        var submissions = 0
        compose.setContent { RivoTheme {
            LoginScreen(false, null, { _, _ -> submissions++ }, {}, onBack = { returns++ })
        } }
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, returns)
        assertEquals(0, submissions)
    }

}
