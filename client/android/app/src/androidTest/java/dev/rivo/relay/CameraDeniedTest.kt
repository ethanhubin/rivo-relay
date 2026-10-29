package dev.rivo.relay

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.ui.login.LoginScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Host revokes CAMERA before this test; no GrantPermissionRule or synthetic permission result. */
@RunWith(AndroidJUnit4::class)
class CameraDeniedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realCameraDenialKeepsManualPairingAvailable() {
        var submitted: Pair<String, String>? = null
        compose.setContent { RivoTheme {
            var error by remember { mutableStateOf<String?>(null) }
            LoginScreen(false, error, { address, code -> submitted = address to code }, { error = it })
        } }
        compose.onNodeWithTag("scan_connect").performClick()
        compose.waitUntil(30_000) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            fun reject(node: AccessibilityNodeInfo): Boolean {
                val resource = node.viewIdResourceName.orEmpty()
                val text = node.text?.toString().orEmpty()
                if (node.packageName?.toString()?.contains("permissioncontroller") == true && node.isClickable && node.isEnabled &&
                    (resource.endsWith(":id/permission_deny_button") || resource.endsWith(":id/permission_deny_and_dont_ask_again_button") || text in setOf("Don't allow", "Don’t allow", "不允许", "拒绝")))
                    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                for (index in 0 until node.childCount) if (node.getChild(index)?.let(::reject) == true) return true
                return false
            }
            root?.let(::reject) == true
        }
        compose.waitUntil(30_000) { runCatching { compose.onAllNodesWithText("未获得相机权限", substring = true).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.onNodeWithTag("manual_entry").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(folder, "camera-denied.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        compose.onNodeWithTag("manual_entry").performClick()
        compose.onNodeWithTag("pairing_address").performTextInput("https://node.example")
        compose.onNodeWithTag("pairing_code").performTextInput("denial-branch-fixture")
        compose.onNodeWithTag("manual_connect").performClick()
        compose.runOnIdle { assertEquals("https://node.example" to "denial-branch-fixture", submitted) }
    }
}
