package dev.rivo.relay.ui.todos

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rivo.relay.ui.theme.RivoSize

/** Direct presentation migration from QiGuang TodoScreen's checklist controls. */
@Composable
internal fun TodoCheckbox(checked: Boolean, enabled: Boolean, tag: String, size: Dp = 22.dp,
    touchTargetSize: Dp = 36.dp, onClick: () -> Unit) {
    val fill by animateColorAsState(if (checked) MaterialTheme.colorScheme.primary else Color.Transparent, tween(180), label = "todoCheckboxFill")
    val border by animateColorAsState(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, tween(180), label = "todoCheckboxBorder")
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(220), label = "todoCheckboxCheck")
    val check = MaterialTheme.colorScheme.onPrimary
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.size(touchTargetSize).testTag(tag).semantics {
        role = Role.Checkbox; toggleableState = ToggleableState(checked)
    }, shape = CircleShape, color = Color.Transparent) {
        Box(contentAlignment = Alignment.Center) {
            Surface(Modifier.size(size), shape = RoundedCornerShape(6.dp), color = fill, border = BorderStroke(1.5.dp, border)) {
                Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                    if (progress <= 0f) return@Canvas
                    val start = Offset(this.size.width * .12f, this.size.height * .52f)
                    val join = Offset(this.size.width * .40f, this.size.height * .76f)
                    val end = Offset(this.size.width * .88f, this.size.height * .22f)
                    fun between(a: Offset, b: Offset, portion: Float) = Offset(a.x + (b.x - a.x) * portion, a.y + (b.y - a.y) * portion)
                    drawLine(check, start, between(start, join, (progress / .42f).coerceIn(0f, 1f)), 2.dp.toPx(), StrokeCap.Round)
                    if (progress > .42f) drawLine(check, join, between(join, end, ((progress - .42f) / .58f).coerceIn(0f, 1f)), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
internal fun TodoAnimatedTitle(text: String, completed: Boolean, hasSubtasks: Boolean) {
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val progress by animateFloatAsState(if (completed) 1f else 0f, tween(240, delayMillis = 50), label = "todoTitleStrike")
    val color = MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = if (hasSubtasks) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2, overflow = TextOverflow.Ellipsis, onTextLayout = { layout = it })
        Canvas(Modifier.matchParentSize()) {
            val result = layout ?: return@Canvas
            repeat(result.lineCount) { line ->
                val left = result.getLineLeft(line); val right = result.getLineRight(line)
                val y = result.getLineTop(line) + (result.getLineBottom(line) - result.getLineTop(line)) * .54f
                drawLine(color, Offset(left, y), Offset(left + (right - left) * progress, y), 1.2.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Composable
internal fun TodoExpandButton(expanded: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "todoChevronRotation")
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(RivoSize.MinTouchTarget).testTag(tag).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(Modifier.size(30.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(12.dp, 8.dp).graphicsLayer { rotationZ = rotation }) {
                    drawLine(color, Offset(1.dp.toPx(), 1.5.dp.toPx()), Offset(this.size.width / 2, this.size.height - 1.dp.toPx()), 1.8.dp.toPx(), StrokeCap.Round)
                    drawLine(color, Offset(this.size.width / 2, this.size.height - 1.dp.toPx()), Offset(this.size.width - 1.dp.toPx(), 1.5.dp.toPx()), 1.8.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}
