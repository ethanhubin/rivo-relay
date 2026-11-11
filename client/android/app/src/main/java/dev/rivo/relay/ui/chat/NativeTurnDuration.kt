package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import dev.rivo.relay.data.*
import kotlinx.coroutines.delay

/** Running, syncing and completed states occupy the same lazy-list item. */
@Composable
fun NativeTurnDuration(footer: NativeTurnFooter, elapsedRealtime: () -> Long = android.os.SystemClock::elapsedRealtime) {
    val turn = footer.turn
    var now by remember(turn?.turnId) { mutableLongStateOf(elapsedRealtime()) }
    LaunchedEffect(turn?.turnId, footer.label) {
        if (footer.label == null) while (true) {
            now = elapsedRealtime()
            delay(1000)
        }
    }
    val seconds = clientTurnElapsedSeconds(footer, now)
    val text = footer.label ?: seconds?.let { "已用时 ${formatNativeThinkingDuration(it)}" } ?: "执行中"
    if (footer.label == null) {
        ChatTimelineSummaryRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ElapsedClockGlyph(seconds ?: 0)
            Text(text, modifier = Modifier.weight(1f).testTag("turn_duration_${turn?.turnId}"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else AssistantMessageFooter("", "turn:${turn?.turnId}", turn?.completedAt, completionLabel = text)
}

/** Historical rows omit the live conversation's completion status. */
@Composable
fun HistoricalTurnTiming(timings: List<NativeHistoricalTiming>) {
    timings.forEach { timing ->
        TurnTimingLine(timing.label, "historical_turn_timing_${timing.turnId}")
    }
}

@Composable
private fun TurnTimingLine(text: String, tag: String) {
    Text(text, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag(tag),
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Command-sized frame with a clockwise hand ticking 30 degrees each second. */
@Composable
private fun ElapsedClockGlyph(seconds: Long) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "elapsed_border")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "elapsed_border_phase",
    )
    Canvas(Modifier.size(18.dp).testTag("turn_duration_spinner")) {
        val stroke = 1.35.dp.toPx()
        val outline = Path().apply {
            addRoundRect(RoundRect(
                left = size.width * .12f, top = size.height * .14f,
                right = size.width * .88f, bottom = size.height * .84f,
                cornerRadius = CornerRadius(size.width * .12f),
            ))
        }
        drawPath(outline, color.copy(alpha = .25f), style = Stroke(stroke))
        val measure = PathMeasure().apply { setPath(outline, true) }
        val start = phase * measure.length
        val end = start + measure.length * .30f
        val highlight = Path().apply {
            measure.getSegment(start, minOf(end, measure.length), this)
            if (end > measure.length) {
                measure.getSegment(0f, end - measure.length, this)
            }
        }
        drawPath(highlight, color, style = Stroke(stroke, cap = StrokeCap.Round))
        val pivot = Offset(size.width * .50f, size.height * .49f)
        rotate((seconds % 12) * 30f, pivot) {
            drawLine(color, pivot, Offset(pivot.x, pivot.y - size.height * .23f),
                strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
