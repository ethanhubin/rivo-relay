package dev.rivo.relay

import dev.rivo.relay.data.NativeMessage
import dev.rivo.relay.ui.chat.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeGalleryCacheTest {
    private fun message(id: String, text: String, turn: String = "turn") = NativeMessage(id, turn, "agentMessage", text, JSONObject())

    @Test fun onlyTheChangedMessageIsReparsedAndRemovedHistoryIsEvicted() {
        var parses = 0
        val cache = NativeGalleryCache { parses++; markdownImageSources(it) }
        val first = message("a", "![a](/tmp/a.png)")
        val second = message("b", "![b](/tmp/b.png)")
        assertEquals(listOf("/tmp/a.png", "/tmp/b.png"), cache.sources(listOf(first, second), "/tmp", emptyMap()))
        assertEquals(2, parses)
        cache.sources(listOf(first.copy(raw = JSONObject()), second), "/tmp", emptyMap())
        assertEquals(2, parses)
        val changed = second.copy(text = "![b](/tmp/changed.png)")
        assertEquals(listOf("/tmp/a.png", "/tmp/changed.png"), cache.sources(listOf(first, changed), "/tmp", emptyMap()))
        assertEquals(3, parses)
        cache.sources(listOf(changed), "/tmp", emptyMap())
        cache.sources(listOf(first, changed), "/tmp", emptyMap())
        assertEquals(4, parses)
    }

    @Test fun galleryUsesMarkdownRulesAndSeparatesSameItemIdsInDifferentTurnsAndDirectories() {
        val first = message("same", "~~~\n![literal](/tmp/hidden.png)\n~~~\n![a](<a (one).png>)", "one")
        val second = message("same", "`![literal](/tmp/hidden2.png)` ![b](b.png)", "two")
        val cache = NativeGalleryCache()
        assertEquals(listOf("/work/a (one).png", "/work/b.png"), cache.sources(listOf(first, second), "/work", emptyMap()))
        assertEquals(listOf("/other/a (one).png", "/other/b.png"), cache.sources(listOf(first, second), "/other", emptyMap()))
        assertEquals(listOf("/new/b.png"), NativeGalleryCache().sources(listOf(second), "/new", emptyMap()))
    }

    @Test fun indexedAssetsReplaceOnlyTheMatchingGenerationAndTextDeltasDoNotInvalidateRequests() {
        val generation = NativeMessage("image", "turn", "imageGeneration", "", JSONObject()
            .put("savedPath", "/tmp/generated.png").put("status", "completed"))
        val text = message("text", "working")
        val requests = generatedMediaRequests(listOf(text, generation))
        assertEquals(requests, generatedMediaRequests(listOf(text.copy(text = "working more"), generation)))
        assertTrue(generatedMediaRequests(listOf(text)).isEmpty())
        val unrelated = JSONObject().put("turnId", "other").put("itemId", "image").put("status", "ready").put("originalUrl", "/v1/media/wrong/original")
        val asset = JSONObject().put("turnId", "turn").put("itemId", "image").put("status", "ready").put("originalUrl", "/v1/media/right/original")
        val cache = NativeGalleryCache()
        assertEquals(listOf("/tmp/generated.png"), cache.sources(listOf(generation), "/tmp", nativeMediaIndex(listOf(unrelated))))
        assertEquals(listOf("/v1/media/right/original"), cache.sources(listOf(generation), "/tmp", nativeMediaIndex(listOf(unrelated, asset))))
    }
}
