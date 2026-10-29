package dev.rivo.relay.ui.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.ThreadSummary
import dev.rivo.relay.ui.theme.*

@Composable
fun SessionsScreen(projectName: String, threads: List<ThreadSummary>, loading: Boolean, canCreate: Boolean,
    onBack: () -> Unit, onThread: (ThreadSummary) -> Unit, onNew: () -> Unit, onRefresh: () -> Unit, onPin: (ThreadSummary, Boolean) -> Unit = { _, _ -> }) {
    var selected by remember { mutableStateOf<ThreadSummary?>(null) }
    var search by rememberSaveable(projectName) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().testTag("sessions_page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("‹ 返回") }
            Text(projectName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onNew, enabled = canCreate, modifier = Modifier.testTag("new_thread")) { Text("＋ 新建") }
        }
        OutlinedTextField(search, { search = it }, placeholder = { Text("搜索会话") }, singleLine = true,
            shape = RoundedCornerShape(RivoRadius.Panel), modifier = Modifier.fillMaxWidth().padding(horizontal = RivoSpacing.Page).testTag("search_sessions"))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(RivoSpacing.Page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("最近会话 · ${threads.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val shown = threads.sortedByDescending { it.pinned }.filter { search.isBlank() || it.name.contains(search, true) || it.preview.contains(search, true) }
            if (!loading && shown.isEmpty()) item { Text(if (search.isBlank()) "还没有会话" else "没有匹配的会话", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(shown, key = { it.id }) { thread ->
                Surface(Modifier.fillMaxWidth().testTag("thread_${thread.id}").combinedClickable(onClick = { onThread(thread) }, onLongClick = { selected = thread }, onLongClickLabel = "会话操作"),
                    shape = RoundedCornerShape(RivoRadius.Card), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (thread.pinned) Text("置顶", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(thread.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (thread.preview.isNotBlank()) Text(thread.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (thread.status in setOf("running", "inProgress", "active", "waiting")) Text("进行中", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        else Text("›", Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { TextButton(onRefresh, enabled = !loading) { Text("刷新会话") } }
        }
    }
    selected?.let { thread ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(thread.name, maxLines = 2) },
            text = { Text(if (thread.pinned) "此会话已置顶" else "置顶后显示在会话列表最前面") },
            confirmButton = { TextButton(onClick = { selected = null; onPin(thread, !thread.pinned) }, enabled = canCreate,
                modifier = Modifier.testTag("pin_session")) { Text(if (thread.pinned) "取消置顶" else "置顶") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } })
    }

}
