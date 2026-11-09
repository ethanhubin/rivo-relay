package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.sites.SitesScreen
import dev.rivo.relay.ui.chat.NativeActivityGroup
import dev.rivo.relay.ui.theme.RivoTheme
import dev.rivo.relay.ui.sites.ConnectorIcon
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.File
import android.graphics.Bitmap

/** Deterministic display fixtures; no production account or deployment changes. */
class SitesScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun shot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun listScreenshot() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"example","title":"GH · 1–80级内容与奖励轴","url":"https://gh-level-axis-20261104.eeyu8182.chatgpt.site","accessMode":"public"}],"cursor":null}"""))
        server.start()
        val api = RelayApi("http://127.0.0.1:${server.port}")
        try {
            compose.setContent { RivoTheme { Surface { Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { SitesScreen(api, "Mac mini") }
                NavigationBar {
                    NavigationBarItem(false, {}, { Text("⌘") }, label={Text("Codex")})
                    NavigationBarItem(true, {}, { ConnectorIcon("appgarden") }, label={Text("Sites")})
                    NavigationBarItem(false, {}, { Text("✓") }, label={Text("待办")})
                    NavigationBarItem(false, {}, { Text("⚙") }, label={Text("设置")})
                }
            } } } }
            compose.waitUntil(15000) { compose.onAllNodesWithText("GH · 1–80级内容与奖励轴").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("公开").assertIsDisplayed()
            shot("sites-list.png")
        } finally { server.shutdown() }
    }
    @Test fun conversationScreenshot() {
        val raw = JSONObject("""{"id":"site-result","type":"mcpToolCall","status":"completed","server":"codex_apps","tool":"sites.get_site","result":{"structuredContent":{"id":"example","title":"GH · 1–80级内容与奖励轴","current_live_url":"https://gh-level-axis-20261104.eeyu8182.chatgpt.site"},"content":[{"type":"resource_link","name":"使用说明","uri":"https://example.com/guide"}]}}""")
        val message = NativeMessage("site-result", "turn", "mcpToolCall", "", raw, activity=nativeActivity(raw))
        compose.setContent { RivoTheme { Surface { Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Text("‹   Sites 会话", style=MaterialTheme.typography.titleLarge)
            HorizontalDivider()
            NativeActivityGroup(NativeTimelineRow.Activities(listOf(message))) {}
        } } } }
        compose.onNodeWithText("GH · 1–80级内容与奖励轴").assertIsDisplayed()
        compose.onNodeWithText("打开网站").assertDoesNotExist()
        compose.onNodeWithText("使用说明").assertIsDisplayed()
        compose.onNodeWithText("打开链接").assertDoesNotExist()
        shot("sites-conversation.png")
    }
}
