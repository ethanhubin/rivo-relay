package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.rivo.relay.connection.NodeConnection
import dev.rivo.relay.connection.SavedNode
import dev.rivo.relay.ui.settings.SettingsScreen
import dev.rivo.relay.ui.theme.RivoTheme
import okhttp3.mockwebserver.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class SettingsNodeUsageUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun selectionRefreshAndResetUseInspectedNode() {
        val usedB = AtomicInteger(70)
        val resetsA = AtomicInteger()
        val resetsB = AtomicInteger()
        val usageB = AtomicInteger()
        fun server(id: String, used: () -> Int, resets: AtomicInteger) = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/v1/usage/reset") {
                        resets.incrementAndGet()
                        val body = JSONObject(request.body.readUtf8())
                        assertEquals("credit-$id", body.getString("creditId"))
                        return MockResponse().setBody("""{"status":"accepted","submissionId":"${body.getString("submissionId")}","receipt":{"requestId":"${body.getString("submissionId")}","resetOutcome":"reset"}}""")
                    }
                    if (request.path == "/v1/usage") {
                        val response = MockResponse().setBody("""{"rateLimits":{"secondary":{"usedPercent":${used()},"windowDurationMins":10080,"resetsAt":1794672000}},"resetSupported":true,"resetCreditSelectionSupported":true,"rateLimitResetCredits":{"availableCount":${if (id == "a") 1 else 2},"credits":[{"id":"credit-$id","status":"available"}]}}""")
                        if (id == "b" && usageB.incrementAndGet() == 1) response.setBodyDelay(1200, TimeUnit.MILLISECONDS)
                        return response
                    }
                    return MockResponse().setBody("""{"id":"$id","releaseCode":136}""")
                }
            }
            start()
        }
        val a = server("a", { 20 }, resetsA)
        val b = server("b", { usedB.get() }, resetsB)
        try {
            val ca = NodeConnection(a.url("/").toString().trimEnd('/'), "节点 A", "a", "fixture", "", runtime = "codex")
            val cb = NodeConnection(b.url("/").toString().trimEnd('/'), "节点 B", "b", "fixture", "", runtime = "codex")
            val api = ca.api()
            compose.setContent { RivoTheme { Surface {
                SettingsScreen(connection = ca, api = api,
                    savedNodes = listOf(ca, cb).map { SavedNode(it.base, it.name, true, it.runtime) },
                    resolveConnection = { if (it == ca.base) ca else cb })
            } } }
            awaitText("周额度剩余 80%")
            select(cb)
            compose.onNodeWithText("周额度剩余 80%").assertDoesNotExist()
            select(ca)
            awaitText("周额度剩余 80%")
            select(cb)
            awaitText("周额度剩余 30%")
            compose.onNodeWithTag("settings_reset_count", useUnmergedTree = true).assertTextEquals("2 次")
            usedB.set(45)
            compose.onNodeWithTag("settings_refresh").performScrollTo().performClick()
            awaitText("周额度剩余 55%")
            compose.onNodeWithTag("open_usage_details").performScrollTo().performClick()
            awaitText("额度重置 · 节点 B")
            compose.onNodeWithTag("usage_reset_count").assertTextEquals("可用重置次数：2")
            compose.waitUntil(10_000) { compose.onNodeWithTag("usage_reset_credit_credit-b").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled).not() }
            compose.onNodeWithTag("usage_reset_credit_credit-b").performClick()
            compose.onNodeWithTag("reset_usage").performClick()
            compose.waitUntil(10_000) { compose.onNodeWithTag("confirm_usage_reset").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled).not() }
            compose.onNodeWithTag("confirm_usage_reset").performClick()
            compose.waitUntil(10_000) { resetsB.get() == 1 }
            assertEquals(0, resetsA.get())
        } finally { a.shutdown(); b.shutdown() }
    }

    @Test fun onlyCodexShowsAndRequestsUsageForInspectedNode() {
        val nonCodexUsageRequests = AtomicInteger()
        val runtimes = listOf("codex", "dsh", "antigravity", "")
        val servers = runtimes.mapIndexed { index, runtime ->
            MockWebServer().apply {
                dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        return when (request.requestUrl?.encodedPath) {
                            "/v1/usage", "/v1/usage/reset" -> {
                                if (runtime != "codex") nonCodexUsageRequests.incrementAndGet()
                                MockResponse().setBody("""{"rateLimits":{"secondary":{"usedPercent":20,"windowDurationMins":10080,"resetsAt":1794672000}}}""")
                            }
                            else -> MockResponse().setBody("""{"id":"node-$index","releaseCode":${140 + index}}""")
                        }
                    }
                }
                start()
            }
        }
        try {
            val connections = servers.mapIndexed { index, server ->
                NodeConnection(server.url("/").toString().trimEnd('/'), "节点 $index", "node-$index",
                    "fixture", "", runtime = runtimes[index])
            }
            val codex = connections.first()
            val api = codex.api()
            compose.setContent { RivoTheme { Surface {
                SettingsScreen(connection = codex, api = api,
                    savedNodes = connections.map { SavedNode(it.base, it.name, true, it.runtime) },
                    resolveConnection = { base -> connections.firstOrNull { it.base == base } })
            } } }

            awaitText("周额度剩余 80%")
            connections.drop(1).forEachIndexed { index, connection ->
                select(connection)
                awaitText("节点版本：${141 + index}", substring = true)
                compose.onNodeWithTag("settings_connection_status").assertExists()
                compose.onNodeWithTag("settings_weekly_usage").assertDoesNotExist()
                compose.onNodeWithTag("open_usage_details").assertDoesNotExist()
            }
            select(codex)
            awaitText("周额度剩余 80%")
            compose.onNodeWithTag("open_usage_details").assertExists()
            assertEquals(0, nonCodexUsageRequests.get())
        } finally {
            servers.forEach { it.shutdown() }
        }
    }

    private fun awaitText(text: String, substring: Boolean = false) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun select(connection: NodeConnection) {
        compose.onNodeWithTag("settings_node_selector").performScrollTo().performClick()
        compose.onNodeWithTag("settings_switch_node_${connection.base}").performClick()
    }
}
