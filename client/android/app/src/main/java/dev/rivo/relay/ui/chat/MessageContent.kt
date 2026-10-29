package dev.rivo.relay.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp

/** QiGuang's selectable message rendering, using Relay's existing native file boundary. */
@Composable
fun MessageContent(
    markdown: String,
    cwd: String = "",
    onQuoteText: ((String) -> Unit)? = null,
    onSaveFile: ((String) -> Unit)? = null,
    onFile: (String) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val onCopyText: (String) -> Unit = { clipboard.setText(AnnotatedString(it)) }
    val openExternalLink = rememberConfirmedWebLinkOpener(markdown, onSaveFile)
    val openRegularLink: (String) -> Unit = { target ->
        when {
            isMessageFileWebLink(target) -> onFile(target)
            isHttpWebLink(target) -> openExternalLink(target)
            else -> messageFileTarget(target, cwd)?.let(onFile)
        }
    }
    val openImage: (String) -> Unit = { target ->
        if (isHttpWebLink(target)) onFile(target)
        else messageFileTarget(target, cwd)?.let(onFile)
    }
    val parts = remember(markdown) { reviewMessageParts(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        parts.forEachIndexed { partIndex, part -> key(partIndex) { when (part) {
            is ReviewMessagePart.Text -> {
                val blocks = remember(part.value) { parseChatMarkdownBlocks(part.value) }
                blocks.forEachIndexed { blockIndex, block -> key(blockIndex) { when (block) {
                    is ChatMarkdownBlock.Text -> MessageText(block.markdown, onCopyText, onQuoteText, openRegularLink, openImage)
                    is ChatMarkdownBlock.Table -> ChatMarkdownTable(block, "message_$partIndex", blockIndex, onCopyText, onQuoteText, openRegularLink, openImage)
                } } }
            }
            is ReviewMessagePart.Finding -> ReviewFindingCard(part.comment, cwd, onFile, onQuoteText, onCopyText, openRegularLink, openImage)
        } } }
    }
}

@Composable
private fun ReviewFindingCard(comment: ReviewComment, cwd: String, onFile: (String) -> Unit, onQuoteText: ((String) -> Unit)?, onCopyText: (String) -> Unit, onOpenLink: (String) -> Unit, onOpenImage: (String) -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                comment.priority?.let { priority -> Surface(color = if (priority <= 1) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small) { Text("P$priority", Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium) } }
                Text(comment.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            }
            MessageText(comment.body, onCopyText, onQuoteText, onOpenLink, onOpenImage, style = MaterialTheme.typography.bodyMedium)
            val path = nativeFilePath(comment.file, cwd)
            TextButton(onClick = { path?.let { onFile(it + comment.start?.let { line -> ":$line" }.orEmpty()) } }, enabled = path != null, contentPadding = PaddingValues(0.dp)) {
                Text(comment.file + comment.start?.let { start -> ":$start" + comment.end?.takeIf { it != start }?.let { "–$it" }.orEmpty() }.orEmpty(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun MessageText(
    markdown: String,
    onCopyText: (String) -> Unit,
    onQuoteText: ((String) -> Unit)?,
    onOpenLink: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val colors = MaterialTheme.colorScheme
    val rendered = remember(markdown, colors.primary, colors.surfaceContainerHighest, colors.onSurfaceVariant) {
        renderChatMarkdown(markdown, colors.primary, colors.surfaceContainerHighest, colors.onSurfaceVariant)
    }
    SelectableReadOnlyText(
        rendered = rendered,
        modifier = modifier,
        testTag = "message_text",
        onCopyText = onCopyText,
        onQuoteText = onQuoteText,
        onOpenLink = onOpenLink,
        onOpenImageLink = onOpenImage,
        textStyle = style.copy(color = LocalContentColor.current),
    )
}

/** Preserve source locations while nativeFilePath resolves and validates the native path. */
internal fun messageFileTarget(target: String, cwd: String): String? {
    // Do not reinterpret web/app schemes as relative native paths.
    val withoutLocation = target.replace(Regex(""":\d+(?::\d+)?$"""), "")
    val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(withoutLocation)?.value
    if (scheme != null && !scheme.equals("file:", true) && !Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(target)) return null
    val path = nativeFilePath(target, cwd) ?: return null
    val location = Regex("""(?:#L|:)(\d+)(?::\d+|(?:C\d+)?(?:-L?\d+(?:C\d+)?)?)?$""")
        .find(target)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    return path + location?.let { ":$it" }.orEmpty()
}

/** QiGuang resolves file links before offering the external webpage menu. */
internal fun isMessageFileWebLink(target: String): Boolean =
    isHttpWebLink(target) && fileMimeType(target) !in setOf("application/octet-stream", "text/html")
