package dev.rivo.relay.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.window.Dialog
import dev.rivo.relay.data.string
import dev.rivo.relay.data.SpeechCapability
import dev.rivo.relay.data.SpeechRepository
import dev.rivo.relay.ui.theme.RivoSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
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
    goalMode: Boolean = false,
    goalModeEnabled: Boolean = false,
    onSelectMode: ((String) -> Unit)? = null,
    inputMinLines: Int = 2,
) {
    var attachmentsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) attachmentsOpen = false }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var inputValue by remember { mutableStateOf(TextFieldValue(text, selection = TextRange(text.length))) }
    val mediaClient = LocalCodexMediaClient.current
    val api = mediaClient?.api
    val target = mediaClient?.threadId.orEmpty()
    val latestTarget by rememberUpdatedState(target)
    val latestOnTextChange by rememberUpdatedState(onTextChange)
    val latestOnSend by rememberUpdatedState(onSend)
    val latestEnabled by rememberUpdatedState(enabled && !preparing)
    val latestCanSend by rememberUpdatedState(canSend)
    val latestText by rememberUpdatedState(text)
    val scope = rememberCoroutineScope()
    val repository = remember(api) { api?.let { SpeechRepository(it) } }
    var capability by remember(api) { mutableStateOf(SpeechCapability(false, "正在检查语音服务")) }
    val recorder = remember { VoiceRecorder() }
    val recordingEpoch = remember { AtomicLong() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var recording by remember(api, target) { mutableStateOf(false) }
    var amplitude by remember(api, target) { mutableStateOf(0f) }
    var permissionNotice by remember { mutableStateOf<String?>(null) }
    val controller = remember(api, target) {
        repository?.let { VoiceTranscriptionController(it, scope, { latestTarget }, { inputValue.text },
            { value, cursor -> inputValue = TextFieldValue(value, TextRange(cursor)); latestOnTextChange(value) }) }
    }
    val voice = controller?.state?.collectAsState()?.value ?: VoiceUiState()
    fun cancelVoice() {
        recordingEpoch.incrementAndGet()
        recording = false; amplitude = 0f
        recorder.cancel(); controller?.cancel()
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The permission dialog ends the original tap. Never start capture from its callback.
        permissionNotice = if (granted) "麦克风权限已开启，请再次点击开始录音" else "需要麦克风权限才能语音输入"
    }
    LaunchedEffect(repository) {
        if (repository != null) try { capability = repository.capability() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            capability = SpeechCapability(false, "语音服务暂不可用，请重新连接节点后重试")
        }
    }
    LaunchedEffect(text, target) {
        if (inputValue.text != text) {
            cancelVoice()
            inputValue = TextFieldValue(text, selection = TextRange(text.length))
        }
    }
    LaunchedEffect(enabled, preparing) { if (!enabled || preparing) cancelVoice() }
    LaunchedEffect(voice.transcribing, voice.error) {
        if (!voice.transcribing && recording) {
            recordingEpoch.incrementAndGet(); recording = false; amplitude = 0f; recorder.cancel()
        }
    }
    LaunchedEffect(recording, controller) {
        if (recording) {
            delay(120_000)
            recording = false; amplitude = 0f; recorder.stop(); controller?.finish()
        }
    }
    LaunchedEffect(controller, voice.sendReady) {
        val ready = voice.sendReady
        if (ready != 0L) {
            val finalDraft = inputValue.text
            // A parent may publish its final draft after this child recomposes.
            // Wait for that exact draft; a frame alone is not an ownership fence.
            val eligible = withTimeoutOrNull(2_000) {
                snapshotFlow { latestEnabled && latestCanSend && latestText == finalDraft }.first { it }
            } == true
            withFrameNanos { }
            if (eligible && latestEnabled && latestCanSend && latestText == finalDraft && inputValue.text == finalDraft &&
                controller?.consumeSendReady(ready) == true) latestOnSend()
            else controller?.cancel("转写已完成，请确认草稿后发送")
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancelVoice() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancelVoice() }
    }
    val toggleVoice: () -> Unit = {
        permissionNotice = null
        when {
            recording -> { recording = false; amplitude = 0f; recorder.stop(); controller?.finish() }
            voice.transcribing -> cancelVoice()
            controller != null && capability.available && enabled && !preparing -> {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                else {
                    val epoch = recordingEpoch.incrementAndGet()
                    if (controller.start(inputValue.selection.end)) {
                        recording = true
                        val started = recorder.start(controller::acceptAudio,
                            onAmplitude = { value -> mainHandler.post { if (recordingEpoch.get() == epoch) amplitude = value } },
                            onStopped = { failure -> mainHandler.post {
                                if (recordingEpoch.get() == epoch && recording) {
                                    recording = false; amplitude = 0f; recorder.stop()
                                    if (failure == null) controller.finish() else controller.cancel(failure)
                                }
                            } })
                        if (!started) { recording = false; controller.cancel("录音设备无法启动，请检查麦克风权限") }
                    }
                }
            }
        }
    }
    LaunchedEffect(focusRequest) { if (focusRequest > 0) { focusRequester.requestFocus(); keyboard?.show() } }
    // QiGuang uses 42dp visible controls; preserve Rivo's 44dp touch target.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides RivoSize.MinTouchTarget) {
        Column(
            modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("message_composer"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (images.isNotEmpty() || files.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f).heightIn(max = 164.dp).verticalScroll(rememberScrollState()).testTag("draft_attachments"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    images.forEach { image ->
                        val bitmap = remember(image) {
                            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
                            var sample = 1
                            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
                            android.graphics.BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size,
                                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                                ?.let { orientDraftBitmap(it, image.bytes).asImageBitmap() }
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
                        Surface(Modifier.fillMaxWidth().clickable(enabled = enabled && !preparing) { onOpenFile(file) }.testTag("draft_file_" + file.string("name")),
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
            (voice.error ?: permissionNotice)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = if (voice.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("voice_status"))
            }
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("composer_surface"),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = inputValue,
                            onValueChange = { if (it.text != inputValue.text) cancelVoice(); inputValue = it; onTextChange(it.text) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f).pasteImageAttachments(context, enabled && !preparing, onPasteImages)
                                .focusRequester(focusRequester).heightIn(min = 52.dp)
                                .padding(horizontal = 16.dp, vertical = 14.dp).testTag("message_input"),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                            minLines = inputMinLines,
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
                        Box(Modifier.align(Alignment.Top).padding(end = 8.dp, top = 8.dp)) {
                            VoiceInputButton(recording, voice.transcribing, voice.error,
                                capability.available && controller != null && enabled && !preparing,
                                capability.reason, amplitude, toggleVoice)
                        }
                    }
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
                        ComposerModeSelector(if (goalMode) "goal" else if (discussionMode) "plan" else "default",
                            enabled, goalModeEnabled) { mode ->
                            if (onSelectMode != null) onSelectMode(mode)
                            else if ((mode == "plan") != discussionMode) onToggleMode()
                        }
                        Spacer(Modifier.weight(1f))
                        if (enabled && settingsExpanded && settingsContent != null) Dialog(onDismissRequest = onDismissSettings) {
                            Surface(Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("composer_settings_panel"),
                                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 4.dp) { settingsContent() }
                        }

                        if (!busy && isRunning && text.isBlank() && images.isEmpty() && files.isEmpty() && !voice.transcribing) OutlinedButton(
                            onClick = onStop, enabled = canStop,
                            modifier = Modifier.width(80.dp).height(44.dp).testTag("stop_turn"), shape = RoundedCornerShape(22.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp)) { Text("■ 停止", style = MaterialTheme.typography.labelLarge) }
                        else Button(
                            onClick = {
                                if (voice.transcribing) {
                                    if (controller?.markSendPending() == true) {
                                        if (recording) { recording = false; amplitude = 0f; recorder.stop() }
                                        controller.finish()
                                    }
                                } else onSend()
                            },
                            enabled = !busy && enabled && (canSend || voice.transcribing) && !preparing && !voice.sendPending,
                            modifier = Modifier.width(80.dp).height(44.dp).testTag("send_message"),
                            shape = RoundedCornerShape(22.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(14.dp).testTag("send_progress"),
                                    color = LocalContentColor.current, strokeWidth = 2.dp)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(if (voice.sendPending && !busy) "转写中…" else if (busy) "发送" else if (queueing) "↑ 排队" else if (isRunning) "↑ 补充" else "↑ 发送",
                                style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    if (attachmentsOpen) AttachmentSourceDialog(10 - images.size, 16 - files.size, onDismiss = { attachmentsOpen = false },
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
    TextButton(onClick, enabled = enabled,
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
