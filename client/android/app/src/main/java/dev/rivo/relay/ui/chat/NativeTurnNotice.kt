package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.NativeTurnSettings
import dev.rivo.relay.data.string

/** Native turn metadata, not an invented assistant message or retry instruction. */
@Composable
internal fun NativeTurnNotice(turn: NativeTurnSettings) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().testTag("native_turn_notice_${turn.turnId}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(when (turn.status) {
                "failed" -> "本轮执行失败"
                "interrupted" -> "本轮已中断"
                "canceled", "cancelled" -> "本轮已取消"
                else -> "本轮原生诊断"
            }, style = MaterialTheme.typography.labelMedium,
                color = if (turn.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            turn.error?.string("message")?.takeIf(String::isNotBlank)?.let { message ->
                SelectionContainer { Text(boundedMarkdownPreview(message).let { it.text + if (it.truncated) "\n… 内容已截断 …" else "" },
                    style = MaterialTheme.typography.bodySmall) }
            }
            turn.error?.string("additionalDetails")?.takeIf(String::isNotBlank)?.let { details ->
                SelectionContainer { Text(boundedMarkdownPreview(details).text, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
