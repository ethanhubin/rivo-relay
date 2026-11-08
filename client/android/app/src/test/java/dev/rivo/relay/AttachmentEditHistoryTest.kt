package dev.rivo.relay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import dev.rivo.relay.ui.chat.*
import org.junit.Assert.*
import org.junit.Test

class AttachmentEditHistoryTest {
    private val original = AttachmentEditorSnapshot(Rect(0f, 0f, 1f, 1f), false, emptyList(), null, Color.Red)

    @Test fun undoRestoresCropGeometryAndColorInReverseOrder() {
        val history = AttachmentEditHistory()
        val cropped = original.copy(crop = Rect(0f, .25f, 1f, .75f), cropStarted = true)
        val annotation = AttachmentAnnotation(1, AttachmentEditorTool.ARROW, Offset(.1f, .3f), Offset(.8f, .6f), Color.Red)
        val drawn = cropped.copy(annotations = listOf(annotation), selectedId = 1)
        val resized = drawn.copy(annotations = listOf(annotation.copy(end = Offset(.9f, .7f))))
        val colored = resized.copy(color = Color.Blue, annotations = resized.annotations.map { it.copy(color = Color.Blue) })
        history.record(original, cropped)
        history.record(cropped, drawn)
        history.record(drawn, resized)
        history.record(resized, colored)
        assertEquals(resized, history.undo())
        assertEquals(drawn, history.undo())
        assertEquals(cropped, history.undo())
        assertEquals(original, history.undo())
        assertFalse(history.canUndo)
        assertNull(history.undo())
    }

    @Test fun noOpAndSelectionDoNotConsumeUndoAndNewEditsUseRestoredState() {
        val history = AttachmentEditHistory()
        history.record(original, original.copy(selectedId = 3))
        assertFalse(history.canUndo)
        val blue = original.copy(color = Color.Blue)
        history.record(original, blue)
        val restored = history.undo()!!
        history.record(restored, restored.copy(color = Color.Green))
        assertEquals(original, history.undo())
        assertNull(history.undo())
    }
}
