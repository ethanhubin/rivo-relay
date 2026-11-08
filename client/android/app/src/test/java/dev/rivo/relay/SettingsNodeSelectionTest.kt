package dev.rivo.relay

import dev.rivo.relay.connection.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SettingsNodeSelectionTest {
    private val originalRead = RouteMemory.read
    private val originalWrite = RouteMemory.write
    @org.junit.After fun restoreRouteMemory() {
        RouteMemory.read = originalRead
        RouteMemory.write = originalWrite
    }

    @Test fun inspectingAndEditingAnotherNodeDoesNotSwitchActiveNode() {
        val a = NodeConnection("https://a.example", "A", "a", "device-a", "secret-a")
        val b = NodeConnection("https://b.example", "B", "b", "device-b", "secret-b",
            routes = NodeRoutes("https://b.example", "https://relay.example/relay/" + "a".repeat(32), "b".repeat(64)))
        val values = mutableMapOf("connection" to a.serialized(),
            "nodes" to JSONArray().put(org.json.JSONObject(a.serialized())).put(org.json.JSONObject(b.serialized())).toString())
        val repository = PairingRepository(values::get, { key, value -> values[key] = value }, { values.remove(it) })
        val active = values["connection"]
        assertEquals("b", repository.connection(b.base)?.nodeId)
        assertEquals(active, values["connection"])
        assertEquals(ConnectionMode.RELAY, repository.setMode(b.base, ConnectionMode.RELAY)?.routes?.mode)
        assertEquals(ConnectionMode.RELAY, repository.connection(b.base)?.routes?.mode)
        assertEquals(active, values["connection"])
        assertNull(repository.connection("https://unknown.example"))
        assertEquals(active, values["connection"])
        assertEquals("b", repository.select(b.base)?.nodeId)
        assertEquals("b", repository.restore()?.nodeId)
        repository.setMode(b.base, ConnectionMode.DIRECT)
        assertEquals(ConnectionMode.DIRECT, repository.restore()?.routes?.mode)
    }
}
