package dev.rivo.relay

import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.ui.chat.MessageContent
import dev.rivo.relay.ui.chat.SourceCodePreview
import dev.rivo.relay.ui.theme.RivoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Mirrors QiGuang's text-selection and external-link UI acceptance flows. */
@RunWith(AndroidJUnit4::class)
class MessageInteractionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val opened = mutableListOf<String>()
    private val saved = mutableListOf<String>()
    private val files = mutableListOf<String>()
    private val quotes = mutableListOf<String>()

    private fun showMessage(text: String, failOpening: Boolean = false) {
        compose.setContent { RivoTheme {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) {
                    if (failOpening) error("No browser")
                    opened += uri
                }
            }) {
                Surface { Box(Modifier.fillMaxSize().padding(24.dp)) {
                    MessageContent(text, "/project", onQuoteText = { quotes += it }, onSaveFile = { saved += it }, onFile = { files += it })
                } }
            }
        } }
    }

    private fun tapText() = compose.onNodeWithTag("message_text").performTouchInput { click(Offset(18f, 18f)) }

    private fun evidence(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }

    @Test fun webpageConfirmationCancelAndBrowserMatchQiGuang() {
        showMessage("[示例网页](HTTP://example.com)")
        tapText()
        compose.onNodeWithTag("message_web_link_dialog").assertIsDisplayed()
        compose.onNodeWithTag("message_web_link_share").assertIsDisplayed()
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
        evidence("message-web-link-menu")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("message_web_link_dialog").assertDoesNotExist()
        tapText()
        compose.onNodeWithText("用浏览器打开").performClick()
        compose.runOnIdle { assertEquals(listOf("http://example.com"), opened) }
    }

    @Test fun webpageSaveIsDirectAndDoesNotOpenBrowserOrFilePreview() {
        val url = "https://example.com/download?id=1"
        showMessage(url)
        tapText()
        compose.onNodeWithText("保存到本机").performClick()
        compose.runOnIdle { assertEquals(listOf(url), saved); assertTrue(opened.isEmpty()); assertTrue(files.isEmpty()) }
        compose.onNodeWithTag("message_web_link_dialog").assertDoesNotExist()
    }

    @Test fun missingBrowserKeepsRecoverableDialog() {
        showMessage("https://example.com", failOpening = true)
        tapText()
        compose.onNodeWithText("用浏览器打开").performClick()
        compose.onNodeWithText("无法打开链接，请检查是否已安装浏览器。").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("message_web_link_dialog").assertDoesNotExist()
    }

    @Test fun sourceCitationKeepsLineWhenOpeningFile() {
        showMessage("[源码](src/Main.kt#L12-L18)")
        tapText()
        compose.runOnIdle { assertEquals(listOf("/project/src/Main.kt:12"), files); assertTrue(opened.isEmpty()) }
    }

    @Test fun remoteFileGoesToTheTypeSpecificViewer() {
        val url = "https://example.com/report.pdf?token=fixture"
        showMessage("[报告]($url)")
        tapText()
        compose.runOnIdle { assertEquals(listOf(url), files); assertTrue(opened.isEmpty()) }
        compose.onNodeWithTag("message_web_link_dialog").assertDoesNotExist()
    }

    @Test fun longPressSelectsParagraphAndSupportsSelectAllThenQuote() {
        val text = "长按选择当前段落"
        showMessage(text)
        val message = compose.onNodeWithTag("message_text")
        message.performTouchInput { longClick() }
        compose.waitUntil(3_000) {
            runCatching { message.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange] == TextRange(0, text.length) }.getOrDefault(false)
        }
        compose.onNodeWithTag("message_text_context_menu").assertIsDisplayed()
        evidence("message-selection-menu")
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("引用").performClick()
        compose.runOnIdle { assertEquals(listOf(text), quotes) }
        compose.onNodeWithTag("message_text_context_menu").assertDoesNotExist()
    }

    @Test fun selectionMenuCopiesRenderedTextAndCloses() {
        val text = "复制选中的消息内容"
        showMessage(text)
        compose.onNodeWithTag("message_text").performTouchInput { longClick() }
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("复制").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            assertEquals(text, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        compose.onNodeWithTag("message_text_context_menu").assertDoesNotExist()
    }

    @Test fun sourceCopyExcludesLineNumbers() {
        val source = "val answer = 42\nprintln(answer)"
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize()) { SourceCodePreview(source, "a.kt", Modifier.fillMaxSize()) } } }
        compose.onNodeWithTag("source_copy").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            assertEquals(source, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }
}
