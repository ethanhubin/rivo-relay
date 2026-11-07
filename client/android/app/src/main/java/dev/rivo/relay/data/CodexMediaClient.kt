package dev.rivo.relay.data

import android.content.Context
import coil.ImageLoader
import coil.request.ImageRequest
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Media requests always terminate at the selected authenticated node, including public origins. */
class CodexMediaClient(val api: RelayApi, val threadId: String, context: Context) {
    val imageLoader = ImageLoader.Builder(context.applicationContext).okHttpClient(api.mediaTransport())
        .memoryCache { coil.memory.MemoryCache.Builder(context.applicationContext).maxSizeBytes(32 * 1024 * 1024).build() }.build()
    fun imageRequest(context: Context, source: String, thumbnail: Boolean): ImageRequest.Builder {
        if (source.startsWith("data:") || source.startsWith("content:") || source.startsWith("file:"))
            return ImageRequest.Builder(context).data(source)
        val req = api.mediaRequest(source, thumbnail)
        val key = MessageDigest.getInstance("SHA-256").digest((req.url.toString() + "\u0000" + req.header("Authorization")).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return ImageRequest.Builder(context).data(req.url.toString()).headers(req.headers).memoryCacheKey(key).diskCacheKey(key)
    }
    suspend fun lookup(source: String): JSONObject? {
        val id = Regex("^/v1/media/(?:library|generated)/([a-f0-9]{64})/(?:content|thumbnail)$").matchEntire(source)?.groupValues?.get(1)
        return if (id != null) api.call("/v1/media/library/${encoded(id)}/provenance")
        else api.call("/v1/media/library?source=${encoded(source)}").objects("items").firstOrNull()
    }
    suspend fun favorite(sourceOrId: String, desired: Boolean?): MediaFavoriteResult {
        val record = if (sourceOrId.startsWith('/') || sourceOrId.contains(":") || sourceOrId.startsWith("data:")) lookup(sourceOrId)
            else if (Regex("[a-f0-9]{64}").matches(sourceOrId)) api.call("/v1/media/library/${encoded(sourceOrId)}/provenance") else null
        if (desired == null) return MediaFavoriteResult(record?.string("id").orEmpty(), record?.optBoolean("favorite") == true)
        val id = record?.string("id")?.takeIf { it.isNotBlank() } ?: api.call("/v1/media/library", "POST",
            JSONObject().put("source", sourceOrId).apply { if (!threadId.startsWith("pending:")) put("threadId", threadId) })
            .let { it.optJSONObject("item") ?: it }.string("id").also { require(it.isNotBlank()) { "节点没有返回媒体 ID" } }
        val saved = api.call("/v1/media/library/${encoded(id)}", "PATCH", JSONObject().put("favorite", desired))
        return MediaFavoriteResult(id, (saved.optJSONObject("item") ?: saved).optBoolean("favorite", desired))
    }
    suspend fun generationInfo(id: String): MediaGenerationInfo {
        val result = api.call("/v1/media/library/${encoded(id)}/provenance")
        val provenance = result.optJSONObject("provenance") ?: result
        val info = provenance.optJSONObject("facts") ?: provenance
        val parameters = linkedMapOf<String, String>()
        info.keys().forEach { key -> if (key !in setOf("prompt", "negativePrompt", "source")) parameters[key] = info.opt(key).toString() }
        listOf("threadId", "turnId", "itemId").forEach { key -> result.string(key).takeIf { it.isNotBlank() }?.let { parameters[key] = it } }
        parameters["提示词来源"] = when (provenance.string("promptKind")) { "original" -> "原始提示词"; "revised" -> "模型修订提示词"; else -> "原始请求未记录" }
        return MediaGenerationInfo(info.string("prompt").ifBlank { info.string("revisedPrompt").ifBlank { info.string("revised_prompt") } }, info.string("negativePrompt"), "Codex", parameters)
    }
    suspend fun cached(context: Context, source: String, progress: (Long, Long) -> Unit = { _, _ -> }): File = api.cachedMedia(context.cacheDir, source, progress)
    fun close() = imageLoader.shutdown()
}
data class MediaFavoriteResult(val mediaId: String, val favorite: Boolean)
data class MediaGenerationInfo(val prompt: String, val negativePrompt: String = "", val source: String, val parameters: Map<String, String> = emptyMap())
data class MediaPromptResult(val prompt: String, val source: String = "reverse")
