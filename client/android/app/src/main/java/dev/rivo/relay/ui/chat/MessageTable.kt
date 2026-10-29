package dev.rivo.relay.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Directly migrated from QiGuang ChatTimelineContent.
@Composable
internal fun ChatMarkdownTable(
  table: ChatMarkdownBlock.Table,
  messageId: String,
  blockIndex: Int,
  onCopyText: (String) -> Unit,
  onQuoteText: ((String) -> Unit)?,
  onOpenLink: (String) -> Unit,
  onOpenImage: (String) -> Unit,
) {
  if (shouldRenderMarkdownTableAsCards(table)) {
    Column(
      modifier = Modifier.fillMaxWidth().testTag("markdown_table_${messageId}_$blockIndex"),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      table.rows.forEachIndexed { rowIndex, row ->
        MarkdownTableCard(
          table = table,
          row = row,
          testTag = "markdown_table_card_${messageId}_${blockIndex}_$rowIndex",
          onCopyText = onCopyText,
          onQuoteText = onQuoteText,
          onOpenLink = onOpenLink,
          onOpenImage = onOpenImage,
        )
      }
    }
  } else {
    MarkdownTableGrid(
      table = table,
      testTag = "markdown_table_${messageId}_$blockIndex",
      onCopyText = onCopyText,
      onQuoteText = onQuoteText,
      onOpenLink = onOpenLink,
      onOpenImage = onOpenImage,
    )
  }
}

@Composable
private fun MarkdownTableCard(
  table: ChatMarkdownBlock.Table,
  row: List<String>,
  testTag: String,
  onCopyText: (String) -> Unit,
  onQuoteText: ((String) -> Unit)?,
  onOpenLink: (String) -> Unit,
  onOpenImage: (String) -> Unit,
) {
  val statusIndex = row.lastIndex.takeIf { row.size >= 3 && markdownTableCellIsCompact(row[it]) }
  Surface(
    modifier = Modifier.fillMaxWidth().testTag(testTag),
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        MarkdownTableCell(
          markdown = row.first(),
          modifier = Modifier.weight(1f),
          textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
          testTag = "${testTag}_title",
          onCopyText = onCopyText,
          onQuoteText = onQuoteText,
          onOpenLink = onOpenLink,
          onOpenImage = onOpenImage,
        )
        statusIndex?.let { index ->
          Surface(
            modifier = Modifier.width(IntrinsicSize.Min).widthIn(max = 112.dp),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
          ) {
            MarkdownTableCell(
              markdown = row[index],
              modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
              textStyle = MaterialTheme.typography.labelMedium,
              testTag = "${testTag}_status",
              onCopyText = onCopyText,
              onQuoteText = onQuoteText,
              onOpenLink = onOpenLink,
              onOpenImage = onOpenImage,
            )
          }
        }
      }
      row.indices.drop(1).filter { it != statusIndex }.forEach { columnIndex ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            table.headers[columnIndex],
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
          )
          MarkdownTableCell(
            markdown = row[columnIndex],
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium,
            testTag = "${testTag}_cell_$columnIndex",
            onCopyText = onCopyText,
            onQuoteText = onQuoteText,
            onOpenLink = onOpenLink,
            onOpenImage = onOpenImage,
          )
        }
      }
    }
  }
}

@Composable
private fun MarkdownTableGrid(
  table: ChatMarkdownBlock.Table,
  testTag: String,
  onCopyText: (String) -> Unit,
  onQuoteText: ((String) -> Unit)?,
  onOpenLink: (String) -> Unit,
  onOpenImage: (String) -> Unit,
) {
  val gridWidth = maxOf(LocalConfiguration.current.screenWidthDp - 40, table.headers.size * 136).dp
  Surface(
    modifier = Modifier.fillMaxWidth().testTag(testTag),
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    color = MaterialTheme.colorScheme.surface,
  ) {
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
      Column(Modifier.width(gridWidth)) {
        MarkdownTableGridRow(
          cells = table.headers,
          alignments = table.alignments,
          header = true,
          testTag = "${testTag}_header",
          onCopyText = onCopyText,
          onQuoteText = onQuoteText,
          onOpenLink = onOpenLink,
          onOpenImage = onOpenImage,
        )
        table.rows.forEachIndexed { rowIndex, row ->
          Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant))
          MarkdownTableGridRow(
            cells = row,
            alignments = table.alignments,
            header = false,
            testTag = "${testTag}_row_$rowIndex",
            onCopyText = onCopyText,
            onQuoteText = onQuoteText,
            onOpenLink = onOpenLink,
            onOpenImage = onOpenImage,
          )
        }
      }
    }
  }
}

@Composable
private fun MarkdownTableGridRow(
  cells: List<String>,
  alignments: List<MarkdownTableAlignment>,
  header: Boolean,
  testTag: String,
  onCopyText: (String) -> Unit,
  onQuoteText: ((String) -> Unit)?,
  onOpenLink: (String) -> Unit,
  onOpenImage: (String) -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth().testTag(testTag)
        .then(if (header) Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow) else Modifier),
  ) {
    cells.forEachIndexed { index, cell ->
      MarkdownTableCell(
        markdown = cell,
        modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 9.dp),
        textStyle =
          MaterialTheme.typography.bodySmall.copy(
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = markdownTableTextAlign(alignments.getOrElse(index) { MarkdownTableAlignment.START }),
          ),
        testTag = "${testTag}_cell_$index",
        onCopyText = onCopyText,
        onQuoteText = onQuoteText,
        onOpenLink = onOpenLink,
        onOpenImage = onOpenImage,
      )
    }
  }
}

@Composable
private fun MarkdownTableCell(
  markdown: String,
  modifier: Modifier,
  textStyle: TextStyle,
  testTag: String,
  onCopyText: (String) -> Unit,
  onQuoteText: ((String) -> Unit)?,
  onOpenLink: (String) -> Unit,
  onOpenImage: (String) -> Unit,
) {
  SelectableReadOnlyText(
    rendered = renderMarkdownTableCell(markdown),
    modifier = modifier,
    testTag = testTag,
    onCopyText = onCopyText,
    onQuoteText = onQuoteText,
    onOpenLink = onOpenLink,
    onOpenImageLink = onOpenImage,
    textStyle = textStyle.copy(color = LocalContentColor.current),
  )
}

@Composable
private fun renderMarkdownTableCell(markdown: String): AnnotatedString {
  val colors = MaterialTheme.colorScheme
  return remember(markdown, colors.primary, colors.surfaceContainerHighest, colors.onSurfaceVariant) {
    renderChatMarkdown(markdown, colors.primary, colors.surfaceContainerHighest, colors.onSurfaceVariant)
  }
}

private fun markdownTableCellIsCompact(markdown: String): Boolean =
  markdown.replace(Regex("[\\\\`*_~]"), "").length <= 8

private fun markdownTableTextAlign(alignment: MarkdownTableAlignment): TextAlign =
  when (alignment) {
    MarkdownTableAlignment.START -> TextAlign.Start
    MarkdownTableAlignment.CENTER -> TextAlign.Center
    MarkdownTableAlignment.END -> TextAlign.End
  }
