package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeTimelineCacheTest {
    @Test fun realDeltasThenStructuralTransitionsMatchFullProjection() {
        val initial = sessionSnapshot(JSONObject("""{"thread":{"id":"t","turns":[{"id":"turn","items":[{"id":"u","type":"userMessage","content":[]},{"id":"i","type":"agentMessage","text":"Hi"}]}]}}"""))
        val event = JSONObject("""{"method":"item/agentMessage/delta","params":{"threadId":"t","turnId":"turn","itemId":"i","relayOffset":0,"delta":"Hi there"}}""")
        val updated = initial.applyLiveDeltas(listOf(event))!!
        val command = sessionSnapshot(JSONObject("""{"thread":{"id":"t","turns":[{"id":"turn","items":[{"id":"c","type":"commandExecution","command":"pwd","status":"inProgress"}]}]}}""")).messages.single()
        val transitions = listOf(
            updated.messages.first().copy(id = "older").let { listOf(it) + updated.messages },
            listOf(updated.messages.first().copy(text = "changed"), updated.messages.last()),
            listOf(command, updated.messages.last()),
            updated.messages + command,
            emptyList()
        )
        for (messages in transitions) {
            val cache = NativeTimelineCache()
            val before = cache.project(initial.messages, "turn", initial.turnSettings)
            val after = cache.project(updated.messages, "turn", initial.turnSettings)
            assertSame(before.first(), after.first())
            assertEquals(nativeTimelineRows(updated.messages, "turn", initial.turnSettings), after)
            assertEquals(nativeTimelineRows(messages, "turn", initial.turnSettings),
                cache.project(messages, "turn", initial.turnSettings))
        }
        val cache = NativeTimelineCache()
        cache.project(initial.messages, "turn", initial.turnSettings)
        cache.project(updated.messages, "turn", initial.turnSettings)
        val failed = listOf(NativeTurnSettings("turn", status = "failed"))
        assertEquals(nativeTimelineRows(updated.messages, null, failed), cache.project(updated.messages, null, failed))
        assertEquals(nativeTimelineRows(updated.messages, "turn", initial.turnSettings),
            cache.project(updated.messages, "turn", initial.turnSettings))
    }

    @Test fun revisionsAndStructuralChangesMatchFullProjection() {
        val cache = NativeTimelineCache()
        val first = NativeMessage("a", "t", "userMessage", "question", JSONObject())
        val reply = NativeMessage("b", "t", "agentMessage", "answer", JSONObject())
        val revision = reply.copy(text = "answer extended")
        val cases = listOf(
            listOf(first, reply), listOf(first, revision),
            listOf(first, revision.copy(text = "replacement")),
            listOf(first.copy(id = "older"), first, revision),
            listOf(first, revision.copy(type = "reasoning")),
            emptyList(), listOf(reply.copy(turnId = "other"))
        )
        for (active in listOf("t", null)) for (messages in cases) {
            for (settings in listOf(emptyList(), listOf(NativeTurnSettings("t", status = "failed")))) {
                assertEquals(nativeTimelineRows(messages, active, settings), cache.project(messages, active, settings))
            }
        }
        cache.project(listOf(first, reply), "t", emptyList())
        val before = cache.project(listOf(first, reply), "t", emptyList())
        val after = cache.project(listOf(first, revision), "t", emptyList())
        assertSame(before.first(), after.first())
        assertEquals(nativeTimelineRows(listOf(first, revision), "t"), after)
    }
}
