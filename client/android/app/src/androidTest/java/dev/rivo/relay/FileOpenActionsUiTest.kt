package dev.rivo.relay

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rivo.relay.ui.chat.*
import dev.rivo.relay.ui.theme.RivoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only callbacks: these tests never open a system picker, install, or share sheet. */
@RunWith(AndroidJUnit4::class)
class FileOpenActionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun externalIntentsGrantOnlyReadAccessToTheExplicitFile() {
        val uri = Uri.parse("content://dev.rivo.relay.files/previews/report.pdf")
        val view = externalFileIntent(uri, "report.pdf", "application/pdf", false)
        val share = externalFileIntent(uri, "report.pdf", "application/pdf", true)
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(uri, view.data)
        assertEquals(Intent.ACTION_SEND, share.action)
        @Suppress("DEPRECATION")
        val sharedUri = share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(uri, sharedUri)
        listOf(view, share).forEach { intent ->
            assertEquals("application/pdf", intent.type)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
    }

    @Test fun apkUsesCompactSaveOrOpenDialog() {
        val actions = mutableListOf<FileAction>()
        var dismissed = false
        compose.setContent { RivoTheme { Surface {
            FileOpenActions("release.apk", true, { actions += it }, { dismissed = true })
        } } }
        compose.onNodeWithTag("file_open_actions").assertIsDisplayed()
        compose.onNodeWithText("release.apk").assertIsDisplayed()
        compose.onNodeWithText("打开").assertIsDisplayed()
        compose.onNodeWithTag("share_file").assertDoesNotExist()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithTag("save_file").performClick()
        compose.onNodeWithTag("open_file").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(listOf(FileAction.SAVE, FileAction.OPEN), actions); assertTrue(dismissed) }
    }

    @Test fun ordinaryFilesOfferShareOpenAndSaveWithoutImplicitAction() {
        val actions = mutableListOf<FileAction>()
        compose.setContent { RivoTheme { Surface { FileOpenActions("report.docx", false, { actions += it }, {}) } } }
        compose.onNodeWithText("report.docx").assertIsDisplayed()
        compose.onNodeWithText("用其他应用打开").assertIsDisplayed()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithTag("share_file").performClick()
        compose.onNodeWithTag("open_file").performClick()
        compose.onNodeWithTag("save_file").performClick()
        compose.runOnIdle { assertEquals(listOf(FileAction.SHARE, FileAction.OPEN, FileAction.SAVE), actions) }
    }

    @Test fun transferShowsBytesProgressAndExplicitCancellation() {
        var canceled = false
        compose.setContent { RivoTheme {
            FileTransferDialog(FileTransferState("保存报告", bytes = 512, total = 1024, bytesPerSecond = 128),
                onCancel = { canceled = true }, onRetry = { error("Not failed") }, onClose = { error("Still running") })
        } }
        compose.onNodeWithTag("file_transfer_details").assertTextContains("50%", substring = true)
        compose.onNodeWithTag("file_transfer_details").assertTextContains("128 B/s", substring = true)
        compose.onNodeWithTag("file_transfer_cancel").performClick()
        compose.runOnIdle { assertTrue(canceled) }
    }

    @Test fun failedTransferOffersRetryWithoutLosingError() {
        var retried = false
        var closed = false
        compose.setContent { RivoTheme {
            FileTransferDialog(FileTransferState("打开报告", running = false, failed = true, message = "网络已断开"),
                onCancel = { error("Not running") }, onRetry = { retried = true }, onClose = { closed = true })
        } }
        compose.onNodeWithText("网络已断开").assertIsDisplayed()
        compose.onNodeWithTag("file_transfer_retry").performClick()
        compose.runOnIdle { assertTrue(retried); assertFalse(closed) }
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }
}
