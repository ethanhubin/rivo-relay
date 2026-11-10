package dev.rivo.relay

import dev.rivo.relay.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PermissionDefaultsTest {
    @Test fun permissionsPersistWithoutModelAndRemainNodeLocal() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("rivo-permissions").toFile()
        try {
            val store = ConversationStore(directory)
            store.saveDefaultSettings(JSONObject().put("sandbox", "workspace-write").put("approvalPolicy", "preset-request"))
            val reopened = ConversationStore(directory)
            assertEquals("workspace-write", reopened.defaultSettings()!!.string("sandbox"))
            assertEquals("preset-request", reopened.defaultSettings()!!.string("approvalPolicy"))
            assertNull(ConversationStore(java.io.File(directory, "another-node")).defaultSettings())
            store.saveDraftOrdered("pending:test", ConversationDraft(sandbox = "inherit", approvalPolicy = "never"))
            val draft = ConversationStore(directory).loadDraft("pending:test")
            assertEquals("inherit", draft.sandbox)
            assertEquals("never", draft.approvalPolicy)
            assertFalse(draft.empty)
            store.saveDefaultSettings(JSONObject())
            assertFalse(ConversationStore(directory).defaultSettings()!!.has("sandbox"))
        } finally { directory.deleteRecursively() }
    }
}
