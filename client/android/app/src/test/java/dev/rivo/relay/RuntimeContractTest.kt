package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeContractTest {
    @Test fun missingProtocolKeepsLegacyBehaviorButDeclaredUnknownOperationsFailClosed() {
        assertTrue(RuntimeCapabilities.from(null).allows("session.fork"))
        assertTrue(RuntimeCapabilities.from(JSONObject("""{"media":{}}""")).allows("session.send"))
        val caps = RuntimeCapabilities.from(JSONObject("""{"schemaVersion":1,"operations":{"session.send":{"supported":true,"available":true},"session.fork":{"supported":true,"available":false,"reasonCode":"busy"}}}"""))
        assertTrue(caps.allows("session.send"))
        assertFalse(RuntimeCapabilities.from(JSONObject("""{"schemaVersion":1}""")).allows("session.send"))
        assertFalse(caps.allows("session.fork"))
        assertTrue(caps.supports("session.fork"))
        assertEquals("busy", caps.operation("session.fork").reasonCode)
        assertFalse(caps.allows("files.upload"))
        assertFalse(RuntimeCapabilities.from(JSONObject("""{"schemaVersion":2,"operations":{}}""")).allows("session.send"))
        assertFalse(RuntimeCapabilities.from(JSONObject("""{"schemaVersion":1,"operations":{"session.send":{"supported":"true","available":true}}}""")).allows("session.send"))
    }

    @Test fun sessionDeclarationsCannotEscalateNodeCapabilitiesOrLeakBetweenSessions() {
        val node = RuntimeCapabilities.from(JSONObject("""{"schemaVersion":1,"operations":{"session.send":{"supported":true,"available":true},"files.upload":{"supported":false,"available":false}}}"""))
        val session = node.withOverrides(JSONObject("""{"schemaVersion":1,"operations":{"session.send":{"supported":true,"available":false,"reasonCode":"read_only"},"files.upload":{"supported":true,"available":true}}}"""))
        assertFalse(session.allows("session.send"))
        assertFalse(session.allows("files.upload"))
        assertTrue(node.allows("session.send"))
        assertTrue(node.withOverrides(null).allows("session.send"))
        assertFalse(node.withOverrides(JSONObject("""{"schemaVersion":2}""")).allows("session.send"))
        assertFalse(node.withOverrides(JSONObject("""{"schemaVersion":1,"operations":{}}""")).allows("session.send"))
    }

    @Test fun thirdProviderInputAcceptanceNeedsNoNativeTurnOrProviderName() {
        val receipt = JSONObject("""{"kind":"input-accepted","submissionId":"s","threadId":"t"}""")
        assertEquals(InputReceipt.Accepted("s", "t"), decodeInputReceipt(receipt, "s", "t"))
        assertNull(decodeInputReceipt(receipt, "other", "t"))
        assertNull(decodeInputReceipt(receipt, "s", "other"))
        receipt.put("executionId", "unexpected")
        assertNull(decodeInputReceipt(receipt, "s", "t"))
        receipt.remove("executionId")
        receipt.put("kind", "execution-started")
        assertNull(decodeInputReceipt(receipt, "s", "t"))
        receipt.put("executionId", "e")
        assertEquals(InputReceipt.Started("s", "t", "e"), decodeInputReceipt(receipt, "s", "t"))
        receipt.put("kind", "future-kind").put("turnId", "legacy")
        assertNull(decodeInputReceipt(receipt, "s", "t"))
    }

    @Test fun persistedLegacyReceiptsRemainReadableWithoutWeakeningNewProtocol() {
        assertEquals(InputReceipt.Started("s", "t", "turn"), decodeInputReceipt(JSONObject("""{"threadId":"t","turnId":"turn"}"""), "s", "t"))
        val old = JSONObject("""{"runtime":"dsh","acceptance":"input","requestId":"s","threadId":"t"}""")
        assertEquals(InputReceipt.Accepted("s", "t"), decodeInputReceipt(old, "s", "t"))
        assertNull(decodeInputReceipt(old, "different", "t"))
        old.put("kind", "input-accepted") // New protocol must not fall back without its submissionId.
        assertNull(decodeInputReceipt(old, "s", "t"))
    }
    @Test fun neutralReceiptsRejectConflictingLegacyIdentityAliases() {
        val accepted = """{"kind":"input-accepted","submissionId":"s","threadId":"t"}"""
        for (key in listOf("turnId", "turn_id", "executionId", "execution_id")) {
            assertNull(decodeInputReceipt(JSONObject(accepted).put(key, "e"), "s", "t"))
        }
        val started = """{"kind":"execution-started","submissionId":"s","threadId":"t","executionId":"e"}"""
        for (key in listOf("turnId", "turn_id", "execution_id")) {
            assertNull(decodeInputReceipt(JSONObject(started).put(key, "wrong"), "s", "t"))
            assertNotNull(decodeInputReceipt(JSONObject(started).put(key, "e"), "s", "t"))
        }
        for (body in listOf(accepted, started)) {
            for ((key, value) in listOf("submission_id" to "s", "thread_id" to "t")) {
                assertNull(decodeInputReceipt(JSONObject(body).put(key, "wrong"), "s", "t"))
                assertNotNull(decodeInputReceipt(JSONObject(body).put(key, value), "s", "t"))
            }
        }
    }

}
