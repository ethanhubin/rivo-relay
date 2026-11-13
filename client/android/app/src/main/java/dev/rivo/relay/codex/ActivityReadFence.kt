package dev.rivo.relay.codex

import dev.rivo.relay.data.string
import org.json.JSONArray
import org.json.JSONObject

internal data class ActivityObservation(val response: JSONObject, val started: Long)

/** Called under NodeSessionSync's lock; shared by detail and activity reads. */
internal class ActivityReadFence {
    private data class Change(val sequence: Long, val generation: String, val revision: Long, val barrier: Long = 0) {
        fun coveredBy(response: JSONObject) = generation.isNotBlank() && revision >= 0 &&
            response.string("generation") == generation && response.optLong("revision", -1) >= revision
    }
    var sequence = 0L
        private set
    private var global: Change? = null
    private val threads = mutableMapOf<String, Change>()

    fun invalidate(ids: Set<String>?, watermark: JSONObject? = null) {
        if (ids?.isEmpty() == true) return
        val change = Change(++sequence, watermark?.string("generation").orEmpty(), watermark?.optLong("revision", -1) ?: -1)
        if (ids == null) { global = change.copy(generation = "", revision = -1); threads.clear() }
        else ids.forEach {
            // A delayed versioned notice cannot erase a newer unversioned
            // native completion that arrived while the request was in flight.
            threads[it] = change.copy(barrier = if (watermark == null) sequence else threads[it]?.barrier ?: 0)
        }
    }

    fun filter(observation: ActivityObservation, requiredThread: String? = null): JSONObject? {
        val (response, started) = observation
        if (global?.let { it.sequence > started && !it.coveredBy(response) } == true) return null
        val stale = threads.filterValues { it.sequence > started && (it.barrier > started || !it.coveredBy(response)) }.keys
        if (requiredThread in stale) return null
        if (stale.isEmpty()) return response
        // One thread's event cannot discard another thread's authoritative detail.
        // Keep the full snapshot/version, but leave only affected rows unverified.
        // Same-thread completion and global reconnect still fence older evidence.
        val unknown = response.optJSONArray("unknownThreadIds") ?: return response
        val ids = (0 until unknown.length()).map { unknown.getString(it) }.toSet() + stale
        return JSONObject(response.toString()).put("unknownThreadIds", JSONArray(ids.toList()))
    }
}
