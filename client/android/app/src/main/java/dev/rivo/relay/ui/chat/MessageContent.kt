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
    inlineImage: (@Composable (String) -> Unit)? = null,
    cacheBlocks: Boolean = true,
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
                val blocks = remember(part.value, cacheBlocks) { parseChatMarkdownBlocks(part.value, retain = cacheBlocks) }
                blocks.forEachIndexed { blockIndex, block -> key(blockIndex) { when (block) {
                    is ChatMarkdownBlock.Text -> {
                        val parsed = rememberParsedChatMarkdown(block.markdown)
                        MessageText(block.markdown, onCopyText, onQuoteText, openRegularLink, openImage, parsed = parsed)
                        if (inlineImage != null) {
                            parsed.imageSources.forEach { source -> key(source) { inlineImage(source) } }
                        }
                    }
                    is ChatMarkdownBlock.Code -> MessageCodeBlock(block.content, onCopyText, onQuoteText)
                    is ChatMarkdownBlock.Table -> ChatMarkdownTable(block, "message_$partIndex", blockIndex, onCopyText, onQuoteText, openRegularLink, openImage)
                } } }
            }
            is ReviewMessagePart.Visualization -> {
                val path = nativeFilePath(part.path, cwd)
                OutlinedCard(onClick = { path?.let(onFile) }, enabled = path != null, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("可视化 · ${part.path.substringAfterLast('/')}", style = MaterialTheme.typography.titleSmall)
                        Text("打开 HTML 预览", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            is ReviewMessagePart.Finding -> ReviewFindingCard(part.comment, cwd, onFile, onQuoteText, onCopyText, openRegularLink, openImage)
        } } }
    }
}

/** The Markdown parser excludes code spans/fences and recognizes balanced link destinations. */
internal fun markdownImageSources(markdown: String): List<String> = parsedChatMarkdown(markdown).imageSources

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
    parsed: ParsedChatMarkdown? = null,
) {
    val colors = MaterialTheme.colorScheme
    val presentation = parsed ?: rememberParsedChatMarkdown(markdown)
    val rendered = remember(presentation, colors.primary, colors.surfaceContainerHighest, colors.onSurfaceVariant) {
        presentation.styled(colors.primary, colors.surfaceContainerHighest, colors.onSurfaceVariant)
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
    if (presentation.plainTextFallback) Text("复杂格式已按完整原文显示", style = MaterialTheme.typography.labelSmall)
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

@Composable
private fun MessageCodeBlock(content: String, onCopyText: (String) -> Unit, onQuoteText: ((String) -> Unit)?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onCopyText(markdownCopyPlainText(content)) }) { Text("复制纯文本") }
                TextButton(onClick = { onCopyText(content) }) { Text("复制原文") }
            }
            SelectableReadOnlyText(
                rendered = AnnotatedString(content),
                modifier = Modifier.fillMaxWidth(),
                testTag = "message_code",
                onCopyText = onCopyText,
                onQuoteText = onQuoteText,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = LocalContentColor.current,
                ),
            )
        }
    }
}
