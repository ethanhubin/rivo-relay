package dev.rivo.relay.ui.chat

import dev.rivo.relay.data.QueueTask
import dev.rivo.relay.data.objects
import dev.rivo.relay.data.string
import org.json.JSONObject

internal data class RestoredQueueDraft(
    val text: String, val images: List<DraftImage>, val files: List<JSONObject>,
    val model: String?, val effort: String?, val mode: String?, val serviceTier: String?,
)

/** Restores the accepted input before withdrawing; attachment bytes and original input order survive. */
internal fun queueTaskDraft(task: QueueTask): RestoredQueueDraft {
    val input = task.raw.objects("input")
    val images = mutableListOf<DraftImage>()
    val files = mutableListOf<JSONObject>()
    input.filter { it.string("type") != "text" }.forEach { original ->
        val part = JSONObject(original.toString())
        val url = part.string("url")
        if (part.string("type") == "image" && Regex("^data:image/(jpeg|png|webp);base64,").containsMatchIn(url)) {
            val bytes = runCatching { java.util.Base64.getDecoder().decode(url.substringAfter(',')) }.getOrNull()
            if (bytes != null) images += DraftImage("${task.id}:${images.size}", bytes)
            else files += part
        } else files += part
    }
    fun setting(key: String) = task.raw.string(key).takeIf(String::isNotBlank)
    return RestoredQueueDraft(input.filter { it.string("type") == "text" }.joinToString("\n") { it.string("text") }.ifBlank { task.text },
        images, files, setting("model"), setting("effort"), setting("mode"), setting("serviceTier"))
}

internal fun reasoningEffortForModel(capability: JSONObject?, previous: String?): String? {
    val supported = capability?.objects("supportedReasoningEfforts").orEmpty().map { it.string("reasoningEffort") }
    return previous?.takeIf { it in supported } ?: capability?.string("defaultReasoningEffort")?.takeIf { it in supported }
}
