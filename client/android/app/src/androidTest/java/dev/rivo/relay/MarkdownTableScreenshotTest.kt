package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.ui.chat.MessageContent
import dev.rivo.relay.ui.theme.RivoTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
import android.graphics.Bitmap

class MarkdownTableScreenshotTest {
 @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
 @Test fun tableAndCards() {
  compose.setContent { RivoTheme { Surface { Column(Modifier.fillMaxSize().padding(16.dp)) {
   MessageContent("| 项目 | 说明 | 价格 |\n| --- | --- | ---: |\n| 基础版 | 支持会话消息与 Markdown 表格，适合日常使用 | ¥ 12 |\n| 专业版 | **完整功能**，支持更多设备同步 | ¥ 128 |")
  } } } }
  compose.onNodeWithText("切换为卡片").assertExists()
  capture("table-grid.png")
  compose.onNodeWithText("切换为卡片").performClick()
  compose.onAllNodesWithText("价格").assertCountEquals(2)
  capture("table-cards.png")
 }
 private fun capture(name: String) {
  compose.waitForIdle()
  android.os.SystemClock.sleep(1500)
  val instrumentation = InstrumentationRegistry.getInstrumentation()
  val bitmap = instrumentation.uiAutomation.takeScreenshot()
  File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
   bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
  }
 }
}
