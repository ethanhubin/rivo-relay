package dev.rivo.relay.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun MessageContent(markdown: String, cwd: String = "", onFile: (String) -> Unit = {}) {
    val blocks = remember(markdown) { parseChatMarkdownBlocks(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block -> when (block) {
            is ChatMarkdownBlock.Text -> MessageText(block.markdown, cwd, onFile)
            is ChatMarkdownBlock.Table -> MessageTable(block, cwd, onFile)
        } }
    }
}

@Composable
private fun MessageText(markdown: String, cwd: String, onFile: (String) -> Unit, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val colors = MaterialTheme.colorScheme
    val styled = remember(markdown, colors, cwd, onFile) {
        val rendered = renderChatMarkdown(markdown, colors.primary, colors.surfaceContainerHighest, colors.onSurfaceVariant)
        AnnotatedString.Builder(rendered).apply {
            rendered.getStringAnnotations(0, rendered.length).forEach { link ->
                if (isHttpWebLink(link.item)) addLink(LinkAnnotation.Url(link.item, TextLinkStyles(style = SpanStyle(color = colors.primary))), link.start, link.end)
                else nativeFilePath(link.item, cwd)?.let { path ->
                    addLink(LinkAnnotation.Clickable(path, TextLinkStyles(style = SpanStyle(color = colors.primary))) { onFile(path) }, link.start, link.end)
                }
            }
        }.toAnnotatedString()
    }
    SelectionContainer(modifier = modifier) { Text(styled, style = style) }
}

/** QiGuang table rule: short/wide data stay a grid; narrow long-text tables become labelled cards. */
@Composable
private fun MessageTable(table: ChatMarkdownBlock.Table, cwd: String, onFile: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (shouldRenderMarkdownTableAsCards(table)) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        table.rows.forEach { row ->
            val statusIndex = row.lastIndex.takeIf { row.size >= 3 && row[it].replace(Regex("[\\\\`*_~]"), "").length <= 8 }
            Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerLow, border = BorderStroke(.5.dp, colors.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MessageText(row.first(), cwd, onFile, Modifier.weight(1f), MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        statusIndex?.let { index -> Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.widthIn(max = 112.dp)) {
                            MessageText(row[index], cwd, onFile, Modifier.padding(horizontal = 9.dp, vertical = 3.dp), MaterialTheme.typography.labelMedium)
                        } }
                    }
                    row.indices.drop(1).filter { it != statusIndex }.forEach { index -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(table.headers[index], style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        MessageText(row[index], cwd, onFile, style = MaterialTheme.typography.bodyMedium)
                    } }
                }
            }
        }
    } else Surface(shape = MaterialTheme.shapes.small, border = BorderStroke(.5.dp, colors.outlineVariant), color = colors.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = maxOf(maxWidth.value, table.headers.size * 136f).dp
            Box(Modifier.horizontalScroll(rememberScrollState())) { Column(Modifier.width(width)) {
                (listOf(table.headers) + table.rows).forEachIndexed { rowIndex, cells ->
                    if (rowIndex > 0) HorizontalDivider(thickness = .5.dp, color = colors.outlineVariant)
                    Surface(color = if (rowIndex == 0) colors.surfaceContainerLow else colors.surface) {
                        Row(Modifier.fillMaxWidth()) { cells.forEachIndexed { index, cell ->
                            MessageText(cell, cwd, onFile, Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 9.dp),
                                MaterialTheme.typography.bodySmall.copy(fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                                    textAlign = when (table.alignments.getOrNull(index)) { MarkdownTableAlignment.CENTER -> TextAlign.Center; MarkdownTableAlignment.END -> TextAlign.End; else -> TextAlign.Start }))
                        } }
                    }
                }
            } }
        }
    }
}
