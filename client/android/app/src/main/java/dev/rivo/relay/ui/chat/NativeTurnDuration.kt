package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    val text = footer.label ?: "已用时 ${formatNativeThinkingDuration(seconds ?: 0)}"
    TurnTimingLine(text, if (footer.label != null) "session_completion" else "turn_duration_${turn?.turnId}")
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
        style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
