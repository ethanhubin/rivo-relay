package dev.rivo.relay.ui.chat

/** A per-document bound on speculative scans. Exhaustion falls back to the complete source. */
internal class MarkdownScanBudget(length: Int, private val checkCancelled: (() -> Unit)? = null) {
    val limit = length.toLong() * 24 + 128
    var spent = 0L
        private set
    private var nextCancellationCheck = 1024L
    fun consume(count: Int = 1) {
        spent += count.toLong()
        if (spent >= nextCancellationCheck) { checkCancelled?.invoke(); nextCancellationCheck = spent + 1024 }
        if (spent > limit) throw MarkdownScanLimit()
    }
}
internal class MarkdownScanLimit : RuntimeException(null, null, false, false)

internal fun markdownRunLength(source: String, start: Int, marker: Char): Int {
    var end = start
    while (end < source.length && source[end] == marker) end++
    return end - start
}

internal fun markdownIndexOf(source: String, delimiter: String, start: Int, budget: MarkdownScanBudget): Int {
    var index = start
    while (index <= source.length - delimiter.length) {
        budget.consume()
        if (source.startsWith(delimiter, index)) return index
        index++
    }
    return -1
}

/** Shared fence recognition keeps table/image extraction in sync with displayed code. */
internal data class MarkdownFence(val marker: Char, val length: Int) {
    fun closes(line: String): Boolean {
        val candidate = markdownFenceAt(line) ?: return false
        val trimmed = line.trimStart()
        return candidate.marker == marker && candidate.length >= length &&
            trimmed.drop(candidate.length).isBlank()
    }
}

internal fun markdownFenceAt(line: String): MarkdownFence? {
    val indent = line.takeWhile { it == ' ' }.length
    if (indent > 3) return null
    val text = line.substring(indent)
    val marker = text.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val count = text.takeWhile { it == marker }.length
    if (count < 3 || marker == '`' && text.drop(count).contains('`')) return null
    return MarkdownFence(marker, count)
}

internal fun findCodeSpanEnd(source: String, start: Int, ticks: Int, budget: MarkdownScanBudget? = null): Int {
    var index = start
    while (index < source.length) {
        budget?.consume()
        if (source[index] != '`') { index++; continue }
        var end = index
        while (end < source.length && source[end] == '`') { budget?.consume(); end++ }
        if (end - index == ticks) return index
        index = end
    }
    return -1
}

internal data class MarkdownInlineLink(val label: String, val target: String, val image: Boolean, val endExclusive: Int)

/** Balanced destinations, angle paths with spaces, escaped punctuation and optional titles. */
internal fun markdownInlineLinkAt(source: String, start: Int, budget: MarkdownScanBudget? = null): MarkdownInlineLink? {
    val image = source.startsWith("![", start)
    val open = start + if (image) 1 else 0
    if (source.getOrNull(open) != '[') return null
    var index = open + 1
    var brackets = 1
    while (index < source.length && brackets > 0) {
        budget?.consume()
        when (source[index]) {
            '\\' -> { index += 2; continue }
            '[' -> { brackets++; if (brackets > 32) return null }
            ']' -> brackets--
        }
        if (brackets > 0) index++
    }
    if (brackets != 0 || source.getOrNull(index + 1) != '(') return null
    val label = source.substring(open + 1, index)
    index += 2
    while (source.getOrNull(index)?.isWhitespace() == true) { budget?.consume(); index++ }
    val destination = StringBuilder()
    if (source.getOrNull(index) == '<') {
        index++
        var closed = false
        while (index < source.length) {
            budget?.consume()
            val c = source[index++]
            if (c == '>') { closed = true; break }
            if (c == '\n' || c == '\r' || c == '<') return null
            if (c == '\\' && source.getOrNull(index)?.let { it in markdownDestinationEscapes } == true) destination.append(source[index++])
            else destination.append(c)
        }
        if (!closed) return null
    } else {
        var parentheses = 0
        while (index < source.length) {
            budget?.consume()
            val c = source[index]
            if (c == '\\' && source.getOrNull(index + 1)?.let { it in markdownDestinationEscapes } == true) {
                destination.append(source[index + 1]); index += 2; continue
            }
            if (c == ')' && parentheses == 0 || c.isWhitespace()) break
            if (c == '(') parentheses++
            if (c == ')') parentheses--
            destination.append(c)
            index++
        }
        if (parentheses != 0) return null
    }
    val beforeSpace = index
    while (source.getOrNull(index)?.isWhitespace() == true) { budget?.consume(); index++ }
    if (source.getOrNull(index) != ')') {
        if (index == beforeSpace) return null
        val quote = source.getOrNull(index)?.takeIf { it == '"' || it == '\'' || it == '(' } ?: return null
        val close = if (quote == '(') ')' else quote
        index++
        var closed = false
        while (index < source.length) {
            budget?.consume()
            val c = source[index++]
            if (c == '\\' && index < source.length) { index++; continue }
            if (c == close) { closed = true; break }
        }
        if (!closed) return null
        while (source.getOrNull(index)?.isWhitespace() == true) { budget?.consume(); index++ }
    }
    if (source.getOrNull(index) != ')') return null
    return MarkdownInlineLink(label, destination.toString(), image, index + 1)
}

private val markdownDestinationEscapes = "\\`*_{}[]<>()#+-.!|>'\""
