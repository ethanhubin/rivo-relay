package dev.rivo.relay.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ComposerAttachmentRow(name: String, enabled: Boolean, openEnabled: Boolean,
    onOpen: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier,
    thumbnail: (@Composable () -> Unit)? = null,
    removeModifier: Modifier = Modifier, removeDescription: String = "移除文件：$name",
    mimeType: String = "", canOpen: Boolean = fileOpenKind(name, mimeType) != FileOpenKind.EXTERNAL) {
    var showName by remember(name) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) showName = false }
    Box(modifier.size(72.dp)) {
        Surface(Modifier.matchParentSize().semantics { contentDescription = name }
            .clickable(enabled = enabled) { if (openEnabled && canOpen) { showName = false; onOpen() } else showName = !showName },
            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (thumbnail != null) thumbnail() else Icon(
                    when {
                        fileOpenKind(name, mimeType) == FileOpenKind.IMAGE -> FileIcons.Picture
                        fileOpenKind(name, mimeType) == FileOpenKind.VIDEO -> FileIcons.PlayArrow
                        fileOpenKind(name, mimeType) == FileOpenKind.AUDIO -> FileIcons.MusicNote
                        name.substringAfterLast('.', "").lowercase() in setOf("zip", "7z", "rar", "gz", "tar") -> FileIcons.Archive
                        name.substringAfterLast('.', "").lowercase() in setOf("csv", "xlsx", "xls", "ods") -> FileIcons.Table
                        else -> FileIcons.Document
                    }, contentDescription = null,
                    modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showName) Popup(popupPositionProvider = remember { object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (anchorBounds.center.x - popupContentSize.width / 2)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val above = anchorBounds.top - popupContentSize.height - 12
                val y = (if (above >= 0) above else anchorBounds.bottom + 12)
                    .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return IntOffset(x, y)
            }
        } },
            onDismissRequest = { showName = false }, properties = PopupProperties(focusable = true)) {
            Surface(Modifier.widthIn(max = 260.dp).testTag("attachment_filename_tip")
                .clickable { showName = false }, shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp) {
                Text(name, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodySmall)
            }
        }
        Box(removeModifier.align(Alignment.TopEnd).size(32.dp)
            .semantics { contentDescription = removeDescription }
            .clickable(enabled = enabled, onClick = onRemove), contentAlignment = Alignment.Center) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = .85f)) {
                Icon(FileIcons.Close, contentDescription = null,
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(20.dp).padding(3.dp))
            }
        }
    }
}

@Composable
internal fun ComposerImageAttachmentRow(bitmap: androidx.compose.ui.graphics.ImageBitmap?, enabled: Boolean,
    openEnabled: Boolean, onOpen: () -> Unit, onRemove: () -> Unit,
    modifier: Modifier = Modifier, removeModifier: Modifier = Modifier) {
    ComposerAttachmentRow("图片附件，点击查看与编辑", enabled, openEnabled, onOpen, onRemove, modifier,
        thumbnail = {
            bitmap?.let {
                androidx.compose.foundation.Image(it, "编辑待发送图片",
                    Modifier.fillMaxSize().testTag("draft_image"),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
        }, removeModifier = removeModifier, removeDescription = "移除图片", canOpen = true)
}

internal fun draftThumbnail(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        ?.let { orientDraftBitmap(it, bytes).asImageBitmap() }
}
