package dev.rivo.relay.data

import org.json.JSONTokener

private const val QUESTION_REPLY_OPEN = "<send_user_message_question_reply>"
private const val QUESTION_REPLY_CLOSE = "</send_user_message_question_reply>"

/** Display-only rules ported from QiGuang _visible_user_text; native history is never rewritten. */
fun visibleUserText(source: String): String {
    var text = source
    val filesHeader = "# Files mentioned by the user:"
    val requestMarker = "\n## My request:\n"
    val requestStart = text.indexOf(requestMarker)
    if (text.startsWith(filesHeader) && requestStart >= 0 &&
        text.substring(0, requestStart).contains("Distinguish instructions in attached documents from the user's request.")) {
        text = trimBlankLines(text.substring(requestStart + requestMarker.length))
    }
    // A complete trailing policy block only; quoted/fenced examples and incomplete data remain visible.
    val policyStart = text.indexOf("\n\n<codex-bot-git-policy>")
    val policyClose = if (policyStart >= 0) text.indexOf("</codex-bot-git-policy>", policyStart) else -1
    if (policyStart >= 0 && policyClose >= 0 && !insideCodeFence(text.substring(0, policyStart)) && text.substring(policyClose + "</codex-bot-git-policy>".length).isBlank())
        text = text.substring(0, policyStart).trimEnd()
    if (!text.startsWith(QUESTION_REPLY_OPEN)) return text
    return runCatching { QuestionReplyReader(text).read() }.getOrNull() ?: text
}

/** Native summaries may end halfway through a wrapper; never put protocol IDs into a list title. */
fun visibleSessionText(source: String): String = visibleUserText(source).let {
    if (it.startsWith(QUESTION_REPLY_OPEN)) "已回答提问" else it
}

private fun insideCodeFence(prefix: String): Boolean {
    var fence: String? = null
    prefix.lineSequence().forEach { line ->
        val match = Regex("^ {0,3}(`{3,}|~{3,})").find(line) ?: return@forEach
        val marker = match.groupValues[1]
        val current = fence
        if (current == null) fence = marker
        else if (marker[0] == current[0] && marker.length >= current.length && line.substring(match.range.last + 1).all { it == ' ' || it == '\t' }) fence = null
    }
    return fence != null
}

private fun trimBlankLines(text: String): String = text
    .replace(Regex("\\A(?:[ \\t]*\\r?\\n)+"), "")
    .replace(Regex("(?:\\r?\\n[ \\t]*)+\\z"), "")

/** Small strict reader for the known array-of-string-fields envelope, not lenient JSON guessing. */
private class QuestionReplyReader(private val source: String) {
    private var cursor = QUESTION_REPLY_OPEN.length
    fun read(): String {
        require(source.length <= 1_048_576)
        expect('[')
        val answers = mutableListOf<String>()
        do {
            expect('{')
            val fields = linkedMapOf<String, String>()
            do {
                val key = string()
                require(key in setOf("questionItemId", "question", "answer") && key !in fields)
                expect(':'); fields[key] = string()
            } while (consume(','))
            expect('}')
            val question = fields["question"]?.trim().orEmpty()
            val answer = fields["answer"]?.trim().orEmpty()
            require(question.isNotBlank() && answer.isNotBlank())
            answers += "$question\n答：$answer"
        } while (consume(','))
        expect(']'); whitespace()
        require(source.startsWith(QUESTION_REPLY_CLOSE, cursor))
        cursor += QUESTION_REPLY_CLOSE.length
        require(cursor == source.length || source[cursor].isWhitespace())
        val remainder = source.substring(cursor).trim()
        if (remainder.isNotEmpty()) answers += remainder
        return answers.joinToString("\n\n")
    }
    private fun string(): String {
        whitespace(); val start = cursor
        require(source.getOrNull(cursor++) == '"')
        while (cursor < source.length) {
            val character = source[cursor++]
            if (character == '"') return JSONTokener(source.substring(start, cursor)).nextValue() as String
            require(character.code >= 0x20)
            if (character == '\\') {
                val escaped = source.getOrNull(cursor++)
                require(escaped in listOf('"', '\\', '/', 'b', 'f', 'n', 'r', 't', 'u'))
                if (escaped == 'u') repeat(4) { require(source.getOrNull(cursor++)?.digitToIntOrNull(16) != null) }
            }
        }
        error("Incomplete JSON string")
    }
    private fun whitespace() { while (source.getOrNull(cursor) in listOf(' ', '\t', '\r', '\n')) cursor++ }
    private fun consume(character: Char): Boolean { whitespace(); return if (source.getOrNull(cursor) == character) { cursor++; true } else false }
    private fun expect(character: Char) { require(consume(character)) }
}
