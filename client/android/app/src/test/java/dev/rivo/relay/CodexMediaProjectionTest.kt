package dev.rivo.relay

import dev.rivo.relay.data.NativeMessage
import dev.rivo.relay.ui.chat.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexMediaProjectionTest {
    @Test fun videoFormatsRouteToPlayerWithoutMisclassifyingTypeScript() {
        for (extension in listOf("flv", "m2ts", "mts", "vob", "rmvb", "3gpp")) {
            assertEquals(FileOpenKind.VIDEO, fileOpenKind("/native/clip.$extension"))
            assertTrue(fileMimeType("clip.$extension").startsWith("video/"))
        }
        assertEquals(FileOpenKind.TEXT, fileOpenKind("/project/main.ts"))
        assertEquals(FileOpenKind.VIDEO, fileOpenKind("/native/clip.ts", "video/mp2t"))
    }
    @Test fun savedReplicaUsesItsRecordedMimeAndFilename() {
        val source = "/v1/media/library/id/content"
        val asset = JSONObject().put("originalUrl", source).put("name", "Codex 生成图片").put("mimeType", "image/jpeg")
        assertEquals("Codex 生成图片.jpg" to "image/jpeg", mediaActionMetadata(source, listOf(asset), true))
        assertEquals("image.webp" to "image/webp", mediaActionMetadata("data:image/webp;base64,AAAA", emptyList(), true))
    }
    @Test fun canonicalImageVariantsAndGeneratedSourcesKeepTheirIdentity() {
        assertEquals("/project/plot.png", nativeImageSource(JSONObject("""{"type":"input_image","image_url":"plot.png"}"""), "/project"))
        assertEquals("/native/file.png", nativeImageSource(JSONObject("""{"type":"localimage","path":"/native/file.png"}"""), "/project"))
        assertNull(nativeImageSource(JSONObject("""{"type":"text","url":"https://example.com/file.png"}"""), "/project"))
        listOf("data:image/png;base64,AAAA", "https://example.com/output.png", "/native/output.png").forEach { value ->
            val item = NativeMessage("image", "turn", "imageGeneration", "", JSONObject().put("result", value))
            assertEquals(value, generatedImageSource(item, emptyList()))
        }
    }
    @Test fun pendingOrWrongTurnReplicaCannotReplaceNativeImage() {
        val item = NativeMessage("image", "turn", "imageGeneration", "", JSONObject().put("savedPath", "/native/result.png"))
        val asset = JSONObject().put("itemId", "image").put("turnId", "turn").put("status", "pending").put("originalUrl", "/v1/media/library/id/content")
        assertEquals("/native/result.png", generatedImageSource(item, listOf(asset)))
        asset.put("status", "ready").put("turnId", "other")
        assertEquals("/native/result.png", generatedImageSource(item, listOf(asset)))
        asset.put("turnId", "turn")
        assertEquals("/v1/media/library/id/content", generatedImageSource(item, listOf(asset)))
    }
}
