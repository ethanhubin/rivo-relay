package dev.rivo.relay

import android.graphics.Bitmap
import android.util.Base64
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import dev.rivo.relay.data.CodexUsage
import dev.rivo.relay.data.CodexUsageLimit
import dev.rivo.relay.data.CodexUsageWindow
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.ui.chat.*
import dev.rivo.relay.ui.sessions.SessionsScreen
import dev.rivo.relay.ui.theme.RivoTheme
import dev.rivo.relay.data.ThreadSummary
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class FilePreviewRoutingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun evidence(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(folder, "$name-0.2.1.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }

    @Test fun apkRouteDoesNotReadOrDownloadBeforeAnExplicitAction() {
        var visible by mutableStateOf(true)
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Text("对比 Relay 与栖光 Codex 功能")
            if (visible) FilePreviewDialog("/Users/ethan/Documents/Rivo-Relay/workspace/artifacts/rivo-relay-0.2.1-debug.apk",
                { error("APK must not use preview API") }, { _, _, _ -> error("Opening a menu must not download") }) { visible = false }
        } } }
        compose.onNodeWithTag("file_open_actions").assertIsDisplayed()
        compose.onNodeWithTag("file_preview").assertDoesNotExist()
        compose.onNodeWithText("保存到本机").assertIsDisplayed()
        evidence("apk-actions")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("file_open_actions").assertDoesNotExist()
    }

    @Test fun canceledDownloadCannotResurrectItsProgressDialog() {
        var report: ((Long, Long) -> Unit)? = null
        compose.setContent { RivoTheme {
            FilePreviewDialog("/workspace/cancellation-fixture.zip", { error("No inline preview") }, { _, _, progress ->
                report = progress
                progress(1, 20)
                awaitCancellation()
            }) {}
        } }
        compose.onNodeWithTag("open_file").performClick()
        compose.waitUntil { report != null }
        compose.onNodeWithTag("file_transfer_cancel").performClick()
        compose.runOnIdle { report!!(10, 20) }
        compose.waitForIdle()
        compose.onNodeWithTag("file_transfer_dialog").assertDoesNotExist()
        compose.onNodeWithTag("file_open_actions").assertIsDisplayed()
    }

    @Test fun inlineImageCanLaunchSaveWithTheImageNameAndMime() {
        var launched: Intent? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launched = contract.createIntent(compose.activity, input)
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val output = ByteArrayOutputStream()
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        image.compress(Bitmap.CompressFormat.PNG, 100, output); image.recycle()
        val source = "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) { RivoTheme {
            FilePreviewDialog(source, { error("Inline image must not call node") }, { _, _, _ -> error("Saving requires picker result") }) {}
        } } }
        compose.waitUntil { compose.onAllNodesWithTag("save_file").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("save_file").performClick()
        compose.runOnIdle {
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, launched?.action)
            assertEquals("image.png", launched?.getStringExtra(Intent.EXTRA_TITLE))
            assertEquals("image/png", launched?.type)
        }
    }

    @Test fun markdownNavigationSeparatesTheNativePathFromItsLineNumber() {
        var requested: String? = null
        compose.setContent { RivoTheme {
            FilePreviewDialog("/project/README.md", { path ->
                requested = path
                if (path.endsWith("README.md")) JSONObject().put("encoding", "utf-8").put("mimeType", "text/markdown").put("text", "[source.kt](source.kt:12)")
                else JSONObject().put("encoding", "utf-8").put("mimeType", "text/plain").put("text", (1..30).joinToString("\n") { "line $it" })
            }, { _, _, _ -> }) {}
        } }
        compose.waitUntil { compose.onAllNodesWithTag("message_text").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("message_text").performTouchInput { click(Offset(18f, 18f)) }
        compose.waitUntil { requested == "/project/source.kt" }
        compose.onNodeWithText("line 12").assertIsDisplayed()
    }

    @Test fun sessionListMatchesTheReferenceDensity() {
        val threads = listOf(
            ThreadSummary("one", "对比 Relay 与栖光 Codex 功能", "隐藏预览", "running", "/project", "p"),
            ThreadSummary("two", "将 Rivo-Relay 发布到 GitHub", "隐藏预览", "idle", "/project", "p"),
            ThreadSummary("three", "迁移附件图片编辑功能", "隐藏预览", "idle", "/project", "p"),
            ThreadSummary("four", "设计产品原型图供审核", "隐藏预览", "idle", "/project", "p"))
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            SessionsScreen("Rivo-Relay", threads, false, true, {}, {}, {}, {}, onArchivedChanged = {},
                usage = CodexUsage(listOf(CodexUsageLimit("codex", "Codex", null, CodexUsageWindow(87, 10080, System.currentTimeMillis() / 1000 + 500000)))))
        } } }
        compose.onNodeWithTag("thread_four").assertIsDisplayed()
        compose.onNodeWithText("隐藏预览").assertDoesNotExist()
        compose.onNodeWithTag("new_thread").assertIsDisplayed()
        evidence("sessions")
    }
}
