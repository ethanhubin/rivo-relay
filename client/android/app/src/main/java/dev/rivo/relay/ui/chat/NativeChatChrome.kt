package dev.rivo.relay.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.codex.syncStatusLabel
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.codex.control
import dev.rivo.relay.codex.displayControl
import dev.rivo.relay.codex.showRelease
import dev.rivo.relay.codex.showTakeover
import dev.rivo.relay.codex.takeoverControl
import dev.rivo.relay.codex.ControlState
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.common.ConnectionStatusDot
import dev.rivo.relay.ui.common.CodexQuotaProgressBar
import dev.rivo.relay.ui.theme.NightBackground
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp).testTag("writer_status"),
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
        AlertDialog(onDismissRequest = { runtimeDetails = false }, title = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Text("Codex 连接") } },
            text = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Column(Modifier.testTag("writer_runtime_details").verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } } }, confirmButton = { TextButton({ runtimeDetails = false }) { Text("关闭") } })
    }

}

/** QiGuang SessionScreens.Header: 64dp title/status row, overflow actions and a bottom quota line. */
@Composable
internal fun NativeChatHeader(state: SessionState, usage: CodexUsage?, onBack: () -> Unit, onSettings: () -> Unit,
    onRefresh: () -> Unit, onReview: () -> Unit, onStop: () -> Unit, canStop: Boolean,
    pinned: Boolean, canManage: Boolean, onPin: ((Boolean) -> Unit)?, onArchive: (() -> Unit)?,
    onWriterAction: ((String) -> Unit)? = null, writerBusy: Boolean = false, writerBlockedReason: String? = null, recoveryState: RecoveryState? = null, onDelete: (() -> Unit)? = null) {
    val snapshot = state.snapshot
    val context = LocalContext.current
    var confirmDelete by remember(state.id) { mutableStateOf(false) }
    val canDelete = canManage && snapshot != null && snapshot.activeTurnId.isBlank() && snapshot.requests.isEmpty() && snapshot.queue.isEmpty()
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("永久删除会话？") },
        text = { Text("此会话及其派生子会话将被永久删除，无法恢复。") },
        confirmButton = { TextButton(enabled = canDelete, onClick = { confirmDelete = false; onDelete?.invoke() },
            modifier = Modifier.testTag("confirm_delete_session")) { Text("永久删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
    var menu by remember(state.id) { mutableStateOf(false) }
    val activeSettings = snapshot?.let { current -> current.turnSettings.firstOrNull { it.turnId == current.activeTurnId } }
    val model = activeSettings?.model ?: snapshot?.settings?.string("model").orEmpty()
    val effort = activeSettings?.effort ?: snapshot?.settings?.string("reasoningEffort").orEmpty()
    val configuration = listOf(model, effort).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Codex" }
    val status = state.syncStatusLabel()
    Box(Modifier.fillMaxWidth().height(64.dp).testTag("conversation_header")) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.size(40.dp).shadow(2.dp, RoundedCornerShape(20.dp), ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f)).testTag("back_sessions").semantics { contentDescription = "返回会话列表" },
                contentPadding = PaddingValues(0.dp)) {
                val arrowColor = LocalContentColor.current
                Canvas(Modifier.size(24.dp)) {
                    val stroke = 2.dp.toPx()
                    val tip = Offset(size.width * .22f, size.height * .5f)
                    drawLine(arrowColor, tip, Offset(size.width * .78f, size.height * .5f), stroke, StrokeCap.Round)
                    drawLine(arrowColor, tip, Offset(size.width * .49f, size.height * .23f), stroke, StrokeCap.Round)
                    drawLine(arrowColor, tip, Offset(size.width * .49f, size.height * .77f), stroke, StrokeCap.Round)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onSettings).padding(horizontal = 8.dp).testTag("conversation_status"), verticalArrangement = Arrangement.Center) {
                SelectionContainer { Text(snapshot?.name ?: if (state.readError != null && !state.loading) "会话读取失败" else "正在读取会话…",
                    Modifier.testTag("conversation_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ConnectionStatusDot(when {
                        recoveryState == RecoveryState.CONNECTING -> null
                        recoveryState == RecoveryState.DISCONNECTED -> false
                        recoveryState == RecoveryState.CONNECTED -> true
                        state.loading -> null
                        state.offline || state.readError != null -> false
                        state.stale -> null
                        snapshot == null -> null
                        else -> true
                    }, Modifier.testTag("conversation_connection_status"))
                    Text(listOfNotNull(configuration, status).joinToString(" · "), Modifier.weight(1f, fill = false).testTag("conversation_configuration"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    contextPercent(snapshot?.tokenUsage)?.let { Text("· 上下文 $it%", Modifier.testTag("context_usage"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                }
            }
            Box {
                TextButton(onClick = { menu = true }, colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.size(40.dp).shadow(2.dp, RoundedCornerShape(20.dp), ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f)).testTag("conversation_more_button")
                    .semantics { contentDescription = "更多" }, contentPadding = PaddingValues(0.dp)) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(menu, { menu = false }, modifier = Modifier.width(248.dp),
                    shape = RoundedCornerShape(28.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp, shadowElevation = 12.dp) {
                    ConversationMenuItem(icon = "review", text = { Text("改动与代码审查") }, onClick = { menu = false; onReview() }, enabled = snapshot != null,
                        modifier = Modifier.testTag("open_codex_review"))
                    ConversationMenuItem(icon = "refresh", text = { Text("刷新会话") }, onClick = { menu = false; onRefresh() }, modifier = Modifier.testTag("refresh_conversation"))
                    if (onPin != null) ConversationMenuItem(icon = "pin", text = { Text(if (pinned) "取消置顶" else "置顶") },
                        onClick = { menu = false; onPin(!pinned) }, enabled = true, modifier = Modifier.testTag("set_session_pinned"))
                    if (onArchive != null) ConversationMenuItem(icon = "archive", text = { Text("归档") }, onClick = { menu = false; onArchive() },
                        enabled = canManage && snapshot != null && snapshot.activeTurnId.isBlank() && snapshot.requests.isEmpty() && snapshot.queue.isEmpty(),
                        modifier = Modifier.testTag("archive_session"))
                    if (onDelete != null) ConversationMenuItem(icon = "delete", text = { Text("永久删除", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; confirmDelete = true }, enabled = canDelete, modifier = Modifier.testTag("delete_session"))
                    ConversationMenuItem(icon = "release", text = { Text("释放权限") },
                        onClick = { menu = false; onWriterAction?.invoke("release") },
                        enabled = state.showRelease() && onWriterAction != null && !writerBusy && writerBlockedReason == null &&
                            releaseUnavailableReason(snapshot?.nativeRuntime) == null,
                        modifier = Modifier.testTag("release_session_permission"))
                    ConversationMenuItem(icon = "copy", text = { Text("复制会话 ID") }, enabled = state.id != null, modifier = Modifier.testTag("copy_session_id"),
                        onClick = { menu = false; state.id?.let { id -> context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Codex 会话 ID", id)); Toast.makeText(context, "已复制会话 ID", Toast.LENGTH_SHORT).show() } })
                }
            }
        }
        usage?.weeklyWindow?.let { CodexQuotaProgressBar(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = dev.rivo.relay.ui.theme.RivoSpacing.Page).height(2.dp).testTag("codex_subscription_usage")) }
    }

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
        val effectiveModel = selectedModel ?: settings?.string("model")?.takeIf(String::isNotBlank)
            ?: models.firstOrNull { it.optBoolean("isDefault") }?.let { it.string("model").ifBlank { it.string("id") } }
        val capability = models.firstOrNull { it.string("model").ifBlank { it.string("id") } == effectiveModel }
        val effectiveEffort = selectedEffort ?: settings?.string("reasoningEffort")?.takeIf(String::isNotBlank)
            ?: reasoningEffortForModel(capability, null)
        SettingsSelector("模型", modelOptions.firstOrNull { it.first == effectiveModel }?.second ?: effectiveModel ?: "正在读取默认模型",
            modelOptions, enabled, "composer_model", onModel)
        val efforts = capability?.objects("supportedReasoningEfforts").orEmpty().map { it.string("reasoningEffort") }.filter(String::isNotBlank)
        if (efforts.isNotEmpty()) SettingsSelector("思考强度", effectiveEffort ?: "正在读取默认强度", efforts.map { it to it },
            enabled, "composer_effort", onEffort)
        if (efforts.isEmpty()) Text(effectiveEffort?.let { "思考强度 · $it" } ?: "正在读取默认强度",
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
        val effectiveApproval = if (newSession) approvalChoice?.takeUnless { it == "inherit" } else permissionPreset(settings)
        val inPlan = (selectedMode ?: settings?.optJSONObject("collaborationMode")?.string("mode")) == "plan"
        if (newSession || savesToSession) {
            SettingsSelector("权限模式", approvalSettingLabel(settings, newSession, approvalChoice),
                approvalOptions, enabled && permissionsEditable && (newSession || !inPlan), "composer_approval", onApproval)
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
        }
        if (selectedMode != null) TextButton(onClick = onInheritMode, enabled = enabled, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("inherit_task_mode")) { Text("恢复继承会话模式") }

    }
}

@Composable
internal fun NewSessionSettings(models: List<JSONObject>, model: String?, effort: String?,
    sandbox: String?, approval: String?, enabled: Boolean, permissionsEditable: Boolean,
    onModel: (String?) -> Unit, onEffort: (String?) -> Unit,
    onSandbox: (String?) -> Unit, onApproval: (String?) -> Unit, workspace: (@Composable () -> Unit)? = null) {
    val capability = models.firstOrNull { it.string("model").ifBlank { it.string("id") } == model }
        ?: if (model == null) models.firstOrNull { it.optBoolean("isDefault") } else null
    val efforts = capability?.objects("supportedReasoningEfforts").orEmpty()
        .map { it.string("reasoningEffort") }.filter(String::isNotBlank)
    Column(Modifier.fillMaxWidth().heightIn(max = 340.dp)
        .verticalScroll(rememberScrollState()).testTag("new_session_settings"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("会话设置", style = MaterialTheme.typography.titleSmall)
            Surface(shape = RoundedCornerShape(24.dp), color = if (MaterialTheme.colorScheme.background == NightBackground)
                MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    workspace?.let {
                        it()
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                    SettingsPanelRow("模型", model ?: capability?.string("displayName")?.takeIf(String::isNotBlank)
                        ?: capability?.string("model")?.takeIf(String::isNotBlank) ?: "正在读取默认模型",
                        models.map {
                            it.string("model").ifBlank { it.string("id") } to it.string("displayName").ifBlank { it.string("model").ifBlank { it.string("id") } }
                        }, enabled, "new_session_model", onModel)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    SettingsPanelRow("思考强度", effort ?: reasoningEffortForModel(capability, null) ?: "正在读取默认强度",
                        efforts.map { it to it }, enabled, "new_session_effort", onEffort)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    SettingsPanelRow("权限模式", approvalSettingLabel(null, true, approval),
                        approvalSettingOptions,
                        enabled && permissionsEditable, "new_session_approval", onApproval)
                }
            }
        }
    }
}

@Composable
private fun SettingsSelector(title: String, selected: String, options: List<Pair<String?, String>>, enabled: Boolean, tag: String, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag(tag), shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text(selected, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); SettingsRightArrow()
        }
        DropdownMenu(expanded && enabled, { expanded = false }) { options.forEach { (id, name) ->
            DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }, modifier = Modifier.testTag("${tag}_${id ?: "inherit"}"))
        } }
    }
}

@Composable
private fun ConversationMenuItem(icon: String, text: @Composable () -> Unit, onClick: () -> Unit,
    enabled: Boolean = true, modifier: Modifier = Modifier) {
    val color = if (icon in setOf("archive", "delete")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(text = text, onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        colors = MenuDefaults.itemColors(textColor = color, leadingIconColor = color),
        leadingIcon = { ConversationMenuIcon(icon) })
}

@Composable
private fun ConversationMenuIcon(kind: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.8f * u, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        fun line(vararg coordinates: Float) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(coordinates[0]*u, coordinates[1]*u)
                for (i in 2 until coordinates.size step 2) lineTo(coordinates[i]*u, coordinates[i+1]*u)
            }
            drawPath(path, color, style = stroke)
        }
        fun box(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(color, Offset(x*u,y*u),
            androidx.compose.ui.geometry.Size(w*u,h*u), androidx.compose.ui.geometry.CornerRadius(2*u), style = stroke)
        when (kind) {
            "copy" -> { box(8f,3f,13f,14f); box(3f,8f,13f,13f) }
            "delete" -> { line(4f,6f,20f,6f); box(6f,6f,12f,15f); line(9f,6f,9f,3f,15f,3f,15f,6f); line(10f,10f,10f,17f); line(14f,10f,14f,17f) }
            "archive" -> { box(3f,3f,18f,5f); box(4f,8f,16f,13f); line(9f,12f,15f,12f) }
            "pin" -> { line(4f,21f,9f,16f); line(8f,5f,15f,3f,21f,9f,17f,12f,15f,18f,6f,9f,8f,5f) }
            "refresh" -> {
                drawArc(color, 40f, 290f, false, Offset(4*u,4*u), androidx.compose.ui.geometry.Size(16*u,16*u), style = stroke)
                line(20f,3f,20f,9f,14f,9f)
            }
            "release" -> { box(4f,10f,16f,11f); line(8f,10f,8f,6f,10f,3f,15f,3f,18f,6f); line(12f,14f,12f,17f) }
            else -> { box(4f,3f,16f,18f); line(8f,8f,16f,8f); line(8f,12f,16f,12f); line(8f,16f,12f,16f) }
        }
    }
}

@Composable
internal fun SettingsRightArrow() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(16.dp)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .38f, size.height * .22f)
            lineTo(size.width * .65f, size.height * .5f)
            lineTo(size.width * .38f, size.height * .78f)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
private fun SettingsPanelRow(title: String, selected: String, options: List<Pair<String?, String>>,
    enabled: Boolean, tag: String, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { expanded = true }.testTag(tag)
            .padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(selected, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SettingsRightArrow()
        }
        DropdownMenu(expanded && enabled, { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) },
                    modifier = Modifier.testTag("${tag}_${id ?: "inherit"}"))
            }
        }
    }
}
