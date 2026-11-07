package dev.rivo.relay.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// Syntax is independent of the current theme, so text, images and gallery discovery share one parse.
internal val MARKDOWN_LINK_COLOR = Color(0xFF010001)
internal val MARKDOWN_CODE_COLOR = Color(0xFF010002)
internal val MARKDOWN_QUOTE_COLOR = Color(0xFF010003)

internal data class ParsedChatMarkdown(
    val syntax: AnnotatedString,
    val imageSources: List<String>,
    val plainTextFallback: Boolean,
    val scanSteps: Long,
) {
    fun styled(link: Color, code: Color, quote: Color): AnnotatedString = AnnotatedString.Builder(syntax).apply {
        syntax.spanStyles.forEach { span ->
            val foreground = when (span.item.color) { MARKDOWN_LINK_COLOR -> link; MARKDOWN_QUOTE_COLOR -> quote; else -> span.item.color }
            val background = if (span.item.background == MARKDOWN_CODE_COLOR) code else span.item.background
            if (foreground != span.item.color || background != span.item.background)
                addStyle(span.item.copy(color = foreground, background = background), span.start, span.end)
        }
    }.toAnnotatedString()
}

/** Bounded by source size and entry count; parsing never holds the cache lock. */
internal class MarkdownPresentationCache(private val maxChars: Int = 512 * 1024, private val maxEntries: Int = 128) {
    private val entries = LinkedHashMap<String, ParsedChatMarkdown>(16, .75f, true)
    private var chars = 0
    fun peek(markdown: String): ParsedChatMarkdown? = synchronized(entries) { entries[markdown] }
    fun get(markdown: String, checkCancelled: (() -> Unit)? = null): ParsedChatMarkdown {
        synchronized(entries) { entries[markdown]?.let { return it } }
        val parsed = parseChatMarkdownPresentation(markdown, checkCancelled)
        if (markdown.length > maxChars) return parsed
        synchronized(entries) {
            entries[markdown]?.let { return it }
            entries[markdown] = parsed
            chars += markdown.length
            while (chars > maxChars || entries.size > maxEntries) {
                val oldest = entries.entries.iterator()
                chars -= oldest.next().key.length
                oldest.remove()
            }
        }
        return parsed
    }
}

internal fun parseChatMarkdownPresentation(markdown: String, checkCancelled: (() -> Unit)? = null): ParsedChatMarkdown {
    val budget = MarkdownScanBudget(markdown.length, checkCancelled)
    var fallback = false
    val syntax = try { renderMarkdownSyntax(markdown, budget) } catch (_: MarkdownScanLimit) {
        fallback = true
        AnnotatedString(markdown) // Keep every source character, including incomplete Markdown.
    }
    val images = syntax.getStringAnnotations(MARKDOWN_IMAGE_LINK_TAG, 0, syntax.length).map { it.item }.distinct()
    return ParsedChatMarkdown(syntax, images, fallback, budget.spent)
}

private val markdownPresentations = MarkdownPresentationCache()
internal fun parsedChatMarkdown(markdown: String, checkCancelled: (() -> Unit)? = null): ParsedChatMarkdown = markdownPresentations.get(markdown, checkCancelled)

@Composable
internal fun rememberParsedChatMarkdown(markdown: String): ParsedChatMarkdown {
    val immediate = remember(markdown) {
        markdownPresentations.peek(markdown) ?: if (markdown.length <= 8192) parsedChatMarkdown(markdown) else null
    }
    return key(markdown) {
        val parsed by produceState(immediate) {
            if (value == null) value = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                parsedChatMarkdown(markdown) { context.ensureActive() }
            }
        }
        // A stale worker cannot replace the next revision: this state leaves composition with its key.
        parsed ?: remember(markdown) { ParsedChatMarkdown(AnnotatedString(markdown), emptyList(), false, 0) }
    }
}
