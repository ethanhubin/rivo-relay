package dev.rivo.relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.rivo.relay.ui.chat.*
import dev.rivo.relay.ui.theme.RivoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AttachmentEditorUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun thumbnailOpensEditorCancelPreservesAndCropReplacesBytes() {
        val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val original = DraftImage("draft", compressDraftBitmap(bitmap))
        bitmap.recycle()
        var current by mutableStateOf(original)
        var editing by mutableStateOf(false)
        compose.setContent { RivoTheme {
            MessageComposer("", {}, listOf(current), {}, emptyList(), {}, false, null, true, true, false, false, false,
                {}, {}, {}, {}, {}, onEditImage = { editing = true })
            if (editing) AttachmentImageEditor(current, { editing = false }, { current = it; editing = false })
        } }
        compose.onNodeWithTag("draft_image", useUnmergedTree = true).performTouchInput { click() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("attachment_editor_toolbar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("attachment_editor_tool_crop").performClick()
        compose.onNodeWithTag("attachment_editor_close").performClick()
        compose.runOnIdle { assertSame(original, current) }
        compose.onNodeWithTag("draft_image", useUnmergedTree = true).performTouchInput { click() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("attachment_editor_toolbar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("attachment_editor_tool_crop").performClick()
        compose.onNodeWithTag("attachment_editor_tool_arrow").performClick()
        compose.onNodeWithTag("attachment_editor_canvas").performTouchInput { swipe(center - Offset(80f, 80f), center + Offset(80f, 80f), 300) }
        compose.onNodeWithTag("attachment_editor_done").performClick()
        compose.waitUntil(5000) { current !== original }
        compose.runOnIdle {
            assertEquals(original.id, current.id)
            assertTrue(current.bytes.size <= 1024 * 1024)
            val decoded = BitmapFactory.decodeByteArray(current.bytes, 0, current.bytes.size)
            assertEquals(630, decoded.width)
            assertEquals(840, decoded.height)
            decoded.recycle()
        }
        compose.onNodeWithTag("draft_image", useUnmergedTree = true).assertIsDisplayed()
    }
}
