package dev.rivo.relay.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun AttachmentSourceMenu(expanded: Boolean, imageSlots: Int, fileSlots: Int, onDismiss: () -> Unit,
    onCamera: () -> Unit, onImage: () -> Unit, onFile: () -> Unit, onVideo: () -> Unit,
    onWorkspace: () -> Unit) {
    androidx.activity.compose.BackHandler(expanded) { onDismiss() }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
        modifier = Modifier.width(240.dp).testTag("attachment_dialog"),
        shape = RoundedCornerShape(24.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        data class Option(val label: String, val icon: String, val tag: String, val enabled: Boolean, val action: () -> Unit)
        listOf(
            Option("拍照", "camera", "add_camera", imageSlots > 0, onCamera),
            Option("照片", "image", "add_image", imageSlots > 0, onImage),
            Option("视频", "video", "add_video", fileSlots > 0, onVideo),
            Option("本地文件", "folder", "add_file", fileSlots > 0, onFile),
            Option("工作区文件", "folder", "add_workspace_file", fileSlots > 0, onWorkspace),
        ).forEach { option ->
            DropdownMenuItem(text = { Text(option.label) }, onClick = { onDismiss(); option.action() },
                enabled = option.enabled, modifier = Modifier.testTag(option.tag),
                leadingIcon = {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            AttachmentSourceIcon(option.icon, Modifier.size(22.dp))
                        }
                    }
                }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

/** Native outlines remain visible without font glyphs or an external icon loader. */
@Composable
private fun AttachmentSourceIcon(kind: String, modifier: Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val stroke = Stroke(1.7f * u, cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = Offset(x * u, y * u)
        when (kind) {
            "camera" -> {
                drawRoundRect(color, point(2f, 6f), Size(20f * u, 15f * u), CornerRadius(3f * u), style = stroke)
                drawCircle(color, 4f * u, point(12f, 13f), style = stroke)
                val path = Path().apply { moveTo(7f*u, 6f*u); lineTo(9f*u, 3f*u); lineTo(15f*u, 3f*u); lineTo(17f*u, 6f*u) }
                drawPath(path, color, style = stroke)
            }
            "image" -> {
                drawRoundRect(color, point(3f, 3f), Size(18f * u, 18f * u), CornerRadius(3f * u), style = stroke)
                drawCircle(color, 1.5f * u, point(8f, 8f))
                val path = Path().apply { moveTo(3f*u, 17f*u); lineTo(9f*u, 11f*u); lineTo(13f*u, 15f*u); lineTo(16f*u, 12f*u); lineTo(21f*u, 17f*u) }
                drawPath(path, color, style = stroke)
            }
            "video" -> {
                drawRoundRect(color, point(2f, 5f), Size(14f * u, 14f * u), CornerRadius(3f * u), style = stroke)
                val path = Path().apply { moveTo(16f*u, 10f*u); lineTo(22f*u, 6f*u); lineTo(22f*u, 18f*u); lineTo(16f*u, 14f*u) }
                drawPath(path, color, style = stroke)
            }
            "folder" -> {
                val path = Path().apply { moveTo(3f*u, 5f*u); lineTo(9f*u, 5f*u); lineTo(12f*u, 8f*u); lineTo(21f*u, 8f*u); lineTo(21f*u, 20f*u); lineTo(3f*u, 20f*u); close() }
                drawPath(path, color, style = stroke)
            }
            else -> {
                drawLine(color, point(6f, 6f), point(18f, 18f), stroke.width, StrokeCap.Round)
                drawLine(color, point(18f, 6f), point(6f, 18f), stroke.width, StrokeCap.Round)
            }
        }
    }
}
