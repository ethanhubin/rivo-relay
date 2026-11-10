package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun NativeRequestPanel(request: NativeRequest, enabled: Boolean, onRespond: (JSONObject) -> Unit) {
    if (request.method in setOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval", "execCommandApproval", "applyPatchApproval")) {
        ApprovalRequest(request, enabled, onRespond)
        return
    }
    if (request.method.endsWith("requestUserInput")) {
        UserInputRequest(request, enabled, onRespond)
        return
    }
    var expanded by remember(request.key) { mutableStateOf(true) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("native_request")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("toggle_native_request")) { Text(if (expanded) "Codex 需要你的回应 ▴" else "Codex 需要你的回应 ▾") }
            if (expanded) {
                val params = request.params
                when {
                    request.method == "item/permissions/requestApproval" -> PermissionRequest(params, enabled, onRespond)
                    request.method == "mcpServer/elicitation/request" -> McpRequest(params, enabled, onRespond)
                    else -> {
                        Text(request.method, style = MaterialTheme.typography.titleMedium)
                        Text("此原生请求暂不支持在手机处理，请回到 Desktop 完成。完成后会自动刷新。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun UserInputRequest(request: NativeRequest, enabled: Boolean, onRespond: (JSONObject) -> Unit) {
    val answers = remember(request.key) { mutableStateMapOf<String, String>() }
    var questionIndex by remember(request.key) { mutableIntStateOf(0) }
    val questions = request.params.objects("questions")
    QuestionDialog("Codex 需要你的回应", "native_request", "toggle_native_request") {
        NativeQuestionCard(questions, answers, questionIndex, enabled, { questionIndex = it }, { id, value -> answers[id] = value }) {
            val results = JSONObject()
            questions.forEach { q -> results.put(q.string("id"), JSONObject().put("answers", JSONArray().put(answers[q.string("id")]))) }
            onRespond(JSONObject().put("answers", results))
        }
        if (questions.isNotEmpty() && questions.none { it.optBoolean("isSecret") }) TextButton(onClick = {
            val results = JSONObject()
            questions.forEach { question -> results.put(question.string("id"), JSONObject().put("answers", JSONArray().put("请按合理假设继续。"))) }
            onRespond(JSONObject().put("answers", results))
        }, enabled = enabled, modifier = Modifier.testTag("answer_with_assumptions")) { Text("按合理假设继续") }
    }
}

@Composable
private fun ApprovalRequest(request: NativeRequest, enabled: Boolean, onRespond: (JSONObject) -> Unit) {
    var open by remember(request.key) { mutableStateOf(true) }
    var details by remember(request.key) { mutableStateOf(false) }
    var selected by remember(request.key) { mutableIntStateOf(-1) }
    val params = request.params
    val declared = params.optJSONArray("availableDecisions")
    val decisions: List<Any> = if (declared != null && declared.length() > 0)
        (0 until declared.length()).map { declared.get(it) }
    else if (request.method.contains('/')) listOf("decline", "accept") else listOf(legacyRejection(), "approved")
    val choices = decisions.filter { approvalLabel(it) != null }
    val index = if (selected >= 0) selected else choices.indexOfFirst { it == "accept" || it == "approved" }
    val choice = choices.getOrNull(index)
    val title = if (request.method.contains("fileChange") || request.method == "applyPatchApproval") "允许修改文件？" else "允许运行命令？"
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("native_request")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("等待审批", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { open = true }, modifier = Modifier.testTag("toggle_native_request")) { Text("查看请求") }
        }
    }
    if (open) Dialog(onDismissRequest = { open = false }) {
 dev.rivo.relay.ui.chat.ThemedTextContextMenu {

        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    params.string("reason").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    val detail = params.string("command").ifBlank { params.string("cwd") }.ifBlank { params.string("grantRoot") }
                    if (detail.isNotBlank()) {
                        TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) { Text(if (details) "收起详情 ▴" else "查看详情 ▾") }
                        if (details) Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.small) {
                            SelectionContainer { Text(detail, Modifier.fillMaxWidth().padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    choices.forEachIndexed { i, decision ->
                        val label = approvalLabel(decision).orEmpty()
                        val selectedRule = label.substringAfter('：', "")
                        OutlinedButton(onClick = { selected = i }, enabled = enabled,
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (index == i) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.fillMaxWidth().testTag("approval_choice_$i").semantics { this.selected = index == i }) {
                            Text((if (index == i) "✓  " else "") + label.substringBefore('：'))
                        }
                        if (index == i && selectedRule.isNotBlank()) {
                            Text("将记住以下规则：", style = MaterialTheme.typography.labelSmall)
                            SelectionContainer { Text(selectedRule, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                        }
                    }
                    if (choices.size != decisions.size) Text("更多选项请在电脑端处理", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { open = false }) { Text("稍后处理") }
                    Button(onClick = { choice?.let { onRespond(JSONObject().put("decision", it)) } }, enabled = enabled && choice != null,
                        modifier = Modifier.weight(1f).testTag("approve_request")) {
                        Text(when (choice) {
                            "accept", "approved" -> "允许本次"
                            "decline" -> "确认拒绝"
                            "cancel", "abort" -> "取消请求"
                            else -> "确认选择"
                        })
                    }
                }
            }
        }

}
}
}

internal fun approvalLabel(decision: Any): String? = when (decision) {
    "accept", "approved" -> "允许本次"
    "acceptForSession", "approved_for_session" -> "在此会话中允许"
    "decline" -> "拒绝"
    "cancel", "abort" -> "取消当前请求"
    is JSONObject -> when {
        decision.has("denied") -> "拒绝"
        decision.has("approved_execpolicy_amendment") -> decision.optJSONObject("approved_execpolicy_amendment")?.strings("proposed_execpolicy_amendment")?.takeIf { it.isNotEmpty() }?.let { "允许并记住命令规则：" + it.joinToString(" ") }
        decision.has("network_policy_amendment") -> decision.optJSONObject("network_policy_amendment")?.optJSONObject("network_policy_amendment")?.takeIf { it.string("action") in setOf("allow", "deny") && it.string("host").isNotBlank() }?.let {
            (if (it.string("action") == "allow") "允许访问并记住：" else "拒绝访问并记住：") + it.string("host")
        }
        decision.has("acceptWithExecpolicyAmendment") -> decision.optJSONObject("acceptWithExecpolicyAmendment")?.strings("execpolicy_amendment")?.takeIf { it.isNotEmpty() }?.let { "允许并记住命令规则：" + it.joinToString(" ") }
        decision.has("applyNetworkPolicyAmendment") -> decision.optJSONObject("applyNetworkPolicyAmendment")?.optJSONObject("network_policy_amendment")?.takeIf { it.string("action") in setOf("allow", "deny") && it.string("host").isNotBlank() }?.let {
            (if (it.string("action") == "allow") "允许访问并记住：" else "拒绝访问并记住：") + it.string("host")
        }
        else -> null
    }
    else -> null
}

internal fun legacyRejection(): JSONObject = JSONObject().put("denied", JSONObject().put("rejection", "用户拒绝此请求"))

private fun permissionPath(path: JSONObject): String = when (path.string("type")) {
    "path" -> path.string("path")
    "glob_pattern" -> path.string("pattern")
    "special" -> path.optJSONObject("value")?.let { value ->
        (when (value.string("kind")) { "root" -> "文件系统根目录"; "minimal" -> "系统基础目录"; "project_roots" -> "项目目录"; "tmpdir" -> "系统临时目录"; "slash_tmp" -> "/tmp"; else -> value.string("path") }) + value.string("subpath").takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
    }.orEmpty()
    else -> "未识别的路径范围"
}

@Composable
private fun PermissionRequest(params: JSONObject, enabled: Boolean, onRespond: (JSONObject) -> Unit) {
    val permissions = params.optJSONObject("permissions") ?: JSONObject()
    var scope by remember { mutableStateOf("turn") }
    Text("Codex 请求权限", style = MaterialTheme.typography.titleMedium)
    params.string("reason").takeIf { it.isNotBlank() }?.let { Text(it) }
    params.string("cwd").takeIf { it.isNotBlank() }?.let { Text("工作目录：$it", style = MaterialTheme.typography.bodySmall) }
    permissions.optJSONObject("network")?.let { if (it.optBoolean("enabled")) Text("• 网络访问") }
    permissions.optJSONObject("fileSystem")?.let { fs ->
        fs.strings("read").forEach { Text("• 读取：$it", style = MaterialTheme.typography.bodySmall) }
        fs.strings("write").forEach { Text("• 写入：$it", style = MaterialTheme.typography.bodySmall) }
        fs.objects("entries").forEach { entry -> Text("• " + when (entry.string("access")) { "write" -> "写入"; "read" -> "读取"; else -> "禁止" } + "：" + permissionPath(entry.optJSONObject("path") ?: JSONObject()), style = MaterialTheme.typography.bodySmall) }
    }
    Text("授权范围", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(scope == "turn", { scope = "turn" }, label = { Text("当前任务") }, enabled = enabled)
        FilterChip(scope == "session", { scope = "session" }, label = { Text("当前会话") }, enabled = enabled)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onRespond(JSONObject().put("permissions", JSONObject()).put("scope", "turn")) }, enabled = enabled, modifier = Modifier.weight(1f).testTag("deny_permissions")) { Text("拒绝") }
        Button(onClick = {
            val granted = JSONObject(); listOf("network", "fileSystem").forEach { key -> permissions.optJSONObject(key)?.let { granted.put(key, it) } }
            onRespond(JSONObject().put("permissions", granted).put("scope", scope))
        }, enabled = enabled, modifier = Modifier.weight(1f).testTag("grant_permissions")) { Text("允许请求") }
    }
}

@Composable
private fun McpRequest(params: JSONObject, enabled: Boolean, onRespond: (JSONObject) -> Unit) {
    val uriHandler = LocalUriHandler.current
    var error by remember { mutableStateOf<String?>(null) }
    fun respond(action: String, content: JSONObject? = null) = onRespond(JSONObject().put("action", action).put("content", content ?: JSONObject.NULL).put("_meta", JSONObject.NULL))
    Text(params.string("serverName"), style = MaterialTheme.typography.titleMedium)
    Text(params.string("message").ifBlank { params.string("description") })
    when (params.string("mode")) {
        "url" -> {
            val url = params.string("url")
            SelectionContainer { Text(url, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { runCatching { uriHandler.openUri(url) }.onFailure { error = "无法打开页面，请在 Desktop 处理此验证" } }, enabled = enabled && isHttpWebLink(url)) { Text("打开验证页面") }
            Button(onClick = { respond("accept") }, enabled = enabled) { Text("已完成，继续") }
        }
        "form", "openai/form", "openaiForm" -> {
            val schema = params.optJSONObject("requestedSchema")
            if (schema != null && supportedMcpForm(schema)) McpForm(schema, enabled) { respond("accept", it) }
            else Text("此表单包含手机暂不支持的字段，请回到 Desktop 完成。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "openai/userVerification" -> { Text(params.string("title"), style = MaterialTheme.typography.titleMedium); Text("此请求需要 Desktop 已注册的签名凭据。请在 Desktop 完成身份验证，手机会保留此请求等待结果。") }
        else -> Text("此原生交互暂不支持在手机处理，请回到 Desktop 完成。")
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { respond("decline") }, enabled = enabled) { Text("拒绝请求") }
        TextButton(onClick = { respond("cancel") }, enabled = enabled) { Text("取消") }
    }
}
