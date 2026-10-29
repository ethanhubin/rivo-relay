package dev.rivo.relay

import dev.rivo.relay.ui.chat.nativeFilePath
import dev.rivo.relay.ui.chat.nativeInputs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAttachmentsTest {
    @Test fun structuredInputKeepsNativeTextSpansAndReferenceFields() {
        val reference = JSONObject().put("type", "mention").put("name", "Design").put("path", "/project/design.md")
        val inputs = nativeInputs("Review the design", emptyList(), listOf(reference))
        assertEquals(2, inputs.length())
        assertEquals("text", inputs.getJSONObject(0).getString("type"))
        assertEquals(0, inputs.getJSONObject(0).getJSONArray("text_elements").length())
        assertEquals("/project/design.md", inputs.getJSONObject(1).getString("path"))
        assertEquals("mention", inputs.getJSONObject(1).getString("type"))
    }

    @Test fun fileReferencesResolveAgainstNativeCwdWithoutOpeningNodeUrls() {
        assertEquals("/project/src/main.kt", nativeFilePath("src/main.kt:23:4", "/project"))
        assertEquals("/tmp/design notes.md", nativeFilePath("file:///tmp/design%20notes.md", ""))
        assertEquals("/project/a.kt", nativeFilePath("/project/a.kt#L12", ""))
        assertEquals("C:\\project\\a.kt", nativeFilePath("C:\\project\\a.kt:12", ""))
        assertNull(nativeFilePath("https://example.test/a.kt", "/project"))
        assertNull(nativeFilePath("data:image/png;base64,abc", "/project"))
        assertNull(nativeFilePath("relative.md", ""))
    }
}
