package dev.rivo.relay

import dev.rivo.relay.codex.SessionHeadCache
import dev.rivo.relay.data.ApiException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionHeadCacheTest {
    private fun full() = JSONObject("""{"thread":{"id":"t","turns":[{"id":"turn","status":"completed","items":[{"id":"answer","type":"agentMessage","text":"reply"}]}]},"nativeHeadRevision":"head1","nativeRevision":"r1","nativeSync":{"state":"ready"},"control":{"canOperate":true},"requests":[],"settings":{}}""")
    private fun unchanged() = JSONObject("""{"thread":{"id":"t","name":"renamed"},"nativeHeadRevision":"head1","nativeRevision":"r1","nativeSync":{"state":"ready"},"historyUnchanged":true,"readOnly":true,"control":{"canOperate":false},"requests":[{"key":"approval","method":"approve","params":{}}],"turnSettings":[{"turnId":"turn","model":"new-model"}],"settings":{}}""")

    @Test fun largeMessageIsReusedButControlRequestsAndTurnSettingsAreFresh() {
        val cache = SessionHeadCache()
        val original = full()
        original.getJSONObject("thread").getJSONArray("turns").getJSONObject(0)
            .getJSONArray("items").getJSONObject(0).put("text", "long context ".repeat(100000))
        val first = cache.resolve(original)
        cache.remember(first)
        val response = unchanged()
        val result = cache.resolve(response)
        assertSame(first.snapshot.messages, result.snapshot.messages)
        assertEquals("renamed", result.snapshot.name)
        assertTrue(result.snapshot.readOnly)
        assertFalse(result.snapshot.control!!.getBoolean("canOperate"))
        assertEquals("approval", result.snapshot.requests.single().key)
        assertEquals("new-model", result.snapshot.turnSettings.single().model)
        assertEquals(1, result.json.getJSONObject("thread").getJSONArray("turns").length())
        assertFalse(result.json.has("historyUnchanged"))
        assertFalse(response.getJSONObject("thread").has("turns"))
        assertTrue(original.getJSONObject("control").getBoolean("canOperate"))
    }

    @Test fun mismatchedOrUnacknowledgedHeadsAreRejectedAndForceFullRead() {
        val bad = listOf(unchanged().put("nativeHeadRevision", "different"),
            unchanged().put("nativeRevision", "rollback"),
            unchanged().put("nativeSync", JSONObject().put("state", "converging")),
            unchanged().apply { getJSONObject("thread").put("id", "another") },
            unchanged().apply { getJSONObject("thread").put("turns", org.json.JSONArray()) })
        for (response in bad) {
            val cache = SessionHeadCache()
            cache.remember(cache.resolve(full()))
            assertTrue(runCatching { cache.resolve(response) }.exceptionOrNull() is ApiException)
            assertNull(cache.revision)
        }
        assertTrue(runCatching { SessionHeadCache().resolve(unchanged()) }.exceptionOrNull() is ApiException)
    }

    @Test fun changedAndLegacyResponsesReplaceMessagesNormally() {
        val cache = SessionHeadCache()
        cache.remember(cache.resolve(full()))
        val response = full().put("nativeHeadRevision", "head2")
        response.getJSONObject("thread").getJSONArray("turns").getJSONObject(0)
            .getJSONArray("items").getJSONObject(0).put("text", "corrected")
        val result = cache.resolve(response)
        assertEquals("corrected", result.snapshot.messages.single().text)
        cache.remember(result)
        assertEquals("head2", cache.revision)
        response.remove("nativeHeadRevision")
        cache.remember(cache.resolve(response))
        assertNull(cache.revision)
    }

    @Test fun unchangedItemsNeverFreezeGoalQueueRecoveryOrWaitingState() {
        val cache = SessionHeadCache()
        val head = cache.resolve(full())
        cache.remember(head)
        val response = unchanged().put("status", "waiting").put("activeTurnId", "new-running")
            .put("goalSupported", true).put("goal", JSONObject().put("status", "active").put("objective", "continue"))
            .put("queueDepth", 1).put("recoveryRequired", true).put("nativeExecution", "running")
            .put("deliveryState", "recovery_required").put("displayState", "waiting")
            .put("queue", org.json.JSONArray().put(JSONObject().put("id", "queued").put("status", "waiting")))
            .put("recoveryTask", JSONObject().put("id", "recover").put("status", "unknown").put("recoveryRequired", true))
        val fresh = cache.resolve(response)
        assertSame(head.snapshot.messages, fresh.snapshot.messages)
        assertEquals("waiting", fresh.snapshot.status)
        assertEquals("new-running", fresh.snapshot.activeTurnId)
        assertEquals("active", fresh.snapshot.goal!!.getString("status"))
        assertTrue(fresh.snapshot.goalSupported)
        assertEquals("approval", fresh.snapshot.requests.single().key)
        assertEquals("queued", fresh.snapshot.queue.single().id)
        assertEquals("recover", fresh.snapshot.recoveryTask!!.id)
        assertEquals(1, fresh.snapshot.queueDepth)
        assertTrue(fresh.snapshot.recoveryRequired)
        assertEquals("running", fresh.snapshot.nativeExecution)
        assertEquals("recovery_required", fresh.snapshot.deliveryState)
        assertFalse(fresh.snapshot.control!!.getBoolean("canOperate"))
        cache.remember(fresh)
        val stopped = unchanged().put("status", "idle").put("activeTurnId", "")
            .put("goal", JSONObject().put("status", "complete")).put("requests", org.json.JSONArray())
            .put("queue", org.json.JSONArray()).put("queueDepth", 0).put("recoveryRequired", false)
        val done = cache.resolve(stopped).snapshot
        assertSame(head.snapshot.messages, done.messages)
        assertEquals("complete", done.goal!!.getString("status"))
        assertEquals("idle", done.status)
        assertTrue(done.requests.isEmpty())
        assertTrue(done.queue.isEmpty())
        assertFalse(done.recoveryRequired)
        assertTrue(runCatching { cache.resolve(unchanged().put("nativeSync", JSONObject().put("state", "converging"))) }.exceptionOrNull() is ApiException)
    }
}
