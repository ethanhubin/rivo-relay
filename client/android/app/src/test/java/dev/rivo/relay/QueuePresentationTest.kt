package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QueuePresentationTest {
    @Test fun pendingQueueDoesNotFlashInTimeline() {
        val message = PendingNativeMessage("s", "c", "t", listOf(JSONObject().put("type", "text").put("text", "next")), kind = "queue")
        for (delivery in listOf("submitting", "unknown", "accepted")) {
            val pending = listOf(message.copy(delivery = delivery))
            assertTrue(projectedSessionMessages(null, pending).isEmpty())
            assertEquals("c", projectedPendingQueue(null, pending).single().id)
        }
        val failed = listOf(message.copy(delivery = "failed"))
        assertTrue(projectedPendingQueue(null, failed).isEmpty())
        assertEquals(1, projectedSessionMessages(null, failed).size)
        val native = sessionSnapshot(JSONObject("""{"thread":{"id":"t","turns":[{"id":"turn","items":[{"id":"real","type":"userMessage","clientId":"c","content":[{"type":"text","text":"next"}]}]}]}}"""))
        assertTrue(projectedPendingQueue(native, listOf(message)).isEmpty())
        assertEquals(listOf("real"), projectedSessionMessages(native, listOf(message)).map { it.id })
    }
}
