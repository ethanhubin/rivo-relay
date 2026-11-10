package dev.rivo.relay.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
  var visibleRows by rememberSaveable(messageId, blockIndex) { mutableIntStateOf(MARKDOWN_TABLE_BATCH_SIZE) }
  val visibleTable = remember(table, visibleRows) { table.copy(rows = table.rows.take(visibleRows)) }
  var cards by rememberSaveable(messageId, blockIndex) { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
  TextButton(onClick = { cards = !cards }, modifier = Modifier.testTag("markdown_table_toggle")) {
    Text(if (cards) "切换为表格" else "切换为卡片")
  }
  if (table.rows.size > MARKDOWN_TABLE_BATCH_SIZE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    TextButton(onClick = { onCopyText(markdownTableSource(table)) }, modifier = Modifier.testTag("markdown_table_copy_all")) { Text("复制完整表格") }
    if (onQuoteText != null) TextButton(onClick = { onQuoteText(markdownTableSource(table)) }, modifier = Modifier.testTag("markdown_table_quote_all")) { Text("引用完整表格") }
  }
  if (cards) {
    Column(
      modifier = Modifier.fillMaxWidth().testTag("markdown_table_${messageId}_$blockIndex"),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      visibleTable.rows.forEachIndexed { rowIndex, row -> key(rowIndex) {
        MarkdownTableCard(
          table = table,
          row = row,
          testTag = "markdown_table_card_${messageId}_${blockIndex}_$rowIndex",
          onCopyText = onCopyText,
          onQuoteText = onQuoteText,
          onOpenLink = onOpenLink,
          onOpenImage = onOpenImage,
        )
      } }
    }
  } else {
    MarkdownTableGrid(
      table = visibleTable,
      testTag = "markdown_table_${messageId}_$blockIndex",
      onCopyText = onCopyText,
      onQuoteText = onQuoteText,
      onOpenLink = onOpenLink,
      onOpenImage = onOpenImage,
    )
  }
  if (visibleRows < table.rows.size) TextButton(
    onClick = { visibleRows = (visibleRows + MARKDOWN_TABLE_BATCH_SIZE).coerceAtMost(table.rows.size) },
    modifier = Modifier.testTag("markdown_table_show_more")) { Text("继续显示 ${minOf(MARKDOWN_TABLE_BATCH_SIZE, table.rows.size - visibleRows)} 行（共 ${table.rows.size} 行）") }
  }
}

internal const val MARKDOWN_TABLE_BATCH_SIZE = 24
internal fun markdownTableSource(table: ChatMarkdownBlock.Table): String = table.source.ifEmpty {
  (listOf(table.headers.joinToString(" | "), table.alignments.joinToString(" | ") {
    when (it) { MarkdownTableAlignment.CENTER -> ":---:"; MarkdownTableAlignment.END -> "---:"; else -> "---" }
  }) + table.rows.map { it.joinToString(" | ") }).joinToString("\n")
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
  Surface(
    modifier = Modifier.fillMaxWidth().testTag(testTag),
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      row.forEachIndexed { index, cell ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          MarkdownTableCell(table.headers[index], Modifier.fillMaxWidth(),
            MaterialTheme.typography.labelSmall, "${testTag}_header_$index",
            onCopyText, onQuoteText, onOpenLink, onOpenImage)
          MarkdownTableCell(cell, Modifier.fillMaxWidth(),
            MaterialTheme.typography.bodyMedium, "${testTag}_cell_$index",
            onCopyText, onQuoteText, onOpenLink, onOpenImage)
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
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val widths = table.headers.indices.map { index ->
      val length = (listOf(table.headers[index]) + table.rows.map { it.getOrElse(index) { "" } })
        .maxOf { markdownVisibleLength(it) }
      (length * 8 + 20).coerceIn(88, 260).dp
    }
    val scale = maxOf(1f, maxWidth.value / widths.sumOf { it.value.toDouble() }.toFloat())
    val columnWidths = widths.map { it * scale }
    val gridWidth = columnWidths.fold(0.dp) { sum, width -> sum + width }
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
            columnWidths = columnWidths,
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
              columnWidths = columnWidths,
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

}

@Composable
private fun MarkdownTableGridRow(
  cells: List<String>,
  columnWidths: List<Dp>,
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
        modifier = Modifier.width(columnWidths[index]).padding(horizontal = 10.dp, vertical = 9.dp),
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
  val parsed = rememberParsedChatMarkdown(markdown)
  return remember(parsed, colors.primary, colors.surfaceContainerHighest, colors.onSurfaceVariant) {
    parsed.styled(colors.primary, colors.surfaceContainerHighest, colors.onSurfaceVariant)
  }
}

private fun markdownTableTextAlign(alignment: MarkdownTableAlignment): TextAlign =
  when (alignment) {
    MarkdownTableAlignment.START -> TextAlign.Start
    MarkdownTableAlignment.CENTER -> TextAlign.Center
    MarkdownTableAlignment.END -> TextAlign.End
  }
