package dev.rivo.relay.ui.chat

internal data class ReviewComment(
    val title: String,
    val body: String,
    val file: String,
    val start: Int?,
    val end: Int?,
    val priority: Int?,
)

internal sealed interface ReviewMessagePart {
    data class Text(val value: String) : ReviewMessagePart
    data class Finding(val comment: ReviewComment) : ReviewMessagePart
    data class Visualization(val path: String) : ReviewMessagePart
}

/** Parse a complete directive as data. Unknown/malformed directives remain ordinary text. */
internal fun parseReviewComment(line: String): ReviewComment? {
    val source = line.trim()
    if (!source.startsWith("::code-comment{") || !source.endsWith('}')) return null
    val values = mutableMapOf<String, String>()
    var cursor = "::code-comment{".length
    while (cursor < source.lastIndex) {
        while (cursor < source.lastIndex && source[cursor].isWhitespace()) cursor++
        if (cursor == source.lastIndex) break
        val keyStart = cursor
        while (cursor < source.lastIndex && (source[cursor].isLetterOrDigit() || source[cursor] == '_')) cursor++
        if (keyStart == cursor) return null
        val key = source.substring(keyStart, cursor)
        while (cursor < source.lastIndex && source[cursor].isWhitespace()) cursor++
        if (cursor >= source.lastIndex || source[cursor++] != '=') return null
        while (cursor < source.lastIndex && source[cursor].isWhitespace()) cursor++
        if (cursor >= source.lastIndex || key in values) return null
        val value = StringBuilder()
        if (source[cursor] == '"' || source[cursor] == '\'') {
            val quote = source[cursor++]
            var closed = false
            while (cursor < source.lastIndex) {
                val ch = source[cursor++]
                if (ch == quote) { closed = true; break }
                if (ch == '\\') {
                    if (cursor >= source.lastIndex) return null
                    val escaped = source[cursor++]
                    if (escaped == 'n') value.append('\n')
                    else if (escaped == 't') value.append('\t')
                    else if (escaped == quote || escaped == '\\') value.append(escaped)
                    else { value.append('\\'); value.append(escaped) }
                } else value.append(ch)
            }
            if (!closed) return null
        } else {
            val start = cursor
            while (cursor < source.lastIndex && !source[cursor].isWhitespace()) cursor++
            value.append(source.substring(start, cursor))
        }
        values[key] = value.toString()
    }
    val title = values["title"]?.takeIf { it.isNotBlank() } ?: return null
    val body = values["body"]?.takeIf { it.isNotBlank() } ?: return null
    val file = values["file"]?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } ?: return null
    fun positive(key: String): Int? = values[key]?.toIntOrNull()?.takeIf { it > 0 }
    val start = positive("start")
    val end = positive("end") ?: start
    if (values.containsKey("start") && start == null || values.containsKey("end") && positive("end") == null || start != null && end != null && end < start) return null
    return ReviewComment(title, body, file, start, end, values["priority"]?.toIntOrNull()?.takeIf { it in 0..3 })
}

internal fun reviewMessageParts(markdown: String): List<ReviewMessagePart> {
    val parts = mutableListOf<ReviewMessagePart>()
    val text = mutableListOf<String>()
    var fence: Char? = null
    var fenceLength = 0
    fun flush() { if (text.isNotEmpty()) { parts += ReviewMessagePart.Text(text.joinToString("\n")); text.clear() } }
    markdown.lines().forEach { line ->
        val trimmed = line.trimStart()
        val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' }
        val markerLength = if (marker != null) trimmed.takeWhile { it == marker }.length else 0
        if (markerLength >= 3) {
            if (fence == null) { fence = marker; fenceLength = markerLength }
            else if (fence == marker && markerLength >= fenceLength && trimmed.drop(markerLength).isBlank()) fence = null
            text += line
        } else {
            val comment = if (fence == null) parseReviewComment(line) else null
            val visualization = if (fence == null) parseVisualizationPath(line) else null
            when {
                comment != null -> { flush(); parts += ReviewMessagePart.Finding(comment) }
                visualization != null -> { flush(); parts += ReviewMessagePart.Visualization(visualization) }
                else -> text += line
            }
        }
    }
    flush()
    return parts
}

/** Native visualize attachments reference local HTML; incomplete/unknown data stays visible. */
internal fun parseVisualizationPath(line: String): String? {
    val source = line.trim()
    val prefix = "visualize"
    if (!source.startsWith(prefix) || !source.endsWith("")) return null
    val payload = source.removePrefix(prefix).removeSuffix("")
    return runCatching {
        val tokenizer = org.json.JSONTokener(payload)
        val value = tokenizer.nextValue() as? org.json.JSONObject ?: return null
        if (tokenizer.nextClean() != '\u0000') return null
        val path = value.opt("path") as? String ?: return null
        path.takeIf {
            it.isNotBlank() && it.none(Char::isISOControl) &&
                !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(it) &&
                (it.endsWith(".html", true) || it.endsWith(".htm", true))
        }
    }.getOrNull()
}
