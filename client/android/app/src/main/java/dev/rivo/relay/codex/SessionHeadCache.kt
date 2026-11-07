package dev.rivo.relay.codex

import dev.rivo.relay.data.*
import org.json.JSONArray
import org.json.JSONObject

/** One open session's acknowledged head. Permissions always come from the new response. */
internal class SessionHeadCache {
    data class Head(val json: JSONObject, val snapshot: SessionSnapshot)
    private var saved: Head? = null
    val revision: String? get() = saved?.json?.string("nativeHeadRevision")?.takeIf(String::isNotBlank)

    fun clear() { saved = null }

    fun remember(head: Head) {
        saved = head.takeIf { it.snapshot.nativeReady && it.json.getJSONObject("thread").objects("turns").size <= 2 }
    }

    fun resolve(response: JSONObject): Head {
        if (!response.optBoolean("historyUnchanged")) return Head(response, sessionSnapshot(response))
        val previous = saved
        if (previous == null || revision == null || response.string("nativeHeadRevision") != revision ||
            response.string("nativeRevision") != previous.json.string("nativeRevision") ||
            response.optJSONObject("thread")?.string("id") != previous.snapshot.id ||
            response.optJSONObject("nativeSync")?.string("state") != "ready" ||
            response.getJSONObject("thread").has("turns")) {
            clear()
            throw ApiException(409, "head_mismatch", "历史版本已变化，正在重新读取完整消息")
        }
        val turns = previous.json.getJSONObject("thread").getJSONArray("turns")
        // Containers are shallow copied: large immutable text remains shared, and
        // neither an in-flight shared response nor the acknowledged head is mutated.
        val thread = shallow(response.getJSONObject("thread")).put("turns", turns)
        val full = shallow(response).apply { remove("historyUnchanged"); put("thread", thread) }
        // Re-evaluate turn settings against fresh observations without reparsing items.
        val metadataTurns = JSONArray()
        for (index in 0 until turns.length()) metadataTurns.put(shallow(turns.getJSONObject(index)).put("items", JSONArray()))
        val metadata = shallow(full).put("thread", shallow(thread).put("turns", metadataTurns))
        return Head(full, sessionSnapshot(metadata).copy(messages = previous.snapshot.messages))
    }

    private fun shallow(value: JSONObject) = JSONObject().also { copy ->
        value.keys().forEach { key -> copy.put(key, value.opt(key)) }
    }
}
