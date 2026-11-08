package dev.rivo.relay

import dev.rivo.relay.ui.chat.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownBlockCacheTest {
    @Test fun exactRevisionsPreserveParserBoundaries() {
        val cache = MarkdownBlockCache()
        val versions = listOf("text", "text\n| a | b |", "text\n| a | b |\n| --- | --- |",
            "```\n| a | b |\n| --- | --- |", "```\n| a | b |\n| --- | --- |\n```",
            "replacement\r\n\r\n[link](file.kt)", "")
        for (source in versions) assertEquals(splitChatMarkdownBlocks(source), cache.get(source))
        assertSame(cache.get("text"), cache.get("text"))
    }

    @Test fun streamingRevisionsDoNotEvictHistoryAndCompletionIsRetained() {
        val cache = MarkdownBlockCache(maxEntries = 2)
        val history = cache.get("history")
        repeat(20) { revision ->
            val source = "stream " + "x".repeat(revision)
            assertEquals(splitChatMarkdownBlocks(source), cache.get(source, retain = false))
        }
        assertSame(history, cache.get("history"))
        val final = cache.get("stream final")
        assertSame(final, cache.get("stream final"))
        assertSame(history, cache.get("history"))
    }

    @Test fun evictionAndOversizedSourcesRemainCorrect() {
        val cache = MarkdownBlockCache(maxChars = 8, maxEntries = 1)
        val first = cache.get("one")
        cache.get("two")
        assertNotSame(first, cache.get("one"))
        val large = "large".repeat(100)
        assertEquals(splitChatMarkdownBlocks(large), cache.get(large))
        assertNotSame(cache.get(large), cache.get(large))
    }
}
