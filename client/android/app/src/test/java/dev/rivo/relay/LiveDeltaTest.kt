package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveDeltaTest {
    private fun snapshot() = sessionSnapshot(JSONObject("""{"thread":{"id":"t","turns":[{"id":"turn","items":[{"id":"i","type":"agentMessage","text":"Hi"}]}]}}"""))
    private fun event(text: String, thread: String = "t") = JSONObject("""{"method":"item/agentMessage/delta","params":{"threadId":"$thread","turnId":"turn","itemId":"i","relayOffset":0,"delta":"$text"}}""")
    @Test fun overlappingSnapshotAndEventsNeverDuplicateOrRegressText() {
        val initial = snapshot()
        val updated = initial.applyLiveDelta(event("Hi there"))!!
        assertEquals("Hi there", updated.messages.single().text)
        assertEquals("Hi", initial.messages.single().raw.getString("text"))
        assertSame(updated, updated.applyLiveDelta(event("Hi there")))
        assertSame(updated, updated.applyLiveDelta(event("Hi")))
    }
    @Test fun codePointOffsetsHandleEmojiAndPartiallyOverlappingSnapshots() {
        val initial = snapshot().applyLiveDelta(event("Hi😀"))!!
        val next = event("😀!").also { it.getJSONObject("params").put("relayOffset", 2) }
        assertEquals("Hi😀!", initial.applyLiveDelta(next)!!.messages.single().text)
        val append = event("!").also { it.getJSONObject("params").put("relayOffset", 3) }
        assertEquals("Hi😀!", initial.applyLiveDelta(append)!!.messages.single().text)
        append.getJSONObject("params").put("relayOffset", 99)
        assertNull(initial.applyLiveDelta(append))
    }
    @Test fun commandOutputUpdatesRawAndDisplayTogether() {
        val initial = sessionSnapshot(JSONObject("""{"thread":{"id":"t","turns":[{"id":"turn","items":[{"id":"i","type":"commandExecution","command":"echo ok","aggregatedOutput":"o"}]}]}}"""))
        val output = event("k").put("method", "item/commandExecution/outputDelta")
        output.getJSONObject("params").put("relayOffset", 1)
        val updated = initial.applyLiveDelta(output)!!
        assertEquals("echo ok\n\nok", updated.messages.single().text)
        assertEquals("ok", updated.messages.single().raw.getString("aggregatedOutput"))
        assertEquals("o", initial.messages.single().raw.getString("aggregatedOutput"))
    }
    @Test fun mismatchedOrLegacyEventsRequireSnapshot() {
        val initial = snapshot()
        assertNull(initial.applyLiveDelta(event("Hi!", "other")))
        assertNull(initial.applyLiveDelta(event("different")))
        val legacy = event("Hi!")
        legacy.getJSONObject("params").remove("relayOffset")
        assertNull(initial.applyLiveDelta(legacy))
        val unknown = event("Hi!")
        unknown.getJSONObject("params").put("itemId", "missing")
        assertNull(initial.applyLiveDelta(unknown))
    }
}
