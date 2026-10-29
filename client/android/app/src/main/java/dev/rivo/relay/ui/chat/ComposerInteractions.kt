package dev.rivo.relay.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider

/** Migrated from QiGuang ChatComposerContent: consume only supported image clipboard items. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.pasteImageAttachments(context: Context, enabled: Boolean, onPasteImages: (List<Uri>) -> Unit): Modifier {
    if (!enabled) return this
    return contentReceiver { content ->
        if (content.source != TransferableContent.Source.Clipboard) return@contentReceiver content
        val images = clipboardImageUris(context, content.clipEntry.clipData)
        if (images.isEmpty()) return@contentReceiver content
        onPasteImages(images)
        val consumed = images.toSet()
        content.consume { it.uri in consumed }
    }.appendTextContextMenuComponents {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val images = runCatching { clipboard?.primaryClip }.getOrNull()?.let { clipboardImageUris(context, it) }.orEmpty()
        if (images.isNotEmpty()) item(PasteImageKey, "粘贴图片") { onPasteImages(images); close() }
    }
}
private val PasteImageKey = Any()
private val pastedImageTypes = setOf("image/jpeg", "image/png", "image/webp")
private fun clipboardImageUris(context: Context, clip: ClipData): List<Uri> = buildList {
    repeat(clip.itemCount) { index ->
        val uri = clip.getItemAt(index).uri ?: return@repeat
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()?.trim()?.lowercase()
        if (if (mime != null) mime in pastedImageTypes else pastedImageTypes.any(clip.description::hasMimeType)) add(uri)
    }
}

/** Same bolt and speech bubble geometry as QiGuang's permanent task-mode button. */
@Composable
internal fun ComposerModeIcon(discussion: Boolean) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(16.dp).testTag(if (discussion) "codex_mode_icon_plan" else "codex_mode_icon_default")) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        if (!discussion) drawPath(Path().apply {
            moveTo(size.width * .57f, size.height * .05f); lineTo(size.width * .18f, size.height * .57f)
            lineTo(size.width * .47f, size.height * .57f); lineTo(size.width * .37f, size.height * .95f)
            lineTo(size.width * .82f, size.height * .42f); lineTo(size.width * .53f, size.height * .42f); close()
        }, color)
        else {
            drawRoundRect(color, Offset(size.width * .08f, size.height * .10f), Size(size.width * .84f, size.height * .66f), CornerRadius(size.width * .18f), style = stroke)
            drawLine(color, Offset(size.width * .29f, size.height * .76f), Offset(size.width * .20f, size.height * .91f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(size.width * .20f, size.height * .91f), Offset(size.width * .47f, size.height * .76f), stroke.width, StrokeCap.Round)
            listOf(.34f, .50f, .66f).forEach { drawCircle(color, .85.dp.toPx(), Offset(size.width * it, size.height * .43f)) }
        }
    }
}

/** QiGuang settings popup anchors above the input toolbar and leaves keyboard focus intact. */
internal class ComposerSettingsPositionProvider(private val gap: Int): PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val above = anchorBounds.top - popupContentSize.height - gap
        val x = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(x, if (above >= 0) above else (anchorBounds.bottom + gap).coerceIn(0, maxY))
    }
}
