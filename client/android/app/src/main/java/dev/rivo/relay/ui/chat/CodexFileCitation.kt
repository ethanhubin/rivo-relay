package dev.rivo.relay.ui.chat

internal data class CodexFileCitation(val path: String, val name: String, val endExclusive: Int)

private const val FILE_CITATION_PREFIX = ":codex-file-citation{"

/** Parse quoted attributes without treating braces or Markdown punctuation in a path as syntax. */
internal fun codexFileCitationAt(source: String, start: Int, budget: MarkdownScanBudget? = null): CodexFileCitation? {
  if (!source.startsWith(FILE_CITATION_PREFIX, start)) return null
  var cursor = start + FILE_CITATION_PREFIX.length
  val attributes = mutableMapOf<String, String>()
  while (cursor < source.length) {
    budget?.consume()
    if (source[cursor].isWhitespace()) {
      cursor += 1
      continue
    }
    if (source[cursor] == '}') {
      val path = attributes["path"]?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        ?: return null
      val name = path.substringAfterLast('/').substringAfterLast('\\').takeIf(String::isNotBlank)
        ?: return null
      return CodexFileCitation(path, name, cursor + 1)
    }
    val keyStart = cursor
    if (source[cursor] !in 'A'..'Z' && source[cursor] !in 'a'..'z') return null
    while (cursor < source.length && (source[cursor] in 'A'..'Z' || source[cursor] in 'a'..'z' || source[cursor] in '0'..'9' || source[cursor] in "_-")) { budget?.consume(); cursor++ }
    val key = source.substring(keyStart, cursor)
    if (key in attributes) return null
    while (source.getOrNull(cursor)?.isWhitespace() == true) { budget?.consume(); cursor++ }
    if (source.getOrNull(cursor++) != '=') return null
    while (source.getOrNull(cursor)?.isWhitespace() == true) { budget?.consume(); cursor++ }
    val quote = source.getOrNull(cursor++)?.takeIf { it == '\'' || it == '"' } ?: return null
    val value = StringBuilder()
    var closed = false
    while (cursor < source.length) {
      budget?.consume()
      val character = source[cursor++]
      if (character == quote) { closed = true; break }
      if (character == '\n' || character == '\r') return null
      if (character == '\\' && cursor < source.length) {
        val escaped = source[cursor++]
        if (escaped == '\n' || escaped == '\r') return null
        if (escaped !in "\\\"'") value.append('\\')
        value.append(escaped)
      } else value.append(character)
    }
    if (!closed) return null
    attributes[key] = value.toString()
  }
  return null
}
