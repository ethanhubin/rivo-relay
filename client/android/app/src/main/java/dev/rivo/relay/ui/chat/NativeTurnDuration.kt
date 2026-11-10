package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.NativeTurnSettings
import dev.rivo.relay.data.formatNativeThinkingDuration
import dev.rivo.relay.data.nativeTurnElapsedSeconds
import kotlinx.coroutines.delay

/** The ticker is local to this label, so the history does not recompose every second. */
@Composable
fun NativeTurnDuration(turn: NativeTurnSettings, running: Boolean = false) {
    var now by remember(turn.turnId, turn.startedAt) { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(turn.turnId, turn.startedAt, running) {
        if (running && turn.startedAt != null) while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1000)
        }
    }
    val seconds = nativeTurnElapsedSeconds(turn, now.takeIf { running }) ?: return
    Column(Modifier.fillMaxWidth().testTag("turn_duration_${turn.turnId}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${if (running) "处理中 · 已用时" else "用时"} ${formatNativeThinkingDuration(seconds)}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!running) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
