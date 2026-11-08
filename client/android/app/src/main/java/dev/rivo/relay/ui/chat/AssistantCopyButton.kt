package dev.rivo.relay.ui.chat

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
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

@Composable
internal fun AssistantCopyButton(text: String, identity: String) {
    if (text.isBlank()) return
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // Match QiGuang's compact copy action below assistant responses.
    Text(
        "⧉",
        modifier = Modifier
            .clickable(role = Role.Button, onClickLabel = "复制回复") {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .semantics { contentDescription = "复制回复" }
            .testTag("assistant_copy_$identity"),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun AssistantMessageFooter(text: String, identity: String, outputAt: Long?, completionLabel: String? = null) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        if (completionLabel != null) Text(
            completionLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("session_completion"),
        ) else if (outputAt != null) Text(
            java.time.Instant.ofEpochSecond(outputAt).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("message_output_time"),
        )
        AssistantCopyButton(text, identity)
    }
}
