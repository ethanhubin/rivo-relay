package dev.rivo.relay.codex

import dev.rivo.relay.data.*
import org.json.JSONObject

/** null is node-wide; an empty set explicitly affects no conversations. */
internal fun eventThreadIds(event: RelayEvent): Set<String>? {
    val params = event.payload.optJSONObject("params") ?: event.payload
    params.optJSONArray("threadIds")?.let { ids ->
        return (0 until ids.length()).map { ids.optString(it) }.filter(String::isNotBlank).toSet()
    }
    val id = params.string("threadId").ifBlank { params.string("conversationId") }
        .ifBlank { params.optJSONObject("thread")?.string("id").orEmpty() }
    return id.takeIf(String::isNotBlank)?.let(::setOf)
}

internal fun notificationRefreshTargets(event: RelayEvent): Set<String>? {
    if (event.type in setOf("reset", "connected")) return null
    if (event.type in setOf("queue", "submission")) return eventThreadIds(event)
    if (event.type != "native") return emptySet()
    val method = event.payload.string("method")
    if (method == "relay/todos" || method.startsWith("project/")) return emptySet()
    if (method.startsWith("item/") && !event.payload.has("id")) return emptySet()
    return if (catalogLifecycleEvent(event) || event.payload.has("id") || method == "relay/submission") eventThreadIds(event) else emptySet()
}

internal fun catalogEventRelevant(event: RelayEvent, projectId: String?, threads: Set<String>): Boolean {
    if (!catalogLifecycleEvent(event)) return false
    val params = event.payload.optJSONObject("params")
    val project = params?.string("projectId").orEmpty().ifBlank { params?.optJSONObject("thread")?.string("projectId").orEmpty() }
    if (project.isNotBlank() && projectId != "__rivo_recent__" && project != projectId) return false
    val targets = eventThreadIds(event)
    if (targets?.isEmpty() == true) return false
    // Membership events with no project identity must still discover newly created rows.
    return catalogMembershipEvent(event) || targets == null || targets.any { it in threads }
}

internal data class SubmissionRefresh(val threads: Set<String>, val projects: Boolean, val catalog: Boolean, val todos: Boolean)
internal fun submissionRefresh(pending: PendingSubmission, response: JSONObject): SubmissionRefresh {
    val source = Regex("^/v1/threads/([^/]+)/").find(pending.path)?.groupValues?.get(1)
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    val receipt = response.optJSONObject("receipt")?.string("threadId")?.takeIf(String::isNotBlank)
    val targets = when {
        pending.path.endsWith("/pin") -> emptySet()
        pending.path.endsWith("/fork") -> setOfNotNull(receipt)
        else -> setOfNotNull(source, pending.threadId, receipt)
    }
    return SubmissionRefresh(targets, pending.path.startsWith("/v1/projects"),
        pending.path == "/v1/threads" || pending.path.endsWith("/fork") ||
            listOf("/pin", "/archive", "/delete").any(pending.path::endsWith) || pending.path.startsWith("/v1/todos/"),
        pending.path.startsWith("/v1/todos/"))
}
