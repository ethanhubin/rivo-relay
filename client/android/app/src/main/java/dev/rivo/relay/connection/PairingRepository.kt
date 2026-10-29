package dev.rivo.relay.connection

import dev.rivo.relay.BuildConfig
import dev.rivo.relay.data.RelayApi
import dev.rivo.relay.data.string
import org.json.JSONObject

class NodeConnection(val base: String, val name: String, val nodeId: String, val deviceId: String, private val token: String) {
    fun api() = RelayApi(base, token)
    fun serialized() = JSONObject().put("base", base).put("name", name).put("nodeId", nodeId).put("deviceId", deviceId).put("token", token).toString()
}
data class SavedNode(val base: String, val name: String, val authorized: Boolean)
class PairingRepository(private val credentials: CredentialStore) {
    private fun records() = runCatching { org.json.JSONArray(credentials.read("nodes") ?: "[]") }.getOrDefault(org.json.JSONArray())
    private fun remember(base: String, connection: NodeConnection? = null) {
        val old = records()
        val next = org.json.JSONArray()
        val previous = (0 until old.length()).map { old.getJSONObject(it) }.firstOrNull { it.string("base") == base }
        next.put(connection?.let { JSONObject(it.serialized()) } ?: previous ?: JSONObject().put("base", base).put("name", base))
        (0 until old.length()).map { old.getJSONObject(it) }.filter { it.string("base") != base }.forEach { next.put(it) }
        credentials.save("nodes", next.toString())
    }
    fun saved(): List<SavedNode> {
        restore()?.let { remember(it.base, it) }
        val all = records()
        return (0 until all.length()).map { all.getJSONObject(it) }.map { SavedNode(it.string("base"), it.string("name"), it.string("token").isNotBlank()) }
    }
    fun select(base: String): NodeConnection? {
        val all = records()
        val item = (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.string("base") == base && it.string("token").isNotBlank() } ?: return null
        credentials.save("connection", item.toString())
        return restore()
    }
    fun invalidate() {
        val active = restore()
        val all = records()
        (0 until all.length()).map { all.getJSONObject(it) }.filter { it.string("base") == active?.base }.forEach { it.remove("token") }
        credentials.save("nodes", all.toString())
        forget()
    }
    fun restore(): NodeConnection? = credentials.read("connection")?.let { value -> runCatching {
        val j = JSONObject(value); NodeConnection(j.getString("base"), j.getString("name"), j.string("nodeId"), j.string("deviceId"), j.getString("token"))
    }.getOrElse { credentials.remove("connection"); null } }
    suspend fun pair(input: String, code: String): NodeConnection {
        val address = PairingParser.parse(input, code.ifBlank { "address-only" }, BuildConfig.DEBUG).baseUrl
        remember(address)
        val pairing = PairingParser.parse(input, code, BuildConfig.DEBUG)
        val j = RelayApi(pairing.baseUrl).call("/v1/pair", "POST", JSONObject().put("code", pairing.code).put("deviceName", "Android · Rivo Relay"))
        val node = j.getJSONObject("node")
        val connection = NodeConnection(pairing.baseUrl, node.string("name"), node.string("id"), j.string("deviceId"), j.getString("token"))
        remember(connection.base, connection)
        credentials.save("connection", connection.serialized())
        return connection
    }
    fun forget() { credentials.remove("connection") }
}
