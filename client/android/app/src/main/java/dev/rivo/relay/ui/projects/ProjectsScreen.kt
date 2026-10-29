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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.ui.theme.*
import dev.rivo.relay.data.CodexUsage
import dev.rivo.relay.ui.common.CodexQuotaProgressBar
import kotlin.math.abs
import kotlin.math.min

data class ProjectSummary(
    val id: String,
    val name: String,
    val path: String,
    val threadCount: Int = 0,
    val outstandingWorkCount: Int = 0,
)

/** Project row geometry migrated from QiGuang HomeScreen.CodexProjectRow; no Agent or node registry coupling. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun ProjectsScreen(projects: List<ProjectSummary>, nodeName: String, loading: Boolean,
    onProject: (ProjectSummary) -> Unit, onConnection: () -> Unit, onRefresh: () -> Unit, onAdd: (() -> Unit)? = null,
    usage: CodexUsage? = null, usageLoading: Boolean = false, usageError: String? = null, onRefreshUsage: (() -> Unit)? = null, canAdd: Boolean = true) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().testTag("projects_page"), contentPadding = PaddingValues(horizontal = RivoSpacing.Page),
        verticalArrangement = Arrangement.spacedBy(RivoSpacing.Sm, Alignment.CenterVertically)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = RivoSpacing.Md), verticalArrangement = Arrangement.spacedBy(RivoSpacing.Xs)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Codex 项目", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onConnection, modifier = Modifier.widthIn(max = 144.dp).testTag("node_settings")) {
                        Text(nodeName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                    }
                    if (onAdd != null) Surface(onClick = onAdd, enabled = canAdd, modifier = Modifier.size(34.dp).testTag("add_project"),
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
        items(projects, key = { it.id }) { project ->
            Box(Modifier.fillMaxWidth()) {
                Surface(modifier = Modifier.fillMaxWidth().testTag("project_${project.id}").combinedClickable(
                    onClick = { onProject(project) }, onLongClickLabel = "复制项目名称", onLongClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("项目名称", project.name))
                        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "已复制项目名称", Toast.LENGTH_SHORT).show()
                    }),
                    shape = RoundedCornerShape(RivoRadius.Card), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.heightIn(min = 74.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RivoSpacing.Md)) {
                        Surface(Modifier.size(44.dp), shape = RoundedCornerShape(RivoRadius.Control), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Box(contentAlignment = Alignment.Center) { Text("C", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${project.threadCount} 个会话", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (project.outstandingWorkCount > 0) {
                    ProjectRunningBorder(Modifier.matchParentSize().testTag("project_${project.id}_running_border"))
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
