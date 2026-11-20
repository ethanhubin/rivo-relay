package dev.rivo.relay

import dev.rivo.relay.connection.NodeConnection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NodeNameTest {
    private val original = NodeConnection("https://node.ts.net", "旧名称", "node-id", "device-id", "credential", "old-storage")

    @Test fun authenticatedRuntimeRefreshWorksWithoutRenameAndNeverUsesDisplayName() {
        val updated = original.withNodeInfo(JSONObject().put("id", "node-id")
            .put("capabilities", JSONObject().put("runtime", "dsh")))
        assertEquals("dsh", updated.runtime)
        assertEquals(original.name, updated.name)
        assertSame(updated, updated.withNodeInfo(JSONObject().put("id", "wrong").put("runtime", "codex")))
        assertSame(updated, updated.withNodeInfo(JSONObject().put("id", "node-id")))
        assertEquals("codex", updated.withNodeInfo(JSONObject().put("id", "node-id").put("runtime", "codex")).runtime)
        assertEquals("", original.withNodeInfo(JSONObject().put("id", "node-id").put("name", "DeepSeek Harness")).runtime)
    }

    @Test fun legacyCodexStatusIdentifiesBackendEvenWhenDisconnected() {
        for (transport in listOf("existing-codex-daemon", "desktop", "relay-session-writer", "relay-stdio-metadata")) {
            val info = JSONObject().put("id", "node-id").put("runtime",
                JSONObject().put("runtime", transport).put("connected", false))
            assertEquals("codex", original.withNodeInfo(info).runtime)
            info.put("capabilities", JSONObject().put("runtime", "dsh"))
            assertEquals("dsh", original.withNodeInfo(info).runtime)
        }
        for (transport in listOf("unknown", "future-backend", "")) {
            assertSame(original, original.withNodeInfo(JSONObject().put("id", "node-id")
                .put("runtime", JSONObject().put("runtime", transport).put("connected", true))))
        }
    }

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
