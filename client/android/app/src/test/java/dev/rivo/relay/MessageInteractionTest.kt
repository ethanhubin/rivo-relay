package dev.rivo.relay

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.rivo.relay.ui.chat.*
import org.junit.Assert.*
import org.junit.Test

class MessageInteractionTest {
    @Test fun quotesPreserveParagraphsAndExistingDraft() {
        assertEquals("> First\n>\n> Second\n\n", appendQuoteToDraft("", " First\n\nSecond "))
        assertEquals("Draft\n\n> Selected\n\n", appendQuoteToDraft("Draft  ", "Selected"))
        assertEquals("Draft", appendQuoteToDraft("Draft", "  "))
    }

    @Test fun fileLinksPreserveNativeLocationsAndRejectOtherSchemes() {
        assertEquals("/project/src/Main.kt:12", messageFileTarget("src/Main.kt:12:4", "/project"))
        assertEquals("/project/src/Main.kt:12", messageFileTarget("src/Main.kt#L12-L18", "/project"))
        assertEquals("/tmp/a b.kt:2", messageFileTarget("file:///tmp/a%20b.kt#L2", "/project"))
        assertEquals("C:\\src\\Main.kt:7", messageFileTarget("C:\\src\\Main.kt:7", "/project"))
        assertNull(messageFileTarget("mailto:person@example.com", "/project"))
        assertNull(messageFileTarget("javascript:alert(1)", "/project"))
        assertNull(messageFileTarget("https://example.com/a.txt", "/project"))
    }

    @Test fun webFilesUseTheFileFlowButPagesKeepTheBrowserMenu() {
        assertTrue(isMessageFileWebLink("HTTPS://example.com/report.PDF?token=secret#page=2"))
        assertTrue(isMessageFileWebLink("https://example.com/movie.mp4"))
        assertTrue(isMessageFileWebLink("https://example.com/release.apk"))
        assertFalse(isMessageFileWebLink("https://example.com/page.html"))
        assertFalse(isMessageFileWebLink("https://example.com/download?id=1"))
    }

    @Test fun saveNamesOmitTokensAndUnsafeCharacters() {
        assertEquals("report final.pdf", webLinkSaveName("https://example.com/report%20final.pdf?token=secret#page=1"))
        assertEquals("example.com.html", webLinkSaveName("https://example.com/?token=secret"))
        assertEquals("a_b.txt", webLinkSaveName("https://example.com/a%3Ab.txt"))
    }

    @Test fun visibleMarkdownKeepsCodeLiteralAndFileAnnotations() {
        val rendered = renderChatMarkdown("```kotlin\nval text = \"[literal](https://example.com)\"\n```\n:codex-file-citation{path=\"/tmp/a.kt:12\"}", Color.Blue, Color.Gray, Color.Gray)
        assertTrue(rendered.text.contains("[literal](https://example.com)"))
        assertNull(markdownLinkAt(rendered, rendered.text.indexOf("literal")))
        assertEquals("/tmp/a.kt:12", markdownLinkAt(rendered, rendered.text.indexOf("a.kt")))
    }

    @Test fun toolbarStaysWithinTheWindow() {
        val placement = TextMenuPositionProvider(IntOffset(240, 1), 8)
            .calculatePosition(IntRect(20, 0, 300, 100), IntSize(320, 640), LayoutDirection.Ltr, IntSize(200, 48))
        assertEquals(IntOffset(120, 9), placement)
    }

    @Test fun mimeClassificationSupportsOfficeAndMediaWithoutQueryConfusion() {
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", fileMimeType("report.docx?download=1#top"))
        assertEquals("audio/flac", fileMimeType("recording.FLAC"))
        assertEquals("video/x-matroska", fileMimeType("movie.mkv"))
        assertEquals("text/plain", fileMimeType("source.yaml", "application/octet-stream; charset=binary"))
        assertEquals("text/markdown", fileMimeType("README.md", "text/plain"))
    }
}
