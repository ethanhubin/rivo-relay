package dev.rivo.relay

import dev.rivo.relay.codex.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActivityReadFenceTest {
    private fun snapshot(revision: Int = 5, generation: String = "g") = JSONObject("""{
        "generation":"$generation","revision":$revision,"version":10,"complete":true,
        "tasks":[{"threadId":"t","status":"running"},{"threadId":"other","status":"running"}],
        "knownThreadIds":["t","other"],"unknownThreadIds":[]}""")
    @Test fun unrelatedEventsOnlyFenceTheirRowsInBothDetailAndActivityResponses() {
        val fence = ActivityReadFence(); val read = ActivityObservation(snapshot(), fence.sequence)
        fence.invalidate(setOf("other"))
        for (required in listOf(null, "t")) {
            val filtered = fence.filter(read, required)!!
            val state = NodeActivity().accept(filtered)
            assertEquals("running", state.status("t")); assertNull(state.status("other"))
        }
        assertEquals(0, read.response.getJSONArray("unknownThreadIds").length())
        assertNull(fence.filter(read, "other"))
    }
    @Test fun notificationAlreadyContainedInDetailDoesNotDiscardItsOwnEvidence() {
        val fence = ActivityReadFence(); val start = fence.sequence
        fence.invalidate(setOf("t"), JSONObject("""{"generation":"g","revision":5}"""))
        assertNotNull(fence.filter(ActivityObservation(snapshot(), start), "t"))
        assertNull(fence.filter(ActivityObservation(snapshot(4), start), "t"))
        assertNull(fence.filter(ActivityObservation(snapshot(6, "old"), start), "t"))
    }
    @Test fun delayedVersionedNoticeCannotEraseAnUnversionedCompletionBarrier() {
        val fence = ActivityReadFence(); val read = ActivityObservation(snapshot(), fence.sequence)
        fence.invalidate(setOf("t"))
        fence.invalidate(setOf("t"), JSONObject("""{"generation":"g","revision":4}"""))
        assertNull(fence.filter(read, "t"))
        assertNotNull(fence.filter(ActivityObservation(snapshot(), fence.sequence), "t"))
    }
    @Test fun completionAfterPublicationStillFencesBufferedDetailAndReconnectFencesAll() {
        val fence = ActivityReadFence(); val read = ActivityObservation(snapshot(), fence.sequence)
        assertNotNull(fence.filter(read, "t"))
        fence.invalidate(setOf("t"))
        assertNull(NodeActivity().accept(fence.filter(read)!!).status("t"))
        fence.invalidate(null)
        assertNull(fence.filter(read))
        assertNotNull(fence.filter(ActivityObservation(snapshot(), fence.sequence)))
    }
}
