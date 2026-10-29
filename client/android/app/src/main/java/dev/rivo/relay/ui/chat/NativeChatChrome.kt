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
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.common.CodexQuotaProgressBar
import org.json.JSONObject

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
        snapshot?.activeTurnId?.isNotBlank() == true -> "执行中"
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
    selectedMode: String?, enabled: Boolean, onModel: (String?) -> Unit, onEffort: (String?) -> Unit, onInheritMode: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("下一项任务", style = MaterialTheme.typography.titleSmall)
        val modelOptions = models.map { model ->
            val id = model.string("model").ifBlank { model.string("id") }
            id to model.string("displayName").ifBlank { id }
        }
        SettingsSelector("模型", selectedModel ?: "继承当前模型", listOf(null to "继承当前模型") + modelOptions,
            enabled, "composer_model", onModel)
        val effectiveModel = selectedModel ?: settings?.string("model")
        val capability = models.firstOrNull { it.string("model").ifBlank { it.string("id") } == effectiveModel }
        val efforts = capability?.objects("supportedReasoningEfforts").orEmpty().map { it.string("reasoningEffort") }.filter(String::isNotBlank)
        if (efforts.isNotEmpty()) SettingsSelector("思考强度", selectedEffort ?: "继承当前设置", listOf(null to "继承当前设置") + efforts.map { it to it },
            enabled, "composer_effort", onEffort)
        if (models.isEmpty()) Text("暂未取得可用模型，可继续沿用当前设置。", style = MaterialTheme.typography.labelSmall)
        if (selectedMode != null) TextButton(onClick = onInheritMode, enabled = enabled, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("inherit_task_mode")) { Text("恢复继承会话模式") }
        Text("选择用于下一项任务；当前执行中的任务保持原设置。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
