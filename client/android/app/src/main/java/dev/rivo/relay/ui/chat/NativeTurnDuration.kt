package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.rivo.relay.data.NativeTurnSettings
import dev.rivo.relay.data.formatNativeThinkingDuration
import dev.rivo.relay.data.nativeTurnElapsedSeconds
import kotlinx.coroutines.delay

/** The ticker is local to this label, so the history does not recompose every second. */
@Composable
fun NativeTurnDuration(turn: NativeTurnSettings) {
    var now by remember(turn.turnId, turn.startedAt) { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(turn.turnId, turn.startedAt, turn.status) {
        if (turn.status == "inProgress" && turn.startedAt != null) while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1000)
        }
    }
    if (turn.status != "inProgress") return
    val seconds = nativeTurnElapsedSeconds(turn, now) ?: return
    Text("处理中 · 已用时 ${formatNativeThinkingDuration(seconds)}",
        modifier = Modifier.fillMaxWidth().testTag("turn_duration_${turn.turnId}"),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
