package dev.rivo.relay.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.codex.threadIsActive
import dev.rivo.relay.data.CodexUsage
import dev.rivo.relay.data.ThreadSummary
import dev.rivo.relay.ui.common.CodexQuotaProgressBar
import dev.rivo.relay.ui.theme.*

/** Layout and selection interactions adapted from QiGuang SessionScreens, using Relay identities. */
@Suppress("UNUSED_PARAMETER")
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
    val shown = threads.sortedWith(compareByDescending<ThreadSummary> { it.pinned }
        .thenByDescending { threadIsActive(it) }.thenByDescending { if (threadIsActive(it)) 0 else it.updatedAt })
        .filter { search.isBlank() || it.name.contains(search.trim(), true) || it.preview.contains(search.trim(), true) }
    val visibleIds = shown.map { it.id }.toSet()
    LaunchedEffect(visibleIds) { selectedIds = selectedIds.intersect(visibleIds) }
    val selected = shown.filter { it.id in selectedIds }
    BackHandler(selectedIds.isNotEmpty()) { selectedIds = emptySet() }
    fun toggle(id: String) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    Box(Modifier.fillMaxSize().testTag("sessions_page")) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxWidth().height(64.dp)) {
                    Row(Modifier.fillMaxSize().padding(horizontal = RivoSpacing.Md), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onBack, modifier = Modifier.size(40.dp).testTag("sessions_back_home"), contentPadding = PaddingValues(0.dp)) {
                            Text("‹", style = MaterialTheme.typography.headlineSmall)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(projectName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("会话", Modifier.testTag("session_list_status"), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    usage?.weeklyWindow?.let { window ->
                        CodexQuotaProgressBar(window, Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .padding(horizontal = RivoSpacing.Page).height(2.dp).testTag("session_list_usage"))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = RivoSpacing.Lg), verticalArrangement = Arrangement.spacedBy(RivoSpacing.Sm)) {
                SessionSearchField(search) { search = it }
                Row(horizontalArrangement = Arrangement.spacedBy(RivoSpacing.Sm)) {
                    SessionFilter(if (archived) "最近会话" else "最近会话 ${threads.size}", !archived, { onArchivedChanged?.invoke(false) }, "sessions_recent")
                    if (onArchivedChanged != null) SessionFilter("已归档", archived, { onArchivedChanged(true) }, "sessions_archived")
                }
                if (selectedIds.isNotEmpty()) Surface(Modifier.fillMaxWidth().testTag("sessions_selection"),
                    shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("已选择 ${selected.size} 项", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        if (selectedIds.size != shown.size) TextButton(onClick = { selectedIds = visibleIds }, enabled = canCreate) { Text("全选") }
                        if (!archived) TextButton(onClick = {
                            val pin = !selected.all { it.pinned }
                            if (onBatchPin != null) onBatchPin(selected, pin) else selected.singleOrNull()?.let { onPin(it, pin) }
                            selectedIds = emptySet()
                        }, enabled = canCreate && selected.isNotEmpty() && (onBatchPin != null || selected.size == 1),
                            modifier = Modifier.testTag("pin_session"), contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (selected.all { it.pinned }) "取消置顶" else "置顶") }
                        if (onArchive != null) TextButton(onClick = { onArchive(selected, !archived); selectedIds = emptySet() },
                            enabled = canCreate && selected.isNotEmpty() && (archived || selected.none(::threadIsActive)), modifier = Modifier.testTag("archive_sessions"),
                            contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (archived) "恢复" else "归档") }
                        TextButton(onClick = { selectedIds = emptySet() }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("取消") }
                    }
                }
                if (loading && shown.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                else if (shown.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(when { search.isNotBlank() -> "没有匹配的会话"; archived -> "没有已归档会话"; else -> "还没有 Codex 会话，点击右下角新建" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(RivoSpacing.Xs)) {
                    items(shown, key = { it.id }) { thread ->
                        val isSelected = thread.id in selectedIds
                        Surface(Modifier.fillMaxWidth().testTag("thread_${thread.id}").combinedClickable(
                            onClick = { if (selectedIds.isNotEmpty()) toggle(thread.id) else if (!archived) onThread(thread) },
                            onLongClick = { toggle(thread.id) }, onLongClickLabel = "选择会话"), shape = MaterialTheme.shapes.large,
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else if (thread.pinned)
                                MaterialTheme.colorScheme.primary.copy(alpha = .08f).compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
                                else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.padding(horizontal = RivoSpacing.Md, vertical = RivoSpacing.Md), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(28.dp).testTag("select_thread_${thread.id}"), contentAlignment = Alignment.Center) {
                                    when {
                                        isSelected -> Text("✓", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { contentDescription = "已选择" })
                                        threadIsActive(thread) -> CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = "任务处理中" }, strokeWidth = 2.dp)
                                        else -> Icon(painterResource(android.R.drawable.sym_action_chat), null, Modifier.size(20.dp))
                                    }
                                }
                                Text(thread.name, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (thread.id in unreadThreadIds) Box(Modifier.padding(start = 7.dp).size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .testTag("unread_thread_${thread.id}").semantics { contentDescription = "有未读更新" })
                                if (thread.pinned) SessionPinIcon(Modifier.padding(start = 8.dp).testTag("session_pin_${thread.id}").semantics { contentDescription = "已置顶" })
                            }
                        }
                    }
                }
            }
        }
        // Surface supplies the same round FAB geometry while preserving the disabled-write contract.
        Surface(onClick = onNew, enabled = canCreate, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(62.dp)
            .testTag("new_thread").semantics { contentDescription = "新建会话" }, shape = CircleShape,
            color = if (canCreate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (canCreate) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, shadowElevation = 6.dp) {
            Box(contentAlignment = Alignment.Center) { Text("＋", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSearchField(query: String, onQueryChanged: (String) -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focus = LocalFocusManager.current
    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.BottomCenter) {
        BasicTextField(query, onQueryChanged, Modifier.fillMaxWidth().height(RivoSize.ControlHeight).testTag("search_sessions"), singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), interactionSource = interactions,
            decorationBox = { inner -> OutlinedTextFieldDefaults.DecorationBox(query, inner, true, true, VisualTransformation.None, interactions,
                placeholder = { Text("搜索会话") }, leadingIcon = {
                    val color = LocalContentColor.current
                    Canvas(Modifier.size(RivoSize.SmallActionIcon)) {
                        drawCircle(color, size.minDimension * .3f, Offset(size.width * .43f, size.height * .43f), style = Stroke(1.8.dp.toPx()))
                        drawLine(color, Offset(size.width * .65f, size.height * .65f), Offset(size.width * .88f, size.height * .88f), 1.8.dp.toPx(), StrokeCap.Round)
                    }
                }, trailingIcon = if (query.isNotEmpty()) { { TextButton(onClick = { onQueryChanged("") }, Modifier.size(40.dp).testTag("session_search_clear"), contentPadding = PaddingValues(0.dp)) { Text("×", style = MaterialTheme.typography.titleLarge) } } } else null,
                contentPadding = PaddingValues(horizontal = RivoSpacing.Md, vertical = RivoSpacing.Xs),
                container = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))) }) })
    }
}

@Composable
private fun SessionFilter(label: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    Surface(onClick = onClick, enabled = !selected, modifier = Modifier.testTag(tag), shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun SessionPinIcon(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(20.dp)) {
        val x = size.width / 2; val top = size.height * .16f; val bottom = size.height * .54f; val stroke = size.width * .12f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1, y1), Offset(x2, y2), stroke, StrokeCap.Round)
        line(x - size.width * .22f, top, x + size.width * .22f, top)
        line(x - size.width * .16f, top, x - size.width * .16f, bottom)
        line(x + size.width * .16f, top, x + size.width * .16f, bottom)
        line(x - size.width * .27f, bottom, x + size.width * .27f, bottom)
        line(x, bottom, x, size.height * .88f)
    }
}
