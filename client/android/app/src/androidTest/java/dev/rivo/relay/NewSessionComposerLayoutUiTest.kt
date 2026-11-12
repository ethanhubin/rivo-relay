package dev.rivo.relay

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.data.ConversationDraft
import dev.rivo.relay.data.ConversationStore
import dev.rivo.relay.data.StoredDraftImage
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.theme.RivoTheme
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Full new-session layout with local drafts only; never connects or sends a task. */
@RunWith(AndroidJUnit4::class)
class NewSessionComposerLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var density = 1f
    private val draftText = "第一行内容\n第二行内容\n第三行内容\n第四行内容\n第五行内容\n第六行内容"

    private fun fixture(fontScale: Float = 1f, viewportHeight: State<Int>? = null) {
        // Match MainActivity: resize for IME instead of letting the test Activity pan.
        compose.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val store = ConversationStore(File(compose.activity.cacheDir, "composer-layout-${System.nanoTime()}"))
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.CYAN)
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        runBlocking { store.saveDraft("pending:layout", ConversationDraft(draftText,
            images = listOf(StoredDraftImage("fixture", bytes)))) }
        compose.setContent {
            val baseDensity = LocalDensity.current
            density = baseDensity.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                RivoTheme {
                    Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                        Box(Modifier.fillMaxSize()) {
                            Box(if (viewportHeight == null) Modifier.fillMaxSize()
                                else Modifier.width(360.dp).height(viewportHeight.value.dp)) {
                                ChatScreen(SessionState(id = "pending:layout"), false, false, emptyList(), {},
                                    onSend = { _, _, _, _ -> error("Layout fixture must not send") },
                                    onStop = {}, onRespond = { _, _ -> }, onRefresh = {}, onLoadModels = {},
                                    onEarlier = {}, readFile = { JSONObject() }, pendingTitle = "布局验证",
                                    draftStore = store, readGoalCapability = { true },
                                    pendingWorkspace = {
                                        Text("工作区：本地目录", Modifier.fillMaxWidth().height(52.dp).padding(16.dp))
                                    })
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().any {
                it.config[SemanticsProperties.EditableText].text == draftText
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(folder, "composer-layout-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } } finally { bitmap.recycle() }
        }
    }

    private fun assertComposerIntact() {
        val surface = compose.onNodeWithTag("composer_surface").fetchSemanticsNode().boundsInRoot
        val page = compose.onNodeWithTag("chat_page").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("send_message", "add_attachment")) {
            val button = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("$tag must retain its full height", 44f, button.height / density, 1f)
            assertTrue("$tag must fit inside the surface", button.bottom <= surface.bottom + 1f)
        }
        assertTrue("Composer must fit above the keyboard / viewport bottom", surface.bottom <= page.bottom + 1f)
        val input = compose.onNodeWithTag("message_input").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("send_message").fetchSemanticsNode().boundsInRoot
        assertTrue("Text must not overlap the action row", input.bottom <= button.top + 1f)
        // The thumbnail semantics are merged into the clickable attachment card.
        compose.onNodeWithTag("draft_image", useUnmergedTree = true).assertExists()
        val attachment = compose.onNodeWithContentDescription("图片附件，点击查看与编辑")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Attachment preview must retain its height", 72f, attachment.height / density, 1f)
    }

    private fun assertVisibleLines(lines: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("message_input").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val inputHeight = compose.onNodeWithTag("message_input").fetchSemanticsNode().boundsInRoot.height
        val layout = layouts.single()
        assertTrue("$lines complete lines must fit: input=$inputHeight, lineBottom=${layout.getLineBottom(lines - 1)}", inputHeight + 1f >= layout.getLineBottom(lines - 1))
        assertTrue("Input must stop growing after $lines lines", inputHeight < layout.getLineBottom(lines) - 1f)
    }

    private fun compressedViewport(fontScale: Float) {
        val height = mutableStateOf(650)
        fixture(fontScale, height)
        val originalSettingsHeight = compose.onNodeWithTag("new_session_settings").fetchSemanticsNode().boundsInRoot.height
        val originalInputHeight = compose.onNodeWithTag("message_input").fetchSemanticsNode().boundsInRoot.height
        // Deterministic equivalent of keyboard resize / a short display, without depending on IME size.
        for (availableHeight in listOf(420, 360, 650)) {
            compose.runOnIdle { height.value = availableHeight }
            assertComposerIntact()
            assertVisibleLines(2)
            assertEquals(originalInputHeight, compose.onNodeWithTag("message_input").fetchSemanticsNode().boundsInRoot.height, 1f)
            if (availableHeight < 650) {
                assertTrue("Settings must give space to the composer",
                    compose.onNodeWithTag("new_session_settings").fetchSemanticsNode().boundsInRoot.height < originalSettingsHeight)
                compose.onNodeWithTag("new_session_approval").performScrollTo().assertIsDisplayed()
                assertComposerIntact()
                if (availableHeight == 360) capture("short-font-$fontScale")
            }
        }
    }

    @Test fun attachmentAndMultilineDraftKeepControlsWhenViewportShrinks() = compressedViewport(1f)

    @Test fun largeFontKeepsControlsAndScrollableSettings() = compressedViewport(1.3f)

    @Test fun focusedKeyboardExpandsToFiveLinesAndHidingItRestoresTwo() {
        fixture()
        assertVisibleLines(2)
        compose.onNodeWithTag("message_input").performClick()
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
        assertVisibleLines(5)
        assertComposerIntact()
        capture("keyboard-five-lines")
        compose.onNodeWithTag("message_input").performTextReplacement("one\ntwo\nthree")
        val threeLineHeight = compose.onNodeWithTag("message_input").fetchSemanticsNode().boundsInRoot.height
        for (shortText in listOf("", "one", "one\ntwo")) {
            compose.onNodeWithTag("message_input").performTextReplacement(shortText)
            assertEquals("Expanded input reserves three lines even for a short draft", threeLineHeight,
                compose.onNodeWithTag("message_input").fetchSemanticsNode().boundsInRoot.height, 1f)
            assertComposerIntact()
        }
        capture("keyboard-default-three-lines")
        compose.onNodeWithTag("message_input").performTextReplacement(draftText)
        assertVisibleLines(5)
        compose.onNodeWithTag("new_session_approval").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("message_input").assertIsFocused()
        compose.runOnUiThread {
            WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        compose.waitForIdle()
        assertVisibleLines(2)
        assertComposerIntact()
        compose.onNodeWithTag("message_input").assertTextEquals(draftText)
        capture("collapsed-two-lines")
    }
}
