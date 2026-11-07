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
}
