package dev.rivo.relay.data

import org.json.JSONObject

data class InteractionIdentity(val sessionId: String, val runtimeGeneration: String, val id: String, val revision: String) {
    fun responseBody(response: JSONObject) = JSONObject().put("sessionId", sessionId)
        .put("runtimeGeneration", runtimeGeneration).put("interactionId", id).put("revision", revision).put("response", response)
}
data class InteractionChoice(val id: String, val label: String, val effect: String)
data class RuntimeInteraction(val identity: InteractionIdentity, val kind: String, val title: String,
    val description: String, val detail: String, val choices: List<InteractionChoice>)

/** Native history remains a compatibility projection; new control data never falls back. */
internal fun SessionSnapshot.withRuntimeSession(root: JSONObject): SessionSnapshot {
    if (!root.has("sessionState")) return this
    fun invalid() = copy(status = "unknown", activeTurnId = "", requests = emptyList(), readOnly = true,
        nativeSyncState = "unknown", persistedHistoryReady = false, nativeExecution = "unknown",
        control = JSONObject().put("state", "unknown").put("canOperate", false), runtimeSessionValid = false,
        canAnswerQuestions = false, resumeTurnId = "", queue = emptyList(), recoveryTask = null, recoveryRequired = false,
        capabilities = JSONObject().put("schemaVersion", 1).put("operations", JSONObject()))
    return try {
        val wire = root.getJSONObject("sessionState")
        require(wire.opt("schemaVersion") == 1)
        fun text(j: JSONObject, key: String) = (j.opt(key) as? String)?.takeIf(String::isNotBlank) ?: error("Missing $key")
        val session = text(wire, "sessionId"); require(session == id)
        val generation = text(wire, "runtimeGeneration")
        text(wire, "revision")
        val execution = wire.getJSONObject("execution")
        if (execution.has("id")) require(execution.opt("id") is String)
        val status = text(execution, "status"); require(status in setOf("idle", "running", "waiting", "unknown"))
        val sync = wire.getJSONObject("synchronization")
        val syncState = text(sync, "state"); require(syncState in setOf("ready", "converging", "unknown", "unavailable"))
        require(sync.opt("historyReady") is Boolean)
        val control = wire.getJSONObject("control")
        require(control.opt("state") is String && control.opt("canOperate") is Boolean)
        val rows = wire.getJSONArray("interactions")
        val seen = mutableSetOf<String>()
        val interactions = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val key = text(row, "id"); require(seen.add(key))
            require(row.opt("status") == "pending")
            if (row.has("sessionId")) require(row.opt("sessionId") == session)
            if (row.has("runtimeGeneration")) require(row.opt("runtimeGeneration") == generation)
            val identity = InteractionIdentity(session, generation, key, text(row, "revision"))
            val kind = text(row, "kind"); require(kind in setOf("approval", "questions", "permissions", "elicitation", "unsupported"))
            val choices = row.getJSONArray("choices")
            val choiceIds = mutableSetOf<String>()
            val decoded = (0 until choices.length()).map { n -> choices.getJSONObject(n).let {
                val choice = text(it, "id"); require(choiceIds.add(choice))
                InteractionChoice(choice, text(it, "label"), text(it, "effect"))
            } }
            require(listOf("title", "description", "detail").all { row.opt(it) is String })
            val interaction = RuntimeInteraction(identity, kind, row.string("title"), row.string("description"), row.string("detail"), decoded)
            NativeRequest(key, "interaction/$kind", row.getJSONObject("payload"), interaction)
        }
        copy(status = status, activeTurnId = (execution.opt("id") as? String).orEmpty(), requests = interactions,
            nativeExecution = status, nativeSyncState = syncState, persistedHistoryReady = sync.getBoolean("historyReady"),
            control = control, readOnly = !control.getBoolean("canOperate"), runtimeSessionValid = true)
    } catch (_: Exception) { invalid() }
}

fun NativeRequest.samePendingIdentity(other: NativeRequest): Boolean = if (interaction != null || other.interaction != null)
    interaction != null && interaction.identity == other.interaction?.identity else key == other.key && method == other.method

internal fun interactionIdentity(value: JSONObject): InteractionIdentity {
    fun text(key: String) = (value.opt(key) as? String)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("交互身份缺失")
    return InteractionIdentity(text("sessionId"), text("runtimeGeneration"), text("interactionId"), text("revision"))
}
