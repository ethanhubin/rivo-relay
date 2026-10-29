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
    @Test fun unknownNestedFormIsNotSubmittedAsJsonGuess() {
        assertFalse(supportedMcpForm(JSONObject("""{"type":"object","properties":{"nested":{"type":"object","properties":{}}}}""")))
        assertFalse(supportedMcpForm(JSONObject("""{"type":"object","properties":{"number":{"type":"integer","enum":[1,2]}}}""")))
        assertFalse(supportedMcpForm(JSONObject("""{"type":"object","properties":{"name":{"type":"string","pattern":"[a-z]+"}}}""")))
        assertFalse(supportedMcpForm(JSONObject("""{"type":"object","properties":{"bad":{"type":"string","enum":[1]}}}""")))
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
