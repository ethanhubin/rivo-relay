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
            val ca = NodeConnection(a.url("/").toString().trimEnd('/'), "节点 A", "a", "fixture", "")
            val cb = NodeConnection(b.url("/").toString().trimEnd('/'), "节点 B", "b", "fixture", "")
            val api = ca.api()
            compose.setContent { RivoTheme { Surface {
                SettingsScreen(connection = ca, api = api,
                    savedNodes = listOf(SavedNode(ca.base, ca.name, true), SavedNode(cb.base, cb.name, true)),
                    resolveConnection = { if (it == ca.base) ca else cb })
            } } }
            fun awaitText(text: String) = compose.waitUntil(10_000) {
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
            fun select(connection: NodeConnection) {
                compose.onNodeWithTag("settings_node_selector").performScrollTo().performClick()
                compose.onNodeWithTag("settings_switch_node_${connection.base}").performClick()
            }
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
}
