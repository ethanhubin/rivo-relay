package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeContractTest {
    @Test fun createEntryStaysVisibleWhileLoadingOrTemporarilyUnavailable() {
        val pending = RuntimeCapabilities.Pending
        assertTrue(pending.shows("session.create"))
        assertFalse(pending.allows("session.create"))
        val available = JSONObject("""{"schemaVersion":1,"operations":{"session.create":{"supported":true,"available":true}}}""")
        assertFalse(pending.withOverrides(available).allows("session.create"))
        val busy = RuntimeCapabilities.from(JSONObject("""{"schemaVersion":1,"operations":{"session.create":{"supported":true,"available":false}}}"""))
        assertTrue(busy.shows("session.create"))
        assertFalse(busy.allows("session.create"))
        assertTrue(RuntimeCapabilities.from(available).allows("session.create"))
        assertFalse(RuntimeCapabilities.from(JSONObject("""{"schemaVersion":1,"operations":{}}""")).shows("session.create"))
        assertFalse(RuntimeCapabilities.Unavailable.shows("session.create"))
        assertTrue(RuntimeCapabilities.from(null).allows("session.create"))
    }

    @Test fun attachmentLimitsAreConservativeAcrossNodeAndSession() {
        fun declaration(limit: Any? = null) = JSONObject().put("schemaVersion", 1).put("operations", JSONObject())
            .apply { if (limit != null) put("limits", JSONObject().put("maxAttachmentBytes", limit)) }
        val twentyMiB = 20L * 1024 * 1024
        val node = RuntimeCapabilities.from(declaration(twentyMiB))
        assertEquals(twentyMiB, node.maxAttachmentBytes)
        assertEquals(1024L * 1024 * 1024, RuntimeCapabilities.Legacy.maxAttachmentBytes)
        assertEquals(twentyMiB, node.withOverrides(declaration()).maxAttachmentBytes)
        assertEquals(twentyMiB, node.withOverrides(declaration(40L * 1024 * 1024)).maxAttachmentBytes)
        assertEquals(1024L, node.withOverrides(declaration(1024)).maxAttachmentBytes)
        for (invalid in listOf(0, -1, "20971520", 1.5, JSONObject.NULL))
            assertEquals(0L, RuntimeCapabilities.from(declaration(invalid)).maxAttachmentBytes)
        assertEquals(0L, RuntimeCapabilities.from(declaration().put("limits", "invalid")).maxAttachmentBytes)
    }

    @Test fun optionalSettingsCapabilitiesFallbackOnlyWhenAbsent() {
        val legacy = RuntimeCapabilities.from(JSONObject("""{"schemaVersion":1,"operations":{"session.models":{"supported":true,"available":true},"session.settings":{"supported":true,"available":true}}}"""))
        assertTrue(legacy.allows("session.service_tier"))
        assertTrue(legacy.allows("session.permissions"))
        assertTrue(RuntimeCapabilities.Legacy.allows("session.permissions"))
        val declaration = JSONObject("""{"schemaVersion":1,"operations":{"session.models":{"supported":true,"available":true},"session.settings":{"supported":true,"available":true},"session.service_tier":{"supported":false,"available":false},"session.permissions":{"supported":true,"available":false}}}""")
        val restricted = legacy.withOverrides(declaration)
        assertFalse(restricted.supports("session.service_tier"))
        assertTrue(restricted.supports("session.permissions"))
        assertFalse(restricted.allows("session.permissions"))
        assertFalse(RuntimeCapabilities.from(declaration).withOverrides(JSONObject("""{"schemaVersion":1,"operations":{"session.models":{"supported":true,"available":true},"session.settings":{"supported":true,"available":true}}}""")).allows("session.service_tier"))
        assertFalse(RuntimeCapabilities.Unavailable.allows("session.permissions"))
        val draft = JSONObject("""{"model":"example","effort":"high","serviceTier":"priority","sandbox":"workspace-write","approvalPolicy":"never","goal":{"objective":"example"}}""")
        val filtered = restricted.filterSettings(draft)
        assertFalse(filtered.has("serviceTier"))
        assertFalse(filtered.has("sandbox"))
        assertFalse(filtered.has("approvalPolicy"))
        assertEquals("example", filtered.getString("model"))
        assertTrue(filtered.has("goal"))
        assertTrue(draft.has("serviceTier"))
        assertEquals("priority", legacy.filterSettings(draft).getString("serviceTier"))
    }

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
