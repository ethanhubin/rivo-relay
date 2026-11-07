package dev.rivo.relay.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

@Composable
internal fun PinIcon(modifier: Modifier = Modifier) {
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
