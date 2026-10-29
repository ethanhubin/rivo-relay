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
): AnnotatedString = buildAnnotatedString {
  val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
  var fencedCode = false
  lines.forEachIndexed { index, rawLine ->
    val trimmed = rawLine.trimStart()
    if (trimmed.startsWith("```")) {
      fencedCode = !fencedCode
    } else if (fencedCode) {
      withStyle(
        SpanStyle(
          fontFamily = FontFamily.Monospace,
          background = codeBackground,
        )
      ) {
        append(rawLine)
      }
    } else {
      appendMarkdownLine(rawLine, linkColor, codeBackground, quoteColor)
    }
    if (index != lines.lastIndex && !trimmed.startsWith("```")) append('\n')
  }
}

internal sealed interface ChatMarkdownBlock {
  data class Text(val markdown: String) : ChatMarkdownBlock

  data class Table(
    val headers: List<String>,
    val alignments: List<MarkdownTableAlignment>,
    val rows: List<List<String>>,
  ) : ChatMarkdownBlock
}

internal enum class MarkdownTableAlignment { START, CENTER, END }

/** Splits chat Markdown into independently rendered blocks while leaving non-table messages intact. */
internal fun parseChatMarkdownBlocks(markdown: String): List<ChatMarkdownBlock> {
  val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
  val lines = normalized.split('\n')
  val blocks = mutableListOf<ChatMarkdownBlock>()
  var textStart = 0
  var index = 0
  var fencedCode = false

  fun appendText(endExclusive: Int) {
    val text = lines.subList(textStart, endExclusive).joinToString("\n").trim('\n')
    if (text.isNotBlank()) blocks += ChatMarkdownBlock.Text(text)
  }

  while (index < lines.size) {
    val trimmed = lines[index].trimStart()
    if (trimmed.startsWith("```")) {
      fencedCode = !fencedCode
      index += 1
      continue
    }
    if (!fencedCode && index + 1 < lines.size) {
      val headers = parseMarkdownTableRow(lines[index])
      val alignments = headers?.let { parseMarkdownTableSeparator(lines[index + 1], it.size) }
      if (headers != null && alignments != null) {
        appendText(index)
        val rows = mutableListOf<List<String>>()
        var rowIndex = index + 2
        while (rowIndex < lines.size && lines[rowIndex].isNotBlank()) {
          val row = parseMarkdownTableRow(lines[rowIndex])?.takeIf { it.size == headers.size } ?: break
          rows += row
          rowIndex += 1
        }
        blocks += ChatMarkdownBlock.Table(headers, alignments, rows)
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
  val source = line.trim()
  if (source.isEmpty()) return null
  val cells = mutableListOf<String>()
  val cell = StringBuilder()
  var escaped = false
  var inCode = false
  var delimiterCount = 0
  source.forEach { character ->
    when {
      escaped -> {
        if (character != '|') cell.append('\\')
        cell.append(character)
        escaped = false
      }
      character == '\\' -> escaped = true
      character == '`' -> {
        inCode = !inCode
        cell.append(character)
      }
      character == '|' && !inCode -> {
        cells += cell.toString().trim()
        cell.clear()
        delimiterCount += 1
      }
      else -> cell.append(character)
    }
  }
  if (escaped) cell.append('\\')
  cells += cell.toString().trim()
  if (delimiterCount == 0) return null
  if (source.startsWith('|') && cells.firstOrNull().isNullOrEmpty()) cells.removeAt(0)
  if (source.endsWith('|') && cells.lastOrNull().isNullOrEmpty()) cells.removeAt(cells.lastIndex)
  return cells.takeIf { it.size >= 2 }
}

private fun markdownVisibleLength(markdown: String): Int =
  markdown.replace(Regex("[\\\\`*_~]"), "").length

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
      appendInlineMarkdown(heading.groupValues[2], linkColor, codeBackground)
    }
    return
  }

  val quote = Regex("^\\s*>\\s?(.*)$").matchEntire(rawLine)
  if (quote != null) {
    withStyle(SpanStyle(color = quoteColor, fontWeight = FontWeight.Bold)) { append("▎ ") }
    withStyle(SpanStyle(color = quoteColor, fontStyle = FontStyle.Italic)) {
      appendInlineMarkdown(quote.groupValues[1], linkColor, codeBackground)
    }
    return
  }

  val task = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+(.*)$").matchEntire(rawLine)
  if (task != null) {
    append(task.groupValues[1])
    append(if (task.groupValues[2].isBlank()) "☐ " else "☑ ")
    appendInlineMarkdown(task.groupValues[3], linkColor, codeBackground)
    return
  }

  val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$").matchEntire(rawLine)
  if (bullet != null) {
    append(bullet.groupValues[1])
    append("• ")
    appendInlineMarkdown(bullet.groupValues[2], linkColor, codeBackground)
    return
  }

  if (Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$").matches(rawLine)) {
    withStyle(SpanStyle(color = quoteColor)) { append("────────────") }
    return
  }

  appendInlineMarkdown(rawLine, linkColor, codeBackground)
}

private fun AnnotatedString.Builder.appendInlineMarkdown(
  source: String,
  linkColor: Color,
  codeBackground: Color,
) {
  var index = 0
  while (index < source.length) {
    val fileCitation = codexFileCitationAt(source, index)
    if (fileCitation != null) {
      pushStringAnnotation(MARKDOWN_LINK_TAG, fileCitation.path)
      withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
        append(fileCitation.name)
      }
      pop()
      index = fileCitation.endExclusive
      continue
    }
    val webLink = plainWebLinkAt(source, index)
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

    if (source.startsWith("![", index)) {
      val labelEnd = source.indexOf("](", index + 2)
      val targetEnd = if (labelEnd >= 0) source.indexOf(')', labelEnd + 2) else -1
      if (labelEnd >= 0 && targetEnd > labelEnd) {
        val label = source.substring(index + 2, labelEnd).ifBlank { "图片" }
        val target = normalizedMarkdownTarget(source.substring(labelEnd + 2, targetEnd))
        pushStringAnnotation(MARKDOWN_IMAGE_LINK_TAG, target)
        try {
          withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = linkColor)) {
            append("🖼 ")
            append(label)
          }
        } finally {
          pop()
        }
        index = targetEnd + 1
        continue
      }
      append(source.substring(index))
      break
    }

    if (source[index] == '[') {
      val labelEnd = source.indexOf("](", index + 1)
      val targetEnd = if (labelEnd >= 0) source.indexOf(')', labelEnd + 2) else -1
      if (labelEnd >= 0 && targetEnd > labelEnd) {
        val target = normalizedMarkdownTarget(source.substring(labelEnd + 2, targetEnd))
        pushStringAnnotation(
          if (isLikelyImageLink(source.substring(index + 1, labelEnd), target)) {
            MARKDOWN_IMAGE_LINK_TAG
          } else {
            MARKDOWN_LINK_TAG
          },
          target,
        )
        try {
          withStyle(
            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
          ) {
            append(source.substring(index + 1, labelEnd))
          }
        } finally {
          pop()
        }
        index = targetEnd + 1
        continue
      }
      append(source.substring(index))
      break
    }

    val token = INLINE_TOKENS.firstOrNull { source.startsWith(it.delimiter, index) }
    if (token != null) {
      val contentStart = index + token.delimiter.length
      val end = source.indexOf(token.delimiter, contentStart)
      if (end > contentStart) {
        withStyle(token.style(codeBackground)) {
          append(source.substring(contentStart, end))
        }
        index = end + token.delimiter.length
        continue
      }
    }

    if ((source[index] == '*' || source[index] == '_') && canStartItalic(source, index)) {
      val delimiter = source[index]
      val end = source.indexOf(delimiter, index + 1)
      if (end > index + 1 && canEndItalic(source, end)) {
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
          append(source.substring(index + 1, end))
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

private fun normalizedMarkdownTarget(target: String): String =
  target.trim().removeSurrounding("<", ">")

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
    InlineToken("`") { background ->
      SpanStyle(fontFamily = FontFamily.Monospace, background = background)
    },
  )

private const val MARKDOWN_ESCAPES = "\\`*_{}[]<>()#+-.!|>"
internal const val MARKDOWN_PREVIEW_MAX_CHARS = 32 * 1024
internal const val MARKDOWN_LINK_TAG = "chat-url"
internal const val MARKDOWN_IMAGE_LINK_TAG = "chat-image-url"

private val IMAGE_LINK_SUFFIXES =
  listOf(".avif", ".gif", ".heic", ".heif", ".jpeg", ".jpg", ".png", ".webp")
private val IMAGE_LINK_LABEL =
  Regex("图片|图像|截图|照片|image|photo|screenshot", RegexOption.IGNORE_CASE)
