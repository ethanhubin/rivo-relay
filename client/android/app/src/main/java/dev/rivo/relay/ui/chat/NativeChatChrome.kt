package dev.rivo.relay.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.codex.control
import dev.rivo.relay.codex.displayControl
import dev.rivo.relay.codex.showRelease
import dev.rivo.relay.codex.showTakeover
import dev.rivo.relay.codex.takeoverControl
import dev.rivo.relay.codex.ControlState
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.common.CodexQuotaProgressBar
import org.json.JSONObject

@Composable
internal fun NativeWriterStatus(state: SessionState, nodeName: String, busy: Boolean, onAction: ((String) -> Unit)?, blockedReason: String? = null, onRefresh: (() -> Unit)? = null) {
    val snapshot = state.snapshot
    var runtimeDetails by remember(state.id) { mutableStateOf(false) }
    val runtime = snapshot?.nativeRuntime
    val trusted = !state.stale && !state.loading && snapshot != null
    val control = state.displayControl()
    val confirmedNow = trusted && state.control().state != ControlState.UNKNOWN
    val takeover = state.takeoverControl(blockedReason ?: if (busy) "有操作正在处理或等待核对，完成后可接管" else if (onAction == null) "当前页面暂不支持接管" else null)
    val external = control.state == ControlState.EXTERNAL
    val releaseReason = releaseUnavailableReason(runtime)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("writer_status"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).testTag("writer_status_details").clickable { runtimeDetails = true }) {
            Text("$nodeName · " + when (control.state) {
                ControlState.WRITABLE -> "可操作"
                ControlState.EXTERNAL -> "外部 Codex 占用"
                ControlState.RELEASE_PENDING -> "释放待确认"
                ControlState.RELEASED -> "已释放权限"
                ControlState.WORKSPACE_UNAVAILABLE -> "工作目录不可用"
                ControlState.UNVERIFIED -> "操作权限暂无法核验"
                ControlState.UNKNOWN -> "权限同步中"
            } + if (!confirmedNow && control.state != ControlState.UNKNOWN) " · 待确认" else "",
                modifier = if (external) Modifier.testTag("external_writer_notice") else Modifier,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (control.canOperate || control.state == ControlState.UNKNOWN) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
        if (state.showTakeover()) TextButton(onClick = { onAction?.invoke("takeover") }, enabled = takeover.enabled,
            modifier = Modifier.testTag("ownership_takeover")) { Text("强制接管", maxLines = 1) }
        if (state.showRelease() && onAction != null) {
            TextButton(onClick = { onAction("release") }, enabled = !busy && blockedReason == null && releaseReason == null,
                modifier = Modifier.testTag("writer_action")) {
                Text(if (busy) "处理中…" else "释放权限", maxLines = 1)
            }
        }
    }
    if (runtimeDetails) {
        val details = buildList {
            addAll(listOfNotNull(state.controlNotice, blockedReason,
                releaseReason.takeIf { trusted }, control.reason.takeIf { trusted },
                takeover.reason.takeIf { state.showTakeover() }))
            add(when {
                !trusted -> "连接信息待刷新"
                runtime == null -> "运行时连接信息尚未读取"
                runtime.optBoolean("desktopShared") -> "当前会话连接到 Desktop，共享原生运行时"
                else -> "当前会话连接到原生 app-server"
            })
            if (trusted) {
                runtime?.optJSONObject("bridge")?.let { bridge ->
                    add("Desktop 桥接：" + when { bridge.optBoolean("writerReady") -> "可用"; bridge.optBoolean("available") -> "连接中"; else -> "未连接" })
                    if (!bridge.optBoolean("writerReady")) add(bridge.string("error"))
                }
                if (state.showTakeover() && runtime?.optBoolean("handoffAvailable", true) == false)
                    add(runtime.string("handoffUnavailableReason"))
            }
        }.map(String::trim).filter(String::isNotBlank).distinct()
        AlertDialog(onDismissRequest = { runtimeDetails = false }, title = { Text("Codex 连接") },
            text = { Column(Modifier.testTag("writer_runtime_details").verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } }, confirmButton = { TextButton({ runtimeDetails = false }) { Text("关闭") } })
    }

}

/** QiGuang SessionScreens.Header: 64dp title/status row, overflow actions and a bottom quota line. */
@Composable
internal fun NativeChatHeader(state: SessionState, usage: CodexUsage?, onBack: () -> Unit, onSettings: () -> Unit,
    onRefresh: () -> Unit, onReview: () -> Unit, onStop: () -> Unit, canStop: Boolean,
    pinned: Boolean, canManage: Boolean, onPin: ((Boolean) -> Unit)?, onArchive: (() -> Unit)?) {
    val snapshot = state.snapshot
    val context = LocalContext.current
    var menu by remember(state.id) { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }
    val activeSettings = snapshot?.let { current -> current.turnSettings.firstOrNull { it.turnId == current.activeTurnId } }
    val model = activeSettings?.model ?: snapshot?.settings?.string("model").orEmpty()
    val effort = activeSettings?.effort ?: snapshot?.settings?.string("reasoningEffort").orEmpty()
    val configuration = listOf(model, effort).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Codex" }
    val status = when {
        state.loading -> "同步中"
        state.stale -> if (snapshot != null) "离线缓存" else "连接中断"
        snapshot?.requests?.isNotEmpty() == true -> "等待回应"
        snapshot?.isOngoing == true -> "执行中"
        else -> null
    }
    Box(Modifier.fillMaxWidth().height(64.dp).testTag("conversation_header")) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.size(40.dp).testTag("back_sessions").semantics { contentDescription = "返回会话列表" },
                contentPadding = PaddingValues(0.dp)) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                SelectionContainer { Text(snapshot?.name ?: if (state.readError != null && !state.loading) "会话读取失败" else "正在读取会话…",
                    Modifier.testTag("conversation_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(Modifier.fillMaxWidth().clickable(onClick = onSettings).testTag("conversation_status"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).background(when { state.stale && !state.loading -> MaterialTheme.colorScheme.error
                        state.loading -> MaterialTheme.colorScheme.outline; else -> MaterialTheme.colorScheme.primary }, CircleShape))
                    Text(listOfNotNull(configuration, status).joinToString(" · "), Modifier.weight(1f, fill = false).testTag("conversation_configuration"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    contextPercent(snapshot?.tokenUsage)?.let { Text("· 上下文 $it%", Modifier.testTag("context_usage"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                }
            }
            Box {
                TextButton(onClick = { menu = true }, modifier = Modifier.size(40.dp).testTag("conversation_more_button")
                    .semantics { contentDescription = "更多" }, contentPadding = PaddingValues(0.dp)) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("改动与代码审查") }, onClick = { menu = false; onReview() }, enabled = snapshot != null,
                        modifier = Modifier.testTag("open_codex_review"))
                    DropdownMenuItem(text = { Text("刷新会话") }, onClick = { menu = false; onRefresh() }, modifier = Modifier.testTag("refresh_conversation"))
                    if (snapshot?.activeTurnId?.isNotBlank() == true) DropdownMenuItem(text = { Text("停止当前任务") },
                        onClick = { menu = false; onStop() }, enabled = canStop, modifier = Modifier.testTag("header_stop_turn"))
                    if (onPin != null) DropdownMenuItem(text = { Text(if (pinned) "取消置顶" else "置顶") },
                        onClick = { menu = false; onPin(!pinned) }, enabled = canManage, modifier = Modifier.testTag("set_session_pinned"))
                    if (onArchive != null) DropdownMenuItem(text = { Text("归档") }, onClick = { menu = false; onArchive() },
                        enabled = canManage && snapshot != null && snapshot.activeTurnId.isBlank() && snapshot.requests.isEmpty() && snapshot.queue.isEmpty(),
                        modifier = Modifier.testTag("archive_session"))
                    if (usage != null) DropdownMenuItem(text = { Text("Codex 用量") }, onClick = { menu = false; usageOpen = true }, modifier = Modifier.testTag("chat_usage_details"))
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("复制会话 ID") }, enabled = state.id != null, modifier = Modifier.testTag("copy_session_id"),
                        onClick = { menu = false; state.id?.let { id -> context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Codex 会话 ID", id)); Toast.makeText(context, "已复制会话 ID", Toast.LENGTH_SHORT).show() } })
                }
            }
        }
        usage?.weeklyWindow?.let { CodexQuotaProgressBar(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).testTag("codex_subscription_usage")) }
    }
    if (usageOpen) AlertDialog(onDismissRequest = { usageOpen = false }, title = { Text("Codex 用量") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            usage?.limits.orEmpty().forEach { limit -> Text(limit.name, style = MaterialTheme.typography.titleSmall)
                listOfNotNull(limit.primary, limit.secondary).forEach { window ->
                    Text("${usageWindowLabel(window.windowMinutes)}剩余 ${window.remainingPercent}%")
                    LinearProgressIndicator(progress = { window.remainingPercent / 100f }, modifier = Modifier.fillMaxWidth())
                } }
        }
    }, confirmButton = { TextButton(onClick = { usageOpen = false }) { Text("完成") } })
}

/** The native node supplies model capabilities; draft selections affect only the next submitted task. */
@Composable
internal fun NativeComposerSettings(settings: JSONObject?, models: List<JSONObject>, selectedModel: String?, selectedEffort: String?,
    selectedMode: String?, enabled: Boolean, onModel: (String?) -> Unit, onEffort: (String?) -> Unit, onInheritMode: () -> Unit,
    serviceTier: String? = null, onServiceTier: (String) -> Unit = {}, savesToSession: Boolean = false,
    sandboxChoice: String? = null, approvalChoice: String? = null, newSession: Boolean = false, permissionsEditable: Boolean = true,
    onSandbox: (String?) -> Unit = {}, onApproval: (String?) -> Unit = {},
    defaultSettings: JSONObject? = null, onSaveDefaults: ((JSONObject) -> Unit)? = null, catalogNotice: String? = null) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (savesToSession) "会话设置" else "下一项任务", style = MaterialTheme.typography.titleSmall)
        val modelOptions = models.map { model ->
            val id = model.string("model").ifBlank { model.string("id") }
            id to model.string("displayName").ifBlank { id }
        }
        val inheritModel = inheritedSettingLabel(settings, "model", "模型", newSession)
        val inheritEffort = inheritedSettingLabel(settings, "reasoningEffort", "思考强度", newSession)
        SettingsSelector("模型", selectedModel ?: inheritModel, listOf(null to inheritModel) + modelOptions,
            enabled, "composer_model", onModel)
        val effectiveModel = selectedModel ?: settings?.string("model")
        val effectiveEffort = selectedEffort ?: settings?.string("reasoningEffort")
        val capability = models.firstOrNull { it.string("model").ifBlank { it.string("id") } == effectiveModel }
        val efforts = capability?.objects("supportedReasoningEfforts").orEmpty().map { it.string("reasoningEffort") }.filter(String::isNotBlank)
        if (efforts.isNotEmpty()) SettingsSelector("思考强度", selectedEffort ?: inheritEffort, listOf(null to inheritEffort) + efforts.map { it to it },
            enabled, "composer_effort", onEffort)
        if (efforts.isEmpty()) Text(selectedEffort?.let { "思考强度 · $it" } ?: inheritEffort,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("composer_effort_status"))
        if (models.isEmpty()) Text("暂未取得可用模型，可继续沿用当前设置。", style = MaterialTheme.typography.labelSmall)
        catalogNotice?.let { Text(it, Modifier.testTag("model_catalog_notice"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("快速模式", style = MaterialTheme.typography.bodyMedium)
                Text("提升响应速度，会更快消耗额度", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = serviceTier == "priority", onCheckedChange = { onServiceTier(if (it) "priority" else "default") }, enabled = enabled,
                modifier = Modifier.testTag("codex_fast_mode_switch"))
        }
        val sandboxOptions = sandboxSettingOptions
        if (serviceTier.isNullOrBlank()) Text(
            if (newSession || settings?.has("serviceTier") == true && settings.isNull("serviceTier")) "服务等级沿用原生默认"
            else "服务等级尚未读取；切换开关后将明确设置快速模式。",
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("service_tier_status"))
        val approvalOptions = approvalSettingOptions
        val currentSandbox = nativeSandboxChoice(settings)
        val effectiveSandbox = if (newSession) sandboxChoice?.takeUnless { it == "inherit" } else currentSandbox
        val effectiveApproval = if (newSession) approvalChoice?.takeUnless { it == "inherit" } else (settings?.opt("approvalPolicy") as? String)
        val inPlan = (selectedMode ?: settings?.optJSONObject("collaborationMode")?.string("mode")) == "plan"
        if (newSession || savesToSession) {
            SettingsSelector("文件访问权限", sandboxSettingLabel(settings, newSession, sandboxChoice),
                (if (newSession) listOf(null to "沿用节点默认") else emptyList()) + sandboxOptions,
                enabled && permissionsEditable && (newSession || !inPlan), "composer_sandbox", onSandbox)
            SettingsSelector("审批策略", approvalSettingLabel(settings, newSession, approvalChoice),
                (if (newSession) listOf(null to "沿用节点默认") else emptyList()) + approvalOptions,
                enabled && permissionsEditable, "composer_approval", onApproval)
            if (!permissionsEditable) Text("会话已创建，发送完成后可在会话设置中修改权限。", style = MaterialTheme.typography.labelSmall)
            if (inPlan) Text("讨论模式使用只读权限；退出后恢复所选权限。", style = MaterialTheme.typography.labelSmall)
        }
        if (onSaveDefaults != null) {
            val unavailable = nativeDefaultsUnavailableReason(settings, newSession, effectiveModel, effectiveEffort, serviceTier)
            val tier = serviceTier?.takeIf(String::isNotBlank)
            val defaults = JSONObject().put("model", effectiveModel?.takeIf(String::isNotBlank)).put("effort", effectiveEffort?.takeIf(String::isNotBlank)).put("serviceTier", tier)
                .put("sandbox", effectiveSandbox).put("approvalPolicy", effectiveApproval)
            val isDefault = listOf("model", "effort", "serviceTier", "sandbox", "approvalPolicy").all { defaultSettings?.string(it).orEmpty() == defaults.string(it) }
            TextButton(onClick = { onSaveDefaults(defaults) },
                enabled = enabled && unavailable == null && !isDefault, modifier = Modifier.testTag("codex_set_default")) { Text(if (isDefault && unavailable == null) "已是默认" else "设为新会话默认") }
            unavailable?.let { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("codex_default_unavailable")) }
            Text("默认设置仅用于此节点的新会话。", style = MaterialTheme.typography.labelSmall)
        }
        if (selectedMode != null) TextButton(onClick = onInheritMode, enabled = enabled, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("inherit_task_mode")) { Text("恢复继承会话模式") }
        Text(if (savesToSession) "选择会保存到当前会话；具体生效轮次由 Codex 决定。" else "选择用于下一项任务；当前执行中的任务保持原设置。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        settings?.let {
            HorizontalDivider()
            Text(nativePermissionSummary(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            it.string("cwd").takeIf(String::isNotBlank)?.let { path -> SelectionContainer { Text(path, style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis) } }
        }
    }
}

@Composable
private fun SettingsSelector(title: String, selected: String, options: List<Pair<String?, String>>, enabled: Boolean, tag: String, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.labelMedium)
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag), contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text(selected, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text("⌄")
        }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { (id, name) ->
            DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }, modifier = Modifier.testTag("${tag}_${id ?: "inherit"}"))
        } }
    }
}
