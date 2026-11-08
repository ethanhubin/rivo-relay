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
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun AttachmentSourceDialog(imageSlots: Int, fileSlots: Int, onDismiss: () -> Unit,
    onImage: () -> Unit, onFile: () -> Unit, onVideo: () -> Unit, onWorkspace: () -> Unit) {
    val hasMedia = LocalCodexMediaClient.current != null
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = 720.dp).testTag("attachment_dialog"),
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("添加附件", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("attachment_cancel").semantics { contentDescription = "关闭添加附件" }) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { AttachmentSourceIcon("close", Modifier.size(16.dp)) }
                            }
                        }
                    }
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val tabs = listOf("本地" to "local", "节点" to "node") + if (hasMedia) listOf("媒体库" to "media") else emptyList()
                            tabs.forEachIndexed { index, (label, tag) ->
                                Surface(onClick = { tab = index }, modifier = Modifier.weight(1f).testTag("attachment_tab_$tag").semantics { role = Role.Tab; selected = tab == index },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (tab == index) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = if (tab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) {
                                    Box(Modifier.heightIn(min = 40.dp).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                        Text(label, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                    if (tab == 0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AttachmentSourceOption("image", "图片", "add_image", imageSlots > 0, Modifier.weight(1f), onImage)
                            AttachmentSourceOption("video", "视频", "add_video", fileSlots > 0, Modifier.weight(1f), onVideo)
                            AttachmentSourceOption("folder", "文件", "add_file", fileSlots > 0, Modifier.weight(1f), onFile)
                        }
                        Text(when {
                            imageSlots <= 0 && fileSlots <= 0 -> "附件数量已达上限"
                            imageSlots <= 0 -> "图片已达上限 · 文件 / 视频最大 1 GiB"
                            fileSlots <= 0 -> "还可添加 $imageSlots 张图片 · 文件已达上限"
                            else -> "图片可多选 · 文件 / 视频最大 1 GiB"
                        }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    } else if (tab == 2 && hasMedia) CodexMediaLibrary(imageSlots, fileSlots, onDismiss)
                    else {
                        Surface(onClick = onWorkspace, enabled = fileSlots > 0, modifier = Modifier.fillMaxWidth().testTag("add_workspace_file"),
                            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                AttachmentSourceIcon("folder", Modifier.size(24.dp))
                                Text("工作区文件", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text("›", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        Text(if (fileSlots > 0) "引用当前节点中的文件" else "文件数量已达上限", Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentSourceOption(icon: String, title: String, tag: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.testTag(tag), shape = RoundedCornerShape(18.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f)) {
        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = LocalContentColor.current) {
                Box(Modifier.size(62.dp, 58.dp), contentAlignment = Alignment.Center) { AttachmentSourceIcon(icon, Modifier.size(25.dp)) }
            }
            Text(title, style = MaterialTheme.typography.bodyMedium)
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
