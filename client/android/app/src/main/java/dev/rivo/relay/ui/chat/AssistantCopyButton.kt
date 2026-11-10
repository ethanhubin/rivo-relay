package dev.rivo.relay.ui.chat

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rivo.relay.data.NativeHistoricalTiming

@Composable
internal fun AssistantCopyButton(text: String, identity: String) {
    if (text.isBlank()) return
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    FooterAction("复制回复", "assistant_copy_$identity", onClick = {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }) {
        Text("⧉", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FooterAction(description: String, tag: String, enabled: Boolean = true,
    onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(24.dp).clickable(enabled = enabled, role = Role.Button,
        onClickLabel = description, onClick = onClick)
        .semantics { contentDescription = description }.testTag(tag),
        contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun AssistantForkButton(turnId: String, enabled: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f)
    FooterAction("从这里创建聊天分支", "fork_turn_$turnId", enabled, onClick) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(dev.rivo.relay.R.drawable.ic_codex_branch),
            contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
    }
}

@Composable
internal fun AssistantMessageFooter(text: String, identity: String, outputAt: Long?, completionLabel: String? = null,
    forkTurnId: String? = null, forkEnabled: Boolean = true, onFork: (() -> Unit)? = null,
    historicalTiming: NativeHistoricalTiming? = null) {
    val date = outputAt?.let { runCatching {
        java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrNull() }
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        if (completionLabel != null) Text(
            completionLabel.substringBefore(" 耗时") +
                (date?.let { " $it" } ?: "") +
                completionLabel.substringAfter(" 耗时", "").takeIf { it.isNotEmpty() }?.let { " 耗时$it" }.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false).testTag("session_completion"),
        ) else if (date != null) Text(
            date,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("message_output_time"),
        )
        historicalTiming?.let { timing ->
            Text(timing.label, modifier = Modifier.weight(1f, fill = false).testTag("historical_turn_timing_${timing.turnId}"),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AssistantCopyButton(text, identity)
        if (forkTurnId != null && onFork != null) AssistantForkButton(forkTurnId, forkEnabled, onFork)
    }
}
