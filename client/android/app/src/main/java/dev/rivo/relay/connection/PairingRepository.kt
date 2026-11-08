package dev.rivo.relay.connection

import dev.rivo.relay.BuildConfig
import dev.rivo.relay.data.RelayApi
import dev.rivo.relay.data.string
import org.json.JSONObject

class NodeConnection(val base: String, val name: String, val nodeId: String, val deviceId: String, private val token: String, val storageBase: String = base, val routes: NodeRoutes? = null) {
    fun api(dns: okhttp3.Dns = dev.rivo.relay.data.TailnetDns()) = RelayApi(base, token, dns, routes, nodeId)
    fun withNodeInfo(info: JSONObject): NodeConnection {
        // A display-name refresh must never migrate credentials or local storage.
        if (info.optString("id") != nodeId) return this
        val updated = (info.opt("name") as? String)?.trim().orEmpty()
        if (updated.isBlank() || updated == name) return this
        return NodeConnection(base, updated, nodeId, deviceId, token, storageBase, routes)
    }
    fun serialized() = JSONObject().put("base", base).put("name", name).put("nodeId", nodeId).put("deviceId", deviceId).put("token", token).put("storageBase", storageBase).apply { routes?.let { put("routes", it.json()) } }.toString()
}
data class SavedNode(val base: String, val name: String, val authorized: Boolean)
class PairingRepository internal constructor(
    private val read: (String) -> String?,
    private val save: (String, String) -> Unit,
    private val remove: (String) -> Unit,
) {
    constructor(credentials: CredentialStore) : this(credentials::read, credentials::save, credentials::remove)
    init {
        RouteMemory.read = { read(it) }
        RouteMemory.write = { key, value -> save(key, value) }
        for (key in listOf("connection", "nodes")) {
            read(key)?.let { raw ->
                val migrated = NodeEndpoints.migrateStored(raw, key == "nodes")
                if (migrated != raw) save(key, migrated)
            }
        }
    }
    init {
        val old = read("nodes") ?: "[]"
        val provisioned = NodeEndpoints.provisionedNodes(old, read("connection"))
        if (provisioned != old) save("nodes", provisioned)
    }
    private fun records() = runCatching { org.json.JSONArray(read("nodes") ?: "[]") }.getOrDefault(org.json.JSONArray())
    private fun remember(base: String, connection: NodeConnection? = null) {
        val old = records()
        val next = org.json.JSONArray()
        val previous = (0 until old.length()).map { old.getJSONObject(it) }.firstOrNull { it.string("base") == base || (connection != null && it.string("nodeId") == connection.nodeId) }
        next.put(connection?.let { JSONObject(it.serialized()) } ?: previous ?: JSONObject().put("base", base).put("name", base))
        (0 until old.length()).map { old.getJSONObject(it) }.filter { item -> item.string("base") != base && (connection == null || item.string("nodeId") != connection.nodeId) &&
            !(connection?.routes != null && item.string("token").isBlank() && item.string("base") in listOf(connection.routes.direct, connection.routes.relay)) }.forEach { next.put(it) }
        save("nodes", next.toString())
    }
    fun saved(): List<SavedNode> {
        restore()?.let { remember(it.base, it) }
        val all = records()
        return (0 until all.length()).map { all.getJSONObject(it) }.map { SavedNode(it.string("base"), it.string("name"), it.string("token").isNotBlank()) }
    }
    fun connection(base: String): NodeConnection? {
        val all = records()
        val item = (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.string("base") == (if (base.trimEnd('/') == NodeEndpoints.LEGACY) NodeEndpoints.DIRECT else base) && it.string("token").isNotBlank() } ?: return null
        return decode(item.toString())
    }
    fun select(base: String): NodeConnection? = connection(base)?.also { save("connection", it.serialized()) }
    private fun decode(value: String): NodeConnection {
        val j = JSONObject(value)
        return NodeConnection(j.getString("base"), j.getString("name"), j.string("nodeId"), j.string("deviceId"), j.getString("token"), j.optString("storageBase", j.getString("base")), j.optJSONObject("routes")?.let(NodeRoutes::from))
    }
    fun invalidate() {
        val active = restore()
        val all = records()
        (0 until all.length()).map { all.getJSONObject(it) }.filter { it.string("base") == active?.base }.forEach { it.remove("token") }
        save("nodes", all.toString())
        forget()
    }
    fun restore(): NodeConnection? = read("connection")?.let { value -> runCatching {
        val j = JSONObject(value); NodeConnection(j.getString("base"), j.getString("name"), j.string("nodeId"), j.string("deviceId"), j.getString("token"), j.optString("storageBase", j.getString("base")), j.optJSONObject("routes")?.let(NodeRoutes::from))
    }.getOrElse { remove("connection"); null } }
    suspend fun pair(input: String, code: String): NodeConnection {
        val address = PairingParser.parse(input, code.ifBlank { "address-only" }, BuildConfig.DEBUG).baseUrl
        remember(address)
        val pairing = PairingParser.parse(input, code, BuildConfig.DEBUG)
        val old = records().let { all -> (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull {
            pairing.nodeId.isNotBlank() && it.string("nodeId") == pairing.nodeId && it.string("token").isNotBlank()
        } }
        val binding = pairing.routes?.binding().orEmpty()
        val resume = old?.let { JSONObject().put("deviceId", it.string("deviceId"))
            .put("binding", binding).put("proof", routeProof(it.getString("token"), "request", pairing.code, pairing.nodeId, binding)) }
        // Never send an existing bearer token to a newly scanned endpoint. A
        // challenge bound to the one-use code proves possession in both directions.
        val j = RelayApi(pairing.baseUrl, routes = pairing.routes)
            .call("/v1/pair", "POST", JSONObject().put("code", pairing.code).put("deviceName", "Android · Rivo Relay")
                .apply { resume?.let { put("resume", it) } })
        val node = j.getJSONObject("node")
        require(pairing.nodeId.isBlank() || pairing.nodeId == node.string("id")) { "配对节点身份不匹配" }
        val reused = j.optString("reuseProof").isNotBlank()
        val token = if (reused) {
            require(old != null && j.string("deviceId") == old.string("deviceId") &&
                java.security.MessageDigest.isEqual(j.getString("reuseProof").toByteArray(),
                    routeProof(old.getString("token"), "response", pairing.code, pairing.nodeId, binding).toByteArray())) { "节点授权校验失败" }
            old!!.getString("token")
        } else j.getString("token").also { require(it.isNotBlank()) { "节点授权为空" } }
        val stableBase = old?.string("base") ?: pairing.baseUrl
        val connection = NodeConnection(stableBase, node.string("name"), node.string("id"), j.string("deviceId"), token,
            old?.optString("storageBase", stableBase) ?: stableBase, pairing.routes)
        remember(connection.base, connection)
        if (pairing.updateSource && pairing.routes != null) save("update-routes", pairing.routes.copy(mode = ConnectionMode.AUTO).json().toString())
        save("connection", connection.serialized())
        return connection
    }
    fun updateName(connection: NodeConnection) {
        remember(connection.base, connection)
        val active = restore()
        if (active?.base == connection.base && active.nodeId == connection.nodeId && active.deviceId == connection.deviceId)
            save("connection", connection.serialized())
    }
    fun setMode(mode: ConnectionMode): NodeConnection? = restore()?.let { setMode(it.base, mode) }
    fun setMode(base: String, mode: ConnectionMode): NodeConnection? {
        val selected = connection(base) ?: return null
        val routes = selected.routes ?: return selected
        val updated = decode(JSONObject(selected.serialized()).put("routes", routes.copy(mode = mode).validated().json()).toString())
        remember(updated.base, updated)
        if (restore()?.base == updated.base) save("connection", updated.serialized())
        return updated
    }
    fun forget() { remove("connection") }
}
