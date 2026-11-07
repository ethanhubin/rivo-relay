package dev.rivo.relay

import dev.rivo.relay.connection.NodeConnection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NodeNameTest {
    private val original = NodeConnection("https://node.ts.net", "旧名称", "node-id", "device-id", "credential", "old-storage")

    @Test fun renamePreservesPairingAndLocalDataScope() {
        val updated = original.withNodeInfo(JSONObject().put("id", "node-id").put("name", "tailnet-machine"))
        assertEquals("tailnet-machine", updated.name)
        val before = JSONObject(original.serialized())
        val after = JSONObject(updated.serialized())
        for (key in listOf("base", "nodeId", "deviceId", "token", "storageBase"))
            assertEquals(before.getString(key), after.getString(key))
        assertSame(updated, updated.withNodeInfo(JSONObject().put("id", "node-id").put("name", "tailnet-machine")))
    }

    @Test fun wrongNodeOrIncompleteMetadataCannotOverwriteCachedName() {
        for (info in listOf(JSONObject(), JSONObject().put("id", "wrong").put("name", "other"),
            JSONObject().put("id", "node-id").put("name", "  "),
            JSONObject().put("id", "node-id").put("name", JSONObject.NULL),
            JSONObject().put("id", "node-id").put("name", 42)))
            assertSame(original, original.withNodeInfo(info))
    }
}
