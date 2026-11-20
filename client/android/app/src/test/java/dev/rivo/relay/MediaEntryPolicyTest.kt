package dev.rivo.relay

import dev.rivo.relay.ui.chat.*
import org.junit.Assert.*
import org.junit.Test

class MediaEntryPolicyTest {
    @Test fun imageLinksIncludeSvgWithoutGrantingGeneralFileAccess() {
        for (extension in listOf("png", "jpg", "webp", "gif", "svg", "bmp", "ico"))
            assertTrue(extension, isMediaImageSource("https://media.example/image.$extension?version=1"))
        assertFalse(isMediaImageSource("https://media.example/document.pdf"))
        assertFalse(isMediaImageSource("/private/file.png"))
        assertFalse(isMediaImageSource("https://media.example/unknown"))
    }

    @Test fun editorOnlyOffersSupportedRasterFormatsAndUsesKnownMime() {
        for (extension in listOf("jpg", "jpeg", "png", "webp"))
            assertTrue(canEditMediaImage("https://media.example/image.$extension"))
        for (extension in listOf("svg", "gif", "bmp", "ico", "avif"))
            assertFalse(canEditMediaImage("https://media.example/image.$extension"))
        assertFalse(canEditMediaImage("/v1/media/library/id/content"))
        assertTrue(canEditMediaImage("/v1/media/library/id/content", "image/png"))
        assertFalse(canEditMediaImage("https://media.example/image.png", "image/svg+xml"))
        assertTrue(canEditMediaImage("data:image/png;base64,fixture"))
        assertFalse(canEditMediaImage("data:image/svg+xml;base64,fixture"))
    }
}
