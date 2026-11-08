package dev.rivo.relay.ui.projects

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.ui.common.PinIcon
import dev.rivo.relay.ui.theme.*
import dev.rivo.relay.data.CodexUsage
import dev.rivo.relay.R
import dev.rivo.relay.ui.common.CodexQuotaProgressBar
import kotlin.math.abs
import kotlin.math.min

data class ProjectSummary(
    val id: String,
    val name: String,
    val path: String,
    val threadCount: Int = 0,
    val outstandingWorkCount: Int = 0,
    val runningTurnCount: Int = 0,
    val activeGoalCount: Int = 0,
)

/** Project row geometry migrated from QiGuang HomeScreen.CodexProjectRow; no Agent or node registry coupling. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun ProjectsScreen(projects: List<ProjectSummary>, nodeName: String, loading: Boolean,
    onProject: (ProjectSummary) -> Unit, onConnection: () -> Unit, onRefresh: () -> Unit, onAdd: (() -> Unit)? = null,
    usage: CodexUsage? = null, usageLoading: Boolean = false, usageError: String? = null, onRefreshUsage: (() -> Unit)? = null, canAdd: Boolean = true,
    savedNodes: List<dev.rivo.relay.connection.SavedNode> = emptyList(), currentBase: String = "",
    onSelectNode: (String) -> Unit = {}, onAddNode: () -> Unit = {}, canAddNode: Boolean = true, nodeConnected: Boolean? = null,
    pinnedProjectIds: List<String> = emptyList(), onPinProject: ((String, Boolean) -> Unit)? = null) {
    var nodeMenuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pinRanks = remember(pinnedProjectIds) { pinnedProjectIds.withIndex().associate { it.value to it.index } }
    val orderedProjects = remember(projects, pinRanks) { projects.sortedBy { pinRanks[it.id] ?: Int.MAX_VALUE } }
    LazyColumn(Modifier.fillMaxSize().testTag("projects_page"), contentPadding = PaddingValues(horizontal = RivoSpacing.Page),
        verticalArrangement = Arrangement.spacedBy(RivoSpacing.Sm, Alignment.Top)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = RivoSpacing.Md), verticalArrangement = Arrangement.spacedBy(RivoSpacing.Xs)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { nodeMenuOpen = true },
                            contentPadding = PaddingValues(vertical = 12.dp),
                            modifier = Modifier.heightIn(min = 56.dp).testTag("node_settings")) {
                            Icon(painterResource(R.drawable.ic_openai), contentDescription = "Codex",
                                modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(10.dp))
                            val statusColor = when (nodeConnected) {
                                true -> Color(0xFF2E9B59)
                                false -> MaterialTheme.colorScheme.error
                                null -> Color(0xFFD49A24)
                            }
                            val statusLabel = when (nodeConnected) {
                                true -> "节点服务已连接"
                                false -> "节点服务连接失败"
                                null -> "正在连接节点服务"
                            }
                            Text(nodeName, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(8.dp))
                            Canvas(Modifier.size(7.dp).testTag("node_connection_status")
                                .semantics { contentDescription = statusLabel }) {
                                drawCircle(statusColor)
                            }
                            Text(" ▾", style = MaterialTheme.typography.labelLarge)
                        }
                        DropdownMenu(expanded = nodeMenuOpen, onDismissRequest = { nodeMenuOpen = false },
                            modifier = Modifier.widthIn(max = 320.dp).testTag("node_switch_menu")) {
                            savedNodes.filter { it.authorized }.forEach { saved ->
                                val selected = saved.base == currentBase
                                DropdownMenuItem(text = { Text(saved.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    leadingIcon = { Text(if (selected) "✓" else "", Modifier.width(20.dp)) },
                                    onClick = { nodeMenuOpen = false; if (!selected) onSelectNode(saved.base) },
                                    modifier = Modifier.testTag("switch_node_${saved.base}"))
                            }
                        }
                    }
                    if (onAdd != null) Surface(onClick = onAdd, enabled = canAdd, modifier = Modifier.size(48.dp).testTag("add_project")
                        .semantics { contentDescription = "新建项目" },
                        shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(contentAlignment = Alignment.Center) { Text("+", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                    }
                }
                usage?.weeklyWindow?.let { window ->
                    Text("周额度 ${window.remainingPercent}%", Modifier.testTag("project_weekly_usage"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CodexQuotaProgressBar(window, Modifier.fillMaxWidth().height(2.dp).testTag("project_usage_bar"))
                }
            }
        }
        if (loading && projects.isEmpty()) item { Text("正在同步 Codex Desktop 项目…", Modifier.fillMaxWidth().padding(vertical = RivoSpacing.Md), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!loading && projects.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("暂无可访问的项目", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRefresh) { Text("重新加载") }
            }
        }
        items(orderedProjects, key = { it.id }) { project ->
            val pinned = project.id in pinRanks
            var projectMenuOpen by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth()) {
                Surface(modifier = Modifier.fillMaxWidth().testTag("project_${project.id}").combinedClickable(
                    onClick = { onProject(project) }, onLongClickLabel = "项目操作", onLongClick = { projectMenuOpen = true }),
                    shape = RoundedCornerShape(RivoRadius.Card), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.heightIn(min = 74.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RivoSpacing.Md)) {
                        Surface(Modifier.size(44.dp), shape = RoundedCornerShape(RivoRadius.Control), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Box(contentAlignment = Alignment.Center) { Text("C", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${project.threadCount} 个会话" + if (project.outstandingWorkCount > 0 && project.runningTurnCount == 0 && project.activeGoalCount == 0) " · 有待处理任务" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (pinned) PinIcon(Modifier.testTag("project_pin_${project.id}").semantics { contentDescription = "已置顶" })
                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (project.runningTurnCount > 0 || project.activeGoalCount > 0) {
                    ProjectRunningBorder(Modifier.matchParentSize().testTag("project_${project.id}_running_border"))
                }
                DropdownMenu(expanded = projectMenuOpen, onDismissRequest = { projectMenuOpen = false }) {
                    if (onPinProject != null) DropdownMenuItem(
                        text = { Text(if (pinned) "取消置顶" else "置顶") },
                        onClick = { projectMenuOpen = false; onPinProject(project.id, !pinned) },
                    )
                    DropdownMenuItem(text = { Text("复制项目名称") }, onClick = {
                        projectMenuOpen = false
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("项目名称", project.name))
                        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "已复制项目名称", Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }
    }
}

/** QiGuang HomeScreen.CodexProjectRunningBorder; drawing-only overlay preserves row hit targets. */
@Composable
private fun ProjectRunningBorder(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "projectRunningBorder")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "projectRunningBorderPhase",
    )
    val accent = MaterialTheme.colorScheme.primary
    val highlight = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        val strokeWidth = 2.dp.toPx()
        val inset = strokeWidth / 2f
        val samples = 32
        val colors = List(samples + 1) { index ->
            val position = index.toFloat() / samples
            val directDistance = abs(position - phase)
            val circularDistance = min(directDistance, 1f - directDistance)
            val intensity = (1f - circularDistance / 0.16f).coerceIn(0f, 1f)
            if (intensity == 0f) Color.Transparent
            else lerp(accent, highlight, intensity * 0.45f).copy(alpha = 0.18f + 0.82f * intensity)
        }
        drawRoundRect(
            brush = Brush.sweepGradient(colors = colors, center = center),
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            cornerRadius = CornerRadius(RivoRadius.Card.toPx()),
            style = Stroke(width = strokeWidth),
        )
    }
}
