package dev.rivo.relay.ui.settings

import dev.rivo.relay.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val latest = AppRelease("1.0.0", "说明", "", "", false)

    @Test fun loginPromptsOnceAndDismissalSurvivesRepeatedLoginState() = runTest {
        val model = UpdateViewModel()
        var checks = 0
        val check: suspend () -> AppRelease? = { checks++; latest }
        model.onLogin(false, check)
        runCurrent()
        assertEquals(0, checks)
        model.onLogin(true, check)
        runCurrent()
        assertTrue(model.promptVisible)
        model.dismissPrompt()
        model.onLogin(true, check)
        runCurrent()
        assertEquals(1, checks)
        assertFalse(model.promptVisible)
        model.onLogin(false, check)
        model.onLogin(true, check)
        runCurrent()
        assertEquals(2, checks)
        assertTrue(model.promptVisible)
    }

    @Test fun currentVersionAndCheckFailureDoNotPrompt() = runTest {
        val model = UpdateViewModel()
        model.onLogin(true) { null }
        runCurrent()
        assertTrue(model.checked)
        assertFalse(model.promptVisible)
        model.onLogin(false) { null }
        model.onLogin(true) { error("离线") }
        runCurrent()
        assertFalse(model.promptVisible)
        assertEquals("离线", model.error)
    }

    @Test fun logoutWhileCheckingDoesNotShowPrompt() = runTest {
        val model = UpdateViewModel()
        model.onLogin(true) { latest }
        model.onLogin(false) { null }
        runCurrent()
        assertFalse(model.promptVisible)
    }

    @Test fun installRechecksAndConsentCannotSurviveChangedAssessment() = runTest {
        val model = UpdateViewModel()
        val good = NodeCompatibility("1", "在线节点", CompatibilityState.COMPATIBLE, "兼容")
        val bad = NodeCompatibility("2", "旧节点", CompatibilityState.INCOMPATIBLE, "等待节点升级")
        val partial = UpdateCompatibility(listOf(good, bad))
        assertFalse(model.installationAllowed(latest, null) { partial })
        assertNotNull(model.confirmation)
        assertTrue(model.installationAllowed(latest, partial.confirmationKey(latest)) { partial })
        assertFalse(model.installationAllowed(latest, partial.confirmationKey(latest)) { UpdateCompatibility(listOf(bad)) })
        assertTrue(model.error!!.contains("等待节点升级"))
        val changed = partial.copy(nodes=listOf(good, bad.copy(state=CompatibilityState.UNKNOWN, reason="离线")))
        assertFalse(model.installationAllowed(latest, partial.confirmationKey(latest)) { changed })
        assertEquals(changed, model.confirmation)
    }
}
