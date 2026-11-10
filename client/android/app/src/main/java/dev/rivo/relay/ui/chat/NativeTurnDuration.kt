package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
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
fun NativeTurnDuration(footer: NativeTurnFooter) {
    val turn = footer.turn
    var now by remember(turn?.turnId) { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(turn?.turnId, footer.label, footer.synchronizing) {
        if (footer.label == null && !footer.synchronizing) while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1000)
        }
    }
    val seconds = turn?.let { nativeTurnElapsedSeconds(it, if (footer.synchronizing) footer.observedAt else now) }
    val text = footer.label ?: listOfNotNull(
        if (footer.synchronizing) "同步中" else "处理中",
        seconds?.let { "已用时 ${formatNativeThinkingDuration(it)}" } ?: "计时同步中"
    ).joinToString(" · ")
    Text(text, modifier = Modifier.fillMaxWidth().testTag(if (footer.label != null) "session_completion" else "turn_duration_${turn?.turnId}"),
        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
