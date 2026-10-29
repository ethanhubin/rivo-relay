package dev.rivo.relay.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.rivo.relay.data.string
import dev.rivo.relay.ui.theme.RivoSize
import org.json.JSONObject

/**
 * QiGuang Codex Composer layout: a 52dp-minimum, three-line BasicTextField,
 * divider, and one compact action row (8x6dp insets, 6dp spacing).
 * Source: QiGuang ui/main/ChatComposerContent.kt and AttachmentAddIcon.kt.
 * Rivo keeps draft ownership and native submission in ChatScreen. Mode icons,
 * attachment cards and the non-focusable settings popup follow the source UI.
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
    onAddVideo: () -> Unit = onAddFile,
    onOpenFile: (JSONObject) -> Unit = {},
    discussionMode: Boolean = false,
    onToggleMode: () -> Unit = {},
    focusRequest: Int = 0,
    onPasteImages: (List<android.net.Uri>) -> Unit = {},
    settingsExpanded: Boolean = false,
    onDismissSettings: () -> Unit = {},
    settingsContent: (@Composable () -> Unit)? = null,
) {
    var attachmentsOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var inputValue by remember { mutableStateOf(TextFieldValue(text, selection = TextRange(text.length))) }
    LaunchedEffect(text) { if (inputValue.text != text) inputValue = TextFieldValue(text, selection = TextRange(text.length)) }
    LaunchedEffect(focusRequest) { if (focusRequest > 0) { focusRequester.requestFocus(); keyboard?.show() } }
    LaunchedEffect(enabled) { if (!enabled) attachmentsOpen = false }
    // QiGuang uses 42dp visible controls; preserve Rivo's 44dp touch target.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides RivoSize.MinTouchTarget) {
        Column(
            modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("message_composer"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (images.isNotEmpty() || files.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().heightIn(max = 164.dp).verticalScroll(rememberScrollState()).testTag("draft_attachments"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    images.forEach { image ->
                        val bitmap = remember(image) {
                            android.graphics.BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)?.asImageBitmap()
                        }
                        Surface(Modifier.fillMaxWidth().clickable(enabled = enabled && !preparing) { onEditImage(image) },
                            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            bitmap?.let {
                                Image(it, "编辑待发送图片", Modifier.size(48.dp).clip(RoundedCornerShape(11.dp))
                                    .testTag("draft_image"), contentScale = ContentScale.Crop)
                            }
                            Column(Modifier.weight(1f)) { Text("图片已准备好", style = MaterialTheme.typography.bodySmall)
                                Text("点击查看与编辑", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            TextButton(onClick = { onRemoveImage(image) }, enabled = enabled,
                                modifier = Modifier.semantics { contentDescription = "移除图片" }) { Text("移除") }
                        } }
                    }
                    files.forEach { file ->
                        Surface(Modifier.fillMaxWidth().clickable { onOpenFile(file) }.testTag("draft_file_" + file.string("name")),
                            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("附", style = MaterialTheme.typography.labelLarge)
                                Column(Modifier.weight(1f)) {
                                    Text(file.string("name"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("点击查看", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { onRemoveFile(file) }, enabled = enabled,
                                    modifier = Modifier.semantics { contentDescription = "移除文件：" + file.string("name") }) { Text("移除") }
                            }
                        }
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
                        value = inputValue,
                        onValueChange = { inputValue = it; onTextChange(it.text) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().pasteImageAttachments(context, enabled && !preparing, onPasteImages)
                            .focusRequester(focusRequester).heightIn(min = 52.dp)
                            .padding(horizontal = 16.dp, vertical = 14.dp).testTag("message_input"),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        minLines = 1,
                        maxLines = 3,
                        decorationBox = { inner ->
                            Box {
                                if (text.isEmpty()) Text(
                                    when { isRunning -> "输入下一项任务或实时引导"; discussionMode -> "输入想讨论的方案或问题"; else -> "描述要实现或修改的功能" },
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
                        }
                        OutlinedButton(onClick = onToggleMode, enabled = enabled,
                            modifier = Modifier.height(36.dp).focusProperties { canFocus = false }.testTag("codex_task_mode"),
                            contentPadding = PaddingValues(horizontal = 8.dp)) {
                            ComposerModeIcon(discussionMode)
                            Spacer(Modifier.width(5.dp))
                            Text(if (discussionMode) "讨论模式" else "普通模式", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                        Spacer(Modifier.weight(1f))
                        Box {
                            ComposerToolButton(onSettings, true, "运行设置", "composer_settings") {
                                Text("⚙", style = MaterialTheme.typography.titleMedium)
                            }
                            val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
                            if (settingsExpanded && settingsContent != null) Popup(
                                popupPositionProvider = remember(gap) { ComposerSettingsPositionProvider(gap) }, onDismissRequest = onDismissSettings,
                                properties = PopupProperties(focusable = false, dismissOnBackPress = true, dismissOnClickOutside = true)) {
                                Surface(Modifier.widthIn(min = 240.dp, max = 280.dp).heightIn(max = 440.dp).testTag("composer_settings_panel"),
                                    shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 10.dp, tonalElevation = 4.dp) { settingsContent() }
                            }
                        }
                        if (isRunning && text.isBlank() && images.isEmpty() && files.isEmpty()) OutlinedButton(
                            onClick = onStop, enabled = canStop,
                            modifier = Modifier.width(80.dp).height(44.dp).testTag("stop_turn"), shape = RoundedCornerShape(22.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp)) { Text("■ 停止", style = MaterialTheme.typography.labelLarge) }
                        else Button(
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
    if (attachmentsOpen) AttachmentSourceDialog(3 - images.size, 16 - files.size, onDismiss = { attachmentsOpen = false },
        onImage = { attachmentsOpen = false; onAddImage() }, onFile = { attachmentsOpen = false; onAddFile() },
        onVideo = { attachmentsOpen = false; onAddVideo() }, onWorkspace = { attachmentsOpen = false; onAddWorkspaceFile() })
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
        modifier = Modifier.size(44.dp).focusProperties { canFocus = false }.semantics { contentDescription = description }.testTag(tag),
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
