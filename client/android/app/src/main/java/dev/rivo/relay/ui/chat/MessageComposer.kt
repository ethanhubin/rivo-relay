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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.luminance
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
 * QiGuang Codex Composer layout with a content-expanding, three-line BasicTextField,
 * and one compact action row (8x6dp insets, 6dp spacing).
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
    prediction: String? = null,
    canContinue: Boolean = false,
    onContinue: () -> Unit = {},
    onEditImage: (DraftImage) -> Unit = {},
    onAddFile: () -> Unit = {},
    onAddVideo: () -> Unit = onAddFile,
    onOpenFile: (JSONObject) -> Unit = {},
    discussionMode: Boolean = false,
    onToggleMode: () -> Unit = {},
    textEditable: Boolean = enabled,
    focusRequest: Int = 0,
    onPasteImages: (List<android.net.Uri>) -> Unit = {},
    settingsExpanded: Boolean = false,
    onDismissSettings: () -> Unit = {},
    settingsContent: (@Composable () -> Unit)? = null,
    goalMode: Boolean = false,
    goalModeEnabled: Boolean = false,
    onSelectMode: ((String) -> Unit)? = null,
) {
    var attachmentsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) attachmentsOpen = false }
    val context = LocalContext.current
    val launchCamera = rememberAttachmentCamera({ onPasteImages(listOf(it)) }) { message ->
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    var visibleTextHeight by remember(density) { mutableStateOf(0.dp) }
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
    LaunchedEffect(focusRequest) { if (focusRequest > 0 && textEditable) { focusRequester.requestFocus(); keyboard?.show() } }
    // QiGuang uses 42dp visible controls; preserve Rivo's 44dp touch target.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides RivoSize.MinTouchTarget) {
        Column(
            modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 10.dp)
                .testTag("message_composer"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (images.isNotEmpty() || files.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f).horizontalScroll(rememberScrollState()).testTag("draft_attachments"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.forEach { image ->
                        val bitmap = remember(image) { draftThumbnail(image.bytes) }
                        ComposerImageAttachmentRow(bitmap, enabled, enabled && !preparing,
                            { onEditImage(image) }, { onRemoveImage(image) })
                    }
                    files.forEach { file ->
                        ComposerAttachmentRow(file.string("name"), enabled, enabled && !preparing,
                            { onOpenFile(file) }, { onRemoveFile(file) },
                            Modifier.testTag("draft_file_" + file.string("name")))
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
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                // A hairline remains one physical pixel at every display density.
                border = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
                    BorderStroke(0.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                shadowElevation = 8.dp,
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top) {
                        BasicTextField(
                            value = inputValue,
                            onValueChange = { if (it.text != inputValue.text) cancelVoice(); inputValue = it; onTextChange(it.text) },
                            enabled = textEditable,
                            modifier = Modifier.weight(1f).pasteImageAttachments(context, enabled && !preparing, onPasteImages)
                                .focusRequester(focusRequester)
                                .padding(horizontal = 16.dp).heightIn(min = visibleTextHeight).testTag("message_input"),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (textEditable) 1f else 0.38f)),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                            minLines = 1,
                            maxLines = 3,
                            onTextLayout = { layout ->
                                // Fallback fonts (including Chinese) can exceed the default
                                // Latin-based maxLines height. Keep three complete line boxes.
                                visibleTextHeight = with(density) {
                                    kotlin.math.ceil(layout.getLineBottom(minOf(3, layout.lineCount) - 1)).toInt().toDp()
                                }
                            },
                            decorationBox = { inner ->
                                Box {
                                    if (text.isEmpty()) Text(
                                        prediction ?: when { isRunning -> "输入下一项任务或实时引导"; discussionMode -> "输入想讨论的方案或问题"; else -> "描述要实现或修改的功能" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    inner()
                                }
                            },
                        )
                        Box(Modifier.padding(end = 8.dp)) {
                            VoiceInputButton(recording, voice.transcribing, voice.error,
                                capability.available && controller != null && enabled && !preparing,
                                capability.reason, amplitude, toggleVoice)
                        }
                    }
                    if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box {
                            ComposerToolButton({ attachmentsOpen = true }, enabled && !preparing,
                                "添加附件", "add_attachment") { ComposerAddIcon() }
                            AttachmentSourceMenu(attachmentsOpen, 10 - images.size, 16 - files.size,
                                onDismiss = { attachmentsOpen = false },
                                onCamera = launchCamera, onImage = onAddImage, onVideo = onAddVideo, onFile = onAddFile,
                                onWorkspace = onAddWorkspaceFile)
                        }
                        ComposerModeSelector(if (goalMode) "goal" else if (discussionMode) "plan" else "default",
                            enabled, goalModeEnabled) { mode ->
                            if (onSelectMode != null) onSelectMode(mode)
                            else if ((mode == "plan") != discussionMode) onToggleMode()
                        }
                        Spacer(Modifier.weight(1f))
                        if (settingsExpanded && settingsContent != null) Dialog(onDismissRequest = onDismissSettings) {
 dev.rivo.relay.ui.chat.ThemedTextContextMenu {

                            Surface(Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("composer_settings_panel"),
                                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 4.dp) { settingsContent() }

}
}

                        if (!busy && isRunning && text.isBlank() && images.isEmpty() && files.isEmpty() && !voice.transcribing) OutlinedButton(
                            onClick = onStop, enabled = canStop,
                            modifier = Modifier.width(80.dp).height(44.dp).testTag("stop_turn"), shape = RoundedCornerShape(22.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp)) { Text("■ 停止", style = MaterialTheme.typography.labelLarge) }
                        else if (canContinue && prediction == null && text.isBlank() && images.isEmpty() && files.isEmpty() && !voice.transcribing && !recording) Button(
                            onClick = onContinue, enabled = enabled && !busy && !preparing,
                            modifier = Modifier.width(80.dp).height(44.dp).testTag("continue_turn"),
                            shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("▶ 继续", style = MaterialTheme.typography.labelLarge) }
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
                            modifier = Modifier.width(80.dp).height(if (queueing) 40.dp else 44.dp).testTag("send_message"),
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


}

@Composable
internal fun ComposerToolButton(
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
internal fun ComposerAddIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(28.dp)) {
        val stroke = size.minDimension * .10f
        val inset = size.minDimension * .22f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawLine(color, Offset(inset, center.y), Offset(size.width - inset, center.y), stroke, StrokeCap.Round)
        drawLine(color, Offset(center.x, inset), Offset(center.x, size.height - inset), stroke, StrokeCap.Round)
    }
}
