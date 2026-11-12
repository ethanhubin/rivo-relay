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

    @Test fun installBlocksUncertainCurrentNodeWithoutCrossNodeConfirmation() = runTest {
        val model = UpdateViewModel()
        val good = NodeCompatibility(CompatibilityState.COMPATIBLE, "兼容")
        val bad = NodeCompatibility(CompatibilityState.INCOMPATIBLE, "等待节点升级")
        assertTrue(model.installationAllowed { UpdateCompatibility(good) })
        assertFalse(model.installationAllowed { UpdateCompatibility(bad) })
        assertEquals(UpdateCompatibility(bad).summary, model.error)
    }
}
