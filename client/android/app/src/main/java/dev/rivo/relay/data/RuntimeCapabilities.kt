package dev.rivo.relay.data

import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONObject

/** Operations describe behavior, never a particular runtime implementation. */
data class RuntimeOperation(val supported: Boolean, val available: Boolean, val reasonCode: String? = null)
class RuntimeCapabilities private constructor(private val operations: Map<String, RuntimeOperation>?, val maxAttachmentBytes: Long = 1024L * 1024 * 1024) {
    fun operation(id: String): RuntimeOperation = operations?.get(id)
        ?: settingFallbacks[id]?.let { operation(it) }
        ?: if (operations == null) RuntimeOperation(true, true) else RuntimeOperation(false, false, "unsupported")
    fun allows(id: String) = operation(id).let { it.supported && it.available }
    fun supports(id: String) = operation(id).supported
    fun withOverrides(value: JSONObject?): RuntimeCapabilities {
        if (value == null || !value.has("schemaVersion") && !value.has("operations")) return this
        val override = from(value)
        if (operations == null) return RuntimeCapabilities(override.operations, minOf(maxAttachmentBytes, override.maxAttachmentBytes))
        val keys = operations.keys + override.operations.orEmpty().keys + settingFallbacks.keys
        return RuntimeCapabilities(keys.associateWith { key ->
            val node = operation(key); val session = override.operation(key)
            RuntimeOperation(node.supported && session.supported, node.available && session.available,
                session.reasonCode ?: node.reasonCode)
        }, minOf(maxAttachmentBytes, override.maxAttachmentBytes))
    }
    /** Remove restored/default options that this node cannot apply. Does not mutate the draft. */
    fun filterSettings(value: JSONObject): JSONObject = JSONObject(value.toString()).apply {
        if (!allows("session.service_tier")) remove("serviceTier")
        if (!allows("session.permissions")) { remove("sandbox"); remove("approvalPolicy") }
    }
    companion object {
        private val settingFallbacks = mapOf("session.service_tier" to "session.models", "session.permissions" to "session.settings")
        val Legacy = RuntimeCapabilities(null)
        val Unavailable = RuntimeCapabilities(emptyMap(), 0)
        fun from(value: JSONObject?): RuntimeCapabilities {
            if (value == null || !value.has("schemaVersion") && !value.has("operations")) return Legacy
            if (value.opt("schemaVersion") != 1) return Unavailable
            val source = value.optJSONObject("operations") ?: return Unavailable
            val limits = value.optJSONObject("limits")
            val rawLimit = limits?.opt("maxAttachmentBytes")
            val limit = when {
                value.has("limits") && limits == null -> 0L
                limits?.has("maxAttachmentBytes") != true -> 1024L * 1024 * 1024
                rawLimit is Int && rawLimit > 0 -> rawLimit.toLong()
                rawLimit is Long && rawLimit > 0 -> rawLimit
                else -> 0L
            }.coerceAtMost(1024L * 1024 * 1024)
            return RuntimeCapabilities(source.keys().asSequence().associateWith { key ->
                val row = source.optJSONObject(key)
                RuntimeOperation(row?.opt("supported") == true, row?.opt("available") == true,
                    (row?.opt("reasonCode") as? String)?.takeIf(String::isNotBlank))
            }, limit)
        }
    }
}
val LocalRuntimeCapabilities = staticCompositionLocalOf { RuntimeCapabilities.Legacy }
