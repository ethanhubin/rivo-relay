package dev.rivo.relay

import dev.rivo.relay.ui.chat.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSettingsPresentationTest {
    @Test fun missingSessionSettingsAreUnknownRatherThanCustomOrInherited() {
        for (settings in listOf(null, JSONObject(), JSONObject("""{"sandbox":null,"approvalPolicy":null}"""))) {
            assertEquals("尚未读取文件访问权限", sandboxSettingLabel(settings, false, null))
            assertEquals("尚未读取审批策略", approvalSettingLabel(settings, false, null))
            assertEquals("继承当前模型（尚未读取）", inheritedSettingLabel(settings, "model", "模型", false))
        }
        assertEquals("文件访问权限：尚未读取\n原生审批：尚未读取", nativePermissionSummary(JSONObject()))
    }

    @Test fun newSessionsExplicitlyInheritNodeDefaults() {
        assertEquals("沿用节点默认", sandboxSettingLabel(null, true, "inherit"))
        assertEquals("沿用节点默认", approvalSettingLabel(null, true, null))
        assertEquals("沿用节点默认模型", inheritedSettingLabel(null, "model", "模型", true))
        assertEquals("工作区读写", sandboxSettingLabel(null, true, "workspace-write"))
        assertEquals("不要求审批", approvalSettingLabel(null, true, "never"))
    }

    @Test fun knownAndCustomPoliciesRemainDistinct() {
        val known = JSONObject("""{"sandboxPolicy":{"type":"workspaceWrite"},"approvalPolicy":"on-request","model":"native-model"}""")
        assertEquals("工作区读写", sandboxSettingLabel(known, false, null))
        assertEquals("按需审批", approvalSettingLabel(known, false, null))
        assertEquals("继承当前模型 · native-model", inheritedSettingLabel(known, "model", "模型", false))
        val custom = JSONObject("""{"sandbox":{"type":"externalSandbox"},"approvalPolicy":{"granular":{"mcp_elicitations":true}}}""")
        assertEquals("当前自定义权限", sandboxSettingLabel(custom, false, null))
        assertEquals("当前自定义审批", approvalSettingLabel(custom, false, null))
        assertEquals("外部沙盒\n原生审批：细粒度规则", nativePermissionSummary(custom))
    }

    @Test fun releaseAvailabilityRemainsCompatibleWithOlderNodes() {
        assertNull(releaseUnavailableReason(null))
        assertNull(releaseUnavailableReason(JSONObject()))
        assertNull(releaseUnavailableReason(JSONObject().put("releaseAvailable", true)))
        assertEquals("首条消息尚未持久化", releaseUnavailableReason(JSONObject()
            .put("releaseAvailable", false).put("releaseUnavailableReason", "首条消息尚未持久化")))
        assertTrue(releaseUnavailableReason(JSONObject().put("releaseAvailable", false))!!.contains("保留"))
    }

    @Test fun unknownAndCustomSettingsCannotBeSavedAsGuessedPresets() {
        assertNotNull(nativeDefaultsUnavailableReason(JSONObject(), false, null, null, null))
        val known = JSONObject("""{"sandbox":{"type":"workspaceWrite"},"approvalPolicy":"on-request","reasoningEffort":"medium"}""")
        assertNotNull(nativeDefaultsUnavailableReason(known, false, "model", "medium", null))
        known.put("serviceTier", JSONObject.NULL)
        assertNull(nativeDefaultsUnavailableReason(known, false, "model", "medium", null))
        assertNull(nativeDefaultsUnavailableReason(known, false, "model", "medium", "priority"))
        known.getJSONObject("sandbox").put("networkAccess", true)
        assertTrue(nativeDefaultsUnavailableReason(known, false, "model", "medium", "priority")!!.contains("自定义路径或网络"))
        known.getJSONObject("sandbox").remove("networkAccess")
        known.put("approvalPolicy", JSONObject("""{"granular":{"mcp_elicitations":false}}"""))
        assertTrue(nativeDefaultsUnavailableReason(known, false, "model", "medium", "priority")!!.contains("自定义审批"))
        known.put("approvalPolicy", "on-request").put("sandbox", JSONObject().put("type", "externalSandbox"))
        assertTrue(nativeDefaultsUnavailableReason(known, false, "model", "medium", "priority")!!.contains("自定义文件权限"))
        assertNull(nativeDefaultsUnavailableReason(null, true, null, null, null))
    }
}
