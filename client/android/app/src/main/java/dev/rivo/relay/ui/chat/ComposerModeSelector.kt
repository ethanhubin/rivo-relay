package dev.rivo.relay.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

internal class ModeMenuPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0))
    }
}

@Composable
internal fun TaskModeIcon(mode: String) {
    if (mode != "goal") ComposerModeIcon(mode == "plan") else {
        val color = LocalContentColor.current
        Canvas(Modifier.size(24.dp)) {
            drawCircle(color, style = Stroke(1.6.dp.toPx()))
            drawCircle(color, radius = size.minDimension * .28f, style = Stroke(1.6.dp.toPx()))
            drawCircle(color, radius = 1.6.dp.toPx())
        }
    }
}

@Composable
internal fun ComposerModeSelector(mode: String, enabled: Boolean, goalEnabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(expanded) { expanded = false }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    val labels = listOf("default" to "普通模式", "plan" to "计划模式", "goal" to "目标模式")
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    Box {
        TextButton(onClick = { expanded = !expanded }, enabled = enabled,
            modifier = Modifier.height(40.dp).testTag("codex_task_mode"), contentPadding = PaddingValues(horizontal = 10.dp)) {
            TaskModeIcon(mode)
            Spacer(Modifier.width(6.dp))
            Text(labels.first { it.first == mode }.second, style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) Popup(popupPositionProvider = remember(gap) { ModeMenuPosition(gap) },
            onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
            Surface(Modifier.width(258.dp).testTag("composer_mode_menu"), shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 8.dp) {
                Column(Modifier.padding(6.dp)) {
                    labels.forEach { (value, label) ->
                        val available = value != "goal" || goalEnabled
                        Surface(shape = RoundedCornerShape(12.dp), color = if (mode == value) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                            DropdownMenuItem(text = { Column {
                                Text(label)
                                Text(when (value) {
                                    "plan" -> "先讨论并制定执行计划"
                                    "goal" -> if (goalEnabled) "围绕设定目标持续推进" else "当前无法设置目标"
                                    else -> "直接处理当前任务"
                                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }, leadingIcon = { TaskModeIcon(value) }, trailingIcon = { if (mode == value) Text("✓") },
                                enabled = available, onClick = { expanded = false; onSelect(value) },
                                modifier = Modifier.heightIn(min = 64.dp).testTag("composer_mode_$value"))
                        }
                    }
                }
            }
        }
    }
}
