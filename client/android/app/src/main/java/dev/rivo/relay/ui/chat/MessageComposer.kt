package dev.rivo.relay.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.string
import dev.rivo.relay.ui.theme.RivoSize
import org.json.JSONObject

/**
 * QiGuang Codex Composer layout: a 52dp-minimum, three-line BasicTextField,
 * divider, and one compact action row (8x6dp insets, 6dp spacing).
 * Source: QiGuang ui/main/ChatComposerContent.kt and AttachmentAddIcon.kt.
 * Rivo keeps input ownership, picker/IME handling, settings and confirmation
 * dialogs in ChatScreen. This component only renders and forwards actions.
 */
@Composable
fun MessageComposer(
    text: String,
    onTextChange: (String) -> Unit,
    images: List<DraftImage>,
    onRemoveImage: (DraftImage) -> Unit,
    files: List<JSONObject>,
    onRemoveFile: (JSONObject) -> Unit,
    preparing: Boolean,
    error: String?,
    enabled: Boolean,
    canSend: Boolean,
    isRunning: Boolean,
    canStop: Boolean,
    busy: Boolean,
    onAddImage: () -> Unit,
    onAddWorkspaceFile: () -> Unit,
    onSettings: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    queueing: Boolean = false,
    onEditImage: (DraftImage) -> Unit = {},
    onAddFile: () -> Unit = {},
) {
    var attachmentsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) attachmentsOpen = false }
    // QiGuang uses 42dp visible controls; preserve Rivo's 44dp touch target.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides RivoSize.MinTouchTarget) {
        Column(
            modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("message_composer"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (images.isNotEmpty() || files.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    images.forEach { image ->
                        val bitmap = remember(image) {
                            android.graphics.BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)?.asImageBitmap()
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            bitmap?.let {
                                Image(it, "编辑待发送图片", Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = enabled && !preparing) { onEditImage(image) }
                                    .testTag("draft_image"), contentScale = ContentScale.Crop)
                            }
                            IconButton(onClick = { onRemoveImage(image) }, enabled = enabled,
                                modifier = Modifier.size(44.dp).semantics { contentDescription = "移除图片" }) {
                                Text("×", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                    files.forEach { file ->
                        InputChip(selected = false, onClick = { onRemoveFile(file) }, enabled = enabled,
                            label = { Text(file.string("name") + " ×", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.widthIn(max = 200.dp)
                                .semantics { contentDescription = "移除文件：" + file.string("name") })
                    }
                }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("attachment_error"))
            }
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("composer_surface"),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .padding(horizontal = 16.dp, vertical = 14.dp).testTag("message_input"),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        minLines = 1,
                        maxLines = 3,
                        decorationBox = { inner ->
                            Box {
                                if (text.isEmpty()) Text(
                                    if (isRunning) "补充当前任务…" else "描述要完成的工作…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                inner()
                            }
                        },
                    )
                    if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    else HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box {
                            ComposerToolButton({ attachmentsOpen = true }, enabled && !preparing,
                                "添加附件", "add_attachment") { ComposerAddIcon() }
                            DropdownMenu(expanded = attachmentsOpen, onDismissRequest = { attachmentsOpen = false }) {
                                DropdownMenuItem(text = { Text("图片") },
                                    onClick = { attachmentsOpen = false; onAddImage() },
                                    enabled = enabled && !preparing && images.size < 3,
                                    modifier = Modifier.testTag("add_image"))
                                DropdownMenuItem(text = { Text("手机文件") },
                                    onClick = { attachmentsOpen = false; onAddFile() },
                                    enabled = enabled && !preparing && files.size < 16,
                                    modifier = Modifier.testTag("add_file"))
                                DropdownMenuItem(text = { Text("节点工作区文件") },
                                    onClick = { attachmentsOpen = false; onAddWorkspaceFile() },
                                    enabled = enabled && !preparing && files.size < 16,
                                    modifier = Modifier.testTag("add_workspace_file"))
                                Text("手机文件将上传至节点，单个最多 1 GiB",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        ComposerToolButton(onSettings, true, "运行设置", "composer_settings") {
                            Text("⚙", style = MaterialTheme.typography.titleMedium)
                        }
                        if (isRunning) ComposerToolButton(onStop, canStop, "停止当前任务", "stop_turn") {
                            Text("停止", style = MaterialTheme.typography.labelMedium,
                                color = if (canStop) MaterialTheme.colorScheme.error else LocalContentColor.current,
                                maxLines = 1)
                        }
                        Button(
                            onClick = onSend,
                            enabled = enabled && canSend && !preparing,
                            modifier = Modifier.width(80.dp).height(44.dp).testTag("send_message"),
                            shape = RoundedCornerShape(22.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            Text(if (busy) "提交中…" else if (queueing) "↑ 排队" else if (isRunning) "↑ 补充" else "↑ 发送",
                                style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerToolButton(
    onClick: () -> Unit,
    enabled: Boolean,
    description: String,
    tag: String,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(onClick, enabled = enabled,
        modifier = Modifier.size(44.dp).semantics { contentDescription = description }.testTag(tag),
        shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(0.dp), content = content)
}

/** Same two round-cap strokes as QiGuang AttachmentAddIcon. */
@Composable
private fun ComposerAddIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(20.dp)) {
        val stroke = size.minDimension * .16f
        val inset = size.minDimension * .22f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawLine(color, Offset(inset, center.y), Offset(size.width - inset, center.y), stroke, StrokeCap.Round)
        drawLine(color, Offset(center.x, inset), Offset(center.x, size.height - inset), stroke, StrokeCap.Round)
    }
}
