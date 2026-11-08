package dev.rivo.relay.data

import android.content.Context
import coil.ImageLoader
import coil.request.ImageRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import coil.request.CachePolicy
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Media requests always terminate at the selected authenticated node, including public origins. */
class CodexMediaClient(val api: RelayApi, val threadId: String, context: Context) {
    val imageLoader = api.images(context)
    var revision by androidx.compose.runtime.mutableStateOf(0L)
        private set
    fun updateRevision(value: Long) { revision = value }
    private val versions = androidx.compose.runtime.mutableStateMapOf<String, JSONObject>()
    private val validationJob = kotlinx.coroutines.SupervisorJob()
    private val flights = MediaValidationFlights(kotlinx.coroutines.CoroutineScope(validationJob + kotlinx.coroutines.Dispatchers.Main.immediate))
    suspend fun verify(source: String) {
        if (source.startsWith("data:") || source.startsWith("content:") || source.startsWith("file:")) return
        flights.run(source) {
        val result = try {
            readMediaVersions { api.call("/v1/media/versions?source=${encoded(source)}") }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (!isTransientMediaFailure(error)) versions.remove(source)
            throw error
        }
        run {
            if (result == null) versions.remove(source) else versions[source] = result
        }
    }
    }
    fun hasVersion(source: String): Boolean = versions.containsKey(source)
    fun imageRequest(context: Context, source: String, thumbnail: Boolean): ImageRequest.Builder {
        if (source.startsWith("data:") || source.startsWith("content:") || source.startsWith("file:"))
            return ImageRequest.Builder(context).data(source)
        val req = api.mediaRequest(source, thumbnail)
        val key = MessageDigest.getInstance("SHA-256").digest((req.url.toString() + "\u0000" + req.header("Authorization")).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val version = versions[source]?.string(if (thumbnail) "thumbnail" else "original")
        return ImageRequest.Builder(context).data(req.url.toString()).headers(req.headers)
            .apply { if (!version.isNullOrBlank()) addHeader("If-Match", version) }
            .memoryCacheKey(key + ":" + version.orEmpty()).diskCacheKey(key + ":" + version.orEmpty())
            .memoryCachePolicy(if (version.isNullOrBlank()) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .diskCachePolicy(if (version.isNullOrBlank()) CachePolicy.DISABLED else CachePolicy.ENABLED)
    }
    suspend fun lookup(source: String): JSONObject? {
        val id = Regex("^/v1/media/(?:library|generated)/([a-f0-9]{64})/(?:content|thumbnail)$").matchEntire(source)?.groupValues?.get(1)
        return if (id != null) api.call("/v1/media/library/${encoded(id)}/provenance")
        else api.call("/v1/media/library?source=${encoded(source)}").objects("items").firstOrNull()
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
    fun close() { validationJob.cancel() } // Shared image loader remains owned by the API.
}
data class MediaGenerationInfo(val prompt: String, val negativePrompt: String = "", val source: String, val parameters: Map<String, String> = emptyMap())
data class MediaPromptResult(val prompt: String, val source: String = "reverse")
