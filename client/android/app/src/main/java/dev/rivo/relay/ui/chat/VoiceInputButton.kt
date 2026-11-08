package dev.rivo.relay.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun VoiceInputButton(recording: Boolean, transcribing: Boolean, error: String?,
                              available: Boolean, reason: String?, amplitude: Float, onToggle: () -> Unit) {
    val color = when {
        recording -> MaterialTheme.colorScheme.onErrorContainer
        transcribing -> MaterialTheme.colorScheme.onSecondaryContainer
        error != null || !available -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .48f)
        else -> MaterialTheme.colorScheme.primary
    }
    val container = when {
        recording -> MaterialTheme.colorScheme.errorContainer
        transcribing -> MaterialTheme.colorScheme.secondaryContainer
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val description = when {
        recording -> "停止录音"
        transcribing -> "取消正在转写的语音"
        !available -> reason ?: "语音服务不可用"
        error != null -> "语音转写失败，点击重试"
        else -> "开始语音输入"
    }
    TextButton(onClick = onToggle, enabled = available || recording || transcribing,
        modifier = Modifier.size(44.dp).focusProperties { canFocus = false }.testTag("voice_input_button")
            .semantics { contentDescription = description }, shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = container, contentColor = color,
            disabledContentColor = color, disabledContainerColor = container)) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = 2.dp.toPx()
            when {
                recording -> drawRoundRect(color, Offset(size.width * .25f, size.height * .25f),
                    Size(size.width * .5f, size.height * .5f), CornerRadius(size.width * .08f))
                transcribing -> listOf(.42f, .72f, .52f).forEachIndexed { index, height ->
                    drawRoundRect(color, Offset(size.width * (.2f + .23f * index), size.height * (1f - height) / 2),
                        Size(size.width * .14f, size.height * height), CornerRadius(size.width * .07f))
                }
                else -> {
                    drawRoundRect(color, Offset(size.width * .34f, size.height * .08f),
                        Size(size.width * .32f, size.height * .52f), CornerRadius(size.width * .16f), style = Stroke(stroke))
                    drawArc(color, 0f, 180f, false, Offset(size.width * .2f, size.height * .3f),
                        Size(size.width * .6f, size.height * .46f), style = Stroke(stroke))
                    drawLine(color, Offset(size.width * .5f, size.height * .76f), Offset(size.width * .5f, size.height * .91f), stroke)
                    drawLine(color, Offset(size.width * .33f, size.height * .91f), Offset(size.width * .67f, size.height * .91f), stroke)
                }
            }
            if (recording) drawCircle(color.copy(alpha = .35f), size.minDimension * (.35f + amplitude.coerceIn(0f, 1f) * .14f), style = Stroke(1.dp.toPx()))
        }
    }
}
