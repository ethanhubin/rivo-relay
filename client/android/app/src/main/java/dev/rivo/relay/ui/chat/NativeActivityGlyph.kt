package dev.rivo.relay.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

// Activity glyph geometry follows QiGuang's Codex timeline.
@Composable
internal fun NativeActivityGlyph(kind: String) {
  val color = MaterialTheme.colorScheme.onSurfaceVariant
  Canvas(modifier = Modifier.size(18.dp)) {
    val stroke = Stroke(width = 1.35.dp.toPx())
    val left = size.width * 0.12f
    val top = size.height * 0.14f
    val right = size.width * 0.88f
    val bottom = size.height * 0.84f
    when (kind) {
      "command" -> {
        drawRoundRect(
          color = color,
          topLeft = Offset(left, top),
          size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
          cornerRadius = CornerRadius(size.width * 0.12f),
          style = stroke,
        )
        drawLine(color, Offset(size.width * 0.28f, size.height * 0.40f), Offset(size.width * 0.39f, size.height * 0.50f), stroke.width)
        drawLine(color, Offset(size.width * 0.39f, size.height * 0.50f), Offset(size.width * 0.28f, size.height * 0.60f), stroke.width)
        drawLine(color, Offset(size.width * 0.50f, size.height * 0.61f), Offset(size.width * 0.70f, size.height * 0.61f), stroke.width)
      }
      "file" -> {
        drawLine(color, Offset(size.width * 0.25f, size.height * 0.72f), Offset(size.width * 0.72f, size.height * 0.25f), stroke.width)
        drawLine(color, Offset(size.width * 0.30f, size.height * 0.78f), Offset(size.width * 0.77f, size.height * 0.31f), stroke.width)
        drawLine(color, Offset(size.width * 0.23f, size.height * 0.79f), Offset(size.width * 0.36f, size.height * 0.76f), stroke.width)
      }
      "image", "image_generation" -> {
        drawRoundRect(
          color = color,
          topLeft = Offset(left, top),
          size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
          cornerRadius = CornerRadius(size.width * 0.14f),
          style = stroke,
        )
        drawCircle(color, size.width * 0.07f, Offset(size.width * 0.66f, size.height * 0.35f), style = stroke)
        val path = Path().apply {
          moveTo(size.width * 0.20f, size.height * 0.70f)
          lineTo(size.width * 0.40f, size.height * 0.49f)
          lineTo(size.width * 0.53f, size.height * 0.62f)
          lineTo(size.width * 0.64f, size.height * 0.53f)
          lineTo(size.width * 0.82f, size.height * 0.70f)
        }
        drawPath(path, color, style = stroke)
      }
      "web" -> {
        drawCircle(color, size.width * 0.27f, Offset(size.width * 0.43f, size.height * 0.42f), style = stroke)
        drawLine(color, Offset(size.width * 0.62f, size.height * 0.62f), Offset(size.width * 0.82f, size.height * 0.82f), stroke.width)
      }
      else -> {
        drawRoundRect(
          color = color,
          topLeft = Offset(size.width * 0.18f, top),
          size = androidx.compose.ui.geometry.Size(size.width * 0.64f, bottom - top),
          cornerRadius = CornerRadius(size.width * 0.10f),
          style = stroke,
        )
        drawLine(color, Offset(size.width * 0.32f, size.height * 0.34f), Offset(size.width * 0.68f, size.height * 0.34f), stroke.width)
        drawLine(color, Offset(size.width * 0.32f, size.height * 0.52f), Offset(size.width * 0.68f, size.height * 0.52f), stroke.width)
        drawLine(color, Offset(size.width * 0.32f, size.height * 0.70f), Offset(size.width * 0.58f, size.height * 0.70f), stroke.width)
      }
    }
  }
}
