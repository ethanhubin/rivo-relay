package dev.rivo.relay

import dev.rivo.relay.ui.chat.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeFormsTest {
    @Test fun mcpTypedFormValidatesAndReturnsNativeTypes() {
        val schema = JSONObject("""{"type":"object","required":["name","count","confirm","choices"],"properties":{"name":{"type":"string","minLength":2},"count":{"type":"integer","minimum":1,"maximum":4},"confirm":{"type":"boolean"},"choices":{"type":"array","minItems":1,"items":{"anyOf":[{"const":"a","title":"Alpha"},{"const":"b","title":"Beta"}]}}}}""")
        val response = mcpFormContent(schema, mapOf("name" to "Rivo", "count" to "2", "confirm" to "false"), mapOf("choices" to setOf("b")))
        assertEquals("Rivo", response.getString("name")); assertEquals(2, response.getInt("count")); assertFalse(response.getBoolean("confirm"))
        assertEquals("b", response.getJSONArray("choices").getString(0))
        assertThrows(IllegalArgumentException::class.java) { mcpFormContent(schema, mapOf("name" to "Rivo", "count" to "7", "confirm" to "true"), mapOf("choices" to setOf("a"))) }
    }
    @Test fun unknownConstraintsAreNotSubmittedAsJsonGuess() {
        assertFalse(supportedMcpForm(JSONObject("""{"type":"object","properties":{"nested":{"${'$'}ref":"#/${'$'}defs/SomeObject"}}}""")))
        assertFalse(supportedMcpForm(JSONObject("""{"type":"object","properties":{"number":{"type":"integer","enum":[1,2]}}}""")))
        assertFalse(supportedMcpForm(JSONObject("""{"type":"object","properties":{"name":{"type":"string","pattern":"[a-z]+"}}}""")))
        assertFalse(supportedMcpForm(JSONObject("""{"type":"object","properties":{"bad":{"type":"string","enum":[1]}}}""")))
    }
    @Test fun nestedObjectsAndObjectArraysPreserveTypesAndValidateChildren() {
        val schema = JSONObject("""{"type":"object","required":["profile","rows"],"properties":{"profile":{"type":"object","required":["email"],"properties":{"email":{"type":"string","format":"email"},"active":{"type":"boolean"}}},"rows":{"type":"array","minItems":1,"items":{"type":"object","required":["count"],"properties":{"count":{"type":"integer","minimum":1}}}}}}""")
        assertTrue(supportedMcpForm(schema))
        val values = mapOf("profile/email" to "a@example.com", "profile/active" to "false", "rows/@count" to "1", "rows/0/count" to "2")
        val result = mcpFormContent(schema, values, emptyMap())
        assertFalse(result.getJSONObject("profile").getBoolean("active"))
        assertEquals(2, result.getJSONArray("rows").getJSONObject(0).getInt("count"))
        assertThrows(IllegalArgumentException::class.java) { mcpFormContent(schema, values + ("rows/0/count" to "0"), emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { mcpFormContent(schema, values - "profile/email", emptyMap()) }
    }
    @Test fun optionalNestedObjectAndEscapedKeysAreUnambiguous() {
        val schema = JSONObject("""{"type":"object","properties":{"a/b":{"type":"string"},"a":{"type":"object","properties":{"b":{"type":"integer"}}}}}""")
        val omitted = mcpFormContent(schema, mapOf("a~1b" to "separate"), emptyMap())
        assertEquals("separate", omitted.getString("a/b")); assertFalse(omitted.has("a"))
        val filled = mcpFormContent(schema, mapOf("a~1b" to "separate", "a/b" to "3"), emptyMap())
        assertEquals(3, filled.getJSONObject("a").getInt("b"))
    }
    @Test fun uniqueNumericArraysCompareValuesAndNumbersDoNotOverflow() {
        val schema = JSONObject("""{"type":"object","properties":{"rows":{"type":"array","uniqueItems":true,"items":{"type":"number"}},"big":{"type":"integer"}}}""")
        assertThrows(IllegalArgumentException::class.java) { mcpFormContent(schema, mapOf("rows/@count" to "2", "rows/0" to "1.0", "rows/1" to "1.00"), emptyMap()) }
        assertEquals("9223372036854775808", mcpFormContent(schema, mapOf("big" to "9223372036854775808"), emptyMap()).get("big").toString())
        assertThrows(IllegalArgumentException::class.java) { mcpFormContent(schema, mapOf("big" to "1e99999999"), emptyMap()) }
    }
    @Test fun nativeDecisionAmendmentsHaveExplicitActionLabels() {
        val decision = JSONObject("""{"applyNetworkPolicyAmendment":{"network_policy_amendment":{"host":"example.com","action":"allow"}}}""")
        assertEquals("允许访问并记住：example.com", approvalLabel(decision))
        assertEquals("在此会话中允许", approvalLabel("acceptForSession"))
        assertNull(approvalLabel("not-a-native-choice"))
    }
    @Test fun legacyRejectionUsesStructuredReviewDecision() {
        assertEquals("用户拒绝此请求", legacyRejection().getJSONObject("denied").getString("rejection"))
        assertEquals("拒绝", approvalLabel(legacyRejection()))
    }
    @Test fun settingsSummaryNeverExposesInstructions() {
        val settings = JSONObject("""{"approvalPolicy":"never","sandbox":{"type":"workspaceWrite"},"developer_instructions":"hidden-instructions"}""")
        assertEquals("工作区读写\n原生审批：不要求", nativePermissionSummary(settings))
    }
}
