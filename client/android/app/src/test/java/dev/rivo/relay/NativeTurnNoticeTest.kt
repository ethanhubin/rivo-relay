package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeTurnNoticeTest {
    @Test fun failedEmptyTurnRetainsNativeErrorWithoutInventingAMessage() {
        val raw = JSONObject("""{"thread":{"id":"thread","turns":[{"id":"failed","status":"failed","items":[],"error":{"message":"provider unavailable","additionalDetails":"retry later"}}]}}""")
        val state = sessionSnapshot(raw)
        assertTrue(state.messages.isEmpty())
        val row = nativeTimelineRows(state.messages, turnSettings = state.turnSettings).single() as NativeTimelineRow.Notice
        assertEquals("failed", row.turn.turnId)
        assertEquals("failed", row.turn.status)
        assertEquals("provider unavailable", row.turn.error!!.string("message"))
        assertEquals("retry later", row.turn.error!!.string("additionalDetails"))
        assertEquals("notice:failed", row.key)
    }

    @Test fun noticesFollowTheirNativeTurnEvenWithoutItemsAndPendingStaysLast() {
        fun message(id: String, turn: String) = NativeMessage(id, turn, "agentMessage", id, JSONObject())
        val first = message("first", "one")
        val second = message("second", "three")
        val pending = message("pending", "local-pending")
        val settings = listOf(NativeTurnSettings("one", status = "completed"),
            NativeTurnSettings("two", status = "interrupted"), NativeTurnSettings("three", status = "canceled"))
        val rows = nativeTimelineRows(listOf(first, second, pending), turnSettings = settings)
        assertEquals(listOf("message:one:first", "notice:two", "message:three:second", "notice:three", "message:local-pending:pending"), rows.map { it.key })
    }

    @Test fun completedNormalTurnsDoNotProduceNoticesButNativeErrorDoes() {
        val rows = nativeTimelineRows(emptyList(), turnSettings = listOf(
            NativeTurnSettings("normal", status = "completed"),
            NativeTurnSettings("with-error", status = "completed", error = JSONObject().put("message", "native error"))))
        assertEquals(listOf("notice:with-error"), rows.map { it.key })
    }

    @Test fun rawSnapshotRoundTripPreservesTurnStatusErrorAndModelObservation() {
        val raw = JSONObject("""{"thread":{"id":"thread","turns":[{"id":"turn","status":"interrupted","items":[{"id":"cmd","type":"commandExecution","status":"inProgress","command":"check"}],"error":{"message":"interrupted by owner"}}]},"turnSettings":[{"turnId":"turn","model":"native-model","effort":"high"}]}""")
        val restored = sessionSnapshot(JSONObject(raw.toString()))
        assertEquals("native-model", restored.turnSettings.single().model)
        assertEquals("high", restored.turnSettings.single().effort)
        assertEquals("interrupted", restored.messages.single().turnStatus)
        assertEquals("failed", restored.messages.single().activity!!.status)
        assertEquals("interrupted by owner", restored.turnSettings.single().error!!.string("message"))
    }

    @Test fun firstSubmissionOverlayRemainsBeforeNativeRowsAndNotices() {
        val snapshot = sessionSnapshot(JSONObject("""{"thread":{"id":"thread","turns":[{"id":"turn","status":"failed","items":[{"id":"answer","type":"agentMessage","text":"partial answer"}]}]}}"""))
        val pending = PendingNativeMessage("submission", "client", "thread",
            listOf(JSONObject().put("type", "text").put("text", "first prompt")), startsThread = true)
        val projected = projectedSessionMessages(snapshot, listOf(pending))
        val rows = nativeTimelineRows(projected, turnSettings = snapshot.turnSettings)
        assertEquals(listOf("pending:client", "answer"), rows.filterIsInstance<NativeTimelineRow.Message>().map { it.message.id })
        assertEquals("message:pending:client:pending:client", rows.first().key)
        assertEquals("notice:turn", rows.last().key)
    }

    @Test fun anchoredOverlayWithoutTurnMetadataKeepsItsOriginalPosition() {
        val snapshot = sessionSnapshot(JSONObject("""{"thread":{"id":"thread","turns":[{"id":"one","status":"completed","items":[{"id":"anchor","type":"agentMessage","text":"first"}]},{"id":"empty","status":"interrupted","items":[]},{"id":"two","status":"completed","items":[{"id":"next","type":"agentMessage","text":"second"}]}]}}"""))
        val pending = PendingNativeMessage("submission", "client", "thread",
            listOf(JSONObject().put("type", "text").put("text", "guidance")), kind = "guidance",
            nativeTurnId = "not-yet-in-metadata", anchorItemId = "anchor")
        val projected = projectedSessionMessages(snapshot, listOf(pending))
        val rows = nativeTimelineRows(projected, turnSettings = snapshot.turnSettings)
        assertEquals(listOf("anchor", "pending:client", "next"), rows.filterIsInstance<NativeTimelineRow.Message>().map { it.message.id })
        assertEquals(listOf("message:one:anchor", "message:not-yet-in-metadata:pending:client", "notice:empty", "message:two:next"), rows.map { it.key })
    }

    @Test fun emptyTurnNoticesRespectNativeOrderWithoutMovingLeadingOrTrailingOverlays() {
        fun message(id: String, turn: String) = NativeMessage(id, turn, "agentMessage", id, JSONObject())
        val messages = listOf(message("leading", "pending:first"), message("body", "middle"), message("trailing", "pending:last"))
        val turns = listOf(NativeTurnSettings("empty-first", status = "failed"),
            NativeTurnSettings("middle", status = "interrupted"), NativeTurnSettings("empty-last", status = "canceled"))
        val rows = nativeTimelineRows(messages, turnSettings = turns)
        assertEquals(listOf("leading", "body", "trailing"), rows.filterIsInstance<NativeTimelineRow.Message>().map { it.message.id })
        assertEquals(listOf("empty-first", "middle", "empty-last"), rows.filterIsInstance<NativeTimelineRow.Notice>().map { it.turn.turnId })
        assertEquals(listOf("message:pending:first:leading", "notice:empty-first", "message:middle:body", "notice:middle", "notice:empty-last", "message:pending:last:trailing"), rows.map { it.key })
    }
}
