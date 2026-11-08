package dev.rivo.relay.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** True = connected, null = connecting, false = disconnected. */
@Composable
internal fun ConnectionStatusDot(connected: Boolean?, modifier: Modifier = Modifier) {
    val color = when (connected) {
        true -> Color(0xFF2E9B59)
        null -> Color(0xFFD49A24)
        false -> Color(0xFFD93838)
    }
    val opacity = if (connected == null) {
        val transition = rememberInfiniteTransition(label = "connection_pending")
        val alpha by transition.animateFloat(1f, 0.3f,
            infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "connection_blink")
        alpha
    } else 1f
    val label = when (connected) {
        true -> "节点服务已连接"
        null -> "正在连接节点服务"
        false -> "节点服务已断开"
    }
    Canvas(modifier.size(7.dp).semantics { contentDescription = label }) {
        drawCircle(color, alpha = opacity)
    }
}
