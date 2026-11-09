package dev.rivo.relay.ui.chat

import dev.rivo.relay.data.NativeMessage
import dev.rivo.relay.data.string
import org.json.JSONObject

internal data class NativeMediaIdentity(val turnId: String, val itemId: String)

internal fun nativeMediaIndex(assets: List<JSONObject>): Map<NativeMediaIdentity, JSONObject> = buildMap {
    assets.forEach { asset ->
        val key = NativeMediaIdentity(asset.string("turnId"), asset.string("itemId"))
        if (!containsKey(key) || get(key)?.string("status") != "ready") put(key, asset)
    }
}

internal fun nativeMediaSourceIndex(assets: List<JSONObject>): Map<String, JSONObject> = buildMap {
    assets.forEach { asset ->
        listOf(asset.string("source"), asset.string("originalUrl")).filter(String::isNotBlank).forEach { source ->
            if (!containsKey(source)) put(source, asset)
        }
    }
}

internal data class GeneratedMediaRequest(val identity: NativeMediaIdentity, val status: String, val source: String)
internal fun generatedMediaRequests(messages: List<NativeMessage>): List<GeneratedMediaRequest> = messages.mapNotNull { message ->
    if (message.type != "imageGeneration") null else
        (if (message.raw.optBoolean("mediaRef")) message.raw.string("result").takeIf(String::isNotBlank)
         else generatedImageSource(message, emptyMap()))?.let {
        GeneratedMediaRequest(NativeMediaIdentity(message.turnId, message.id), message.raw.string("status"), it)
    }
}

/** Scoped to a node/thread. Unchanged immutable messages retain their parsed references. */
internal class NativeGalleryCache(private val parseImages: ((String) -> List<String>)? = null) {
    private data class Entry(val message: NativeMessage, val text: String, val markdownSources: List<String>, val sources: List<String>)
    private val entries = mutableMapOf<NativeMediaIdentity, Entry>()
    private var directory: String? = null

    @Synchronized
    fun sources(messages: List<NativeMessage>, cwd: String, assets: Map<NativeMediaIdentity, JSONObject>, checkCancelled: (() -> Unit)? = null): List<String> {
        if (directory != cwd) { entries.clear(); directory = cwd }
        val retained = HashSet<NativeMediaIdentity>(messages.size)
        val result = LinkedHashSet<String>()
        messages.forEach { message ->
            checkCancelled?.invoke()
            val identity = NativeMediaIdentity(message.turnId, message.id)
            retained += identity
            val old = entries[identity]
            val entry = if (old != null && old.message === message) old else {
                val markdown = if (old != null && old.text == message.text) old.markdownSources else
                    if ('[' in message.text) parseImages?.invoke(message.text) ?: parsedChatMarkdown(message.text, checkCancelled).imageSources else emptyList()
                val sources = buildList {
                    message.content.forEach { nativeImageSource(it, cwd)?.let(::add) }
                    markdown.forEach { source ->
                        (if (source.startsWith("data:image/") || isHttpWebLink(source)) source else nativeFilePath(source, cwd))?.let(::add)
                    }
                }
                Entry(message, message.text, markdown, sources).also { entries[identity] = it }
            }
            result.addAll(entry.sources)
            if (message.type == "imageGeneration") generatedImageSource(message, assets)?.let(result::add)
        }
        // Retain only this screen's loaded history, never messages from another thread or old pages.
        entries.keys.retainAll(retained)
        return result.toList()
    }
}
