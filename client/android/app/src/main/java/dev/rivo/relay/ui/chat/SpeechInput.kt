package dev.rivo.relay.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

/** System speech service transcribes into an editable draft; it never submits a Codex turn. */
@Composable
internal fun SpeechInputDialog(onDismiss: () -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    var transcript by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val recognizer = remember { if (available) SpeechRecognizer.createSpeechRecognizer(context) else null }
    val currentResult by rememberUpdatedState(onResult)
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening = false }
            override fun onError(code: Int) {
                listening = false
                error = when (code) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请允许麦克风权限后重试。"
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有识别到语音，请重试。"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统语音服务网络不可用，请重试。"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "系统语音服务忙，请稍后重试。"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "系统语音服务暂不支持当前语言。"
                    else -> "系统语音服务不可用（$code），可继续键盘输入。"
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { transcript = it }
            }
            override fun onPartialResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { transcript = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose { recognizer?.cancel(); recognizer?.destroy() }
    }
    fun start() {
        error = null
        try {
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
            listening = recognizer != null
        } catch (e: Exception) { listening = false; error = e.message ?: "无法启动系统语音服务。" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else error = "未获得麦克风权限，可继续键盘输入。"
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("语音输入") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (available) "使用设备的系统语音服务转写，服务可能需要联网。确认文字后加入草稿。" else "此设备未安装可用的系统语音识别服务。请使用键盘的语音输入或安装系统语音服务。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(transcript, { transcript = it }, label = { Text("识别文字，可修改") }, minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("speech_transcript"))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (listening) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                if (listening) { recognizer?.stopListening(); listening = false }
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }, enabled = available, modifier = Modifier.testTag("speech_record")) { Text(if (listening) "结束录音" else "开始录音") }
        }
    }, confirmButton = { TextButton(onClick = { recognizer?.cancel(); currentResult(transcript.trim()) },
        enabled = transcript.isNotBlank(), modifier = Modifier.testTag("speech_use_text")) { Text("加入草稿") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
