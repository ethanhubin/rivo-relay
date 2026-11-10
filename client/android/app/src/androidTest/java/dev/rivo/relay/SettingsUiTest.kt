package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.data.AppRelease
import dev.rivo.relay.ui.settings.SettingsScreen
import dev.rivo.relay.ui.theme.RivoTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun foregroundRecoveryIsSilentAndSettingsCanReconnectAfterExhaustion() {
        val available = java.util.concurrent.atomic.AtomicBoolean(true)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!available.get()) return MockResponse().setResponseCode(503).setBody("{}")
                return MockResponse().setBody(if (request.path == "/v1/pair")
                    """{"node":{"name":"重连测试","id":"reconnect-fixture"},"deviceId":"fixture","token":"test-only"}"""
                    else """{"projects":[],"todos":[],"models":[],"connected":true}""")
            }
        }
        server.start()
        val address = server.url("/").toString()
        val store = ViewModelStore()
        lateinit var vm: AppViewModel
        try {
            compose.runOnUiThread {
                vm = AppViewModel(compose.activity.application)
                store.put("reconnect-test", vm)
                vm.pair(address, "fixture-code")
            }
            compose.setContent { RivoTheme { RelayApp(vm) } }
            compose.waitUntil(10_000) { vm.state.value.node?.connection?.base?.trimEnd('/') == address.trimEnd('/') }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav_settings").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("nav_settings").performClick()
            fun foreground() { compose.runOnUiThread { vm.onBackground(); vm.onForeground() } }
            fun awaitState(expected: dev.rivo.relay.data.RecoveryState) {
                compose.waitUntil(10_000) { vm.state.value.node?.recovery?.state?.value == expected }
            }
            available.set(false); foreground()
            awaitState(dev.rivo.relay.data.RecoveryState.DISCONNECTED)
            compose.onNodeWithTag("app_error").assertDoesNotExist()
            compose.onNodeWithTag("bottom_notice_panel").assertDoesNotExist()
            compose.onNodeWithTag("settings_refresh").assertIsEnabled()
            available.set(true); foreground()
            awaitState(dev.rivo.relay.data.RecoveryState.CONNECTED)
            available.set(false); foreground()
            awaitState(dev.rivo.relay.data.RecoveryState.DISCONNECTED)
            available.set(true)
            compose.onNodeWithTag("settings_refresh").performClick()
            awaitState(dev.rivo.relay.data.RecoveryState.CONNECTED)
            compose.onNodeWithTag("app_error").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { store.clear() }
            server.shutdown()
        }
    }

    @Test fun bottomSettingsNavigationAndRestore() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                if (request.path == "/v1/pair") """{"node":{"name":"测试节点","id":"settings-fixture"},"deviceId":"fixture","token":"test-only"}"""
                else """{"projects":[],"todos":[],"models":[],"connected":true}""")
        }
        server.start()
        val address = server.url("/").toString()
        val store = ViewModelStore()
        lateinit var vm: AppViewModel
        try {
            compose.runOnUiThread {
                vm = AppViewModel(compose.activity.application)
                store.put("settings-test", vm)
                vm.pair(address, "fixture-code")
            }
            compose.setContent { RivoTheme { RelayApp(vm) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav_settings").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("nav_settings").performClick().assertIsSelected()
            compose.onNodeWithTag("nav_codex").assertIsNotSelected()
            compose.onNodeWithTag("settings_screen").assertIsDisplayed()
            compose.onNodeWithTag("check_updates").assertIsDisplayed()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val folder = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(folder, "settings-0.2.11.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.onNodeWithTag("nav_todos").performClick().assertIsSelected()
            compose.onNodeWithTag("nav_settings").performClick().assertIsSelected()
            compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { store.clear() }
            server.shutdown()
        }
    }

    @Test fun checkDisablesDuplicateTapAndShowsDownloadAndNotes() {
        val result = CompletableDeferred<AppRelease?>()
        var calls = 0
        var opened = ""
        val download = "https://updates.example.test/rivo-relay-0.2.12.apk"
        compose.setContent { RivoTheme { Surface { Box(Modifier.systemBarsPadding()) {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened = uri }
            }) {
                SettingsScreen(checkUpdate = { calls++; result.await() }, downloadAndInstall = { opened = it.apkUrl })
            }
        } } } }
        compose.onNodeWithTag("check_updates").performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls); result.complete(AppRelease("0.2.12", "新增设置页", download, "https://updates.example.test", true)) }
        compose.onNodeWithTag("update_available").assertTextContains("0.2.12", substring = true)
        compose.onNodeWithTag("download_update").performClick()
        compose.runOnIdle { assertEquals(download, opened) }
        compose.onNodeWithText("查看更新说明").performClick()
        compose.onNodeWithText("新增设置页").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
    }
    @Test fun downloadSurvivesLeavingSettings() {
        val visible = androidx.compose.runtime.mutableStateOf(true)
        val finished = CompletableDeferred<Unit>()
        var downloads = 0
        compose.setContent { RivoTheme { Surface {
            if (visible.value) SettingsScreen(
                checkUpdate = { AppRelease("99.0.0", "", "", "", true, size = 1024) },
                downloadAndInstall = { downloads++; finished.await() })
        } } }
        compose.onNodeWithTag("check_updates").performClick()
        compose.onNodeWithTag("download_update").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("update_download_progress").assertExists()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("settings_screen").assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("update_available").assertExists()
        compose.onNodeWithTag("download_update").assertIsNotEnabled()
        compose.onNodeWithTag("update_download_progress").assertExists()
        compose.runOnIdle { assertEquals(1, downloads); finished.complete(Unit) }
        compose.onNodeWithTag("download_update").assertIsEnabled()
        compose.onNodeWithTag("update_download_progress").assertDoesNotExist()
    }

    @Test fun failedCheckCanRetryAndThenShowsCurrentVersion() {
        var calls = 0
        compose.setContent { RivoTheme { Surface { SettingsScreen(checkUpdate = {
            if (calls++ == 0) throw java.io.IOException("无法连接 GitHub，请检查网络后重试")
            null
        }) } } }
        compose.onNodeWithTag("check_updates").performClick()
        compose.onNodeWithTag("update_error").assertExists()
        compose.onNodeWithTag("update_current").assertDoesNotExist()
        compose.onNodeWithTag("check_updates").performClick()
        compose.onNodeWithTag("update_error").assertDoesNotExist()
        compose.onNodeWithTag("update_current").assertIsDisplayed()
    }
}
