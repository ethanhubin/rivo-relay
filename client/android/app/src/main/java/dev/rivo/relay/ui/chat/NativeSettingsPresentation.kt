package dev.rivo.relay.ui.chat

import dev.rivo.relay.data.string
import org.json.JSONObject

internal val sandboxSettingOptions = listOf("read-only" to "只读", "workspace-write" to "工作区读写", "danger-full-access" to "完全访问")
internal val approvalSettingOptions = listOf("on-request" to "按需审批", "untrusted" to "不受信任操作需审批", "never" to "不要求审批", "on-failure" to "失败时审批（旧版）")

internal fun nativeSandbox(settings: JSONObject?): JSONObject? =
    settings?.optJSONObject("sandbox") ?: settings?.optJSONObject("sandboxPolicy")

internal fun nativeSandboxChoice(settings: JSONObject?): String? = when (nativeSandbox(settings)?.string("type")) {
    "readOnly", "read-only" -> "read-only"
    "workspaceWrite", "workspace-write" -> "workspace-write"
    "dangerFullAccess", "danger-full-access" -> "danger-full-access"
    else -> null
}

internal fun sandboxSettingLabel(settings: JSONObject?, newSession: Boolean, choice: String?): String {
    val selected = if (newSession) choice?.takeUnless { it == "inherit" } else nativeSandboxChoice(settings)
    return sandboxSettingOptions.firstOrNull { it.first == selected }?.second
        ?: if (newSession) "沿用节点默认" else if (nativeSandbox(settings)?.string("type").isNullOrBlank())
            "尚未读取文件访问权限" else "当前自定义权限"
}

internal fun approvalSettingLabel(settings: JSONObject?, newSession: Boolean, choice: String?): String {
    val policy = if (newSession) choice?.takeUnless { it == "inherit" } else settings?.opt("approvalPolicy")
    return approvalSettingOptions.firstOrNull { it.first == policy }?.second
        ?: when {
            newSession -> "沿用节点默认"
            policy is JSONObject && policy.length() > 0 -> "当前自定义审批"
            policy is String && policy.isNotBlank() -> "当前自定义审批"
            else -> "尚未读取审批策略"
        }
}

internal fun inheritedSettingLabel(settings: JSONObject?, key: String, title: String, newSession: Boolean): String {
    val known = settings?.string(key)?.takeIf(String::isNotBlank)
    return if (known != null) "继承当前$title · $known"
        else if (newSession) "沿用节点默认$title" else "继承当前$title（尚未读取）"
}

internal fun releaseUnavailableReason(runtime: JSONObject?): String? =
    if (runtime?.has("releaseAvailable") == true && !runtime.optBoolean("releaseAvailable", true))
        runtime.string("releaseUnavailableReason").ifBlank { "暂不能释放，当前会话和草稿已保留。" }
    else null

/** Existing settings may be copied only when every stored preset has a known representation. */
internal fun nativeDefaultsUnavailableReason(settings: JSONObject?, newSession: Boolean,
    effectiveModel: String?, effectiveEffort: String?, serviceTier: String?): String? {
    if (newSession) return null // Null choices deliberately mean inherit node defaults.
    if (effectiveModel.isNullOrBlank() || (effectiveEffort.isNullOrBlank() && settings?.has("reasoningEffort") != true))
        return "模型设置尚未读取完整，暂不能设为新会话默认。"
    if (nativeSandbox(settings)?.string("type").isNullOrBlank())
        return "文件访问权限尚未读取，暂不能设为新会话默认。"
    if (nativeSandboxChoice(settings) == null)
        return "当前自定义文件权限无法保存为权限预设；请先明确选择文件访问权限。"
    val sandbox = nativeSandbox(settings)!!
    val roots = sandbox.optJSONArray("writableRoots")
    val cwd = settings?.string("cwd").orEmpty()
    val knownFields = setOf("type", "networkAccess", "writableRoots", "excludeTmpdirEnvVar", "excludeSlashTmp")
    if (sandbox.keys().asSequence().any { it !in knownFields } || sandbox.optBoolean("networkAccess") ||
        sandbox.optBoolean("excludeTmpdirEnvVar") || sandbox.optBoolean("excludeSlashTmp") ||
        roots != null && (0 until roots.length()).any { cwd.isBlank() || roots.optString(it) != cwd })
        return "当前权限包含自定义路径或网络限制，不能简化为新会话默认；可在新会话中明确选择权限预设。"
    val approval = settings?.opt("approvalPolicy")
    if (approval == null || approval == JSONObject.NULL || approval is String && approval.isBlank())
        return "审批策略尚未读取，暂不能设为新会话默认。"
    if (approvalSettingOptions.none { it.first == approval })
        return "当前自定义审批无法保存为审批预设；请先明确选择审批策略。"
    val inheritedTier = settings?.has("serviceTier") == true && settings.isNull("serviceTier")
    if (serviceTier.isNullOrBlank() && !inheritedTier) return "快速模式尚未读取，暂不能设为新会话默认。"
    if (!serviceTier.isNullOrBlank() && serviceTier !in setOf("default", "priority")) return "当前服务等级无法保存为新会话默认。"
    return null
}
