package dev.rivo.relay.ui.chat

internal data class CodexFileCitation(val path: String, val name: String, val endExclusive: Int)

private val CITATION_ATTRIBUTE =
  Regex("""\s*([A-Za-z][A-Za-z0-9_-]*)\s*=\s*("(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*')""")
private const val FILE_CITATION_PREFIX = ":codex-file-citation{"

/** Parse quoted attributes without treating braces or Markdown punctuation in a path as syntax. */
internal fun codexFileCitationAt(source: String, start: Int): CodexFileCitation? {
  if (!source.startsWith(FILE_CITATION_PREFIX, start)) return null
  var cursor = start + FILE_CITATION_PREFIX.length
  val attributes = mutableMapOf<String, String>()
  while (cursor < source.length) {
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
    val match = CITATION_ATTRIBUTE.matchAt(source, cursor) ?: return null
    val key = match.groupValues[1]
    if (key in attributes) return null
    val quoted = match.groupValues[2]
    attributes[key] = quoted.substring(1, quoted.lastIndex)
      .replace(Regex("""\\([\\"'])""")) { it.groupValues[1] }
    cursor = match.range.last + 1
  }
  return null
}
