package dev.rivo.relay

import dev.rivo.relay.data.PendingNativeMessage
import dev.rivo.relay.ui.chat.hideSubmittedDraft
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SubmittedDraftPresentationTest {
    private val input = JSONArray("""[{"type":"text","text":"hello"},{"type":"image","url":"data:image/png;base64,AA=="}]""")
    private fun pending(delivery: String = "submitting", kind: String = "turn") = PendingNativeMessage(
        "submission", "client", "thread", (0 until input.length()).map { input.getJSONObject(it) }, kind, delivery)

    @Test fun hidesDuringSubmitAndCreateContinuationWithoutOverlay() {
        assertTrue(hideSubmittedDraft(true, true, input, emptyList()))
        for (kind in listOf("create", "turn", "queue"))
            assertTrue(hideSubmittedDraft(true, false, input, listOf(pending(kind = kind))))
    }
    @Test fun definitiveFailureRestoresEvenUnchangedDraft() {
        assertFalse(hideSubmittedDraft(false, true, input, emptyList()))
        assertFalse(hideSubmittedDraft(true, false, input, listOf(pending("failed"))))
    }
    @Test fun uncertainReceiptSurvivesRecreation() {
        assertTrue(hideSubmittedDraft(true, false, input, listOf(pending("unknown"))))
    }
    @Test fun acceptedFirstTurnStaysHiddenWhileFinishingGoal() {
        assertTrue(hideSubmittedDraft(true, false, input, listOf(pending("accepted"))))
        assertFalse(hideSubmittedDraft(false, false, input, listOf(pending("accepted"))))
    }
    @Test fun newerTextOrAttachmentRemainsVisible() {
        assertFalse(hideSubmittedDraft(true, false, JSONArray("""[{"type":"text","text":"next"}]"""), listOf(pending())))
        assertFalse(hideSubmittedDraft(true, false, JSONArray().put(input.getJSONObject(0)), listOf(pending())))
        assertFalse(hideSubmittedDraft(true, false, input, emptyList()))
    }
    @Test fun persistedObjectKeyOrderingDoesNotMatter() {
        val reordered = JSONArray("""[{"text":"hello","type":"text"},{"url":"data:image/png;base64,AA==","type":"image"}]""")
        assertTrue(hideSubmittedDraft(true, false, reordered, listOf(pending())))
    }
}
