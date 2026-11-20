package dev.rivo.relay

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.connection.NodeConnection
import dev.rivo.relay.connection.NodeRoutes
import dev.rivo.relay.data.RelayApi
import dev.rivo.relay.ui.settings.ConnectionSettings
import dev.rivo.relay.ui.theme.RivoTheme
import okhttp3.mockwebserver.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File

class ConnectionSettingsScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun connectedStatusAndRefreshShareOneRow() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                if (request.path == "/v1/usage") """{"rateLimits":{"secondary":{"usedPercent":28,"windowDurationMins":10080,"resetsAt":1794672000}},"rateLimitResetCredits":{"availableCount":2}}"""
                else """{"releaseCode":136}""")
        }
        server.start()
        try {
            val address = server.url("/").toString().trimEnd('/')
            val connection = NodeConnection(address, "测试节点", "screenshot", "fixture", "",
                routes = NodeRoutes(direct = address, relay = "https://relay.example/relay/fixture"))
            val api = RelayApi(address)
            compose.setContent {
                RivoTheme { Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
                        var refresh by remember { mutableIntStateOf(0) }
                        ConnectionSettings(connection, api, {}, onRefresh = { refresh++ }) {
                            androidx.compose.material3.HorizontalDivider()
                            dev.rivo.relay.ui.settings.SettingsNodeUsage(connection, api, refresh)
                        }
                    }
                } }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("settings_network_latency").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("周额度剩余 72%").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText("刷新").assertCountEquals(1)
            compose.onAllNodesWithText("测试节点").assertCountEquals(1)
            compose.onNodeWithTag("settings_reset_count", useUnmergedTree = true).assertTextEquals("2 次")
            compose.onNodeWithTag("settings_refresh").assertIsDisplayed().assertIsEnabled()
            val ping = compose.onNodeWithTag("settings_network_latency").fetchSemanticsNode().boundsInRoot
            val button = compose.onNodeWithTag("settings_refresh").fetchSemanticsNode().boundsInRoot
            assertTrue("Ping and refresh must share a row", ping.center.y >= button.top && ping.center.y <= button.bottom)
            compose.waitForIdle()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.getExternalFilesDir(null), "connection-usage-integrated.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { server.shutdown() }
    }
}
