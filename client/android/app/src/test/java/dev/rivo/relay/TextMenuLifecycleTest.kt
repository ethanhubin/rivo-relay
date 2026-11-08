package dev.rivo.relay

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import dev.rivo.relay.ui.chat.ThemedTextContextMenuProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TextMenuLifecycleTest {
    private val data = object : TextContextMenuDataProvider {
        override fun position(destinationCoordinates: LayoutCoordinates) = Offset.Zero
        override fun contentBounds(destinationCoordinates: LayoutCoordinates) = Rect.Zero
        override fun data() = TextContextMenuData(emptyList())
    }
    @Test fun handleOutsideDismissAllowsDragEndToReopenWithoutBodyTouch() = runTest {
        val provider = ThemedTextContextMenuProvider()
        val first = launch { provider.showTextContextMenu(data) }
        runCurrent()
        provider.session!!.close()
        first.join()
        assertNull(provider.session)
        val reopened = launch { provider.showTextContextMenu(data) }
        runCurrent()
        assertNotNull(provider.session)
        provider.dismiss()
        reopened.join()
    }
    @Test fun copyStillSuppressesImmediateReopenUntilNewGesture() = runTest {
        val provider = ThemedTextContextMenuProvider()
        val first = launch { provider.showTextContextMenu(data) }
        runCurrent()
        val session = provider.session!!
        provider.onActionClosed(session)
        session.close()
        first.join()
        provider.showTextContextMenu(data)
        assertNull(provider.session)
        provider.resumeAfterUserGesture()
        val reopened = launch { provider.showTextContextMenu(data) }
        runCurrent()
        assertNotNull(provider.session)
        provider.dismiss()
        reopened.join()
    }
    @Test fun thirdPartyActionsAreExcludedWithoutRemovingEditingOrImagePaste() {
        val keys = androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
        for (key in listOf(keys.CutKey, keys.CopyKey, keys.PasteKey, keys.SelectAllKey,
            keys.AutofillKey, dev.rivo.relay.ui.chat.PasteImageKey)) {
            assertTrue(dev.rivo.relay.ui.chat.allowedTextMenuKey(key))
        }
        assertFalse(dev.rivo.relay.ui.chat.allowedTextMenuKey(Any()))
        assertFalse(dev.rivo.relay.ui.chat.allowedTextMenuKey("向 Grok 提问"))
        assertFalse(dev.rivo.relay.ui.chat.allowedTextMenuKey("朗读"))
    }
}
