package dev.rivo.relay.data

/** Bounded node-local handoff from sent bytes to confirmed image paths. */
internal class SentImageCache {
    private val images = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var bytes = 0
    @Synchronized fun get(source: String): ByteArray? = images[source]
    @Synchronized fun reconcile(snapshot: SessionSnapshot, pending: List<PendingNativeMessage>) {
        val receipts = pending.associateBy { it.clientId }
        for (message in snapshot.messages) {
            if (message.type !in setOf("userMessage", "user_message")) continue
            val receipt = receipts[message.raw.string("clientId")] ?: continue
            val originals = receipt.input.filter { it.string("type") in setOf("image", "localImage") }
            val confirmed = message.raw.objects("content").filter { it.string("type") in setOf("image", "localImage") }
            if (originals.size != confirmed.size) continue
            for ((original, remote) in originals.zip(confirmed)) {
                val url = original.string("url")
                val path = if (remote.string("type") == "localImage") remote.string("path") else remote.string("url")
                if (path.isBlank() || path.startsWith("data:") || !url.startsWith("data:image/") || !url.substringBefore(',').endsWith(";base64") || images.containsKey(path)) continue
                val data = try { java.util.Base64.getDecoder().decode(url.substringAfter(',')) } catch (_: IllegalArgumentException) { continue }
                if (data.size > 40 * 1024 * 1024) continue
                while (bytes + data.size > 40 * 1024 * 1024 && images.isNotEmpty()) {
                    val entries = images.entries.iterator()
                    bytes -= entries.next().value.size
                    entries.remove()
                }
                images[path] = data
                bytes += data.size
            }
        }
    }
}
