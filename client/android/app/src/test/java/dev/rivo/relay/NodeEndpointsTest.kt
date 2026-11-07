package dev.rivo.relay

import dev.rivo.relay.connection.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NodeEndpointsTest {
    @Test fun migrationRetainsIdentityCredentialAndStorageScope() {
        val old = JSONObject().put("base", NodeEndpoints.LEGACY).put("token", "fixture")
            .put("nodeId", "node").put("deviceId", "device").put("name", "name")
        val migrated = NodeEndpoints.migrateStored(old.toString(), false)
        val result = JSONObject(migrated)
        assertEquals(NodeEndpoints.DIRECT, result.getString("base"))
        assertEquals(NodeEndpoints.LEGACY, result.getString("storageBase"))
        for (key in listOf("token", "nodeId", "deviceId", "name")) assertEquals(old.getString(key), result.getString(key))
        assertEquals(migrated, NodeEndpoints.migrateStored(migrated, false))
        val other = JSONObject(old.toString()).put("base", "https://other.ts.net:18766")
        val list = JSONArray(NodeEndpoints.migrateStored(JSONArray().put(old).put(other).toString(), true))
        assertEquals(NodeEndpoints.DIRECT, list.getJSONObject(0).getString("base"))
        assertEquals(other.toString(), list.getJSONObject(1).toString())
    }
    @Test fun provisionedNodesRetainActiveLoginAndUseSeparateStorage() {
        val source = JSONObject().put("base", NodeEndpoints.DIRECT).put("token", "fixture")
            .put("nodeId", "source").put("deviceId", NodeEndpoints.provisionedDeviceIds.first())
            .put("storageBase", NodeEndpoints.LEGACY)
        val raw = JSONArray().put(source).toString()
        val result = NodeEndpoints.provisionedNodes(raw, source.toString())
        val nodes = JSONArray(result)
        assertEquals(3, nodes.length())
        assertEquals(source.toString(), nodes.getJSONObject(0).toString())
        for (i in 1..2) {
            val node = nodes.getJSONObject(i)
            assertEquals("fixture", node.getString("token"))
            assertEquals(source.getString("deviceId"), node.getString("deviceId"))
            assertNotEquals("source", node.getString("nodeId"))
            assertEquals(node.getString("base"), node.getString("storageBase"))
        }
        assertEquals(result, NodeEndpoints.provisionedNodes(result, source.toString()))
        nodes.getJSONObject(1).remove("token")
        assertFalse(JSONArray(NodeEndpoints.provisionedNodes(nodes.toString(), source.toString())).getJSONObject(1).has("token"))
    }
    @Test fun unrelatedOrUnprovisionedLoginsNeverShareTokens() {
        val source = JSONObject().put("base", "https://other.example").put("token", "fixture")
            .put("deviceId", NodeEndpoints.provisionedDeviceIds.first())
        assertEquals("[]", NodeEndpoints.provisionedNodes("[]", source.toString()))
        source.put("base", NodeEndpoints.DIRECT).put("deviceId", "not-provisioned")
        assertEquals("[]", NodeEndpoints.provisionedNodes("[]", source.toString()))
        source.put("deviceId", NodeEndpoints.provisionedDeviceIds.first()).remove("token")
        assertEquals("[]", NodeEndpoints.provisionedNodes("[]", source.toString()))
    }
    @Test fun releaseAcceptsConfiguredIpButRejectsOtherCleartextNodes() {
        assertEquals(NodeEndpoints.DIRECT, PairingParser.parse(NodeEndpoints.DIRECT, "fixture").baseUrl)
        for (url in listOf("http://100.124.150.1.evil.test:18767", "http://192.168.0.1", "http://example.com")) {
            assertThrows(IllegalArgumentException::class.java) { PairingParser.parse(url, "fixture") }
        }
    }
}
