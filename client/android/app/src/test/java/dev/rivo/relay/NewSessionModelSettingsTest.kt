package dev.rivo.relay

import dev.rivo.relay.data.ConversationDraft
import dev.rivo.relay.data.ConversationStore
import dev.rivo.relay.data.string
import dev.rivo.relay.ui.chat.newSessionModelSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NewSessionModelSettingsTest {
    private val sol = JSONObject("""{"id":"sol","model":"gpt-6.1-sol","isDefault":true,
        "defaultReasoningEffort":"high","supportedReasoningEfforts":[{"reasoningEffort":"high"}]}""")
    private val astra = JSONObject("""{"id":"astra","model":"gpt-6-astra",
        "defaultReasoningEffort":"medium","supportedReasoningEfforts":[{"reasoningEffort":"medium"}]}""")

    @Test fun untouchedModelResolvesToTheCatalogChoiceAndItsEffort() {
        val settings = newSessionModelSettings(listOf(astra, sol), null, null)
        assertEquals("gpt-6.1-sol", settings.model)
        assertEquals("high", settings.effort)
    }

    @Test fun savedOrDraftChoiceWinsOverCatalogDefaultEvenWhenCatalogIsUnavailable() {
        for (models in listOf(listOf(sol, astra), emptyList())) {
            val settings = newSessionModelSettings(models, "gpt-6-astra", "medium")
            assertEquals("gpt-6-astra", settings.model)
            assertEquals("medium", settings.effort)
        }
        assertEquals("medium", newSessionModelSettings(listOf(sol, astra), "gpt-6-astra", null).effort)
    }

    @Test fun missingDefaultInheritsInsteadOfSilentlyPickingTheFirstModel() {
        for (models in listOf(emptyList(), listOf(astra))) {
            val settings = newSessionModelSettings(models, null, null)
            assertNull(settings.model)
            assertNull(settings.effort)
        }
        val idOnly = JSONObject(sol.toString()).apply { remove("model"); put("id", "gpt-6.1-sol") }
        assertEquals("gpt-6.1-sol", newSessionModelSettings(listOf(idOnly), null, null).model)
    }

    @Test fun resolvedModelSurvivesDraftAndDefaultStorageAndCatalogChanges() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("rivo-model-defaults").toFile()
        try {
            val resolved = newSessionModelSettings(listOf(sol), null, null)
            val store = ConversationStore(directory)
            store.saveDraftOrdered("pending:test", ConversationDraft(model = resolved.model, effort = resolved.effort))
            store.saveDefaultSettings(JSONObject().put("model", resolved.model).put("effort", resolved.effort)
                .put("serviceTier", "priority").put("approvalPolicy", "preset-request"))
            val reopened = ConversationStore(directory)
            val draft = reopened.loadDraft("pending:test")
            assertEquals(resolved, newSessionModelSettings(listOf(astra), draft.model, draft.effort))
            val defaults = reopened.defaultSettings()!!
            assertEquals(resolved, newSessionModelSettings(listOf(astra), defaults.string("model"), defaults.string("effort")))
            assertEquals("priority", defaults.string("serviceTier"))
            assertEquals("preset-request", defaults.string("approvalPolicy"))
        } finally { directory.deleteRecursively() }
    }
}
