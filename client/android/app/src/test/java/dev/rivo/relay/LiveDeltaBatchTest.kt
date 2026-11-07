package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveDeltaBatchTest {
    private fun snapshot(items: String) = sessionSnapshot(JSONObject("""{"thread":{"id":"t","turns":[{"id":"turn","items":$items}]}}"""))
    private fun event(method: String, id: String, offset: Int, delta: String, index: Int? = null) = JSONObject()
        .put("method", method).put("params", JSONObject().put("threadId", "t").put("turnId", "turn").put("itemId", id)
            .put("relayOffset", offset).put("delta", delta).apply { if (index != null) put("summaryIndex", index) })

    @Test fun benchmarkBatchedAndUnbatchedProjectionHaveIdenticalResults() {
        val items = org.json.JSONArray((0 until 1200).map { JSONObject().put("id", "item-$it").put("type", "agentMessage").put("text", "😀") })
        val original = snapshot(items.toString())
        val events = (0 until 256).map { event("item/agentMessage/delta", "item-1199", 1 + it * 8, "😀".repeat(8)) }
        val index = NativeMessageIndex(original.messages)
        repeat(2) { original.applyLiveDeltas(events, index) }
        val batched = mutableListOf<Long>()
        val unbatched = mutableListOf<Long>()
        repeat(3) {
            lateinit var batch: SessionSnapshot
            lateinit var individual: SessionSnapshot
            batched += kotlin.system.measureNanoTime { batch = original.applyLiveDeltas(events, index)!! }
            unbatched += kotlin.system.measureNanoTime { individual = events.fold(original) { value, event -> value.applyLiveDelta(event)!! } }
            assertEquals(individual.messages.last().text, batch.messages.last().text)
        }
        println("Delta projection JVM benchmark: 1200 items / 256 chunks; batch median=${batched.sorted()[1] / 1_000_000.0} ms; unbatched median=${unbatched.sorted()[1] / 1_000_000.0} ms (not an Android frame-time measurement)")
    }

    @Test fun manyContinuousChunksReuseUnchangedMessagesAndMatchSequentialUnicodeAlgebra() {
        val original = snapshot("""[{"id":"a","type":"agentMessage","text":"😀"},{"id":"b","type":"agentMessage","text":"unchanged"}]""")
        val events = (1..200).map { event("item/agentMessage/delta", "a", it, "😀") }
        val expected = events.fold(original) { value, event -> value.applyLiveDelta(event)!! }
        val result = original.applyLiveDeltas(events, NativeMessageIndex(original.messages))!!
        assertEquals(expected.messages.first().text, result.messages.first().text)
        assertEquals(201, result.messages.first().text.codePointCount(0, result.messages.first().text.length))
        assertSame(original.messages[1], result.messages[1])
        assertEquals("😀", original.messages.first().raw.string("text"))
        assertSame(result, result.applyLiveDeltas(events))
    }

    @Test fun invalidChunkRejectsEntireBatchWithoutMutatingBaseline() {
        val original = snapshot("""[{"id":"a","type":"agentMessage","text":"a"}]""")
        val events = listOf(event("item/agentMessage/delta", "a", 1, "b"), event("item/agentMessage/delta", "a", 5, "gap"))
        assertNull(original.applyLiveDeltas(events))
        assertEquals("a", original.messages.single().raw.string("text"))
        assertNull(original.applyLiveDelta(event("item/agentMessage/delta", "a", 0, "mismatch")))
    }

    @Test fun planFileAndIndexedReasoningDeltasRequireMatchingTypeIndexAndOffset() {
        val original = snapshot("""[{"id":"p","type":"plan","text":"one"},{"id":"f","type":"fileChange","status":"inProgress","changes":[{"path":"a.kt","diff":"diff"}],"output":"o"},{"id":"r","type":"reasoning","summary":["😀"]}]""")
        val result = original.applyLiveDeltas(listOf(event("item/plan/delta", "p", 3, " two"),
            event("item/fileChange/outputDelta", "f", 1, "k"), event("item/reasoning/summaryTextDelta", "r", 1, " done", 0)))!!
        assertEquals("one two", result.messages[0].text)
        assertTrue(result.messages[1].text.contains("diff")); assertTrue(result.messages[1].text.contains("ok"))
        assertEquals("😀 done", result.messages[2].text)
        assertEquals("😀", original.messages[2].raw.getJSONArray("summary").getString(0))
        assertNull(original.applyLiveDelta(event("item/reasoning/summaryTextDelta", "r", 0, "bad", 99)))
        assertNull(original.applyLiveDelta(event("item/plan/delta", "f", 1, "wrong type")))
        val legacy = event("item/fileChange/outputDelta", "f", 1, "k")
        legacy.getJSONObject("params").remove("relayOffset")
        assertNull(original.applyLiveDelta(legacy))
    }
}
