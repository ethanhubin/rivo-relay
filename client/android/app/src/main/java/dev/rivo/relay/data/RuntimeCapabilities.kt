package dev.rivo.relay.data

import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONObject

/** Operations describe behavior, never a particular runtime implementation. */
data class RuntimeOperation(val supported: Boolean, val available: Boolean, val reasonCode: String? = null)
class RuntimeCapabilities private constructor(private val operations: Map<String, RuntimeOperation>?) {
    fun operation(id: String): RuntimeOperation = operations?.get(id)
        ?: if (operations == null) RuntimeOperation(true, true) else RuntimeOperation(false, false, "unsupported")
    fun allows(id: String) = operation(id).let { it.supported && it.available }
    fun supports(id: String) = operation(id).supported
    fun withOverrides(value: JSONObject?): RuntimeCapabilities {
        if (value == null || !value.has("schemaVersion") && !value.has("operations")) return this
        val override = from(value)
        if (operations == null) return override
        val keys = operations.keys + override.operations.orEmpty().keys
        return RuntimeCapabilities(keys.associateWith { key ->
            val node = operation(key); val session = override.operation(key)
            RuntimeOperation(node.supported && session.supported, node.available && session.available,
                session.reasonCode ?: node.reasonCode)
        })
    }
    companion object {
        val Legacy = RuntimeCapabilities(null)
        val Unavailable = RuntimeCapabilities(emptyMap())
        fun from(value: JSONObject?): RuntimeCapabilities {
            if (value == null || !value.has("schemaVersion") && !value.has("operations")) return Legacy
            if (value.opt("schemaVersion") != 1) return Unavailable
            val source = value.optJSONObject("operations") ?: return Unavailable
            return RuntimeCapabilities(source.keys().asSequence().associateWith { key ->
                val row = source.optJSONObject(key)
                RuntimeOperation(row?.opt("supported") == true, row?.opt("available") == true,
                    (row?.opt("reasonCode") as? String)?.takeIf(String::isNotBlank))
            })
        }
    }
}
val LocalRuntimeCapabilities = staticCompositionLocalOf { RuntimeCapabilities.Legacy }
