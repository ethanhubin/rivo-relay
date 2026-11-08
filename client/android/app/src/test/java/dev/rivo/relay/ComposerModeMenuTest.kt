package dev.rivo.relay

import androidx.compose.ui.unit.*
import dev.rivo.relay.ui.chat.ModeMenuPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerModeMenuTest {
    @Test fun anchorsAboveButtonAndClampsToNarrowScreen() {
        val provider = ModeMenuPosition(10)
        assertEquals(IntOffset(50, 290), provider.calculatePosition(IntRect(50, 500, 180, 540), IntSize(390, 800), LayoutDirection.Ltr, IntSize(258, 200)))
        assertEquals(IntOffset(62, 290), provider.calculatePosition(IntRect(90, 500, 220, 540), IntSize(320, 800), LayoutDirection.Ltr, IntSize(258, 200)))
        assertEquals(IntOffset(0, 0), provider.calculatePosition(IntRect(10, 100, 140, 140), IntSize(320, 300), LayoutDirection.Rtl, IntSize(258, 200)))
    }
}
