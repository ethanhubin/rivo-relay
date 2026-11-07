package dev.rivo.relay

import androidx.compose.ui.graphics.Color
import dev.rivo.relay.ui.chat.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.system.measureNanoTime
import java.util.concurrent.CancellationException

class MarkdownPerformanceTest {
    @Test fun cancelledLongParsesStopWithoutCachingAPartialResult() {
        val cache = MarkdownPresentationCache()
        val source = "[x](".repeat(8192)
        var checks = 0
        try {
            cache.get(source) { checks++; throw CancellationException("superseded") }
            fail("cancelled parsing must stop")
        } catch (_: CancellationException) {
            assertEquals(1, checks)
            assertNull(cache.peek(source))
        }
    }

    @Test fun malformedDestinationsHaveALinearScanBudgetAndPreserveEveryCharacter() {
        for (repetitions in listOf(1024, 8192, 32768)) {
            val source = "[x](".repeat(repetitions) + "tail"
            lateinit var parsed: ParsedChatMarkdown
            val elapsed = measureNanoTime { parsed = parseChatMarkdownPresentation(source) }
            assertTrue(parsed.plainTextFallback)
            assertEquals(source, parsed.syntax.text)
            assertTrue(parsed.imageSources.isEmpty())
            assertTrue(parsed.scanSteps <= MarkdownScanBudget(source.length).limit + 1)
            println("markdown-budget chars=${source.length} scans=${parsed.scanSteps} elapsedMs=${elapsed / 1_000_000.0}")
        }
    }

    @Test fun ordinaryRepeatedLinksKeepFormattingAndImagesAcrossThemeChanges() {
        val source = (1..100).joinToString(" ") { "[item$it](https://example.com/$it)" } + " ![图](</tmp/a (final).png>)"
        val parsed = parseChatMarkdownPresentation(source)
        assertFalse(parsed.plainTextFallback)
        assertEquals(listOf("/tmp/a (final).png"), parsed.imageSources)
        val light = parsed.styled(Color.Blue, Color.Gray, Color.Black)
        val dark = parsed.styled(Color.Cyan, Color.Black, Color.Gray)
        assertEquals(light.text, dark.text)
        assertEquals(parsed.imageSources, dark.getStringAnnotations(MARKDOWN_IMAGE_LINK_TAG, 0, dark.length).map { it.item })
        assertEquals("https://example.com/1", markdownLinkAt(dark, 0))
        assertTrue(dark.spanStyles.any { it.item.color == Color.Cyan })
    }

    @Test fun unbalancedUrlPunctuationAndCodeRunsStayWithinTheScanBudget() {
        val sources = listOf("https://example.com/" + ")".repeat(32768),
            "prefix " + (1..180).joinToString("x") { "`".repeat(it) })
        sources.forEach { source ->
            val parsed = parseChatMarkdownPresentation(source)
            assertTrue(parsed.scanSteps <= MarkdownScanBudget(source.length).limit + source.length)
        }
        val url = sources.first()
        assertEquals(url, parseChatMarkdownPresentation(url).syntax.text)
    }

    @Test fun boundedCacheReusesOneParseAndEvictsInsteadOfRetainingAllHistory() {
        val cache = MarkdownPresentationCache(maxChars = 100, maxEntries = 2)
        val first = cache.get("![first](/tmp/first.png)")
        assertSame(first, cache.get("![first](/tmp/first.png)"))
        cache.get("second"); cache.get("third")
        assertNull(cache.peek("![first](/tmp/first.png)"))
        val huge = "x".repeat(101)
        cache.get(huge)
        assertNull(cache.peek(huge))
    }

    @Test fun completeTableSourceSurvivesPaginationWithoutReconstructionLoss() {
        val source = "Name | Link\n:---|---:\n" + (1..80).joinToString("\n") { "$it | [a\\|b](</tmp/a ($it).kt>)" }
        val table = parseChatMarkdownBlocks(source).single() as ChatMarkdownBlock.Table
        assertEquals(80, table.rows.size)
        assertEquals(source, markdownTableSource(table))
    }
}
