package dev.rivo.relay.connection

import org.json.JSONArray
import org.json.JSONObject

/** Explicit migration of this deployment only; unrelated HTTPS nodes keep their transport. */
object NodeEndpoints {
    const val TAILNET_IP = "100.124.150.1"
    const val LEGACY = "https://ethanmac-mini.tail791ea5.ts.net:18766"
    const val DIRECT = "http://100.124.150.1:18767"
    // These two owner-managed nodes were explicitly provisioned with the existing
    // local node's connection hashes. Never share credentials with arbitrary URLs.
    internal val provisionedDeviceIds = setOf("4e32d09bb49d4153a29e3a803f655592", "38185163eba44756a428946a2fee4ce5", "ac0d199bd8ca4f5c9a5553b0deec8f6c", "d68689a9a346455cbe1067f577c6a22f", "c678704303b442e6ac99a76f2222451e")
    fun provisionedNodes(raw: String, active: String?): String {
        val nodes = JSONArray(raw)
        val entries = (0 until nodes.length()).map { nodes.getJSONObject(it) }
        val source = (entries + listOfNotNull(active?.let { runCatching { JSONObject(it) }.getOrNull() }))
            .firstOrNull { it.optString("base").trimEnd('/') == DIRECT && it.optString("token").isNotBlank() && it.optString("deviceId") in provisionedDeviceIds }
            ?: return raw
        val targets = listOf(
            Triple("https://ethanmacbook-pro.tail791ea5.ts.net:18766", "MacBook Pro", "e04d6984-eab8-5756-b287-27abe448a6cc"),
            Triple("https://e2mac-mini.tail791ea5.ts.net:18766", "e2 Mac mini", "ee47d2e4-ad59-51a2-9162-37ee0731a74a"),
        )
        for ((base, name, id) in targets) {
            // Preserve existing entries, including locally invalidated credentials.
            if (entries.any { it.optString("base").trimEnd('/') == base }) continue
            nodes.put(JSONObject().put("base", base).put("storageBase", base).put("name", name)
                .put("nodeId", id).put("deviceId", source.getString("deviceId")).put("token", source.getString("token")))
        }
        return nodes.toString()
    }
    fun migrateStored(raw: String, list: Boolean): String {
        var changed = false
        fun migrate(item: JSONObject) {
            if (item.optString("base").trimEnd('/') == LEGACY) {
                if (!item.has("storageBase")) item.put("storageBase", item.getString("base"))
                item.put("base", DIRECT)
                changed = true
            }
        }
        return try {
            val value = if (list) JSONArray(raw).also { array ->
                for (i in 0 until array.length()) migrate(array.getJSONObject(i))
            } else JSONObject(raw).also(::migrate)
            if (changed) value.toString() else raw
        } catch (_: Exception) { raw }
    }
}
