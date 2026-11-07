package dev.rivo.relay

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import dev.rivo.relay.ui.chat.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownMessageBoundaryTest {
    private fun render(text: String) = renderChatMarkdown(text, Color.Blue, Color.Gray, Color.Gray)

    @Test fun balancedDestinationsAndAnglePathsKeepTheirCompleteTarget() {
        val text = render("[文档](https://example.com/a_(b).pdf \"说明\") ![图](</tmp/a (final).png> '标题')")
        assertEquals("文档 🖼 图", text.text)
        assertEquals("https://example.com/a_(b).pdf", markdownLinkAt(text, 0))
        assertEquals(listOf("/tmp/a (final).png"), markdownImageSources("![图](</tmp/a (final).png> '标题')"))
        assertEquals("/tmp/a(b).kt", markdownLinkAt(render("[源码](/tmp/a\\(b\\).kt)"), 0))
    }

    @Test fun labelsMayContainFormattingWithoutCreatingNestedDestinations() {
        val text = render("[**read [this]**](https://example.com) [https://label.example](https://target.example)")
        assertEquals("read [this] https://label.example", text.text)
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertEquals(listOf("https://example.com", "https://target.example"),
            text.getStringAnnotations(MARKDOWN_LINK_TAG, 0, text.length).map { it.item })
    }

    @Test fun mismatchedFencesAndLongCodeSpansNeverCreateImagesOrTables() {
        val source = "````markdown\n```\n![literal](/tmp/a.png)\n| a | b |\n|---|---|\n````\n~~~\n![other](/tmp/b.png)\n~~~\n``a ` | ![last](/tmp/c.png)``"
        assertTrue(markdownImageSources(source).isEmpty())
        assertEquals(1, parseChatMarkdownBlocks(source).size)
        val text = render(source)
        assertTrue(text.text.contains("```"))
        assertTrue(text.text.contains("![literal](/tmp/a.png)"))
        assertTrue(text.text.contains("a ` | ![last](/tmp/c.png)"))
    }

    @Test fun literalFenceLikeLinesDoNotCloseCodeEarly() {
        val source = "```text\n```not-a-close\n[hidden](https://example.com)\n```\n[shown](https://safe.example)"
        val text = render(source)
        assertTrue(text.text.contains("```not-a-close"))
        assertEquals(listOf("https://safe.example"), text.getStringAnnotations(MARKDOWN_LINK_TAG, 0, text.length).map { it.item })
    }

    @Test fun tableCodeSpanPipesAndShortRowsStayAlignedWithoutDroppingExtraCells() {
        val blocks = parseChatMarkdownBlocks("A | B | C\n---|---|---\n``a ` | b`` | value | end\nshort | row\nextra | row | data | preserved")
        val table = blocks.first() as ChatMarkdownBlock.Table
        assertEquals(listOf("``a ` | b``", "value", "end"), table.rows.first())
        assertEquals(listOf("short", "row", ""), table.rows.last())
        assertTrue((blocks.last() as ChatMarkdownBlock.Text).markdown.contains("preserved"))
    }

    @Test fun malformedStreamingLinksRemainVisibleUntilComplete() {
        for (source in listOf("[label](https://example.com/a_(b)", "![img](</tmp/a b.png>", "[x](target \"unfinished)")) {
            assertEquals(source, render(source).text)
            assertTrue(markdownImageSources(source).isEmpty())
        }
        assertEquals("🖼 img", render("![img](</tmp/a b.png>)").text)
    }
}
