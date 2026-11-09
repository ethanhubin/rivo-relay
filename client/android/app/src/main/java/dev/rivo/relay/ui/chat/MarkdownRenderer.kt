package dev.rivo.relay.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/** A deliberately small Markdown renderer for chat messages.
 *
 * It handles the formatting commonly emitted by the assistants without making
 * message display depend on a WebView: headings, quotes, lists, fenced and
 * inline code, emphasis, strike-through, links and image labels.
 */
internal fun renderChatMarkdown(
  markdown: String,
  linkColor: Color,
  codeBackground: Color,
  quoteColor: Color,
): AnnotatedString = parsedChatMarkdown(markdown).styled(linkColor, codeBackground, quoteColor)

internal fun renderMarkdownSyntax(markdown: String, budget: MarkdownScanBudget): AnnotatedString = buildAnnotatedString {
  val linkColor = MARKDOWN_LINK_COLOR
  val codeBackground = MARKDOWN_CODE_COLOR
  val quoteColor = MARKDOWN_QUOTE_COLOR
  val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
  var fence: MarkdownFence? = null
  lines.forEachIndexed { index, rawLine ->
    var marker = false
    if (fence != null) {
      if (fence!!.closes(rawLine)) { fence = null; marker = true }
      else withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(rawLine) }
    } else {
      val opening = markdownFenceAt(rawLine)
      if (opening != null) { fence = opening; marker = true }
      else appendMarkdownLine(rawLine, linkColor, codeBackground, quoteColor, budget)
    }
    if (index != lines.lastIndex && !marker) append('\n')
  }
}

internal sealed interface ChatMarkdownBlock {
  data class Text(val markdown: String) : ChatMarkdownBlock
  data class Code(val content: String) : ChatMarkdownBlock

  data class Table(
    val headers: List<String>,
    val alignments: List<MarkdownTableAlignment>,
    val rows: List<List<String>>,
    val source: String = "",
  ) : ChatMarkdownBlock
}

internal enum class MarkdownTableAlignment { START, CENTER, END }

/** Splits chat Markdown into independently rendered blocks while leaving non-table messages intact. */
internal class MarkdownBlockCache(private val maxChars: Int = 512 * 1024, private val maxEntries: Int = 128) {
  private val entries = LinkedHashMap<String, List<ChatMarkdownBlock>>(16, .75f, true)
  private var chars = 0
  @Synchronized fun get(markdown: String, retain: Boolean = true): List<ChatMarkdownBlock> {
    if (!retain) return splitChatMarkdownBlocks(markdown)
    entries[markdown]?.let { return it }
    val result = splitChatMarkdownBlocks(markdown)
    if (markdown.length > maxChars) return result
    entries[markdown] = result
    chars += markdown.length
    while (chars > maxChars || entries.size > maxEntries) {
      val iterator = entries.entries.iterator()
      chars -= iterator.next().key.length
      iterator.remove()
    }
    return result
  }
}

private val markdownBlocks = MarkdownBlockCache()
internal fun parseChatMarkdownBlocks(markdown: String, retain: Boolean = true): List<ChatMarkdownBlock> = markdownBlocks.get(markdown, retain)

internal fun splitChatMarkdownBlocks(markdown: String): List<ChatMarkdownBlock> {
  val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
  val lines = normalized.split('\n')
  val blocks = mutableListOf<ChatMarkdownBlock>()
  var textStart = 0
  var index = 0

  fun appendText(endExclusive: Int) {
    val text = lines.subList(textStart, endExclusive).joinToString("\n").trim('\n')
    if (text.isNotBlank()) blocks += ChatMarkdownBlock.Text(text)
  }

  while (index < lines.size) {
    val opening = markdownFenceAt(lines[index])
    if (opening != null) {
      appendText(index)
      val start = index + 1
      var end = start
      while (end < lines.size && !opening.closes(lines[end])) end += 1
      blocks += ChatMarkdownBlock.Code(lines.subList(start, end).joinToString("\n"))
      index = if (end < lines.size) end + 1 else end
      textStart = index
      continue
    }
    if (index + 1 < lines.size) {
      val headers = parseMarkdownTableRow(lines[index])
      val alignments = headers?.let { parseMarkdownTableSeparator(lines[index + 1], it.size) }
      if (headers != null && alignments != null) {
        appendText(index)
        val rows = mutableListOf<List<String>>()
        var rowIndex = index + 2
        while (rowIndex < lines.size && lines[rowIndex].isNotBlank()) {
          if (markdownFenceAt(lines[rowIndex]) != null) break
          val row = parseMarkdownTableRow(lines[rowIndex]) ?: break
          if (row.size > headers.size) break // Preserve extra cells as visible text instead of dropping data.
          rows += row + List(headers.size - row.size) { "" }
          rowIndex += 1
        }
        blocks += ChatMarkdownBlock.Table(headers, alignments, rows, lines.subList(index, rowIndex).joinToString("\n"))
        index = rowIndex
        textStart = rowIndex
        continue
      }
    }
    index += 1
  }
  appendText(lines.size)
  return blocks.ifEmpty { listOf(ChatMarkdownBlock.Text(normalized)) }
}

internal fun shouldRenderMarkdownTableAsCards(table: ChatMarkdownBlock.Table): Boolean =
  table.headers.size <= 3 && table.rows.any { row -> row.any { markdownVisibleLength(it) >= 12 } }

private fun parseMarkdownTableSeparator(
  line: String,
  expectedColumns: Int,
): List<MarkdownTableAlignment>? {
  val cells = parseMarkdownTableRow(line)?.takeIf { it.size == expectedColumns } ?: return null
  return cells.map { cell ->
    val marker = cell.replace(" ", "")
    if (!Regex("^:?-{3,}:?$").matches(marker)) return null
    when {
      marker.startsWith(':') && marker.endsWith(':') -> MarkdownTableAlignment.CENTER
      marker.endsWith(':') -> MarkdownTableAlignment.END
      else -> MarkdownTableAlignment.START
    }
  }
}

private fun parseMarkdownTableRow(line: String): List<String>? {
  return try { parseMarkdownTableRow(line, MarkdownScanBudget(line.length)) } catch (_: MarkdownScanLimit) { null }
}

private fun parseMarkdownTableRow(line: String, budget: MarkdownScanBudget): List<String>? {
  val source = line.trim()
  if (source.isEmpty()) return null
  val cells = mutableListOf<String>()
  val cell = StringBuilder()
  var escaped = false
  var codeTicks = 0
  var delimiterCount = 0
  var index = 0
  while (index < source.length) {
    budget.consume()
    val character = source[index]
    when {
      escaped -> {
        if (character != '|') cell.append('\\')
        cell.append(character)
        escaped = false
      }
      character == '\\' -> escaped = true
      character == '`' -> {
        val count = markdownRunLength(source, index, '`')
        if (codeTicks == count) codeTicks = 0
        else if (codeTicks == 0 && findCodeSpanEnd(source, index + count, count, budget) >= 0) codeTicks = count
        cell.append("`".repeat(count))
        index += count
        continue
      }
      character == '|' && codeTicks == 0 -> {
        cells += cell.toString().trim()
        cell.clear()
        delimiterCount += 1
      }
      else -> cell.append(character)
    }
    index++
  }
  if (escaped) cell.append('\\')
  cells += cell.toString().trim()
  if (delimiterCount == 0) return null
  if (source.startsWith('|') && cells.firstOrNull().isNullOrEmpty()) cells.removeAt(0)
  if (source.endsWith('|') && cells.lastOrNull().isNullOrEmpty()) cells.removeAt(cells.lastIndex)
  return cells.takeIf { it.isNotEmpty() }
}

private fun markdownVisibleLength(markdown: String): Int =
  markdown.count { it !in "\\`*_~" }

internal data class BoundedMarkdownPreview(val text: String, val truncated: Boolean)

internal fun boundedMarkdownPreview(
  markdown: String,
  maxChars: Int = MARKDOWN_PREVIEW_MAX_CHARS,
): BoundedMarkdownPreview {
  require(maxChars > 0) { "maxChars 必须为正数" }
  if (markdown.length <= maxChars) return BoundedMarkdownPreview(markdown, false)
  val safeEnd =
    if (markdown[maxChars - 1].isHighSurrogate() && markdown[maxChars].isLowSurrogate()) {
      maxChars - 1
    } else {
      maxChars
    }
  return BoundedMarkdownPreview(markdown.substring(0, safeEnd), true)
}

private fun AnnotatedString.Builder.appendMarkdownLine(
  rawLine: String,
  linkColor: Color,
  codeBackground: Color,
  quoteColor: Color,
  budget: MarkdownScanBudget,
) {
  val heading = Regex("^(#{1,6})\\s+(.*)$").matchEntire(rawLine)
  if (heading != null) {
    val level = heading.groupValues[1].length
    val size = when (level) {
      1 -> 1.42.em
      2 -> 1.30.em
      3 -> 1.20.em
      else -> 1.08.em
    }
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size)) {
      appendInlineMarkdown(heading.groupValues[2], linkColor, codeBackground, budget)
    }
    return
  }

  val quote = Regex("^\\s*>\\s?(.*)$").matchEntire(rawLine)
  if (quote != null) {
    withStyle(SpanStyle(color = quoteColor, fontWeight = FontWeight.Bold)) { append("▎ ") }
    withStyle(SpanStyle(color = quoteColor, fontStyle = FontStyle.Italic)) {
      appendInlineMarkdown(quote.groupValues[1], linkColor, codeBackground, budget)
    }
    return
  }

  val task = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+(.*)$").matchEntire(rawLine)
  if (task != null) {
    append(task.groupValues[1])
    append(if (task.groupValues[2].isBlank()) "☐ " else "☑ ")
    appendInlineMarkdown(task.groupValues[3], linkColor, codeBackground, budget)
    return
  }

  val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$").matchEntire(rawLine)
  if (bullet != null) {
    append(bullet.groupValues[1])
    append("• ")
    appendInlineMarkdown(bullet.groupValues[2], linkColor, codeBackground, budget)
    return
  }

  if (markdownHorizontalRule(rawLine)) {
    withStyle(SpanStyle(color = quoteColor)) { append("────────────") }
    return
  }

  appendInlineMarkdown(rawLine, linkColor, codeBackground, budget)
}

private fun markdownHorizontalRule(line: String): Boolean {
  val indent = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return false
  if (indent > 3) return false
  val marker = line[indent].takeIf { it in "-*_" } ?: return false
  var count = 0
  for (index in indent until line.length) {
    if (line[index] == marker) count++ else if (!line[index].isWhitespace()) return false
  }
  return count >= 3
}

private fun AnnotatedString.Builder.appendInlineMarkdown(
  source: String,
  linkColor: Color,
  codeBackground: Color,
  budget: MarkdownScanBudget,
  depth: Int = 0,
  allowLinks: Boolean = true,
) {
  if (depth >= 16) { append(source); return }
  var index = 0
  while (index < source.length) {
    budget.consume()
    val fileCitation = if (allowLinks) codexFileCitationAt(source, index, budget) else null
    if (fileCitation != null) {
      pushStringAnnotation(MARKDOWN_LINK_TAG, fileCitation.path)
      withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
        append(fileCitation.name)
      }
      pop()
      index = fileCitation.endExclusive
      continue
    }
    val webLink = if (allowLinks) plainWebLinkAt(source, index, budget) else null
    if (webLink != null) {
      pushStringAnnotation(MARKDOWN_LINK_TAG, webLink)
      withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
        append(webLink)
      }
      pop()
      index += webLink.length
      continue
    }
    if (source[index] == '\\' && index + 1 < source.length && source[index + 1] in MARKDOWN_ESCAPES) {
      append(source[index + 1])
      index += 2
      continue
    }

    if (source[index] == '`') {
      val count = markdownRunLength(source, index, '`')
      val end = findCodeSpanEnd(source, index + count, count, budget)
      if (end >= 0) {
        var literal = source.substring(index + count, end).replace('\n', ' ')
        if (literal.startsWith(' ') && literal.endsWith(' ') && literal.any { it != ' ' }) literal = literal.drop(1).dropLast(1)
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(literal) }
        index = end + count
        continue
      }
      append("`".repeat(count)); index += count; continue
    }
    val link = if (allowLinks) markdownInlineLinkAt(source, index, budget) else null
    if (link != null) {
      val image = link.image || isLikelyImageLink(link.label, link.target)
      val label = link.label.ifBlank { if (image) "图片" else link.target }
      if (link.target.isNotEmpty()) pushStringAnnotation(if (image) MARKDOWN_IMAGE_LINK_TAG else MARKDOWN_LINK_TAG, link.target)
      withStyle(SpanStyle(color = linkColor, textDecoration = if (image) null else TextDecoration.Underline,
          fontStyle = if (image) FontStyle.Italic else null)) {
        if (link.image) append("🖼 ")
        appendInlineMarkdown(label, linkColor, codeBackground, budget, depth + 1, allowLinks = false)
      }
      if (link.target.isNotEmpty()) pop()
      index = link.endExclusive
      continue
    }

    val token = INLINE_TOKENS.firstOrNull { source.startsWith(it.delimiter, index) }
    if (token != null) {
      val contentStart = index + token.delimiter.length
      val end = markdownIndexOf(source, token.delimiter, contentStart, budget)
      if (end > contentStart) {
        withStyle(token.style(codeBackground)) {
          appendInlineMarkdown(source.substring(contentStart, end), linkColor, codeBackground, budget, depth + 1, allowLinks)
        }
        index = end + token.delimiter.length
        continue
      }
    }

    if ((source[index] == '*' || source[index] == '_') && canStartItalic(source, index)) {
      val delimiter = source[index]
      val end = markdownIndexOf(source, delimiter.toString(), index + 1, budget)
      if (end > index + 1 && canEndItalic(source, end)) {
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
          appendInlineMarkdown(source.substring(index + 1, end), linkColor, codeBackground, budget, depth + 1, allowLinks)
        }
        index = end + 1
        continue
      }
    }

    append(source[index])
    index += 1
  }
}

internal fun markdownLinkAt(rendered: AnnotatedString, offset: Int): String? {
  if (rendered.isEmpty()) return null
  val position = offset.coerceIn(0, rendered.lastIndex)
  return markdownImageLinkAt(rendered, position)
    ?: rendered.getStringAnnotations(MARKDOWN_LINK_TAG, position, position).firstOrNull()?.item
}

internal fun markdownImageLinkAt(rendered: AnnotatedString, offset: Int): String? {
  if (rendered.isEmpty()) return null
  val position = offset.coerceIn(0, rendered.lastIndex)
  return rendered.getStringAnnotations(MARKDOWN_IMAGE_LINK_TAG, position, position).firstOrNull()?.item
}

private fun isLikelyImageLink(label: String, target: String): Boolean {
  val path = target.substringBefore('?').substringBefore('#')
  return IMAGE_LINK_SUFFIXES.any { path.endsWith(it, ignoreCase = true) } ||
    IMAGE_LINK_LABEL.containsMatchIn(label)
}

private fun canStartItalic(source: String, index: Int): Boolean {
  if (index + 1 >= source.length || source[index + 1].isWhitespace()) return false
  return source[index] == '*' || index == 0 || !source[index - 1].isLetterOrDigit()
}

private fun canEndItalic(source: String, index: Int): Boolean =
  index > 0 && !source[index - 1].isWhitespace() &&
    (source[index] == '*' || index + 1 == source.length || !source[index + 1].isLetterOrDigit())

private data class InlineToken(
  val delimiter: String,
  val style: (Color) -> SpanStyle,
)

private val INLINE_TOKENS =
  listOf(
    InlineToken("**") { SpanStyle(fontWeight = FontWeight.Bold) },
    InlineToken("__") { SpanStyle(fontWeight = FontWeight.Bold) },
    InlineToken("~~") { SpanStyle(textDecoration = TextDecoration.LineThrough) },
  )

private const val MARKDOWN_ESCAPES = "\\`*_{}[]<>()#+-.!|>"
internal const val MARKDOWN_PREVIEW_MAX_CHARS = 32 * 1024
internal const val MARKDOWN_LINK_TAG = "chat-url"
internal const val MARKDOWN_IMAGE_LINK_TAG = "chat-image-url"

private val IMAGE_LINK_SUFFIXES =
  listOf(".avif", ".gif", ".heic", ".heif", ".jpeg", ".jpg", ".png", ".webp")
private val IMAGE_LINK_LABEL =
  Regex("图片|图像|截图|照片|image|photo|screenshot", RegexOption.IGNORE_CASE)
