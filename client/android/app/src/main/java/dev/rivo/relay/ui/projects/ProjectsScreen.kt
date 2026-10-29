package dev.rivo.relay.ui.projects

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.ui.theme.*
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
fun ProjectsScreen(projects: List<ProjectSummary>, nodeName: String, loading: Boolean,
    onProject: (ProjectSummary) -> Unit, onConnection: () -> Unit, onRefresh: () -> Unit, onAdd: (() -> Unit)? = null) {
    LazyColumn(Modifier.fillMaxSize().testTag("projects_page"), contentPadding = PaddingValues(horizontal = RivoSpacing.Page, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(RivoSpacing.Sm)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Codex 项目", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onConnection, modifier = Modifier.widthIn(max = 160.dp).testTag("node_settings")) {
                    Text(nodeName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (onAdd != null) FilledTonalButton(onClick = onAdd, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(44.dp).testTag("add_project"), shape = RoundedCornerShape(50)) { Text("＋") }
            }
        }
        if (loading && projects.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!loading && projects.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("暂无可访问的项目", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRefresh) { Text("重新加载") }
            }
        }
        items(projects, key = { it.id }) { project ->
            Box(Modifier.fillMaxWidth()) {
                Surface(onClick = { onProject(project) }, modifier = Modifier.fillMaxWidth().testTag("project_${project.id}"),
                    shape = RoundedCornerShape(RivoRadius.Card), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.heightIn(min = 74.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RivoSpacing.Md)) {
                        Surface(Modifier.size(44.dp), shape = RoundedCornerShape(RivoRadius.Control), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Box(contentAlignment = Alignment.Center) { Text("C", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        if (projects.isNotEmpty()) item { TextButton(onClick = onRefresh, enabled = !loading) { Text(if (loading) "正在刷新…" else "刷新项目") } }
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
