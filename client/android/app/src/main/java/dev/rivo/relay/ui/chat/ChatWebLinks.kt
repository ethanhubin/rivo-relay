package dev.rivo.relay.ui.chat

import java.net.URI

internal fun isHttpWebLink(target: String): Boolean =
  runCatching {
    val uri = URI(target)
    (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
      !uri.rawAuthority.isNullOrBlank()
  }.getOrDefault(false)

private val plainWebLinkPattern = Regex("https?://[^\\s<>\"'`，。；！？、（）【】《》“”‘’]+", RegexOption.IGNORE_CASE)

/** Keep sentence punctuation outside links, but preserve balanced URL parentheses. */
internal fun plainWebLinkAt(source: String, index: Int): String? {
  if (!source.regionMatches(index, "http", 0, 4, ignoreCase = true)) return null
  if (index > 0 && (source[index - 1].isLetterOrDigit() && source[index - 1].code < 128 ||
      source[index - 1] == '_')) return null
  var target = plainWebLinkPattern.matchAt(source, index)?.value ?: return null
  while (target.isNotEmpty()) {
    val last = target.last()
    val opening = when (last) { ')' -> '('; ']' -> '['; '}' -> '{'; else -> null }
    if (last in ".,;:!?" ||
      (opening != null && target.count { it == last } > target.count { it == opening })) {
      target = target.dropLast(1)
    } else break
  }
  return target.takeIf(::isHttpWebLink)
}

