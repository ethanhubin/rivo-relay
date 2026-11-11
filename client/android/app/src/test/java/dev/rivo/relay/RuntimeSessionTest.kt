package dev.rivo.relay

import dev.rivo.relay.data.*
import dev.rivo.relay.codex.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeSessionTest {
    private fun wire() = JSONObject("""{"thread":{"id":"s","turns":[]},"status":"running","activeTurnId":"old",
      "requests":[{"key":"legacy","method":"runtime/approval","params":{}}],
      "sessionState":{"schemaVersion":1,"sessionId":"s","runtimeGeneration":"g","revision":"r",
        "execution":{"status":"waiting","id":"execution"},"synchronization":{"state":"converging","historyReady":true},
        "control":{"state":"writable","canOperate":true},"interactions":[{"id":"i","revision":"ir","kind":"approval","status":"pending",
          "title":"Approve?","description":"Reason","detail":"Command","choices":[{"id":"opaque","label":"Allow this time","effect":"allow-once"}],"payload":{}}]}}""")
    @Test fun neutralControlAndInteractionsOverrideConflictingLegacyProjection() {
        val snapshot = sessionSnapshot(wire())
        assertEquals(true, snapshot.runtimeSessionValid)
        assertEquals("waiting", snapshot.status)
        assertEquals("execution", snapshot.activeTurnId)
        assertTrue(snapshot.historyReady)
        assertFalse(snapshot.nativeReady)
        assertTrue(SessionState(id = "s", snapshot = snapshot).control().canOperate)
        val request = snapshot.requests.single()
        assertEquals("approval", request.interaction!!.kind)
        assertEquals("opaque", request.interaction.choices.single().id)
        val body = request.interaction.identity.responseBody(JSONObject().put("choiceId", "opaque"))
        assertEquals("g", body.getString("runtimeGeneration"))
        assertEquals("ir", body.getString("revision"))
        assertEquals("i", body.getString("interactionId"))
    }
    @Test fun malformedNewProtocolCannotFallBackToOldApprovalOrWritableState() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("schemaVersion", 2) }, { it.put("sessionId", "other") }, { it.remove("runtimeGeneration") },
            { it.remove("interactions") }, { it.getJSONArray("interactions").getJSONObject(0).put("sessionId", "other") },
            { it.getJSONArray("interactions").getJSONObject(0).put("revision", 4) },
            { it.getJSONArray("interactions").getJSONObject(0).put("status", "resolved") })
        for (mutation in mutations) {
            val root = wire(); mutation(root.getJSONObject("sessionState"))
            val snapshot = sessionSnapshot(root)
            assertEquals(false, snapshot.runtimeSessionValid)
            assertTrue(snapshot.requests.isEmpty())
            assertFalse(SessionState(id = "s", snapshot = snapshot).control().canOperate)
        }
        assertEquals(false, sessionSnapshot(wire().put("sessionState", JSONObject.NULL)).runtimeSessionValid)
        assertEquals("legacy", sessionSnapshot(wire().apply { remove("sessionState") }).requests.single().key)
    }
    @Test fun revisionOrRuntimeRestartInvalidatesRememberedPendingIdentity() {
        val old = sessionSnapshot(wire()).requests.single()
        val revised = wire().apply { getJSONObject("sessionState").getJSONArray("interactions").getJSONObject(0).put("revision", "new") }
        val restarted = wire().apply { getJSONObject("sessionState").put("runtimeGeneration", "new") }
        for (root in listOf(revised, restarted)) {
            val next = sessionSnapshot(root).requests.single()
            assertFalse(old.samePendingIdentity(next))
            assertNotEquals(old.rememberKey, next.rememberKey)
        }
        assertTrue(old.samePendingIdentity(sessionSnapshot(wire()).requests.single()))
    }
    @Test fun restartingFromCachedHistoryNeverRevivesPendingInteractions() {
        val directory = java.nio.file.Files.createTempDirectory("runtime-session").toFile()
        try {
            val store = ConversationStore(directory)
            val cached = JSONObject(requireNotNull(store.snapshotCachePayload("s", wire())))
            assertEquals(0, cached.getJSONObject("sessionState").getJSONArray("interactions").length())
            val state = SessionState(id = "s", snapshot = sessionSnapshot(cached)).transition(SessionSyncEvent.Open(false))
            assertTrue(state.snapshot!!.requests.isEmpty())
            assertFalse(state.control().canOperate)
        } finally { directory.deleteRecursively() }
    }

    @Test fun malformedSessionClosesLegacyQuestionQueueAndAllOperationPaths() {
        val root = wire().put("canAnswerQuestions", true).put("resumeTurnId", "old-turn")
            .put("queue", org.json.JSONArray().put(JSONObject().put("id", "q").put("status", "waiting")))
            .put("recoveryTask", JSONObject().put("id", "recovery")).put("recoveryRequired", true)
            .put("capabilities", JSONObject("""{"schemaVersion":1,"operations":{"interaction.respond":{"supported":true,"available":true},"session.pin":{"supported":true,"available":true}}}"""))
        root.getJSONObject("sessionState").put("schemaVersion", 99)
        val snapshot = sessionSnapshot(root)
        val state = SessionState(id = "s", snapshot = snapshot)
        assertFalse(snapshot.canAnswerQuestions)
        assertEquals("", snapshot.resumeTurnId)
        assertTrue(snapshot.queue.isEmpty())
        assertNull(snapshot.recoveryTask)
        assertFalse(snapshot.recoveryRequired)
        assertFalse(state.canManageQueue())
        assertFalse(state.canContinueTask())
        assertFalse(state.canCompose())
        val caps = RuntimeCapabilities.Legacy.withOverrides(snapshot.capabilities)
        for (operation in listOf("interaction.respond", "session.pin", "session.archive", "session.delete", "session.settings", "session.queue"))
            assertFalse(operation, caps.allows(operation))
        // Async questions embedded in historical messages remain supported by valid nodes.
        root.getJSONObject("sessionState").put("schemaVersion", 1)
        assertTrue(sessionSnapshot(root).canAnswerQuestions)
    }

}
