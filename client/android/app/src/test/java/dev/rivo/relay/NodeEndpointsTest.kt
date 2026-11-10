package dev.rivo.relay

import dev.rivo.relay.connection.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NodeEndpointsTest {
    @Test fun restoringLoginNeverRewritesAddressesOrProvisionsOtherNodes() {
        for (base in listOf(NodeEndpoints.DIRECT, "https://ethanmac-mini.tail791ea5.ts.net:18766")) {
            val node = JSONObject().put("base", base).put("storageBase", "saved-storage")
                .put("name", "Saved node").put("nodeId", "node").put("token", "fixture")
                .put("deviceId", "4e32d09bb49d4153a29e3a803f655592")
            val values = mutableMapOf("connection" to node.toString(), "nodes" to JSONArray().put(node).toString())
            val before = values.toMap()
            val repository = PairingRepository(values::get, { key, value -> values[key] = value }, { values.remove(it) })
            assertEquals(before, values)
            assertEquals(base, repository.restore()!!.base)
            assertEquals("saved-storage", repository.restore()!!.storageBase)
            assertEquals(listOf(base), repository.saved().map { it.base })
            assertEquals("fixture", JSONObject(repository.connection(base)!!.serialized()).getString("token"))
        }
    }
    @Test fun releaseAcceptsConfiguredIpButRejectsOtherCleartextNodes() {
        assertEquals(NodeEndpoints.DIRECT, PairingParser.parse(NodeEndpoints.DIRECT, "fixture").baseUrl)
        for (url in listOf("http://100.124.150.1.evil.test:18767", "http://192.168.0.1", "http://example.com")) {
            assertThrows(IllegalArgumentException::class.java) { PairingParser.parse(url, "fixture") }
        }
    }
}
