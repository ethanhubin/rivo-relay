package dev.rivo.relay.ui.chat

/** Strip presentation syntax while retaining link destinations and literal code. */
internal fun markdownCopyPlainText(markdown: String): String {
    fun inline(source: String): String {
        val rendered = parsedChatMarkdown(source).syntax
        val links = rendered.getStringAnnotations(0, rendered.length)
            .filter { it.tag == MARKDOWN_LINK_TAG || it.tag == MARKDOWN_IMAGE_LINK_TAG }
            .sortedBy { it.start }
        return buildString {
            var offset = 0
            for (link in links) {
                if (link.start < offset) continue
                append(rendered.text.substring(offset, link.end))
                val label = rendered.text.substring(link.start, link.end)
                if (label != link.item) append(" (${link.item})")
                offset = link.end
            }
            append(rendered.text.substring(offset))
        }
    }
    return splitChatMarkdownBlocks(markdown).joinToString("\n\n") { block ->
        when (block) {
            is ChatMarkdownBlock.Text -> inline(block.markdown)
            is ChatMarkdownBlock.Code -> block.content
            is ChatMarkdownBlock.Table -> (listOf(block.headers) + block.rows)
                .joinToString("\n") { row -> row.joinToString("\t") { inline(it) } }
        }
    }
}
