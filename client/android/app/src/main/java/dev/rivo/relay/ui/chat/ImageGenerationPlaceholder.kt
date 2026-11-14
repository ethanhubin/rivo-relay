package dev.rivo.relay.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
private fun generatedImagePreviewWidth() = with(LocalDensity.current) {
    (LocalWindowInfo.current.containerSize.width / 2).toDp()
}

@Composable
internal fun ImageGenerationPlaceholder(generating: Boolean, animate: Boolean, modifier: Modifier = Modifier.width(generatedImagePreviewWidth()).aspectRatio(1f),
    showLabel: Boolean = true) {
    var visible by remember { mutableStateOf(false) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate, visible, lifecycle) {
        if (!animate || !visible || !lifecycle.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (isActive) {
            val frame = withFrameNanos { it }
            if (frame - previous >= 32_000_000) {
                if ((coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f) {
                    phase.floatValue += (frame - previous) / 1_000_000_000f
                }
                previous = frame
            }
        }
    }

    val dots = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier.onVisibilityChanged(minFractionVisible = .01f) { visible = it }
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.BottomStart,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val spacing = 6.dp.toPx()
            val cols = (size.width / spacing).toInt().coerceAtLeast(1)
            val rows = (size.height / spacing).toInt().coerceAtLeast(1)
            val left = (size.width - (cols - 1) * spacing) / 2f
            val top = (size.height - (rows - 1) * spacing) / 2f
            val t = phase.floatValue.toDouble()
            val aX = .5f + .30f * sin(t * 2 * PI / 6.3).toFloat()
            val aY = .5f + .29f * cos(t * 2 * PI / 8.1).toFloat()
            val bX = .5f + .33f * cos(t * 2 * PI / 7.4).toFloat()
            val bY = .5f + .26f * sin(t * 2 * PI / 7.7).toFloat()
            val aSize = .33f + .08f * sin(t * 2 * PI / 4.8).toFloat()
            val bSize = .41f + .08f * cos(t * 2 * PI / 3.6).toFloat()
            val radius = 1.4.dp.toPx()
            for (row in 0 until rows) for (col in 0 until cols) {
                val x = (col + .5f) / cols
                val y = (row + .5f) / rows
                val strength = maxOf(dotField(x - aX, y - aY, aSize), dotField(x - bX, y - bY, bSize))
                drawCircle(dots, radius * (.34f + .66f * strength),
                    Offset(left + col * spacing, top + row * spacing), alpha = .13f + .51f * strength)
            }
        }
        if (showLabel) Text(
            if (generating) "正在生成图片…" else "图片正在准备…",
            Modifier.padding(12.dp).background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun dotField(x: Float, y: Float, radius: Float): Float {
    val strength = (1f - (x * x + y * y) / (radius * radius)).coerceIn(0f, 1f)
    return strength * strength * (3f - 2f * strength)
}
