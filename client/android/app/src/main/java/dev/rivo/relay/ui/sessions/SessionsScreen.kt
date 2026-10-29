package dev.rivo.relay.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.codex.threadIsActive
import dev.rivo.relay.data.CodexUsage
import dev.rivo.relay.data.ThreadSummary
import dev.rivo.relay.ui.common.UsageIndicator
import dev.rivo.relay.ui.theme.*

@Composable
fun SessionsScreen(projectName: String, threads: List<ThreadSummary>, loading: Boolean, canCreate: Boolean,
    onBack: () -> Unit, onThread: (ThreadSummary) -> Unit, onNew: () -> Unit, onRefresh: () -> Unit,
    onPin: (ThreadSummary, Boolean) -> Unit = { _, _ -> },
    archived: Boolean = false, onArchivedChanged: ((Boolean) -> Unit)? = null,
    onBatchPin: ((List<ThreadSummary>, Boolean) -> Unit)? = null,
    onArchive: ((List<ThreadSummary>, Boolean) -> Unit)? = null,
    unreadThreadIds: Set<String> = emptySet(), usage: CodexUsage? = null,
    usageLoading: Boolean = false, usageError: String? = null, onRefreshUsage: (() -> Unit)? = null,
    projectKey: String = projectName) {
    var selectedIds by remember(projectKey, archived) { mutableStateOf(emptySet<String>()) }
    var search by rememberSaveable(projectKey) { mutableStateOf("") }
    val selected = threads.filter { it.id in selectedIds }
    val shown = threads.sortedWith(compareByDescending<ThreadSummary> { it.pinned }
        .thenByDescending { threadIsActive(it) }.thenByDescending { if (threadIsActive(it)) 0 else it.updatedAt })
        .filter { search.isBlank() || it.name.contains(search.trim(), true) || it.preview.contains(search.trim(), true) }
    BackHandler(selectedIds.isNotEmpty()) { selectedIds = emptySet() }
    Column(Modifier.fillMaxSize().testTag("sessions_page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("‹ 返回") }
            Text(projectName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onNew, enabled = canCreate, modifier = Modifier.testTag("new_thread")) { Text("＋ 新建") }
        }
        UsageIndicator(usage, Modifier.padding(horizontal = RivoSpacing.Page), loading = usageLoading, error = usageError, onRefresh = onRefreshUsage)
        OutlinedTextField(search, { search = it }, placeholder = { Text("搜索会话") }, singleLine = true,
            shape = RoundedCornerShape(RivoRadius.Panel), modifier = Modifier.fillMaxWidth().padding(horizontal = RivoSpacing.Page).testTag("search_sessions"))
        Row(Modifier.fillMaxWidth().padding(horizontal = RivoSpacing.Page), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !archived, onClick = { if (archived) onArchivedChanged?.invoke(false) },
                label = { Text(if (archived) "最近会话" else "最近会话 · ${threads.size}") }, modifier = Modifier.testTag("sessions_recent"))
            if (onArchivedChanged != null) FilterChip(selected = archived, onClick = { if (!archived) onArchivedChanged(true) },
                label = { Text("已归档") }, modifier = Modifier.testTag("sessions_archived"))
        }
        if (selectedIds.isNotEmpty()) Surface(Modifier.fillMaxWidth().padding(horizontal = RivoSpacing.Page).testTag("sessions_selection"),
            shape = RoundedCornerShape(RivoRadius.Control), color = MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已选择 ${selected.size} 项", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { selectedIds = shown.mapTo(mutableSetOf()) { it.id } }, enabled = shown.isNotEmpty()) { Text("全选") }
                    TextButton(onClick = { selectedIds = emptySet() }) { Text("取消") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!archived) TextButton(onClick = {
                        val pin = !selected.all { it.pinned }
                        if (onBatchPin != null) onBatchPin(selected, pin) else selected.singleOrNull()?.let { onPin(it, pin) }
                        selectedIds = emptySet()
                    }, enabled = canCreate && selected.isNotEmpty() && (onBatchPin != null || selected.size == 1),
                        modifier = Modifier.testTag("pin_session")) { Text(if (selected.isNotEmpty() && selected.all { it.pinned }) "取消置顶" else "置顶") }
                    if (onArchive != null) TextButton(onClick = { onArchive(selected, !archived); selectedIds = emptySet() },
                        enabled = canCreate && selected.isNotEmpty() && (archived || selected.none(::threadIsActive)),
                        modifier = Modifier.testTag("archive_sessions")) { Text(if (archived) "恢复" else "归档") }
                }
                if (!archived && selected.any(::threadIsActive)) Text("运行中的会话暂不能归档", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(RivoSpacing.Page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!loading && shown.isEmpty()) item { Text(when { search.isNotBlank() -> "没有匹配的会话"; archived -> "没有已归档会话"; else -> "还没有会话" },
                Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(shown, key = { it.id }) { thread ->
                val isSelected = thread.id in selectedIds
                fun toggle() { selectedIds = if (isSelected) selectedIds - thread.id else selectedIds + thread.id }
                Surface(Modifier.fillMaxWidth().testTag("thread_${thread.id}").combinedClickable(
                    onClick = { if (selectedIds.isNotEmpty()) toggle() else onThread(thread) },
                    onLongClick = { toggle() }, onLongClickLabel = "选择会话"),
                    shape = RoundedCornerShape(RivoRadius.Card),
                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selectedIds.isNotEmpty()) Checkbox(checked = isSelected, onCheckedChange = { toggle() }, modifier = Modifier.testTag("select_thread_${thread.id}"))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (thread.pinned) Text("置顶", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(thread.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (thread.preview.isNotBlank()) Text(thread.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (thread.id in unreadThreadIds) Box(Modifier.padding(start = 8.dp).size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                            .testTag("unread_thread_${thread.id}").semantics { contentDescription = "有未读更新" })
                        if (threadIsActive(thread)) Text("进行中", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        else if (selectedIds.isEmpty()) Text("›", Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { TextButton(onRefresh, enabled = !loading) { Text("刷新会话") } }
        }
    }
}
